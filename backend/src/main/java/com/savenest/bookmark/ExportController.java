package com.savenest.bookmark;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.common.ApiException;
import com.savenest.organize.CollectionService;
import com.savenest.organize.TagService;
import com.savenest.ratelimit.RateLimiter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/** {@code GET /api/export?format=json|csv}: the current user's data only, as a download. */
@RestController
@RequestMapping("/api")
public class ExportController {

    public static final int FORMAT_VERSION = 1;

    public record JsonExport(int version, Instant exportedAt, List<TagService.TagDto> tags,
                             List<CollectionService.CollectionDto> collections, List<BookmarkDto> bookmarks) {
    }

    static final List<String> CSV_COLUMNS = List.of("id", "url", "title", "description", "notes", "group", "platform",
            "content_type", "subtype", "favorite", "lifecycle_state", "progress_state", "trashed_at", "tags",
            "collections", "metadata_status", "created_at", "updated_at", "last_opened_at", "open_count");

    private final BookmarkService bookmarks;
    private final TagService tags;
    private final CollectionService collections;
    private final RateLimiter rateLimiter;

    public ExportController(BookmarkService bookmarks, TagService tags, CollectionService collections,
                            RateLimiter rateLimiter) {
        this.bookmarks = bookmarks;
        this.tags = tags;
        this.collections = collections;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam(defaultValue = "json") String format,
                                    @AuthenticationPrincipal AppUserPrincipal user) {
        if (!format.equals("json") && !format.equals("csv")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "format must be json or csv");
        }
        rateLimiter.check(RateLimiter.Bucket.EXPORT, user.getUserId().toString());
        var owner = user.getUserId();
        List<BookmarkDto> rows = bookmarks.exportAll(owner);
        String name = "savenest-export-" + LocalDate.now(ZoneOffset.UTC) + "." + format;
        var builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (format.equals("csv")) {
            return builder.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(toCsv(rows).getBytes(StandardCharsets.UTF_8));
        }
        return builder.contentType(MediaType.APPLICATION_JSON)
                .body(new JsonExport(FORMAT_VERSION, Instant.now(), tags.list(owner), collections.list(owner), rows));
    }

    static String toCsv(List<BookmarkDto> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM so spreadsheet apps detect UTF-8
        sb.append(String.join(",", CSV_COLUMNS)).append("\r\n");
        for (BookmarkDto b : rows) {
            List<Object> cells = List.of(b.id(), b.url(), nz(b.title()), nz(b.description()), nz(b.notes()),
                    b.groupId(), nz(b.platform()), nz(b.contentType()), nz(b.subtype()), b.favorite(),
                    b.lifecycleState(), b.progressState(), nz(b.deletedAt()),
                    b.tags().stream().map(BookmarkDto.TagRef::name).collect(Collectors.joining("; ")),
                    b.collections().stream().map(BookmarkDto.CollectionRef::name).collect(Collectors.joining("; ")),
                    b.metadataStatus(), b.createdAt(), b.updatedAt(), nz(b.lastOpenedAt()), b.openCount());
            sb.append(cells.stream().map(c -> cell(String.valueOf(c))).collect(Collectors.joining(","))).append("\r\n");
        }
        return sb.toString();
    }

    private static Object nz(Object o) {
        return o == null ? "" : o;
    }

    /** RFC 4180 quoting plus neutralizing spreadsheet formula injection (cells starting with = + - @ tab CR). */
    static String cell(String v) {
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
