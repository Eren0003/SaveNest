package com.savenest.metadata;

import com.savenest.bookmark.BookmarkRepository;
import com.savenest.bookmark.BookmarkService;
import com.savenest.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Worker DB semantics against real PostgreSQL. Skipped without Docker. */
@TestPropertySource(properties = "savenest.worker.enabled=false")
class MetadataWorkerIntegrationTest extends IntegrationTest {

    @Autowired BookmarkService bookmarks;
    @Autowired BookmarkRepository repo;
    @Autowired MetadataApplier applier;
    @Autowired MetadataJobQueue queue;
    @Autowired JdbcTemplate jdbc;

    /** Other test classes share the database and leave queued jobs; bounded claims must not be starved by them. */
    @org.junit.jupiter.api.BeforeEach
    void clearOpenJobs() {
        jdbc.update("DELETE FROM metadata_jobs WHERE status IN ('queued', 'running')");
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        String email = "w-" + id + "@example.test";
        jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'T', 'x')",
                id, email, email);
        return id;
    }

    private static MetadataEnricher.Outcome ok(String title) {
        return new MetadataEnricher.Outcome("ready", null, false,
                new PageMetadata(title, "desc", null, null, null, null, null, null, "html"),
                null, null, null, null, null);
    }

    @Test
    void saveEnqueuesExactlyOneOpenJob() {
        UUID u = newUser();
        var b = bookmarks.create(u, "https://example.org/a", null, null, null);
        assertThat(queue.enqueue(b.id(), u)).isFalse(); // already queued
        var jobs = queue.claim("w1", 10, 60, 5);
        assertThat(jobs).extracting(MetadataJobQueue.Job::bookmarkId).contains(b.id());
        assertThat(queue.claim("w2", 10, 60, 5)).extracting(MetadataJobQueue.Job::bookmarkId).doesNotContain(b.id());
    }

    @Test
    void refreshPreservesManualEditsAndOverride() {
        UUID u = newUser();
        var b = bookmarks.create(u, "https://example.org/b", null, null, null);
        bookmarks.update(u, b.id(), new BookmarkService.Update("My title", null, null, "articles", false, null, null, null));
        applier.apply(b.id(), u, ok("Fetched title"), false);
        var after = bookmarks.get(u, b.id());
        assertThat(after.title()).isEqualTo("My title");
        assertThat(after.description()).isEqualTo("desc");
        assertThat(after.groupId()).isEqualTo("articles");
        assertThat(after.metadataStatus()).isEqualTo("ready");
    }

    @Test
    void staleJobCannotResurrectTrashedOrDeletedBookmark() {
        UUID u = newUser();
        var b = bookmarks.create(u, "https://example.org/c", null, null, null);
        bookmarks.trash(u, b.id());
        assertThat(applier.begin(b.id(), u)).isEmpty();
        assertThat(applier.apply(b.id(), u, ok("x"), false)).isFalse();

        repo.deleteById(b.id());
        assertThat(applier.apply(b.id(), u, ok("x"), false)).isFalse();
        assertThat(repo.findById(b.id())).isEmpty();
    }

    @Test
    void workerCannotTouchAnotherOwnersBookmark() {
        UUID u = newUser(), other = newUser();
        var b = bookmarks.create(u, "https://example.org/d", null, null, null);
        assertThat(applier.apply(b.id(), other, ok("hijack"), false)).isFalse();
        assertThat(bookmarks.get(u, b.id()).title()).isNotEqualTo("hijack");
    }

    @Test
    void expiredLeaseIsReclaimedAndOldWorkerCannotComplete() {
        UUID u = newUser();
        var b = bookmarks.create(u, "https://example.org/e", null, null, null);
        var job = queue.claim("old", 10, 0, 5).stream().filter(j -> j.bookmarkId().equals(b.id())).findFirst().orElseThrow();
        jdbc.update("UPDATE metadata_jobs SET locked_until = now() - interval '1 second' WHERE id = ?", job.id());
        var again = queue.claim("new", 10, 60, 5);
        assertThat(again).extracting(MetadataJobQueue.Job::id).contains(job.id());
        assertThat(queue.complete(job.id(), "old")).isFalse();
        assertThat(queue.complete(job.id(), "new")).isTrue();
    }
}
