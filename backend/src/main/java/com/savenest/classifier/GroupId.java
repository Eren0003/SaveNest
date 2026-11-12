package com.savenest.classifier;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

/** Source-based navigation groups. Order here is the navigation order. */
public enum GroupId {
    YOUTUBE("youtube", "YouTube", "Videos, Shorts, live videos, playlists, channels"),
    PRODUCTS("products", "Products", "Product links and shopping-platform links"),
    MOVIES_SERIES("movies_series", "Movies & Series", "Film and series detail pages"),
    ANIME("anime", "Anime", "Anime detail pages and relevant streaming links"),
    IMAGES("images", "Images", "Direct images and identifiable image posts"),
    MEMES("memes", "Memes", "Explicit meme-community posts or manually marked memes"),
    ARTICLES("articles", "Articles", "Identifiable articles, blogs, documentation"),
    SOCIAL("social", "Social Posts", "Social posts without reliable meme/image classification"),
    WEBSITES("websites", "Websites", "Known generic pages, storefronts, service homepages"),
    UNSORTED("unsorted", "Unsorted", "Insufficient information or unrecognized links");

    private final String id;
    private final String displayName;
    private final String description;

    GroupId(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    @JsonValue
    public String id() { return id; }
    public String displayName() { return displayName; }
    public String description() { return description; }

    public static Optional<GroupId> fromId(String id) {
        return Arrays.stream(values()).filter(g -> g.id.equals(id)).findFirst();
    }

    @JsonCreator
    static GroupId json(String id) {
        return fromId(id).orElseThrow(() -> new IllegalArgumentException("Unknown group: " + id));
    }
}
