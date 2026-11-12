package com.savenest.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.savenest.classifier.Classification;
import com.savenest.classifier.InvalidUrlException;
import com.savenest.classifier.ParsedUrl;
import com.savenest.classifier.UrlClassifier;
import com.savenest.classifier.UrlIdentity;
import com.savenest.classifier.UrlParser;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Network side of enrichment, free of database access so it can be unit-tested with a fake {@link Fetcher}.
 * Extraction order: URL identity, approved provider endpoint (YouTube oEmbed, no key), public HTML
 * metadata (OG/Twitter/JSON-LD/title), then hostname fallback. Never bypasses blocking or logins.
 */
@Component
public class MetadataEnricher {

    /** What the worker needs to know about the bookmark; a snapshot, not the entity. */
    public record Input(String originalUrl, String platform, String contentType, String subtype, String classSource) {
    }

    /**
     * @param status          ready, partial, failed or blocked
     * @param errorCode       FetchException category code, null on success
     * @param transientError  true if the worker should retry
     * @param reclassified    new classification after short-link resolution, or null
     */
    public record Outcome(String status, String errorCode, boolean transientError, PageMetadata metadata,
                          String resolvedUrl, Classification reclassified, String reclassNormalizedUrl,
                          String reclassDedupeKey, TypeRefinement refinement) {
    }

    /** Metadata-driven refinement; {@code group == null} means "keep group, refine type only". */
    public record TypeRefinement(String group, String contentType, String subtype, String reason) {
    }

    static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");
    /** SVG is intentionally excluded (active content). */
    public static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Fetcher fetcher;
    private final FetchProperties props;

    public MetadataEnricher(Fetcher fetcher, FetchProperties props) {
        this.fetcher = fetcher;
        this.props = props;
    }

    public Outcome enrich(Input in) {
        try {
            if ("youtube".equals(in.platform())) {
                PageMetadata m = youtubeOembed(in.originalUrl());
                if (m != null) return success(m, null, null, null);
                // fall through to HTML if oEmbed is unavailable (e.g. channels have no oEmbed)
            }
            if ("image".equals(in.contentType())) {
                return probeImage(in);
            }
            Set<String> accept = new java.util.HashSet<>(HTML_TYPES);
            accept.addAll(IMAGE_TYPES);
            Fetcher.Response r = fetcher.get(in.originalUrl(), accept, props.maxHtmlBytes());
            String finalUrl = r.finalUrl().toString();
            boolean redirected = r.redirects() > 0;

            Classification re = null;
            String reNorm = null, reKey = null;
            if (redirected && "short_link".equals(in.contentType())) {
                try {
                    ParsedUrl p = UrlParser.parse(finalUrl);
                    re = UrlClassifier.classify(p);
                    reNorm = UrlIdentity.normalizedUrl(p);
                    reKey = UrlIdentity.dedupeKey(p, re);
                } catch (InvalidUrlException ignored) {
                    // keep original classification
                }
            }

            if (IMAGE_TYPES.contains(r.mimeType())) {
                PageMetadata m = new PageMetadata(null, null, finalUrl, null, null, null, null, null, "image");
                TypeRefinement t = in.classSource().equals("url_rule") ? null
                        : new TypeRefinement("images", "image", "verified", "Server returned " + r.mimeType());
                return new Outcome("partial", null, false, m, redirected ? finalUrl : null, re, reNorm, reKey, t);
            }

            String html = new String(r.body(), HttpSafeFetcher.charsetOr(r.charset()));
            PageMetadata m = HtmlMetadataExtractor.extract(html, r.finalUrl());
            TypeRefinement t = refine(in, re, m);
            return success(m, redirected ? finalUrl : null, re, t, reNorm, reKey);
        } catch (FetchException e) {
            return failure(e);
        }
    }

