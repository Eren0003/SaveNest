package com.savenest.classifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Conservative URL identity. Normalizes scheme/host casing and default ports (done by {@link UrlParser}),
 * strips only an explicit allowlist of tracking parameters, and preserves paths, fragments and functional params.
 */
public final class UrlIdentity {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "gclid", "fbclid", "mc_cid", "mc_eid", "igshid", "si");

    private UrlIdentity() {
    }

    public static String normalizedUrl(ParsedUrl u) {
        var uri = u.uri();
        StringBuilder sb = new StringBuilder(uri.getScheme()).append("://").append(uri.getRawAuthority()).append(u.path());
        String q = uri.getRawQuery();
        if (q != null) {
            List<String> kept = new ArrayList<>();
            for (String pair : q.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String key = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                if (!TRACKING_PARAMS.contains(key)) {
                    kept.add(pair);
                }
            }
            if (!kept.isEmpty()) {
                sb.append('?').append(String.join("&", kept));
            }
        }
        if (uri.getRawFragment() != null) {
            sb.append('#').append(uri.getRawFragment());
        }
        return sb.toString();
    }

    /**
     * Platform resource identity when the classifier extracted one (e.g. YouTube video ID across watch/youtu.be/shorts),
     * otherwise a SHA-256 of the normalized URL. Playlists and videos use different content types, so they never collide.
     */
    public static String dedupeKey(ParsedUrl u, Classification c) {
        if (c.resourceId() != null && c.source() == Classification.Source.URL_RULE) {
            return c.platform() + ":" + c.contentType() + ":" + c.resourceId();
        }
        return "url:" + sha256(normalizedUrl(u));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
