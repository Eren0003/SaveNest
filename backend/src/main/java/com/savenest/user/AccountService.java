package com.savenest.user;

import com.savenest.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Account deletion (spec §14). Everything owned by the user is removed:
 * bookmarks, tag/collection memberships, tags, collections and queued metadata jobs all cascade
 * from {@code users.id}; server-side sessions are deleted explicitly because Spring Session JDBC
 * has no foreign key to {@code users}.
 *
 * <p>No stored object or cache holds private per-user data: preview images are proxied on demand
 * and never persisted, and the metadata cache is not implemented (see PROGRESS.md).
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    public record DeletionSummary(int bookmarks, int tags, int collections, int metadataJobs, int sessions) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public AccountService(UserRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    /**
     * Permanently deletes the caller's account after re-authenticating with the current password.
     *
     * @throws ApiException 401 when the session user no longer exists, 403 when the password is wrong
     */
    @Transactional
    public DeletionSummary delete(UUID userId, String password) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Session is no longer valid"));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // Re-authentication failure, not a login failure: the session itself stays valid.
            throw new ApiException(HttpStatus.FORBIDDEN, "invalid_password", "Password is incorrect");
        }

        int bookmarks = count("SELECT count(*) FROM bookmarks WHERE owner_id = ?", userId);
        int tags = count("SELECT count(*) FROM tags WHERE owner_id = ?", userId);
        int collections = count("SELECT count(*) FROM collections WHERE owner_id = ?", userId);
        int jobs = count("SELECT count(*) FROM metadata_jobs WHERE owner_id = ?", userId);

        // Queued work first, so no worker can claim a job for a row that is about to disappear.
        jdbc.update("DELETE FROM metadata_jobs WHERE owner_id = ?", userId);
        // Spring Session JDBC stores the authenticated username; attributes cascade.
        int sessions = jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", user.getEmail());
        users.delete(user); // bookmarks, tags, collections and membership rows cascade

        log.info("Account deleted: userId={} bookmarks={} tags={} collections={} sessions={}",
                userId, bookmarks, tags, collections, sessions);
        return new DeletionSummary(bookmarks, tags, collections, jobs, sessions);
    }

    private int count(String sql, UUID userId) {
        Long n = jdbc.queryForObject(sql, Long.class, userId);
        return n == null ? 0 : n.intValue();
    }
}
