package com.savenest.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.Locale;

/**
 * Pure HTML metadata extraction with jsoup. Scripts are never executed; JSON-LD is parsed as data only.
 * Order: Open Graph / Twitter card, JSON-LD, document title / meta description.
 */
public final class HtmlMetadataExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_TITLE = 500;
    static final int MAX_DESCRIPTION = 2000;

    private HtmlMetadataExtractor() {
    }

    public static PageMetadata extract(String html, URI baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl.toString());
        String jsonLdType = null, ldName = null, ldDesc = null, ldImage = null;
        for (Element s : doc.select("script[type=application/ld+json]")) {
            JsonNode node = parseLd(s.data());
            if (node == null) continue;
            if (jsonLdType == null) jsonLdType = text(node.get("@type"));
            if (ldName == null) ldName = text(node.get("name"), node.get("headline"));
            if (ldDesc == null) ldDesc = text(node.get("description"));
            if (ldImage == null) ldImage = imageOf(node.get("image"));
            if (jsonLdType != null && ldName != null) break;
        }

        String title = first(meta(doc, "og:title"), meta(doc, "twitter:title"), ldName, doc.title());
        String description = first(meta(doc, "og:description"), meta(doc, "twitter:description"), ldDesc,
                meta(doc, "description"));
        String image = absolute(baseUrl, first(meta(doc, "og:image:secure_url"), meta(doc, "og:image"),
                meta(doc, "twitter:image"), meta(doc, "twitter:image:src"), ldImage));
        String siteName = first(meta(doc, "og:site_name"), meta(doc, "application-name"));
        Element canonicalEl = doc.selectFirst("link[rel=canonical][href]");
        String canonical = absolute(baseUrl, canonicalEl == null ? null : canonicalEl.attr("href"));
        Element iconEl = doc.selectFirst("link[rel~=(?i)^(shortcut )?icon$][href], link[rel=apple-touch-icon][href]");
        String favicon = absolute(baseUrl, iconEl != null ? iconEl.attr("href") : "/favicon.ico");
        String ogType = meta(doc, "og:type");

        return new PageMetadata(clean(title, MAX_TITLE), clean(description, MAX_DESCRIPTION), image, favicon,
                clean(siteName, 200), canonical, ogType == null ? null : ogType.toLowerCase(Locale.ROOT),
                jsonLdType == null ? null : clean(jsonLdType, 60), "html");
    }

    private static JsonNode parseLd(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n == null) return null;
            if (n.isArray()) n = n.size() > 0 ? n.get(0) : null;
            if (n != null && n.has("@graph") && n.get("@graph").isArray() && n.get("@graph").size() > 0) {
                for (JsonNode g : n.get("@graph")) {
                    if (g.has("name") || g.has("headline")) return g;
                }
                return n.get("@graph").get(0);
            }
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    private static String imageOf(JsonNode n) {
        if (n == null) return null;
        if (n.isTextual()) return n.asText();
        if (n.isArray() && n.size() > 0) return imageOf(n.get(0));
        if (n.isObject()) return text(n.get("url"));
        return null;
    }

    private static String text(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n == null) continue;
            if (n.isTextual() && !n.asText().isBlank()) return n.asText();
            if (n.isArray() && n.size() > 0 && n.get(0).isTextual()) return n.get(0).asText();
        }
        return null;
    }

    private static String meta(Document doc, String key) {
        Element e = doc.selectFirst("meta[property=" + key + "][content], meta[name=" + key + "][content]");
        if (e == null) return null;
        String v = e.attr("content").strip();
        return v.isEmpty() ? null : v;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Collapses whitespace, strips control characters and any markup, truncates. Output is plain text. */
    static String clean(String s, int max) {
        if (s == null) return null;
        String t = Jsoup.parse(s).text();
        t = t.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("\\s+", " ").strip();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** Resolves against the page URL; only http(s) results are kept (no data:, javascript:, etc.). */
    static String absolute(URI base, String href) {
        if (href == null || href.isBlank() || href.length() > 2000) return null;
        try {
            URI u = base.resolve(href.strip().replace(" ", "%20"));
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (u.getHost() == null || u.getRawUserInfo() != null) return null;
            return u.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
