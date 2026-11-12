package com.savenest.bookmark;

import com.savenest.classifier.Classification;
import com.savenest.classifier.GroupId;
import com.savenest.classifier.InvalidUrlException;
import com.savenest.classifier.ParsedUrl;
import com.savenest.classifier.UrlClassifier;
import com.savenest.classifier.UrlIdentity;
import com.savenest.classifier.UrlParser;
import com.savenest.common.ApiException;
import com.savenest.metadata.MetadataJobQueue;
import com.savenest.organize.CollectionService;
import com.savenest.organize.Tag;
import com.savenest.organize.TagService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BookmarkService {

    public static final int MAX_PAGE_SIZE = 100;

    public enum View { ACTIVE, ARCHIVED, TRASH }

    public record Page(List<BookmarkDto> items, String nextCursor) {
    }

    public record PlatformCount(String platform, long count) {
    }

    public record GroupCount(String id, String displayName, String description, long count,
                             List<PlatformCount> platforms) {
    }

    /** Patch semantics: a null field means "unchanged"; empty string clears text fields. */
    public record Update(String title, String description, String notes, String groupId, boolean clearGroupOverride,
                         Boolean favorite, String lifecycleState, String progressState,
                         List<String> tagNames, List<UUID> collectionIds) {
        public Update(String title, String description, String notes, String groupId, boolean clearGroupOverride,
                      Boolean favorite, String lifecycleState, String progressState) {
            this(title, description, notes, groupId, clearGroupOverride, favorite, lifecycleState, progressState,
                    null, null);
        }
    }

    /** Raw list query parameters as received from the API; validated in {@link #list}. */
    public record Query(View view, String group, String platform, Boolean favorite, String q, String contentType,
                        String subtype, String progress, List<UUID> tagIds, UUID collectionId, String from,
                        String to, String sort) {
    }

    private static final Set<String> VIDEO_TYPES = Set.of("video", "movie", "series", "anime", "media_title");
    private static final Set<String> READ_TYPES = Set.of("article", "post");
    private static final Set<String> PRODUCT_TYPES = Set.of("product");

    private final BookmarkRepository repo;
    private final MetadataJobQueue jobs;
    private final BookmarkSearch search;
    private final TagService tags;
    private final CollectionService collections;

    public BookmarkService(BookmarkRepository repo, MetadataJobQueue jobs, BookmarkSearch search,
                           TagService tags, CollectionService collections) {
        this.repo = repo;
        this.jobs = jobs;
        this.search = search;
        this.tags = tags;
        this.collections = collections;
    }

    /**
     * Saves immediately with a pending metadata status. Duplicates are rejected by the partial unique
     * index, so concurrent saves are safe: exactly one insert wins, the rest receive a 409.
     */
    public BookmarkDto create(UUID owner, String rawUrl, String notes, String groupOverride, String progress) {
        return create(owner, rawUrl, notes, groupOverride, progress, null, null);
    }

    public BookmarkDto create(UUID owner, String rawUrl, String notes, String groupOverride, String progress,
                              List<String> tagNames, List<UUID> collectionIds) {
        ParsedUrl url;
        try {
            url = UrlParser.parse(rawUrl);
        } catch (InvalidUrlException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.code().name().toLowerCase(), e.getMessage());
        }
        Classification c = UrlClassifier.classify(url);
        String dedupeKey = UrlIdentity.dedupeKey(url, c);

        var existing = repo.findByOwnerIdAndDedupeKeyAndDeletedAtIsNull(owner, dedupeKey);
        if (existing.isPresent()) {
            throw duplicate(existing.get());
        }
        Bookmark b = new Bookmark(owner, url.uri().toString(), UrlIdentity.normalizedUrl(url), dedupeKey, url.host(), c);
        if (groupOverride != null) {
            b.setGroupOverride(parseGroup(groupOverride).id());
        }
        if (notes != null && !notes.isBlank()) {
            b.setNotes(notes.trim());
        }
        if (progress != null) {
            b.setProgressState(validateProgress(b.getContentType(), progress));
        }
        // Resolve after validation and the duplicate check; both are owner-scoped, so foreign ids give 404.
        b.getCollections().addAll(collections.require(owner, collectionIds));
        b.getTags().addAll(tags.resolve(owner, tagNames));
        try {
            Bookmark saved = repo.saveAndFlush(b);
            // Enqueue after the insert commits; if this fails the worker's orphan sweep picks it up.
            try {
                jobs.enqueue(saved.getId(), owner);
            } catch (RuntimeException ignored) {
                // Saving must never fail because enrichment could not be scheduled.
            }
            return BookmarkDto.from(saved);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent save of the same resource.
            var winner = repo.findByOwnerIdAndDedupeKeyAndDeletedAtIsNull(owner, dedupeKey);
            if (winner.isPresent()) {
                throw duplicate(winner.get());
            }
            throw e;
        }
    }

    private static ApiException duplicate(Bookmark existing) {
        // Only the caller's own bookmark ID is returned (the lookup is owner-scoped).
        return new ApiException(HttpStatus.CONFLICT, "duplicate_bookmark",
                "This link is already saved", Map.of("existingId", existing.getId()));
    }

    @Transactional(readOnly = true)
    public BookmarkDto get(UUID owner, UUID id) {
        return BookmarkDto.from(find(owner, id));
    }

    @Transactional(readOnly = true)
    public Page list(UUID owner, Query q, int limit, String cursor) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        String g = q.group() == null ? null : parseGroup(q.group()).id();
        String query = q.q() == null ? null : q.q().trim();
        if (query != null && query.length() > BookmarkSearch.MAX_QUERY_LENGTH) {
            throw invalid("Search text exceeds " + BookmarkSearch.MAX_QUERY_LENGTH + " characters");
        }
        if (q.tagIds() != null && q.tagIds().size() > TagService.MAX_PER_REQUEST) {
            throw invalid("At most " + TagService.MAX_PER_REQUEST + " tag filters");
        }
        if (q.progress() != null) {
            validateProgressValue(q.progress());
        }
        BookmarkSearch.Sort sort = BookmarkSearch.Sort.parse(q.sort());
        Instant from = q.from() == null ? null : parseDate(q.from(), "from", 0);
        Instant before = q.to() == null ? null : parseDate(q.to(), "to", 1);
        var filter = new BookmarkSearch.Filter(owner, q.view(), g, blankNull(q.platform()), q.favorite(),
                query, blankNull(q.contentType()), blankNull(q.subtype()), q.progress(),
                q.tagIds() == null || q.tagIds().isEmpty() ? null : q.tagIds(), q.collectionId(), from, before, sort);
        List<Bookmark> rows = search.find(filter, cursor, size + 1);
        boolean more = rows.size() > size;
        List<Bookmark> pageRows = more ? rows.subList(0, size) : rows;
        String next = more ? search.cursorFor(sort, pageRows.get(pageRows.size() - 1)) : null;
        return new Page(pageRows.stream().map(BookmarkDto::from).toList(), next);
    }

    private static String blankNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Inclusive calendar date (UTC); {@code plusDays} makes the upper bound exclusive. */
    private static Instant parseDate(String s, String name, int plusDays) {
        try {
            return LocalDate.parse(s).plusDays(plusDays).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be a date like 2025-01-31");
        }
    }

    @Transactional
    public BookmarkDto update(UUID owner, UUID id, Update u) {
        // Row lock serializes with the metadata worker so neither side's changes are lost.
        Bookmark b = repo.lockByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        if (u.title() != null) b.setTitle(blankToNull(u.title(), 500));
        if (u.description() != null) b.setDescription(blankToNull(u.description(), 5000));
        if (u.notes() != null) b.setNotes(blankToNull(u.notes(), 10000));
        if (u.clearGroupOverride()) {
            b.setGroupOverride(null);
        } else if (u.groupId() != null) {
            b.setGroupOverride(parseGroup(u.groupId()).id());
        }
        if (u.favorite() != null) b.setFavorite(u.favorite());
        if (u.lifecycleState() != null) {
            if (!u.lifecycleState().equals("active") && !u.lifecycleState().equals("archived")) {
                throw invalid("lifecycleState must be active or archived");
            }
            b.setLifecycleState(u.lifecycleState());
        }
        if (u.progressState() != null) {
            b.setProgressState(validateProgress(b.getContentType(), u.progressState()));
        }
        if (u.tagNames() != null) {
            b.getTags().clear();
            b.getTags().addAll(tags.resolve(owner, u.tagNames()));
        }
        if (u.collectionIds() != null) {
            b.getCollections().clear();
            b.getCollections().addAll(collections.require(owner, u.collectionIds()));
        }
        return BookmarkDto.from(b);
    }

    /** Moves to trash. Idempotent. */
    @Transactional
    public void trash(UUID owner, UUID id) {
        Bookmark b = find(owner, id);
        if (b.getDeletedAt() == null) {
            b.setDeletedAt(Instant.now());
        }
    }

    /**
     * Restores from trash. Idempotent for bookmarks that are not trashed. Rechecks duplicates: if the same
     * resource was saved again while this one was in trash, returns 409 with the active bookmark's id.
     * Lifecycle (archived) and progress state are preserved.
     */
    @Transactional
    public BookmarkDto restore(UUID owner, UUID id) {
        Bookmark b = repo.lockByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        if (b.getDeletedAt() == null) {
            return BookmarkDto.from(b);
        }
        var existing = repo.findByOwnerIdAndDedupeKeyAndDeletedAtIsNull(owner, b.getDedupeKey());
        if (existing.isPresent()) {
            throw duplicate(existing.get());
        }
        b.setDeletedAt(null);
        try {
            repo.flush();
        } catch (DataIntegrityViolationException e) {
            // A concurrent save or restore of the same resource won; the partial unique index rejected us.
            throw new ApiException(HttpStatus.CONFLICT, "duplicate_bookmark", "This link is already saved");
        }
        return BookmarkDto.from(b);
    }

    /**
     * Permanently deletes a trashed bookmark. Only trashed bookmarks can be deleted (409 otherwise), so a
     * single request can never destroy an active item. Tag/collection links and metadata jobs cascade.
     */
    @Transactional
    public void deletePermanently(UUID owner, UUID id) {
        Bookmark b = repo.lockByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        if (b.getDeletedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "not_in_trash", "Move the bookmark to trash before deleting it");
        }
        repo.delete(b);
    }

    /** Permanently deletes every trashed bookmark of the owner. Returns the number deleted. */
    @Transactional
    public int emptyTrash(UUID owner) {
        List<UUID> ids = repo.findTrashedIds(owner);
        for (UUID id : ids) {
            repo.lockByIdAndOwnerId(id, owner).filter(b -> b.getDeletedAt() != null).ifPresent(repo::delete);
        }
        return ids.size();
    }

    /** Every bookmark of the owner, including archived and trashed ones, oldest first. */
    @Transactional(readOnly = true)
    public List<BookmarkDto> exportAll(UUID owner) {
        return repo.findByOwnerIdOrderByCreatedAtAscIdAsc(owner).stream().map(BookmarkDto::from).toList();
    }

    /**
     * Re-queues enrichment without creating a new bookmark. Manual edits are preserved by the worker.
     * Trashed bookmarks cannot be refreshed.
     */
    @Transactional
    public BookmarkDto refresh(UUID owner, UUID id) {
        Bookmark b = repo.lockByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        if (b.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "bookmark_trashed", "Restore the bookmark before refreshing");
        }
        if (!b.getMetadataStatus().equals("fetching")) {
            b.markMetadata("pending", null);
        }
        jobs.enqueue(b.getId(), owner);
        return BookmarkDto.from(b);
    }

    /** Records an explicit open action (last-opened timestamp and count). */
    @Transactional
    public BookmarkDto recordOpen(UUID owner, UUID id) {
        Bookmark b = repo.lockByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        b.recordOpen();
        return BookmarkDto.from(b);
    }

    /** Group counts (with platform breakdown) generated from real saved data. */
    @Transactional(readOnly = true)
    public List<GroupCount> groupCounts(UUID owner) {
        Map<String, List<PlatformCount>> platforms = new LinkedHashMap<>();
        for (Object[] row : repo.countByGroupAndPlatform(owner)) {
            platforms.computeIfAbsent((String) row[0], k -> new ArrayList<>())
                    .add(new PlatformCount((String) row[1], ((Number) row[2]).longValue()));
        }
        List<GroupCount> out = new ArrayList<>();
        for (GroupId g : GroupId.values()) {
            List<PlatformCount> pcs = platforms.getOrDefault(g.id(), List.of()).stream()
                    .sorted((a, b) -> Long.compare(b.count(), a.count())).toList();
            out.add(new GroupCount(g.id(), g.displayName(), g.description(),
                    pcs.stream().mapToLong(PlatformCount::count).sum(), pcs));
        }
        return out;
    }

    private Bookmark find(UUID owner, UUID id) {
        return repo.findByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
    }

    private static GroupId parseGroup(String id) {
        return GroupId.fromId(id).orElseThrow(() -> invalid("Unknown group: " + id));
    }

    private static void validateProgressValue(String progress) {
        if (!Set.of("none", "to_watch", "watching", "watched", "to_read", "reading", "read", "wishlist", "purchased")
                .contains(progress)) {
            throw invalid("Unknown progress state: " + progress);
        }
    }

    static String validateProgress(String contentType, String progress) {
        Set<String> allowed = VIDEO_TYPES.contains(contentType) ? Set.of("to_watch", "watching", "watched")
                : READ_TYPES.contains(contentType) ? Set.of("to_read", "reading", "read")
                : PRODUCT_TYPES.contains(contentType) ? Set.of("wishlist", "purchased") : Set.<String>of();
        if (progress.equals("none") || allowed.contains(progress)) {
            return progress;
        }
        throw invalid("progressState '" + progress + "' is not valid for content type " + contentType);
    }

    private static String blankToNull(String s, int max) {
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw invalid("Text exceeds " + max + " characters");
        return t;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", message);
    }
}
