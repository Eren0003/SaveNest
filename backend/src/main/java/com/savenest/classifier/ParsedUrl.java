package com.savenest.classifier;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * A validated http(s) URL with a lowercase ASCII (punycode) host and no default port.
 *
 * @param input        trimmed user input, preserved as the original URL
 * @param uri          interpreted URL (scheme/host normalized, path/query/fragment untouched)
 * @param host         lowercase ASCII host without trailing dot
 * @param schemeAdded  true if https:// was prepended to scheme-less input
 */
public record ParsedUrl(String input, URI uri, String host, boolean schemeAdded) {

    /** Raw path, never empty ("/" at minimum). */
    public String path() {
        String p = uri.getRawPath();
        return p == null || p.isEmpty() ? "/" : p;
    }

    /** Non-empty decoded path segments. */
    public List<String> segments() {
        List<String> out = new ArrayList<>();
        for (String s : path().split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** First value of a query parameter (raw), or null. */
    public String query(String name) {
        String q = uri.getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                return eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** Exact host or a subdomain of {@code domain}; never a loose substring match. */
    public boolean hostIs(String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    public boolean hostIsAny(String... domains) {
        for (String d : domains) {
            if (hostIs(d)) {
                return true;
            }
        }
        return false;
    }
}
