package com.savenest.bookmark;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** API view of a bookmark. Never exposes owner ID. */
public record BookmarkDto(
        UUID id,
        String url,
        String normalizedUrl,
        String host,
        String title,
        String description,
        String notes,
        String groupId,
        String detectedGroupId,
        boolean groupOverridden,
        String platform,
        String contentType,
        String subtype,
        Classification classification,
        boolean favorite,
        String lifecycleState,
        String progressState,
        Instant deletedAt,
        String metadataStatus,
        String metadataErrorCode,
        Instant metadataFetchedAt,
        String previewImageUrl,
        String faviconUrl,
        String siteName,
        String resolvedUrl,
        boolean titleEdited,
        boolean descriptionEdited,
        Instant createdAt,
        Instant updatedAt,
        Instant lastOpenedAt,
        int openCount,
        List<TagRef> tags,
        List<CollectionRef> collections) {

    public record TagRef(UUID id, String name, String color) {
    }

    public record CollectionRef(UUID id, String name, String color, String icon) {
    }

    public record Classification(String confidence, String source, String ruleId, int ruleVersion, String reason) {
    }

    public static BookmarkDto from(Bookmark b) {
        return new BookmarkDto(b.getId(), b.getOriginalUrl(), b.getNormalizedUrl(), b.getHost(),
                b.getTitle() != null ? b.getTitle() : b.getHost(), b.getDescription(), b.getNotes(),
                b.effectiveGroup(), b.getDetectedGroup(), b.getGroupOverride() != null,
                b.getPlatform(), b.getContentType(), b.getSubtype(),
                new Classification(b.getClassConfidence(), b.getClassSource(), b.getClassRuleId(),
                        b.getClassRuleVersion(), b.getClassReason()),
                b.isFavorite(), b.getLifecycleState(), b.getProgressState(), b.getDeletedAt(),
                b.getMetadataStatus(), b.getMetadataErrorCode(), b.getMetadataFetchedAt(), b.getPreviewImageUrl(),
                b.getFaviconUrl(), b.getSiteName(), b.getResolvedUrl(), b.isTitleEdited(), b.isDescriptionEdited(),
                b.getCreatedAt(), b.getUpdatedAt(), b.getLastOpenedAt(), b.getOpenCount(),
                b.getTags().stream().sorted(Comparator.comparing(t -> t.getNormalizedName()))
                        .map(t -> new TagRef(t.getId(), t.getName(), t.getColor())).toList(),
                b.getCollections().stream().sorted(Comparator.comparing(c -> c.getNormalizedName()))
                        .map(c -> new CollectionRef(c.getId(), c.getName(), c.getColor(), c.getIcon())).toList());
    }
}
