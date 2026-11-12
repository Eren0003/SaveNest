package com.savenest.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.savenest.bookmark.Bookmark;
import com.savenest.bookmark.BookmarkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Database side of enrichment. Short transactions; no network I/O while holding row locks. */
@Component
public class MetadataApplier {

    private final BookmarkRepository repo;
    private final ObjectMapper mapper;

    public MetadataApplier(BookmarkRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Marks the bookmark as fetching and returns a snapshot, or empty if it was deleted/trashed. */
    @Transactional
    public Optional<MetadataEnricher.Input> begin(UUID bookmarkId, UUID ownerId) {
        return repo.lockByIdAndOwnerId(bookmarkId, ownerId)
                .filter(b -> b.getDeletedAt() == null)
                .map(b -> {
                    b.markMetadata("fetching", null);
                    return new MetadataEnricher.Input(b.getOriginalUrl(), b.getPlatform(), b.getContentType(),
                            b.getSubtype(), b.getClassSource());
                });
    }

    /**
     * Applies a result. Only ever updates an existing, non-trashed row: a stale or duplicated job cannot
     * resurrect a deleted bookmark, and user-edited title/description and group override are preserved.
     *
     * @param finalAttempt true if no retry will follow (a transient failure becomes terminal)
     * @return false if the bookmark no longer exists or is trashed
     */
    @Transactional
    public boolean apply(UUID bookmarkId, UUID ownerId, MetadataEnricher.Outcome o, boolean finalAttempt) {
        Optional<Bookmark> found = repo.lockByIdAndOwnerId(bookmarkId, ownerId);
        if (found.isEmpty() || found.get().getDeletedAt() != null) {
            return false;
        }
        Bookmark b = found.get();
        if (o.transientError() && !finalAttempt) {
            b.markMetadata("pending", o.errorCode());
            return true;
        }
        if (o.resolvedUrl() != null) {
            b.setResolvedUrl(o.resolvedUrl());
        }
        if (o.reclassified() != null) {
            // Keep the old identity if the resolved resource is already saved by this owner.
            String key = o.reclassDedupeKey();
            boolean taken = key != null && repo.findByOwnerIdAndDedupeKeyAndDeletedAtIsNull(ownerId, key)
                    .filter(other -> !other.getId().equals(b.getId())).isPresent();
            b.reclassify(o.reclassified(), taken ? null : o.reclassNormalizedUrl(), taken ? null : key);
        } else if (o.refinement() != null) {
            var t = o.refinement();
            if (t.group() == null) {
                b.refineType(t.contentType(), t.subtype(), "metadata", t.reason());
            } else {
                b.refineGroup(t.group(), t.contentType(), t.subtype(), t.reason());
            }
        }
        PageMetadata m = o.metadata();
        if (m != null) {
            b.applyFetched(m.title(), m.description(), m.imageUrl(), m.faviconUrl(), m.siteName(),
                    m.canonicalUrl(), json(m));
        }
        b.markMetadata(o.status(), o.errorCode());
        return true;
    }

    private String json(PageMetadata m) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", m.source());
        if (m.ogType() != null) raw.put("ogType", m.ogType());
        if (m.jsonLdType() != null) raw.put("jsonLdType", m.jsonLdType());
        try {
            return mapper.writeValueAsString(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
