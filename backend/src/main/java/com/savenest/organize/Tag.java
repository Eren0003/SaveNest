package com.savenest.organize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "tags")
public class Tag {

    public static final int MAX_NAME = 50;

    @Id
    private UUID id;
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
    @Column(nullable = false)
    private String name;
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;
    private String color;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Tag() {
    }

    public Tag(UUID ownerId, String name, String color) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        rename(name);
        this.color = color;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
    }

    /** Collapses whitespace; returns null when the result is empty. */
    public static String display(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    public static String normalize(String display) {
        return display.toLowerCase(Locale.ROOT);
    }

    public void rename(String display) {
        this.name = display;
        this.normalizedName = normalize(display);
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Tag t && id.equals(t.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
