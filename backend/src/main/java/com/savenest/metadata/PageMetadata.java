package com.savenest.metadata;

/**
 * Untrusted metadata extracted from a page or provider. All text is plain text (HTML is never kept);
 * all URLs are absolute http(s) and must still go through the fetcher before being loaded.
 *
 * @param source      "oembed", "html", "image" or "fallback"
 * @param ogType      raw og:type, lowercase, may be null
 * @param jsonLdType  first schema.org @type found in JSON-LD, may be null
 */
public record PageMetadata(String title, String description, String imageUrl, String faviconUrl,
                           String siteName, String canonicalUrl, String ogType, String jsonLdType,
                           String source) {

    public static PageMetadata empty(String source) {
        return new PageMetadata(null, null, null, null, null, null, null, null, source);
    }

    public boolean hasCoreFields() {
        return title != null;
    }
}
