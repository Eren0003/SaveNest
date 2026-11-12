package com.savenest.user;

import com.savenest.bookmark.BookmarkService;
import com.savenest.common.ApiException;
import com.savenest.organize.CollectionService;
import com.savenest.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account deletion (spec §14): re-authentication, cascade, session invalidation, owner scoping. */
@TestPropertySource(properties = "savenest.worker.enabled=false")
class AccountDeletionIntegrationTest extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired BookmarkService bookmarks;
    @Autowired CollectionService collections;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        return users.saveAndFlush(new User("acct-" + UUID.randomUUID() + "@example.test", "Acct",
                passwordEncoder.encode(PASSWORD))).getId();
    }

    private void seed(UUID owner) {
        var col = collections.create(owner, "Wishlist", null, null, null);
        bookmarks.create(owner, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "note", null, null,
                List.of("watch-later"), List.of(col.id()));
        var trashed = bookmarks.create(owner, "https://example.org/gone", null, null, null, List.of("keep"), null);
        bookmarks.trash(owner, trashed.id());
    }

    private long count(String table, UUID owner) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE owner_id = ?", Long.class, owner);
        return n == null ? 0 : n;
    }

    @Test
    void deleteRemovesEveryOwnedRowAndLeavesOtherAccountsIntact() {
        UUID victim = newUser();
        UUID other = newUser();
        seed(victim);
        seed(other);

        var summary = accounts.delete(victim, PASSWORD);
        assertThat(summary.bookmarks()).isEqualTo(2);
        assertThat(summary.tags()).isEqualTo(2);
        assertThat(summary.collections()).isEqualTo(1);

        assertThat(users.findById(victim)).isEmpty();
        for (String table : List.of("bookmarks", "tags", "collections", "metadata_jobs")) {
            assertThat(count(table, victim)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bookmark_tags bt JOIN tags t ON t.id = bt.tag_id WHERE t.owner_id = ?""",
                Long.class, victim)).isZero();

        // The other account is untouched.
        assertThat(count("bookmarks", other)).isEqualTo(2);
        assertThat(count("collections", other)).isEqualTo(1);
        assertThat(users.findById(other)).isPresent();
    }

    @Test
    void wrongPasswordIsRejectedAndChangesNothing() {
        UUID u = newUser();
        seed(u);
        assertThatThrownBy(() -> accounts.delete(u, "not-the-password"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(403);
                    assertThat(e.getCode()).isEqualTo("invalid_password");
                });
        assertThat(users.findById(u)).isPresent();
        assertThat(count("bookmarks", u)).isEqualTo(2);
    }

    @Test
    void httpDeleteRequiresConfirmationAndEndsTheSession() throws Exception {
        String email = "http-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Acct"}""".formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        var session = login.getResponse().getCookie("SAVENEST_SESSION");
        assertThat(session).isNotNull();

        String body = """
                {"password":"%s"}""".formatted(PASSWORD);

        mvc.perform(delete("/api/account").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("confirmation_required"));

        // No session (the CSRF filter runs first, so a valid token is supplied to reach the auth check).
        mvc.perform(delete("/api/account?confirm=true").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/account?confirm=true").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // no CSRF token

        mvc.perform(delete("/api/account?confirm=true").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookmarks").value(0));

        assertThat(users.findByEmailNormalized(User.normalizeEmail(email))).isEmpty();
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Long.class, email)).isZero();
    }
}
