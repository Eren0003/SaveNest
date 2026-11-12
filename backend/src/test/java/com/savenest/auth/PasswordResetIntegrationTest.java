package com.savenest.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.savenest.mail.PasswordResetMailer;
import com.savenest.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// All MockMvc requests come from 127.0.0.1, so the per-IP limits are raised for this suite.
@TestPropertySource(properties = {
        "savenest.password-reset.max-requests-per-ip=10000",
        "savenest.password-reset.lockout-failures-per-ip=10000",
        "savenest.password-reset.secret=test-secret-test-secret-test-secret-123"
})
class PasswordResetIntegrationTest extends IntegrationTest {

    /** Captures emails synchronously instead of sending them. */
    static class RecordingMailer implements PasswordResetMailer {
        final Map<String, String> lastCode = new ConcurrentHashMap<>();
        final List<String> changedNotices = new CopyOnWriteArrayList<>();

        @Override
        public void sendResetCode(String to, String code, Duration validity) {
            lastCode.put(to, code);
        }

        @Override
        public void sendPasswordChanged(String to) {
            changedNotices.add(to);
        }
    }

    @TestConfiguration
    static class MailConfig {
        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }

    private static final String OLD_PW = "correct-horse-battery";
    private static final String NEW_PW = "new-staple-pancake-42";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailer mailer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String email;

    @BeforeEach
    void registerUser() throws Exception {
        email = "reset-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","displayName":"Reset"}""".formatted(email, OLD_PW)))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions forgot(String addr) throws Exception {
        return postJson("/api/auth/forgot-password", "{\"email\":\"%s\"}".formatted(addr));
    }

    private ResultActions verify(String addr, String code) throws Exception {
        return postJson("/api/auth/verify-reset-code", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(addr, code));
    }

    private ResultActions reset(String token, String pw, String confirm) throws Exception {
        return postJson("/api/auth/reset-password",
                "{\"resetToken\":\"%s\",\"newPassword\":\"%s\",\"confirmPassword\":\"%s\"}".formatted(token, pw, confirm));
    }

    private String tokenFor(String code) throws Exception {
        String body = verify(email, code).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.at("/data/resetToken").asText();
    }

    private ResultActions login(String pw) throws Exception {
        return postJson("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, pw));
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // ------------------------------------------------------------------ tests

    @Test
    void validResetFlowChangesPasswordRevokesSessionsAndNotifies() throws Exception {
        Cookie session = login(OLD_PW).andExpect(status().isOk()).andReturn().getResponse().getCookie("SAVENEST_SESSION");

        forgot(email).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value(AuthController.GENERIC_RESET_MESSAGE));
        String code = mailer.lastCode.get(email);
        assertThat(code).matches("\\d{6}");

        // Stored hashed, never in plaintext.
        String stored = jdbc.queryForObject("SELECT r.code_hash FROM password_reset_requests r JOIN users u "
                + "ON u.id = r.user_id WHERE u.email = ? ORDER BY r.created_at DESC LIMIT 1", String.class, email);
        assertThat(stored).hasSize(64).doesNotContain(code);

        String token = tokenFor(code);
        reset(token, NEW_PW, NEW_PW).andExpect(status().isOk());

        login(OLD_PW).andExpect(status().isUnauthorized());
        login(NEW_PW).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(mailer.changedNotices).contains(email);

        // Token is single-use.
        reset(token, "another-long-password-9", "another-long-password-9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_reset_token"));
    }

    @Test
    void invalidCodeIsRejected() throws Exception {
        forgot(email).andExpect(status().isOk());
        verify(email, wrong(mailer.lastCode.get(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_code"));
        verify(email, "12ab").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void expiredCodeIsRejected() throws Exception {
        forgot(email).andExpect(status().isOk());
        String code = mailer.lastCode.get(email);
        jdbc.update("UPDATE password_reset_requests SET expires_at = now() - interval '1 minute' "
                + "WHERE user_id = (SELECT id FROM users WHERE email = ?)", email);
        verify(email, code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_code"));
    }

    @Test
    void reusedCodeIsRejected() throws Exception {
        forgot(email).andExpect(status().isOk());
        String code = mailer.lastCode.get(email);
        tokenFor(code);
        verify(email, code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_code"));
    }

    @Test
    void newCodeSupersedesOldOne() throws Exception {
        forgot(email).andExpect(status().isOk());
        String first = mailer.lastCode.get(email);
        forgot(email).andExpect(status().isOk());
        String second = mailer.lastCode.get(email);
        if (!first.equals(second)) {
            verify(email, first).andExpect(status().isBadRequest());
        }
        verify(email, second).andExpect(status().isOk());
    }

    @Test
    void codeRequestsAreLimitedToThreePerEmail() throws Exception {
        for (int i = 0; i < 3; i++) forgot(email).andExpect(status().isOk());
        forgot(email).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("rate_limited"));

        // Unregistered emails are limited identically (no existence leak).
        String ghost = "ghost-" + UUID.randomUUID() + "@example.test";
        for (int i = 0; i < 3; i++) forgot(ghost).andExpect(status().isOk());
        forgot(ghost).andExpect(status().isTooManyRequests());
    }

    @Test
    void codeIsInvalidatedAfterFiveWrongAttempts() throws Exception {
        forgot(email).andExpect(status().isOk());
        String code = mailer.lastCode.get(email);
        for (int i = 0; i < 5; i++) verify(email, wrong(code)).andExpect(status().isBadRequest());
        // Even the correct code no longer works.
        verify(email, code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_code"));
    }

    @Test
    void repeatedFailuresTriggerTemporaryLockout() throws Exception {
        for (int i = 0; i < 10; i++) verify(email, "000000").andExpect(status().isBadRequest());
        verify(email, "000000").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("locked_out"));
    }

    @Test
    void passwordMismatchIsRejectedAndTokenStaysUsable() throws Exception {
        forgot(email).andExpect(status().isOk());
        String token = tokenFor(mailer.lastCode.get(email));
        reset(token, NEW_PW, NEW_PW + "x").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("password_mismatch"));
        reset(token, "short", "short").andExpect(status().isUnprocessableEntity());
        reset(token, NEW_PW, NEW_PW).andExpect(status().isOk());
    }

    @Test
    void nonExistentEmailGetsSameGenericResponse() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID() + "@example.test";
        forgot(ghost).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value(AuthController.GENERIC_RESET_MESSAGE));
        assertThat(mailer.lastCode).doesNotContainKey(ghost);
        verify(ghost, "123456").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_code"));
    }

    @Test
    void endpointsRequireCsrf() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))).andExpect(status().isForbidden());
    }
}
