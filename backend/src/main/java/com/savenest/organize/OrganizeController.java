package com.savenest.organize;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.common.DataResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class OrganizeController {

    private static final String COLOR = "^#[0-9a-fA-F]{6}$";

    public record TagRequest(@NotBlank @Size(max = 100) String name,
                             @Pattern(regexp = COLOR) String color) {
    }

    public record TagPatch(@Size(max = 100) String name, @Pattern(regexp = COLOR) String color) {
    }

    public record MergeRequest(@NotNull UUID targetId) {
    }

    public record CollectionRequest(@NotBlank @Size(max = 200) String name,
                                    @Size(max = 500) String description,
                                    @Size(max = 40) String icon,
                                    @Pattern(regexp = COLOR) String color) {
    }

    public record CollectionPatchRequest(@Size(max = 200) String name,
                                         @Size(max = 500) String description,
                                         @Size(max = 40) String icon,
                                         @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$") String color,
                                         Integer sortOrder) {
    }

    private final TagService tags;
    private final CollectionService collections;

    public OrganizeController(TagService tags, CollectionService collections) {
        this.tags = tags;
        this.collections = collections;
    }

    @GetMapping("/tags")
    public DataResponse<List<TagService.TagDto>> listTags(@AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(tags.list(user.getUserId()));
    }

    @PostMapping("/tags")
    public ResponseEntity<DataResponse<TagService.TagDto>> createTag(@Valid @RequestBody TagRequest b,
                                                                     @AuthenticationPrincipal AppUserPrincipal user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DataResponse.of(tags.create(user.getUserId(), b.name(), b.color())));
    }

    @PatchMapping("/tags/{id}")
    public DataResponse<TagService.TagDto> updateTag(@PathVariable UUID id, @Valid @RequestBody TagPatch b,
                                                     @AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(tags.update(user.getUserId(), id, b.name(), b.color()));
    }

    @DeleteMapping("/tags/{id}")
    public ResponseEntity<Void> deleteTag(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        tags.delete(user.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Moves every bookmark from this tag to the target tag, then deletes this tag. */
    @PostMapping("/tags/{id}/merge")
    public DataResponse<TagService.TagDto> mergeTag(@PathVariable UUID id, @Valid @RequestBody MergeRequest b,
                                                    @AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(tags.merge(user.getUserId(), id, b.targetId()));
    }

    @GetMapping("/collections")
    public DataResponse<List<CollectionService.CollectionDto>> listCollections(
            @AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(collections.list(user.getUserId()));
    }

    @PostMapping("/collections")
    public ResponseEntity<DataResponse<CollectionService.CollectionDto>> createCollection(
            @Valid @RequestBody CollectionRequest b, @AuthenticationPrincipal AppUserPrincipal user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse.of(
                collections.create(user.getUserId(), b.name(), b.description(), b.icon(), b.color())));
    }

    @PatchMapping("/collections/{id}")
    public DataResponse<CollectionService.CollectionDto> updateCollection(
            @PathVariable UUID id, @Valid @RequestBody CollectionPatchRequest b,
            @AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(collections.update(user.getUserId(), id,
                new CollectionService.Patch(b.name(), b.description(), b.icon(), b.color(), b.sortOrder())));
    }

    @DeleteMapping("/collections/{id}")
    public ResponseEntity<Void> deleteCollection(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        collections.delete(user.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
