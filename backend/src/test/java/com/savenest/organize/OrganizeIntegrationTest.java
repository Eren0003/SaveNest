package com.savenest.organize;

import com.savenest.bookmark.BookmarkDto;
import com.savenest.bookmark.BookmarkService;
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

/** Search, filters, tags and collections against real PostgreSQL. */
@TestPropertySource(properties = "savenest.worker.enabled=false")
class OrganizeIntegrationTest extends IntegrationTest {

    @Autowired BookmarkService bookmarks;
    @Autowired TagService tags;
    @Autowired CollectionService collections;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        String email = "o-" + id + "@example.test";
        jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'T', 'x')",
                id, email, email);
        return id;
    }

    private BookmarkDto save(UUID u, String url, List<String> tagNames, List<UUID> cols) {
        return bookmarks.create(u, url, null, null, null, tagNames, cols);
    }

    private BookmarkService.Query q(String text) {
        return new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, text, null, null, null,
                null, null, null, null, null);
    }

    private List<UUID> ids(UUID u, BookmarkService.Query query) {
        return bookmarks.list(u, query, 50, null).items().stream().map(BookmarkDto::id).toList();
    }

    @Test
    void tagsAreCaseInsensitiveAndReused() {
        UUID u = newUser();
        var a = save(u, "https://example.org/t1", List.of("Gift", "gift", " GIFT "), null);
        var b = save(u, "https://example.org/t2", List.of("gift"), null);
        assertThat(a.tags()).hasSize(1);
        assertThat(tags.list(u)).hasSize(1);
        assertThat(tags.list(u).get(0).count()).isEqualTo(2);
        assertThat(b.tags().get(0).id()).isEqualTo(a.tags().get(0).id());
    }

    @Test
    void searchMatchesTextTagsCollectionsAndIsOwnerScoped() {
        UUID u = newUser();
        UUID other = newUser();
        var col = collections.create(u, "Birthday wishlist", null, null, null);
        var a = save(u, "https://example.org/headphones", List.of("audio"), List.of(col.id()));
        bookmarks.update(u, a.id(), new BookmarkService.Update("Wireless headphones review", null, null, null, false,
                null, null, null));
        var b = save(u, "https://example.org/other", null, null);
        save(other, "https://example.org/headphones", List.of("audio"), null);

        assertThat(ids(u, q("headphones"))).containsExactly(a.id());          // title
        assertThat(ids(u, q("head"))).containsExactly(a.id());                // prefix
        assertThat(ids(u, q("audio"))).containsExactly(a.id());               // tag
        assertThat(ids(u, q("birthday"))).containsExactly(a.id());            // collection name
        assertThat(ids(u, q("wishlist headphones"))).containsExactly(a.id()); // all words must match
        assertThat(ids(u, q("example"))).containsExactlyInAnyOrder(a.id(), b.id()); // domain
        assertThat(ids(u, q("nomatch"))).isEmpty();
    }

    @Test
    void searchTreatsQuerySyntaxAsPlainText() {
        UUID u = newUser();
        save(u, "https://example.org/x", null, null);
        for (String evil : new String[]{"'; drop table bookmarks; --", "& | ! ( ) : *", "zzz & | ! ( ) :*", "\\", "%_", "<->"}) {
            assertThat(ids(u, q(evil))).isEmpty();
        }
        assertThat(ids(u, q("   "))).hasSize(1); // blank query = no filter
    }

    @Test
    void filtersByTagAndCollectionAndFavoriteAndType() {
        UUID u = newUser();
        var col = collections.create(u, "Reading", null, null, null);
        var a = save(u, "https://example.org/a", List.of("one", "two"), List.of(col.id()));
        var b = save(u, "https://example.org/b", List.of("one"), null);
        var tagOne = a.tags().stream().filter(t -> t.name().equals("one")).findFirst().orElseThrow().id();
        var tagTwo = a.tags().stream().filter(t -> t.name().equals("two")).findFirst().orElseThrow().id();

        var byTags = new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, null, null, null, null,
                List.of(tagOne, tagTwo), null, null, null, null);
        assertThat(ids(u, byTags)).containsExactly(a.id()); // AND semantics
        var oneTag = new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, null, null, null, null,
                List.of(tagOne), null, null, null, null);
        assertThat(ids(u, oneTag)).containsExactlyInAnyOrder(a.id(), b.id());
        var byCol = new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, null, null, null, null,
                null, col.id(), null, null, null);
        assertThat(ids(u, byCol)).containsExactly(a.id());
        var byType = new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, null, "no_such_type", null,
                null, null, null, null, null, null);
        assertThat(ids(u, byType)).isEmpty();
    }

    @Test
    void sortingAndCursorPaginationAreStable() {
        UUID u = newUser();
        for (String n : new String[]{"b", "a", "d", "c", "e"}) {
            var d = save(u, "https://example.org/" + n, null, null);
            bookmarks.update(u, d.id(), new BookmarkService.Update("Title " + n, null, null, null, false, null, null, null));
        }
        for (String sort : new String[]{"newest", "oldest", "title", "opened"}) {
            var query = new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, null, null, null, null,
                    null, null, null, null, sort);
            var seen = new java.util.ArrayList<UUID>();
            String cursor = null;
            do {
                var page = bookmarks.list(u, query, 2, cursor);
                page.items().forEach(i -> seen.add(i.id()));
                cursor = page.nextCursor();
            } while (cursor != null);
            assertThat(seen).as(sort).hasSize(5).doesNotHaveDuplicates();
        }
        var title = new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, null, null, null, null,
                null, null, null, null, "title");
        assertThat(bookmarks.list(u, title, 50, null).items()).extracting(BookmarkDto::title)
                .containsExactly("Title a", "Title b", "Title c", "Title d", "Title e");
        // A cursor from one sort cannot be replayed against another.
        String c1 = bookmarks.list(u, title, 2, null).nextCursor();
        var newest = new BookmarkService.Query(BookmarkService.View.ACTIVE, null, null, null, null, null, null, null,
                null, null, null, null, "newest");
        assertThatThrownBy(() -> bookmarks.list(u, newest, 2, c1)).isInstanceOf(ApiException.class);
    }

    @Test
    void cannotAttachAnotherUsersCollection() {
        UUID u = newUser();
        UUID other = newUser();
        var foreign = collections.create(other, "Private", null, null, null);
        assertThatThrownBy(() -> save(u, "https://example.org/z", null, List.of(foreign.id())))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(404);
        var mine = save(u, "https://example.org/z2", null, null);
        assertThatThrownBy(() -> bookmarks.update(u, mine.id(), new BookmarkService.Update(null, null, null, null, false,
                null, null, null, null, List.of(foreign.id()))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void databaseRejectsCrossOwnerLinksEvenIfServiceChecksAreBypassed() {
        UUID u = newUser();
        UUID other = newUser();
        var b = save(u, "https://example.org/db", null, null);
        var foreignTag = tags.create(other, "x", null);
        var foreignCol = collections.create(other, "y", null, null, null);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO bookmark_tags (bookmark_id, tag_id) VALUES (?, ?)", b.id(), foreignTag.id()))
                .hasMessageContaining("different owners");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO bookmark_collections (bookmark_id, collection_id) VALUES (?, ?)", b.id(), foreignCol.id()))
                .hasMessageContaining("different owners");
    }

    @Test
    void deletingCollectionOrTagKeepsBookmarks() {
        UUID u = newUser();
        var col = collections.create(u, "Temp", null, null, null);
        var b = save(u, "https://example.org/keep", List.of("temp"), List.of(col.id()));
        collections.delete(u, col.id());
        tags.delete(u, b.tags().get(0).id());
        var after = bookmarks.get(u, b.id());
        assertThat(after.collections()).isEmpty();
        assertThat(after.tags()).isEmpty();
    }

    @Test
    void renameKeepsLinksAndMergeMovesThem() {
        UUID u = newUser();
        var a = save(u, "https://example.org/m1", List.of("old"), null);
        var b = save(u, "https://example.org/m2", List.of("new"), null);
        var oldId = a.tags().get(0).id();
        var newId = b.tags().get(0).id();

        tags.update(u, oldId, "renamed", null);
        assertThat(bookmarks.get(u, a.id()).tags()).extracting(BookmarkDto.TagRef::name).containsExactly("renamed");
        assertThatThrownBy(() -> tags.update(u, oldId, "NEW", null)).isInstanceOf(ApiException.class); // clash -> 409

        tags.merge(u, oldId, newId);
        assertThat(bookmarks.get(u, a.id()).tags()).extracting(BookmarkDto.TagRef::name).containsExactly("new");
        assertThat(tags.list(u)).hasSize(1);
        assertThat(tags.list(u).get(0).count()).isEqualTo(2);
    }

    @Test
    void tagLimitPerRequestEnforced() {
        UUID u = newUser();
        var many = new java.util.ArrayList<String>();
        for (int i = 0; i < 21; i++) many.add("t" + i);
        assertThatThrownBy(() -> save(u, "https://example.org/many", many, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void countsExcludeTrashAndArchive() {
        UUID u = newUser();
        var a = save(u, "https://example.org/c1", List.of("cnt"), null);
        var b = save(u, "https://example.org/c2", List.of("cnt"), null);
        var c = save(u, "https://example.org/c3", List.of("cnt"), null);
        bookmarks.update(u, b.id(), new BookmarkService.Update(null, null, null, null, false, null, "archived", null));
        bookmarks.trash(u, c.id());
        assertThat(tags.list(u).get(0).count()).isEqualTo(1);
        assertThat(ids(u, q(null))).containsExactly(a.id());
    }
}
