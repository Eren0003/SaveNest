package com.savenest.bookmark;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.common.ApiException;
import com.savenest.common.DataResponse;
import com.savenest.ratelimit.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class BookmarkController {

    public record CreateRequest(@NotBlank @Size(max = 2100) String url,
                                @Size(max = 10000) String notes,
                                @Size(max = 20) String groupId,
                                @Size(max = 20) String progressState,
                                @Size(max = 20) List<@Size(max = 100) String> tagNames,
                                @Size(max = 20) List<UUID> collectionIds) {
    }

    public record UpdateRequest(@Size(max = 500) String title,
                                @Size(max = 5000) String description,
                                @Size(max = 10000) String notes,
                                @Size(max = 20) String groupId,
                                Boolean clearGroupOverride,
                                Boolean favorite,
                                @Size(max = 10) String lifecycleState,
                                @Size(max = 20) String progressState,
                                @Size(max = 20) List<@Size(max = 100) String> tagNames,
                                @Size(max = 20) List<UUID> collectionIds) {
    }

    private final BookmarkService service;
    private final RateLimiter rateLimiter;

    public BookmarkController(BookmarkService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/bookmarks")
    public ResponseEntity<DataResponse<BookmarkDto>> create(@Valid @RequestBody CreateRequest body,
                                                            @AuthenticationPrincipal AppUserPrincipal user) {
        rateLimiter.check(RateLimiter.Bucket.BOOKMARK_CREATE, user.getUserId().toString());
        BookmarkDto dto = service.create(user.getUserId(), body.url(), body.notes(), body.groupId(), body.progressState(),
                body.tagNames(), body.collectionIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse.of(dto));
    }

    @GetMapping("/bookmarks")
    public DataResponse<BookmarkService.Page> list(
            @RequestParam(defaultValue = "active") String view,
            @RequestParam(required = false) String group,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String subtype,
            @RequestParam(required = false) String progress,
            @RequestParam(required = false) List<UUID> tag,
            @RequestParam(required = false) UUID collection,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal AppUserPrincipal user) {
        BookmarkService.View v;
        try {
            v = BookmarkService.View.valueOf(view.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "view must be active, archived or trash");
        }
        var query = new BookmarkService.Query(v, group, platform, favorite, q, contentType, subtype, progress,
                tag, collection, from, to, sort);
        return DataResponse.of(service.list(user.getUserId(), query, limit, cursor));
    }

    @GetMapping("/bookmarks/{id}")
    public DataResponse<BookmarkDto> get(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(service.get(user.getUserId(), id));
    }

    @PatchMapping("/bookmarks/{id}")
    public DataResponse<BookmarkDto> update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest b,
                                            @AuthenticationPrincipal AppUserPrincipal user) {
        var u = new BookmarkService.Update(b.title(), b.description(), b.notes(), b.groupId(),
                Boolean.TRUE.equals(b.clearGroupOverride()), b.favorite(), b.lifecycleState(), b.progressState(),
                b.tagNames(), b.collectionIds());
        return DataResponse.of(service.update(user.getUserId(), id, u));
    }

    @DeleteMapping("/bookmarks/{id}")
    public ResponseEntity<Void> trash(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        service.trash(user.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bookmarks/{id}/restore")
    public DataResponse<BookmarkDto> restore(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(service.restore(user.getUserId(), id));
    }

    /** Irreversible; requires {@code ?confirm=true} and the bookmark must already be in trash. */
    @DeleteMapping("/bookmarks/{id}/permanent")
    public ResponseEntity<Void> deletePermanently(@PathVariable UUID id,
                                                  @RequestParam(defaultValue = "false") boolean confirm,
                                                  @AuthenticationPrincipal AppUserPrincipal user) {
        requireConfirm(confirm);
        service.deletePermanently(user.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    public record EmptyTrashResult(int deleted) {
    }

    /** Irreversible; requires {@code ?confirm=true}. */
    @DeleteMapping("/trash")
    public DataResponse<EmptyTrashResult> emptyTrash(@RequestParam(defaultValue = "false") boolean confirm,
                                                     @AuthenticationPrincipal AppUserPrincipal user) {
        requireConfirm(confirm);
        return DataResponse.of(new EmptyTrashResult(service.emptyTrash(user.getUserId())));
    }

    private static void requireConfirm(boolean confirm) {
        if (!confirm) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "confirmation_required",
                    "Permanent deletion requires confirm=true");
        }
    }

    @PostMapping("/bookmarks/{id}/refresh")
    public ResponseEntity<DataResponse<BookmarkDto>> refresh(@PathVariable UUID id,
                                                             @AuthenticationPrincipal AppUserPrincipal user) {
        rateLimiter.check(RateLimiter.Bucket.METADATA_REFRESH, user.getUserId().toString());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DataResponse.of(service.refresh(user.getUserId(), id)));
    }

    @PostMapping("/bookmarks/{id}/open")
    public DataResponse<BookmarkDto> open(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(service.recordOpen(user.getUserId(), id));
    }

    /** Groups with real counts and platform breakdown (trash and archive excluded). */
    @GetMapping("/groups")
    public DataResponse<List<BookmarkService.GroupCount>> groups(@AuthenticationPrincipal AppUserPrincipal user) {
        return DataResponse.of(service.groupCounts(user.getUserId()));
    }
}
