package com.savenest.organize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A user-created collection (table {@code collections}); independent of system groups. */
@Entity
@Table(name = "collections")
public class UserCollection {

    public static final int MAX_NAME = 100;

    @Id
    private UUID id;
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
    @Column(nullable = false)
    private String name;
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;
    private String description;
    private String icon;
    private String color;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCollection() {
    }

    public UserCollection(UUID ownerId, String name, String description, String icon, String color, int sortOrder) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        rename(name);
        this.description = description;
        this.icon = icon;
        this.color = color;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    public void rename(String display) {
        this.name = display;
        this.normalizedName = Tag.normalize(display);
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getDescription() { return description; }
    public String getIcon() { return icon; }
    public String getColor() { return color; }
    public int getSortOrder() { return sortOrder; }
    public void setDescription(String description) { this.description = description; }
    public void setIcon(String icon) { this.icon = icon; }
    public void setColor(String color) { this.color = color; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    @Override
    public boolean equals(Object o) {
        return o instanceof UserCollection c && id.equals(c.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
