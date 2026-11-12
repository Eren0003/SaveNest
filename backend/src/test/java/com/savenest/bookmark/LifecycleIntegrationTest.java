package com.savenest.bookmark;

import com.savenest.common.ApiException;
import com.savenest.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Archive, trash, restore, permanent delete and export against real PostgreSQL. */
@TestPropertySource(properties = "savenest.worker.enabled=false")
class LifecycleIntegrationTest extends IntegrationTest {

    @Autowired BookmarkService bookmarks;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        String email = "l-" + id + "@example.test";
        jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'T', 'x')",
                id, email, email);
        return id;
    }

    private BookmarkDto save(UUID u, String url) {
        return bookmarks.create(u, url, null, null, null, List.of("keep"), null);
    }

    private static int status(Throwable t) {
        return ((ApiException) t).getStatus().value();
    }

    @Test
    void trashAndRestorePreserveArchiveAndProgress() {
        UUID u = newUser();
        var b = save(u, "https://example.org/article");
        bookmarks.update(u, b.id(), new BookmarkService.Update(null, null, null, null, false, true, "archived", null));
        bookmarks.trash(u, b.id());
        assertThat(bookmarks.get(u, b.id()).deletedAt()).isNotNull();

        var restored = bookmarks.restore(u, b.id());
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.lifecycleState()).isEqualTo("archived");
        assertThat(restored.favorite()).isTrue();
        assertThat(restored.tags()).hasSize(1);
        assertThat(bookmarks.restore(u, b.id()).deletedAt()).isNull(); // idempotent
    }

    @Test
    void restoreRechecksDuplicates() {
        UUID u = newUser();
        var first = save(u, "https://example.org/dup");
        bookmarks.trash(u, first.id());
        var second = save(u, "https://example.org/dup"); // allowed: the first is in trash
        assertThatThrownBy(() -> bookmarks.restore(u, first.id()))
                .satisfies(t -> assertThat(status(t)).isEqualTo(409))
                .satisfies(t -> assertThat(((ApiException) t).getDetails().toString()).contains(second.id().toString()));
        assertThat(bookmarks.get(u, first.id()).deletedAt()).isNotNull();
    }

    @Test
    void permanentDeleteOnlyFromTrashAndOwnerScoped() {
        UUID u = newUser();
        UUID other = newUser();
        var b = save(u, "https://example.org/gone");
        assertThatThrownBy(() -> bookmarks.deletePermanently(u, b.id())).satisfies(t -> assertThat(status(t)).isEqualTo(409));
        bookmarks.trash(u, b.id());
        assertThatThrownBy(() -> bookmarks.deletePermanently(other, b.id())).satisfies(t -> assertThat(status(t)).isEqualTo(404));
        assertThatThrownBy(() -> bookmarks.restore(other, b.id())).satisfies(t -> assertThat(status(t)).isEqualTo(404));

        bookmarks.deletePermanently(u, b.id());
        assertThatThrownBy(() -> bookmarks.get(u, b.id())).satisfies(t -> assertThat(status(t)).isEqualTo(404));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookmark_tags WHERE bookmark_id = ?", Long.class, b.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM metadata_jobs WHERE bookmark_id = ?", Long.class, b.id())).isZero();
    }

    @Test
    void emptyTrashDeletesOnlyOwnTrashedRows() {
        UUID u = newUser();
        UUID other = newUser();
        var keep = save(u, "https://example.org/keep");
        var t1 = save(u, "https://example.org/t1");
        var t2 = save(u, "https://example.org/t2");
        var otherTrashed = save(other, "https://example.org/t1");
        bookmarks.trash(u, t1.id());
        bookmarks.trash(u, t2.id());
        bookmarks.trash(other, otherTrashed.id());

        assertThat(bookmarks.emptyTrash(u)).isEqualTo(2);
        assertThat(bookmarks.exportAll(u)).extracting(BookmarkDto::id).containsExactly(keep.id());
        assertThat(bookmarks.get(other, otherTrashed.id()).deletedAt()).isNotNull();
    }

    @Test
    void exportIncludesOnlyOwnDataInAllStates() {
        UUID u = newUser();
        UUID other = newUser();
        var a = save(u, "https://example.org/a");
        var b = save(u, "https://example.org/b");
        bookmarks.trash(u, b.id());
        save(other, "https://example.org/a");

        var rows = bookmarks.exportAll(u);
        assertThat(rows).extracting(BookmarkDto::id).containsExactly(a.id(), b.id());
        String csv = ExportController.toCsv(rows);
        assertThat(csv.lines().count()).isEqualTo(3);
        assertThat(csv).contains("https://example.org/a").contains("keep");
    }
}
