package com.savenest.bookmark;

import com.savenest.classifier.Classification;
import jakarta.persistence.Column;
import com.savenest.organize.Tag;
import com.savenest.organize.UserCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** JPA entity; never serialized directly, see {@link BookmarkDto}. */
@Entity
@Table(name = "bookmarks")
public class Bookmark {

    @Id
    private UUID id;
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
    @Column(name = "original_url", nullable = false, updatable = false)
    private String originalUrl;
    @Column(name = "normalized_url", nullable = false)
    private String normalizedUrl;
    @Column(name = "resolved_url")
    private String resolvedUrl;
    @Column(name = "canonical_url")
    private String canonicalUrl;
    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;
    @Column(nullable = false)
    private String host;
    private String title;
    @Column(name = "title_edited", nullable = false)
    private boolean titleEdited;
    private String description;
    @Column(name = "description_edited", nullable = false)
    private boolean descriptionEdited;
    private String notes;
    @Column(name = "detected_group", nullable = false)
    private String detectedGroup;
    @Column(name = "group_override")
    private String groupOverride;
    @Column(nullable = false)
    private String platform;
    @Column(name = "content_type", nullable = false)
    private String contentType;
    private String subtype;
    @Column(name = "resource_id")
    private String resourceId;
    @Column(name = "class_confidence", nullable = false)
    private String classConfidence;
    @Column(name = "class_source", nullable = false)
    private String classSource;
    @Column(name = "class_rule_id", nullable = false)
    private String classRuleId;
    @Column(name = "class_rule_version", nullable = false)
    private int classRuleVersion;
    @Column(name = "class_reason", nullable = false)
    private String classReason;
    @Column(name = "is_favorite", nullable = false)
    private boolean favorite;
    @Column(name = "lifecycle_state", nullable = false)
    private String lifecycleState = "active";
    @Column(name = "progress_state", nullable = false)
    private String progressState = "none";
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "bookmark_tags", joinColumns = @JoinColumn(name = "bookmark_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @BatchSize(size = 100)
    private Set<Tag> tags = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "bookmark_collections", joinColumns = @JoinColumn(name = "bookmark_id"),
            inverseJoinColumns = @JoinColumn(name = "collection_id"))
    @BatchSize(size = 100)
    private Set<UserCollection> collections = new HashSet<>();
    @Column(name = "metadata_status", nullable = false)
    private String metadataStatus = "pending";
    @Column(name = "metadata_error_code")
    private String metadataErrorCode;
    @Column(name = "metadata_fetched_at")
    private Instant metadataFetchedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "last_opened_at")
    private Instant lastOpenedAt;
    @Column(name = "open_count", nullable = false)
    private int openCount;
    @Column(name = "preview_image_url")
    private String previewImageUrl;
    @Column(name = "favicon_url")
    private String faviconUrl;
    @Column(name = "site_name")
    private String siteName;
    @Column(name = "metadata_json")
    private String metadataJson;

    protected Bookmark() {
    }

    public Bookmark(UUID ownerId, String originalUrl, String normalizedUrl, String dedupeKey, String host,
                    Classification c) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.originalUrl = originalUrl;
        this.normalizedUrl = normalizedUrl;
        this.dedupeKey = dedupeKey;
        this.host = host;
        this.detectedGroup = c.groupId().id();
        this.platform = c.platform();
        this.contentType = c.contentType();
        this.subtype = c.subtype();
        this.resourceId = c.resourceId();
        this.classConfidence = c.confidence().json();
        this.classSource = c.source().json();
        this.classRuleId = c.ruleId();
        this.classRuleVersion = c.ruleVersion();
        this.classReason = truncate(c.reason(), 500);
        // Postgres stores microseconds; truncate so cursors round-trip exactly.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public String effectiveGroup() {
        return groupOverride != null ? groupOverride : detectedGroup;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getOriginalUrl() { return originalUrl; }
    public String getNormalizedUrl() { return normalizedUrl; }
    public String getResolvedUrl() { return resolvedUrl; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public String getDedupeKey() { return dedupeKey; }
    public String getHost() { return host; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getNotes() { return notes; }
    public String getDetectedGroup() { return detectedGroup; }
    public String getGroupOverride() { return groupOverride; }
    public String getPlatform() { return platform; }
    public String getContentType() { return contentType; }
    public String getSubtype() { return subtype; }
    public String getResourceId() { return resourceId; }
    public String getClassConfidence() { return classConfidence; }
    public String getClassSource() { return classSource; }
    public String getClassRuleId() { return classRuleId; }
    public int getClassRuleVersion() { return classRuleVersion; }
    public String getClassReason() { return classReason; }
    public boolean isFavorite() { return favorite; }
    public String getLifecycleState() { return lifecycleState; }
    public String getProgressState() { return progressState; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getMetadataStatus() { return metadataStatus; }
    public String getMetadataErrorCode() { return metadataErrorCode; }
    public Instant getMetadataFetchedAt() { return metadataFetchedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastOpenedAt() { return lastOpenedAt; }
    public int getOpenCount() { return openCount; }

    public void setTitle(String title) { this.title = title; this.titleEdited = true; }
    public void setDescription(String description) { this.description = description; this.descriptionEdited = true; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setGroupOverride(String groupOverride) { this.groupOverride = groupOverride; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public void setLifecycleState(String lifecycleState) { this.lifecycleState = lifecycleState; }
    public void setProgressState(String progressState) { this.progressState = progressState; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public Set<Tag> getTags() { return tags; }
    public Set<UserCollection> getCollections() { return collections; }

    public String getPreviewImageUrl() { return previewImageUrl; }
    public String getFaviconUrl() { return faviconUrl; }
    public String getSiteName() { return siteName; }
    public String getMetadataJson() { return metadataJson; }
    public boolean isTitleEdited() { return titleEdited; }
    public boolean isDescriptionEdited() { return descriptionEdited; }

    // ---- Enrichment (worker only). Never touches user-edited fields or the group override. ----

    public void markMetadata(String status, String errorCode) {
        this.metadataStatus = status;
        this.metadataErrorCode = errorCode;
    }

    public void recordOpen() {
        this.openCount++;
        this.lastOpenedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void applyFetched(String title, String description, String imageUrl, String faviconUrl, String siteName,
                             String canonicalUrl, String metadataJson) {
        if (!titleEdited && title != null) this.title = truncate(title, 500);
        if (!descriptionEdited && description != null) this.description = truncate(description, 5000);
        if (imageUrl != null) this.previewImageUrl = truncate(imageUrl, 2200);
        if (faviconUrl != null) this.faviconUrl = truncate(faviconUrl, 2200);
        if (siteName != null) this.siteName = truncate(siteName, 200);
        if (canonicalUrl != null) this.canonicalUrl = truncate(canonicalUrl, 2200);
        if (metadataJson != null) this.metadataJson = truncate(metadataJson, 20000);
        this.metadataFetchedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void setResolvedUrl(String resolvedUrl) {
        this.resolvedUrl = resolvedUrl == null ? null : truncate(resolvedUrl, 2200);
    }

    /** Replaces the detected classification (e.g. after short-link resolution). The override is untouched. */
    public void reclassify(Classification c, String normalizedUrl, String dedupeKey) {
        this.detectedGroup = c.groupId().id();
        this.platform = c.platform();
        this.contentType = c.contentType();
        this.subtype = c.subtype();
        this.resourceId = c.resourceId();
        this.classConfidence = c.confidence().json();
        this.classSource = c.source().json();
        this.classRuleId = c.ruleId();
        this.classRuleVersion = c.ruleVersion();
        this.classReason = truncate(c.reason(), 500);
        if (normalizedUrl != null) this.normalizedUrl = truncate(normalizedUrl, 2200);
        if (dedupeKey != null) this.dedupeKey = dedupeKey;
    }

    /** Metadata-based refinement of type only (rule priority 3/5); never changes a strong URL rule's group. */
    public void refineType(String contentType, String subtype, String source, String reason) {
        this.contentType = contentType;
        this.subtype = subtype;
        this.classSource = source;
        this.classReason = truncate(reason, 500);
    }

    public void refineGroup(String group, String contentType, String subtype, String reason) {
        this.detectedGroup = group;
        this.contentType = contentType;
        this.subtype = subtype;
        this.classSource = "metadata";
        this.classConfidence = "medium";
        this.classRuleId = "metadata.refine";
        this.classReason = truncate(reason, 500);
    }
}