    private Outcome probeImage(Input in) {
        try {
            Set<String> accept = new java.util.HashSet<>(IMAGE_TYPES);
            accept.addAll(HTML_TYPES);
            Fetcher.Response r = fetcher.get(in.originalUrl(), accept, props.maxImageBytes());
            if (IMAGE_TYPES.contains(r.mimeType()) && ImageSniffer.matches(r.body(), r.mimeType())) {
                PageMetadata m = new PageMetadata(null, null, r.finalUrl().toString(), null, null, null, null, null, "image");
                return new Outcome("partial", null, false, m, r.redirects() > 0 ? r.finalUrl().toString() : null,
                        null, null, null, new TypeRefinement(null, "image", "verified",
                        "Image extension confirmed by MIME type " + r.mimeType()));
            }
            if (HTML_TYPES.contains(r.mimeType())) {
                PageMetadata m = HtmlMetadataExtractor.extract(
                        new String(r.body(), HttpSafeFetcher.charsetOr(r.charset())), r.finalUrl());
                return success(m, null, null, new TypeRefinement("unsorted", "unknown", null,
                        "Image-like URL returned an HTML page, not an image"));
            }
            return new Outcome("failed", "unsupported_type", false, PageMetadata.empty("fallback"), null, null,
                    null, null, null);
        } catch (FetchException e) {
            return failure(e);
        }
    }

    /** Official oEmbed endpoint; returns null when unavailable so HTML extraction can be tried. */
    private PageMetadata youtubeOembed(String url) throws FetchException {
        String endpoint = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        try {
            Fetcher.Response r = fetcher.get(endpoint, Set.of("application/json", "text/json"), 64 * 1024);
            JsonNode n = MAPPER.readTree(r.body());
            String title = HtmlMetadataExtractor.clean(n.path("title").asText(null), HtmlMetadataExtractor.MAX_TITLE);
            if (title == null) return null;
            String author = HtmlMetadataExtractor.clean(n.path("author_name").asText(null), 200);
            String thumb = HtmlMetadataExtractor.absolute(r.finalUrl(), n.path("thumbnail_url").asText(null));
            return new PageMetadata(title, author == null ? null : "by " + author, thumb,
                    "https://www.youtube.com/favicon.ico", "YouTube", null, null, null, "oembed");
        } catch (FetchException e) {
            if (e.category().isTransient()) throw e;
            return null; // 401/404 (private/removed/non-video) -> try HTML
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Rule priority: URL rules win; metadata only refines uncertain types or ambiguous media titles. */
    static TypeRefinement refine(Input in, Classification reclassified, PageMetadata m) {
        if (reclassified != null) return null;
        String og = m.ogType() == null ? "" : m.ogType();
        String ld = m.jsonLdType() == null ? "" : m.jsonLdType().toLowerCase(Locale.ROOT);
        if ("media_title".equals(in.contentType())) {
            if (og.equals("video.movie") || ld.equals("movie")) {
                return new TypeRefinement(null, "movie", null, "Page metadata identifies a movie");
            }
            if (og.equals("video.tv_show") || ld.equals("tvseries")) {
                return new TypeRefinement(null, "series", null, "Page metadata identifies a series");
            }
            return null;
        }
        boolean weak = in.classSource().equals("fallback") || in.classSource().equals("domain_default");
        if (weak && "unknown".equals(in.contentType())) {
            if (og.equals("article") || Set.of("article", "newsarticle", "blogposting", "techarticle").contains(ld)) {
                return new TypeRefinement("articles", "article", null, "Page metadata identifies an article");
            }
        }
        return null;
    }

    private Outcome success(PageMetadata m, String resolved, Classification re, TypeRefinement t) {
        return success(m, resolved, re, t, null, null);
    }

    private Outcome success(PageMetadata m, String resolved, Classification re, TypeRefinement t,
                            String reNorm, String reKey) {
        String status = m.title() != null && (m.description() != null || m.imageUrl() != null) ? "ready" : "partial";
        return new Outcome(status, null, false, m, resolved, re, reNorm, reKey, t);
    }

    private static Outcome failure(FetchException e) {
        FetchException.Category c = e.category();
        return new Outcome(c.isBlocked() ? "blocked" : "failed", c.code(), c.isTransient(),
                PageMetadata.empty("fallback"), null, null, null, null, null);
    }
}
