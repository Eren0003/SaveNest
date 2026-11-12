package com.savenest.auth;

import com.savenest.common.ApiException;
import com.savenest.mail.PasswordResetMailer;
import com.savenest.user.User;
import com.savenest.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Forgot-password flow: email a 6-digit code, exchange it for a short-lived reset token, then
 * set a new password. Codes and tokens are stored only as HMAC-SHA256 digests.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration CODE_VALIDITY = Duration.ofMinutes(10);
    static final Duration TOKEN_VALIDITY = Duration.ofMinutes(10);
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS_PER_CODE = 5;

    private static final String CODE_REQUEST = "code_request";
    private static final String VERIFY_FAILURE = "verify_failure";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final PasswordResetMailer mailer;
    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;
    private final int maxRequestsPerEmail;
    private final int maxRequestsPerIp;
    private final int maxFailuresPerEmail;
    private final int maxFailuresPerIp;
    private final Clock clock = Clock.systemUTC();

    public PasswordResetService(UserRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
                                PasswordResetMailer mailer,
                                @Value("${savenest.password-reset.secret:}") String secret,
                                @Value("${savenest.password-reset.max-requests-per-email:3}") int maxRequestsPerEmail,
                                @Value("${savenest.password-reset.max-requests-per-ip:20}") int maxRequestsPerIp,
                                @Value("${savenest.password-reset.lockout-failures-per-email:10}") int maxFailuresPerEmail,
                                @Value("${savenest.password-reset.lockout-failures-per-ip:30}") int maxFailuresPerIp) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.maxRequestsPerEmail = maxRequestsPerEmail;
        this.maxRequestsPerIp = maxRequestsPerIp;
        this.maxFailuresPerEmail = maxFailuresPerEmail;
        this.maxFailuresPerIp = maxFailuresPerIp;
        if (secret == null || secret.length() < 32) {
            log.warn("PASSWORD_RESET_SECRET is missing or shorter than 32 chars; using a random per-process key. "
                    + "Pending reset codes will not survive a restart. Set it in production.");
            byte[] key = new byte[32];
            random.nextBytes(key);
            this.secret = key;
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- step 1: request a code

    /** Always completes silently for unknown emails; throws only for rate limiting (applied to every email). */
    @Transactional
    public void requestCode(String email, String clientIp) {
        String normalized = User.normalizeEmail(email);
        String emailKey = subject("email", normalized);
        String ipKey = subject("ip", clientIp);
        Instant now = clock.instant();

        if (countEvents(emailKey, CODE_REQUEST, now) >= maxRequestsPerEmail
                || countEvents(ipKey, CODE_REQUEST, now) >= maxRequestsPerIp) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Too many code requests. Please wait 15 minutes and try again.");
        }
        recordEvent(emailKey, CODE_REQUEST);
        recordEvent(ipKey, CODE_REQUEST);

        Optional<User> user = users.findByEmailNormalized(normalized);
        if (user.isEmpty()) {
            log.info("Password reset requested for unregistered email");
            return;
        }
        UUID userId = user.get().getId();
        // Only the newest code is valid.
        jdbc.update("UPDATE password_reset_requests SET invalidated_at = now() "
                + "WHERE user_id = ? AND invalidated_at IS NULL AND completed_at IS NULL", userId);

        String code = String.format("%06d", random.nextInt(1_000_000));
        UUID requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO password_reset_requests (id, user_id, code_hash, expires_at) VALUES (?, ?, ?, ?)",
                requestId, userId, hmac("code:" + requestId + ":" + code), Timestamp.from(now.plus(CODE_VALIDITY)));
        mailer.sendResetCode(user.get().getEmail(), code, CODE_VALIDITY);
        log.info("Password reset code issued: userId={} requestId={}", userId, requestId);
    }

    // ---------------------------------------------------------------- step 2: verify the code

    /** @return an opaque reset token valid for {@link #TOKEN_VALIDITY}. */
    @Transactional(noRollbackFor = ApiException.class)
    public String verifyCode(String email, String code, String clientIp) {
        String normalized = User.normalizeEmail(email);
        String emailKey = subject("email", normalized);
        String ipKey = subject("ip", clientIp);
        Instant now = clock.instant();

        if (countEvents(emailKey, VERIFY_FAILURE, now) >= maxFailuresPerEmail
                || countEvents(ipKey, VERIFY_FAILURE, now) >= maxFailuresPerIp) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "locked_out",
                    "Too many incorrect attempts. Please wait 15 minutes before trying again.");
        }

        Optional<User> user = users.findByEmailNormalized(normalized);
        List<Map<String, Object>> rows = user.isEmpty() ? List.of() : jdbc.queryForList(
                "SELECT id, code_hash, expires_at, attempt_count FROM password_reset_requests "
                        + "WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL "
                        + "ORDER BY created_at DESC LIMIT 1 FOR UPDATE", user.get().getId());

        if (rows.isEmpty()) {
            // Compute a digest anyway so unknown emails cost the same as known ones.
            hmac("code:" + UUID.randomUUID() + ":" + code);
            throw fail(emailKey, ipKey);
        }
        Map<String, Object> row = rows.get(0);
        UUID requestId = (UUID) row.get("id");
        boolean expired = ((Timestamp) row.get("expires_at")).toInstant().isBefore(now);
        boolean matches = MessageDigest.isEqual(
                hmac("code:" + requestId + ":" + code).getBytes(StandardCharsets.US_ASCII),
                ((String) row.get("code_hash")).getBytes(StandardCharsets.US_ASCII));

        if (expired) {
            jdbc.update("UPDATE password_reset_requests SET invalidated_at = now() WHERE id = ?", requestId);
            throw fail(emailKey, ipKey);
        }
        if (!matches) {
            int attempts = ((Number) row.get("attempt_count")).intValue() + 1;
            jdbc.update("UPDATE password_reset_requests SET attempt_count = ?, "
                    + "invalidated_at = CASE WHEN ? THEN now() ELSE NULL END WHERE id = ?",
                    attempts, attempts >= MAX_ATTEMPTS_PER_CODE, requestId);
            if (attempts >= MAX_ATTEMPTS_PER_CODE) {
                log.warn("Password reset code invalidated after too many attempts: requestId={}", requestId);
            }
            throw fail(emailKey, ipKey);
        }

        String token = randomToken();
        jdbc.update("UPDATE password_reset_requests SET used_at = now(), reset_token_hash = ?, "
                + "reset_token_expires_at = ? WHERE id = ?",
                hmac("token:" + token), Timestamp.from(now.plus(TOKEN_VALIDITY)), requestId);
        log.info("Password reset code verified: requestId={}", requestId);
        return token;
    }

    // ---------------------------------------------------------------- step 3: set the new password

    @Transactional
    public void resetPassword(String token, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "password_mismatch", "Passwords do not match");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id, r.user_id, u.email, u.email_normalized FROM password_reset_requests r "
                        + "JOIN users u ON u.id = r.user_id "
                        + "WHERE r.reset_token_hash = ? AND r.completed_at IS NULL AND r.invalidated_at IS NULL "
                        + "AND r.reset_token_expires_at > now() FOR UPDATE OF r", hmac("token:" + token));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_reset_token",
                    "Your reset session has expired. Please request a new code.");
        }
        Map<String, Object> row = rows.get(0);
        UUID requestId = (UUID) row.get("id");
        UUID userId = (UUID) row.get("user_id");
        String emailAddress = (String) row.get("email");
        PasswordPolicy.check(newPassword, (String) row.get("email_normalized"));

        jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?",
                passwordEncoder.encode(newPassword), userId);
        jdbc.update("UPDATE password_reset_requests SET completed_at = now(), invalidated_at = now() WHERE id = ?",
                requestId);
        // Any other outstanding codes for this user die too.
        jdbc.update("UPDATE password_reset_requests SET invalidated_at = now() "
                + "WHERE user_id = ? AND invalidated_at IS NULL", userId);
        // Sign out everywhere (Spring Session JDBC stores the username = email).
        int sessions = jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", emailAddress);

        mailer.sendPasswordChanged(emailAddress);
        log.info("Password reset completed: userId={} sessionsRevoked={}", userId, sessions);
    }

    /** Housekeeping: drop throttle events and finished requests older than a day. */
    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void purgeOld() {
        jdbc.update("DELETE FROM password_reset_events WHERE created_at < now() - interval '1 day'");
        jdbc.update("DELETE FROM password_reset_requests WHERE created_at < now() - interval '1 day'");
    }

    // ---------------------------------------------------------------- helpers

    private ApiException fail(String emailKey, String ipKey) {
        recordEvent(emailKey, VERIFY_FAILURE);
        recordEvent(ipKey, VERIFY_FAILURE);
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_code",
                "This code is invalid, expired or has already been used. Check it or request a new code.");
    }

    private int countEvents(String subjectHash, String kind, Instant now) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM password_reset_events WHERE subject_hash = ? AND kind = ? AND created_at > ?",
                Integer.class, subjectHash, kind, Timestamp.from(now.minus(WINDOW)));
        return n == null ? 0 : n;
    }

    private void recordEvent(String subjectHash, String kind) {
        jdbc.update("INSERT INTO password_reset_events (subject_hash, kind) VALUES (?, ?)", subjectHash, kind);
    }

    private String subject(String type, String value) {
        return hmac("subject:" + type + ":" + (value == null ? "" : value));
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
