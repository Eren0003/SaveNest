package com.savenest.metadata;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.bookmark.BookmarkRepository;
import com.savenest.common.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Same-origin proxy for preview images. Remote images are fetched only through the restricted fetcher,
 * limited to raster types whose bytes match the declared type (no SVG), and served with nosniff and a
 * sandbox CSP. The browser never contacts third-party hosts, so the app CSP stays {@code img-src 'self'}.
 */
@RestController
public class PreviewImageController {

    private final BookmarkRepository repo;
    private final Fetcher fetcher;
    private final FetchProperties props;

    public PreviewImageController(BookmarkRepository repo, Fetcher fetcher, FetchProperties props) {
        this.repo = repo;
        this.fetcher = fetcher;
        this.props = props;
    }

    @GetMapping("/api/bookmarks/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        var b = repo.findByIdAndOwnerId(id, user.getUserId()).orElseThrow(ApiException::notFound);
        if (b.getPreviewImageUrl() == null) throw ApiException.notFound();
        try {
            Fetcher.Response r = fetcher.get(b.getPreviewImageUrl(), MetadataEnricher.IMAGE_TYPES, props.maxImageBytes());
            if (!ImageSniffer.matches(r.body(), r.mimeType())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_image", "Preview is not a valid image");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(r.mimeType()))
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePrivate())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .body(r.body());
        } catch (FetchException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "preview_unavailable", "Preview image unavailable");
        }
    }
}
