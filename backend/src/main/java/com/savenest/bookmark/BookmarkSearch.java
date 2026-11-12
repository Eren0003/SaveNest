package com.savenest.bookmark;

import com.savenest.common.ApiException;
import com.savenest.organize.Tag;
import com.savenest.organize.UserCollection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtered, searched and sorted listing with keyset (cursor) pagination. Every query is scoped to the owner.
 * The cursor embeds the sort so a cursor can never be replayed against a different ordering.
 */
@Component
public class BookmarkSearch {

    public enum Sort {
        NEWEST, OLDEST, TITLE, OPENED;

        public static Sort parse(String s) {
            if (s == null) return NEWEST;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "newest" -> NEWEST;
                case "oldest" -> OLDEST;
                case "title" -> TITLE;
                case "opened" -> OPENED;
                default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                        "sort must be newest, oldest, title or opened");
            };
        }
    }

    public record Filter(UUID owner, BookmarkService.View view, String group, String platform, Boolean favorite,
                         String q, String contentType, String subtype, String progress, List<UUID> tagIds,
                         UUID collectionId, Instant createdFrom, Instant createdBefore, Sort sort) {
    }

    public static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_TOKENS = 8;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    @PersistenceContext
    private EntityManager em;

    public List<Bookmark> find(Filter f, String cursor, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Bookmark> cq = cb.createQuery(Bookmark.class);
        Root<Bookmark> root = cq.from(Bookmark.class);
        List<Predicate> p = new ArrayList<>(filters(f, cb, cq, root));
        Expression<UUID> id = root.get("id");

        switch (f.sort()) {
            case NEWEST, OLDEST -> {
                boolean desc = f.sort() == Sort.NEWEST;
                Expression<Instant> key = root.get("createdAt");
                if (cursor != null) {
                    Cursor c = decode(cursor, f.sort());
                    p.add(keyset(cb, key, id, parseInstant(c.key()), c.id(), desc));
                }
                cq.orderBy(desc ? List.of(cb.desc(key), cb.desc(id)) : List.of(cb.asc(key), cb.asc(id)));
            }
            case OPENED -> {
                Expression<Instant> key = cb.coalesce(root.<Instant>get("lastOpenedAt"), Instant.EPOCH);
                if (cursor != null) {
                    Cursor c = decode(cursor, f.sort());
                    p.add(keyset(cb, key, id, parseInstant(c.key()), c.id(), true));
                }
                cq.orderBy(cb.desc(key), cb.desc(id));
            }
            case TITLE -> {
                Expression<String> key = cb.lower(cb.coalesce(root.<String>get("title"), root.<String>get("host")));
                if (cursor != null) {
                    Cursor c = decode(cursor, f.sort());
                    p.add(keyset(cb, key, id, c.key(), c.id(), false));
                }
                cq.orderBy(cb.asc(key), cb.asc(id));
            }
        }
        cq.select(root).where(p.toArray(new Predicate[0]));
        return em.createQuery(cq).setMaxResults(limit).getResultList();
    }

    public String cursorFor(Sort sort, Bookmark b) {
        String key = switch (sort) {
            case NEWEST, OLDEST -> b.getCreatedAt().truncatedTo(ChronoUnit.MICROS).toString();
            case OPENED -> (b.getLastOpenedAt() == null ? Instant.EPOCH : b.getLastOpenedAt())
                    .truncatedTo(ChronoUnit.MICROS).toString();
            case TITLE -> (b.getTitle() != null ? b.getTitle() : b.getHost()).toLowerCase(Locale.ROOT);
        };
        String raw = sort.name() + "|" + key + "|" + b.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private List<Predicate> filters(Filter f, CriteriaBuilder cb, CriteriaQuery<?> cq, Root<Bookmark> root) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(root.get("ownerId"), f.owner()));
        switch (f.view()) {
            case ACTIVE -> {
                p.add(cb.isNull(root.get("deletedAt")));
                p.add(cb.equal(root.get("lifecycleState"), "active"));
            }
            case ARCHIVED -> {
                p.add(cb.isNull(root.get("deletedAt")));
                p.add(cb.equal(root.get("lifecycleState"), "archived"));
            }
            case TRASH -> p.add(cb.isNotNull(root.get("deletedAt")));
        }
        if (f.group() != null) {
            p.add(cb.or(cb.equal(root.get("groupOverride"), f.group()),
                    cb.and(cb.isNull(root.get("groupOverride")), cb.equal(root.get("detectedGroup"), f.group()))));
        }
        if (f.platform() != null) p.add(cb.equal(root.get("platform"), f.platform()));
        if (f.favorite() != null) p.add(cb.equal(root.get("favorite"), f.favorite()));
        if (f.contentType() != null) p.add(cb.equal(root.get("contentType"), f.contentType()));
        if (f.subtype() != null) p.add(cb.equal(root.get("subtype"), f.subtype()));
        if (f.progress() != null) p.add(cb.equal(root.get("progressState"), f.progress()));
        if (f.createdFrom() != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), f.createdFrom()));
        if (f.createdBefore() != null) p.add(cb.lessThan(root.<Instant>get("createdAt"), f.createdBefore()));
        if (f.tagIds() != null) {
            for (UUID tagId : f.tagIds()) {
                Subquery<UUID> sq = cq.subquery(UUID.class);
                Root<Bookmark> sb = sq.from(Bookmark.class);
                Join<Bookmark, Tag> t = sb.join("tags");
                sq.select(sb.<UUID>get("id")).where(cb.equal(sb.get("id"), root.get("id")), cb.equal(t.get("id"), tagId));
                p.add(cb.exists(sq));
            }
        }
        if (f.collectionId() != null) {
            Subquery<UUID> sq = cq.subquery(UUID.class);
            Root<Bookmark> sb = sq.from(Bookmark.class);
            Join<Bookmark, UserCollection> c = sb.join("collections");
            sq.select(sb.<UUID>get("id")).where(cb.equal(sb.get("id"), root.get("id")),
                    cb.equal(c.get("id"), f.collectionId()));
            p.add(cb.exists(sq));
        }
        List<String> words = tokens(f.q());
        if (words.isEmpty() && f.q() != null && !f.q().isBlank()) {
            p.add(cb.disjunction()); // only punctuation: nothing can match, rather than "match everything"
        }
        for (String token : words) {
            p.add(cb.or(
                    cb.isTrue(cb.function("bookmark_text_match", Boolean.class,
                            root.get("title"), root.get("description"), root.get("notes"),
                            root.get("host"), root.get("platform"), cb.literal(token + ":*"))),
                    cb.like(cb.lower(root.<String>get("host")), "%" + token + "%"),
                    cb.like(cb.lower(root.<String>get("platform")), token + "%"),
                    nameExists(cb, cq, root, "tags", token),
                    nameExists(cb, cq, root, "collections", token)));
        }
        return p;
    }

    /** Matches a tag or collection name at the start of any word (names are stored normalized/lower-case). */
    private static Predicate nameExists(CriteriaBuilder cb, CriteriaQuery<?> cq, Root<Bookmark> root,
                                        String attribute, String token) {
        Subquery<UUID> sq = cq.subquery(UUID.class);
        Root<Bookmark> sb = sq.from(Bookmark.class);
        Join<Bookmark, Object> j = sb.join(attribute);
        sq.select(sb.<UUID>get("id")).where(cb.equal(sb.get("id"), root.get("id")),
                cb.or(cb.like(j.<String>get("normalizedName"), token + "%"),
                        cb.like(j.<String>get("normalizedName"), "% " + token + "%")));
        return cb.exists(sq);
    }

    /** Alphanumeric words only, so user input can never inject full-text query syntax. */
    static List<String> tokens(String q) {
        List<String> out = new ArrayList<>();
        if (q == null) return out;
        Matcher m = TOKEN.matcher(q.toLowerCase(Locale.ROOT));
        while (m.find() && out.size() < MAX_TOKENS) {
            out.add(m.group());
        }
        return out;
    }

    private static <K extends Comparable<? super K>> Predicate keyset(CriteriaBuilder cb, Expression<K> key,
                                                                      Expression<UUID> id, K keyValue, UUID idValue,
                                                                      boolean desc) {
        return desc
                ? cb.or(cb.lessThan(key, keyValue), cb.and(cb.equal(key, keyValue), cb.lessThan(id, idValue)))
                : cb.or(cb.greaterThan(key, keyValue), cb.and(cb.equal(key, keyValue), cb.greaterThan(id, idValue)));
    }

    private record Cursor(String key, UUID id) {
    }

    private static Cursor decode(String cursor, Sort sort) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int first = raw.indexOf('|');
            int last = raw.lastIndexOf('|');
            if (first < 0 || last <= first || !raw.substring(0, first).equals(sort.name())) {
                throw new IllegalArgumentException("cursor does not match sort");
            }
            return new Cursor(raw.substring(first + 1, last), UUID.fromString(raw.substring(last + 1)));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "Invalid cursor");
        }
    }

    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor", "Invalid cursor");
        }
    }
}
