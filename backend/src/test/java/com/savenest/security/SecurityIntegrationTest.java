package com.savenest.security;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.bookmark.BookmarkDto;
import com.savenest.bookmark.BookmarkService;
import com.savenest.common.ApiException;
import com.savenest.organize.CollectionService;
import com.savenest.organize.TagService;
import com.savenest.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 7 security checks over the real HTTP stack (filters, CSRF, controllers, PostgreSQL):
 * authentication, CSRF, cross-user isolation, duplicate-response leakage, export scoping,
 * concurrent duplicate saves and security headers.
 */
@TestPropertySource(properties = "savenest.worker.enabled=false")
class SecurityIntegrationTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookmarkService bookmarks;
    @Autowired TagService tags;
    @Autowired CollectionService collections;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        String email = "s-" + id + "@example.test";
        jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'T', 'x')",
                id, email, email);
        return id;
    }

    private static RequestPostProcessor as(UUID id) {
        return user(new AppUserPrincipal(id, id + "@example.test", null));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void everyApiEndpointRequiresASession() throws Exception {
        UUID any = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/bookmarks"), get("/api/bookmarks/" + any), get("/api/bookmarks/" + any + "/preview"),
                get("/api/groups"), get("/api/tags"), get("/api/collections"), get("/api/export"),
                get("/api/auth/me"));
        for (var r : requests) {
            mvc.perform(r).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("unauthenticated"));
        }
        List<MockHttpServletRequestBuilder> writes = List.of(
                json(post("/api/bookmarks"), "{\"url\":\"https://example.org\"}"),
                json(post("/api/classify"), "{\"url\":\"https://example.org\"}"),
                json(patch("/api/bookmarks/" + any), "{}"), delete("/api/bookmarks/" + any),
                post("/api/bookmarks/" + any + "/restore"), post("/api/bookmarks/" + any + "/refresh"),
                post("/api/bookmarks/" + any + "/open"), delete("/api/bookmarks/" + any + "/permanent?confirm=true"),
                delete("/api/trash?confirm=true"), json(post("/api/tags"), "{\"name\":\"x\"}"),
                json(post("/api/collections"), "{\"name\":\"x\"}"));
        for (var r : writes) {
            mvc.perform(r.with(csrf())).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/heapdump")).andExpect(status().is4xxClientError());
        mvc.perform(get("/")).andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void authenticatedWritesWithoutCsrfAreRejected() throws Exception {
        UUID u = newUser();
        var b = bookmarks.create(u, "https://example.org/csrf", null, null, null);
        mvc.perform(json(post("/api/bookmarks"), "{\"url\":\"https://example.org/x\"}").with(as(u)))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/bookmarks/" + b.id()), "{\"title\":\"hacked\"}").with(as(u)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/bookmarks/" + b.id()).with(as(u))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/trash?confirm=true").with(as(u))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/bookmarks"), "{\"url\":\"https://example.org/x\"}").with(as(u)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertThat(bookmarks.get(u, b.id()).title()).isNotEqualTo("hacked");
        assertThat(bookmarks.get(u, b.id()).deletedAt()).isNull();
    }

    // ---------------------------------------------------------------- cross-user isolation

    @Test
    void otherUsersBookmarksAreInvisibleAndImmutable() throws Exception {
        UUID owner = newUser();
        UUID attacker = newUser();
        var b = bookmarks.create(owner, "https://example.org/private", "secret note", null, null);
        String path = "/api/bookmarks/" + b.id();

        mvc.perform(get(path).with(as(attacker))).andExpect(status().isNotFound());
        mvc.perform(get(path + "/preview").with(as(attacker))).andExpect(status().isNotFound());
        mvc.perform(json(patch(path), "{\"title\":\"pwned\",\"favorite\":true}").with(as(attacker)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(path + "/open").with(as(attacker)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(path + "/refresh").with(as(attacker)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete(path).with(as(attacker)).with(csrf())).andExpect(status().isNotFound());

        bookmarks.trash(owner, b.id());
        mvc.perform(post(path + "/restore").with(as(attacker)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete(path + "/permanent?confirm=true").with(as(attacker)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/trash?confirm=true").with(as(attacker)).with(csrf()))
                .andExpect(jsonPath("$.data.deleted").value(0));

        var after = bookmarks.get(owner, b.id());
        assertThat(after.title()).isNotEqualTo("pwned");
        assertThat(after.favorite()).isFalse();
        assertThat(after.openCount()).isZero();
        assertThat(after.deletedAt()).isNotNull();

        // Lists, search and counts never include the owner's rows.
        mvc.perform(get("/api/bookmarks?view=trash").with(as(attacker))).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/bookmarks?q=secret").with(as(attacker))).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void otherUsersTagsAndCollectionsCannotBeUsedOrChanged() throws Exception {
        UUID owner = newUser();
        UUID attacker = newUser();
        var tag = tags.create(owner, "owner-tag", null);
        var col = collections.create(owner, "Owner list", null, null, null);
        var attackerTag = tags.create(attacker, "mine", null);
        var attackerBm = bookmarks.create(attacker, "https://example.org/a", null, null, null);

        mvc.perform(json(patch("/api/tags/" + tag.id()), "{\"name\":\"renamed\"}").with(as(attacker)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/tags/" + tag.id()).with(as(attacker)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/tags/" + attackerTag.id() + "/merge"), "{\"targetId\":\"" + tag.id() + "\"}")
                .with(as(attacker)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(json(patch("/api/collections/" + col.id()), "{\"name\":\"renamed\"}").with(as(attacker)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/collections/" + col.id()).with(as(attacker)).with(csrf())).andExpect(status().isNotFound());

        // Attaching a foreign collection on create or update is rejected.
        mvc.perform(json(post("/api/bookmarks"), "{\"url\":\"https://example.org/b\",\"collectionIds\":[\"" + col.id() + "\"]}")
                .with(as(attacker)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(json(patch("/api/bookmarks/" + attackerBm.id()), "{\"collectionIds\":[\"" + col.id() + "\"]}")
                .with(as(attacker)).with(csrf())).andExpect(status().isNotFound());
        // Filtering by a foreign collection or tag returns nothing of the owner's.
        mvc.perform(get("/api/bookmarks?collection=" + col.id()).with(as(attacker)))
                .andExpect(jsonPath("$.data.items.length()").value(0));
        // Tag names are resolved within the caller's account, never to another user's tag.
        mvc.perform(json(patch("/api/bookmarks/" + attackerBm.id()), "{\"tagNames\":[\"owner-tag\"]}")
                .with(as(attacker)).with(csrf())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookmark_tags WHERE tag_id = ?", Long.class, tag.id())).isZero();

        mvc.perform(get("/api/tags").with(as(attacker)))
                .andExpect(jsonPath("$.data[?(@.name == 'owner-tag' && @.id == '" + tag.id() + "')]").isEmpty());
        mvc.perform(get("/api/collections").with(as(attacker))).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(tags.list(owner)).extracting(TagService.TagDto::name).containsExactly("owner-tag");
    }

    @Test
    void duplicateResponsesNeverRevealAnotherUsersBookmark() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        BookmarkDto mine = bookmarks.create(owner, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null, null, null);
        // Same resource for another user: a fresh bookmark, not a 409 pointing at the owner's row.
        mvc.perform(json(post("/api/bookmarks"), "{\"url\":\"https://youtu.be/dQw4w9WgXcQ\"}").with(as(other)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(org.hamcrest.Matchers.not(mine.id().toString())));
        // Same user: 409 with own existing id.
        mvc.perform(json(post("/api/bookmarks"), "{\"url\":\"https://youtu.be/dQw4w9WgXcQ\"}").with(as(owner)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate_bookmark"))
                .andExpect(jsonPath("$.error.requestId").exists());
    }

    @Test
    void exportContainsOnlyTheCallersData() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        bookmarks.create(owner, "https://example.org/owner-only", null, null, null, List.of("owner-tag"), null);
        bookmarks.create(other, "https://example.org/other", null, null, null);

        String jsonBody = mvc.perform(get("/api/export?format=json").with(as(other)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonBody).contains("example.org/other").doesNotContain("owner-only").doesNotContain("owner-tag");

        String csv = mvc.perform(get("/api/export?format=csv").with(as(other)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("example.org/other").doesNotContain("owner-only");
    }

    // ---------------------------------------------------------------- input validation

    @Test
    void dangerousOrMalformedInputIsRejected() throws Exception {
        UUID u = newUser();
        for (String url : new String[]{"javascript:alert(1)", "file:///etc/passwd", "https://user:pass@example.org/",
                "data:text/html,hi", "ftp://example.org/"}) {
            mvc.perform(json(post("/api/bookmarks"), "{\"url\":\"" + url + "\"}").with(as(u)).with(csrf()))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(get("/api/bookmarks?limit=100000").with(as(u))).andExpect(status().isOk());
        mvc.perform(get("/api/bookmarks?sort=id;drop table users").with(as(u))).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/bookmarks?view=everything").with(as(u))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/bookmarks?cursor=not-a-cursor").with(as(u))).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/bookmarks?q=" + "a".repeat(500)).with(as(u))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/bookmarks?q=' OR 1=1 --").with(as(u))).andExpect(status().isOk());
        mvc.perform(json(post("/api/bookmarks"), "{not json").with(as(u)).with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/trash").with(as(u)).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("confirmation_required"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isPositive();
    }

    @Test
    void extractedTextIsReturnedAsDataNotMarkup() throws Exception {
        UUID u = newUser();
        var b = bookmarks.create(u, "https://example.org/xss", "<script>alert(1)</script>", null, null);
        mvc.perform(get("/api/bookmarks/" + b.id()).with(as(u)))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.data.notes").value("<script>alert(1)</script>"));
    }

    // ---------------------------------------------------------------- headers

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void clientSuppliedRequestIdIsIgnored() throws Exception {
        String id = mvc.perform(get("/api/auth/me").header("X-Request-Id", "attacker-chosen"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(id).isNotEqualTo("attacker-chosen");
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void concurrentDuplicateSavesCreateExactlyOneBookmark() throws Exception {
        UUID u = newUser();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String variant = i % 2 == 0 ? "https://example.org/race?utm_source=" + i : "https://EXAMPLE.org/race";
                Callable<Integer> task = () -> {
                    start.await();
                    try {
                        bookmarks.create(u, variant, null, null, null);
                        return 201;
                    } catch (ApiException e) {
                        return e.getStatus().value();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (var f : results) {
                codes.add(f.get());
            }
            assertThat(codes).containsOnly(201, 409);
            assertThat(codes.stream().filter(c -> c == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookmarks WHERE owner_id = ? AND deleted_at IS NULL",
                Long.class, u)).isEqualTo(1);
    }
}
