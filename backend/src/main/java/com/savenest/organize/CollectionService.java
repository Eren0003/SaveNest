package com.savenest.organize;

import com.savenest.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CollectionService {

    public static final int MAX_PER_REQUEST = 20;
    public static final int MAX_COLLECTIONS = 200;

    public record CollectionDto(UUID id, String name, String description, String icon, String color,
                                int sortOrder, long count) {
    }

    /** Partial update: null = unchanged; empty string clears description/icon/color. */
    public record Patch(String name, String description, String icon, String color, Integer sortOrder) {
    }

    private final CollectionRepository repo;

    public CollectionService(CollectionRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<CollectionDto> list(UUID owner) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : repo.countActive(owner)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return repo.findByOwnerIdOrderBySortOrderAscNormalizedNameAsc(owner).stream()
                .map(c -> dto(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    @Transactional
    public CollectionDto create(UUID owner, String name, String description, String icon, String color) {
        String display = validName(name);
        TagService.validColor(color);
        if (repo.findByOwnerIdOrderBySortOrderAscNormalizedNameAsc(owner).size() >= MAX_COLLECTIONS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "limit_reached",
                    "At most " + MAX_COLLECTIONS + " collections per account");
        }
        var existing = repo.findByOwnerIdAndNormalizedName(owner, Tag.normalize(display));
        if (existing.isPresent()) {
            throw exists(existing.get());
        }
        try {
            return dto(repo.saveAndFlush(new UserCollection(owner, display, blank(description, 500), blank(icon, 40),
                    color, 0)), 0);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "collection_exists", "A collection with this name already exists");
        }
    }

    @Transactional
    public CollectionDto update(UUID owner, UUID id, Patch p) {
        UserCollection c = repo.findByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        if (p.name() != null) {
            String display = validName(p.name());
            var clash = repo.findByOwnerIdAndNormalizedName(owner, Tag.normalize(display));
            if (clash.isPresent() && !clash.get().getId().equals(id)) {
                throw exists(clash.get());
            }
            c.rename(display);
        }
        if (p.description() != null) c.setDescription(blank(p.description(), 500));
        if (p.icon() != null) c.setIcon(blank(p.icon(), 40));
        if (p.color() != null) {
            String color = p.color().isBlank() ? null : p.color();
            TagService.validColor(color);
            c.setColor(color);
        }
        if (p.sortOrder() != null) c.setSortOrder(p.sortOrder());
        try {
            repo.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "collection_exists", "A collection with this name already exists");
        }
        return dto(c, 0);
    }

    /** Deletes only the collection and its membership rows; bookmarks are never deleted. */
    @Transactional
    public void delete(UUID owner, UUID id) {
        repo.delete(repo.findByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound));
    }

    /** Returns the owner's collections for the ids; any foreign or missing id yields 404 (no existence leak). */
    @Transactional(readOnly = true)
    public List<UserCollection> require(UUID owner, List<UUID> ids) {
        Set<UUID> unique = ids == null ? Set.of() : new LinkedHashSet<>(ids);
        if (unique.size() > MAX_PER_REQUEST) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    "At most " + MAX_PER_REQUEST + " collections per bookmark");
        }
        if (unique.isEmpty()) {
            return new ArrayList<>();
        }
        List<UserCollection> found = repo.findByOwnerIdAndIdIn(owner, unique);
        if (found.size() != unique.size()) {
            throw ApiException.notFound();
        }
        return new ArrayList<>(found);
    }

    private static ApiException exists(UserCollection existing) {
        return new ApiException(HttpStatus.CONFLICT, "collection_exists", "A collection with this name already exists",
                Map.of("existingId", existing.getId()));
    }

    private static CollectionDto dto(UserCollection c, long count) {
        return new CollectionDto(c.getId(), c.getName(), c.getDescription(), c.getIcon(), c.getColor(),
                c.getSortOrder(), count);
    }

    private static String validName(String raw) {
        String display = Tag.display(raw);
        if (display == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Name must not be empty");
        }
        if (display.length() > UserCollection.MAX_NAME) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    "Name exceeds " + UserCollection.MAX_NAME + " characters");
        }
        return display;
    }

    private static String blank(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Text exceeds " + max + " characters");
        }
        return t;
    }
}
