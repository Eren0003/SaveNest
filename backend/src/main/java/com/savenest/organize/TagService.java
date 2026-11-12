package com.savenest.organize;

import com.savenest.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TagService {

    public static final int MAX_PER_REQUEST = 20;
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public record TagDto(UUID id, String name, String color, long count) {
    }

    private final TagRepository repo;

    public TagService(TagRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<TagDto> list(UUID owner) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : repo.countActive(owner)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return repo.findByOwnerIdOrderByNormalizedNameAsc(owner).stream()
                .map(t -> dto(t, counts.getOrDefault(t.getId(), 0L))).toList();
    }

    @Transactional
    public TagDto create(UUID owner, String name, String color) {
        String display = validName(name);
        validColor(color);
        var existing = repo.findByOwnerIdAndNormalizedName(owner, Tag.normalize(display));
        if (existing.isPresent()) {
            throw exists(existing.get());
        }
        try {
            return dto(repo.saveAndFlush(new Tag(owner, display, color)), 0);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "tag_exists", "A tag with this name already exists");
        }
    }

    /** Rename and/or recolor. Bookmark links follow the tag row, so they are preserved. */
    @Transactional
    public TagDto update(UUID owner, UUID id, String name, String color) {
        Tag t = repo.findByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        if (name != null) {
            String display = validName(name);
            var clash = repo.findByOwnerIdAndNormalizedName(owner, Tag.normalize(display));
            if (clash.isPresent() && !clash.get().getId().equals(id)) {
                throw exists(clash.get());
            }
            t.rename(display);
        }
        if (color != null) {
            validColor(color);
            t.setColor(color);
        }
        try {
            repo.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "tag_exists", "A tag with this name already exists");
        }
        return dto(t, 0);
    }

    @Transactional
    public void delete(UUID owner, UUID id) {
        Tag t = repo.findByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
        repo.delete(t); // membership rows cascade; bookmarks are untouched
    }

    /** Re-links every bookmark of {@code sourceId} to {@code targetId}, then removes the source tag. */
    @Transactional
    public TagDto merge(UUID owner, UUID sourceId, UUID targetId) {
        if (sourceId.equals(targetId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Cannot merge a tag into itself");
        }
        Tag source = repo.findByIdAndOwnerId(sourceId, owner).orElseThrow(ApiException::notFound);
        Tag target = repo.findByIdAndOwnerId(targetId, owner).orElseThrow(ApiException::notFound);
        repo.copyLinks(source.getId(), target.getId());
        repo.delete(source);
        repo.flush();
        return dto(target, 0);
    }

    /**
     * Get-or-create by case-insensitive name. Safe under concurrency: inserts use ON CONFLICT DO NOTHING.
     * Always scoped to the owner, so another user's tag can never be attached.
     */
    @Transactional
    public List<Tag> resolve(UUID owner, List<String> names) {
        Map<String, String> byNormalized = new LinkedHashMap<>();
        if (names != null) {
            for (String raw : names) {
                String display = validName(raw);
                byNormalized.putIfAbsent(Tag.normalize(display), display);
            }
        }
        if (byNormalized.size() > MAX_PER_REQUEST) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    "At most " + MAX_PER_REQUEST + " tags per bookmark");
        }
        if (byNormalized.isEmpty()) {
            return new ArrayList<>();
        }
        for (var e : byNormalized.entrySet()) {
            repo.insertIfAbsent(UUID.randomUUID(), owner, e.getValue(), e.getKey());
        }
        return new ArrayList<>(repo.findByOwnerIdAndNormalizedNameIn(owner, byNormalized.keySet()));
    }

    private static ApiException exists(Tag existing) {
        return new ApiException(HttpStatus.CONFLICT, "tag_exists", "A tag with this name already exists",
                Map.of("existingId", existing.getId()));
    }

    private static TagDto dto(Tag t, long count) {
        return new TagDto(t.getId(), t.getName(), t.getColor(), count);
    }

    private static String validName(String raw) {
        String display = Tag.display(raw);
        if (display == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "Tag name must not be empty");
        }
        if (display.length() > Tag.MAX_NAME) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                    "Tag name exceeds " + Tag.MAX_NAME + " characters");
        }
        return display;
    }

    static void validColor(String color) {
        if (color != null && !COLOR.matcher(color).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "color must look like #RRGGBB");
        }
    }
}
