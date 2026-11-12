package com.savenest.classifier;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure, network-free URL validation and interpretation. */
public final class UrlParser {

    public static final int MAX_LENGTH = 2048;

    private static final Pattern HAS_AUTHORITY_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://.*", Pattern.DOTALL);
    private static final Pattern SCHEME_PREFIX = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):.*", Pattern.DOTALL);
    private static final Pattern HOST_LABELS = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)*$");

    private UrlParser() {
    }

    public static ParsedUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "URL is required");
        }
        String input = raw.strip();
        if (input.length() > MAX_LENGTH) {
            throw new InvalidUrlException(InvalidUrlException.Code.URL_TOO_LONG, "URL is too long");
        }
        if (input.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "URL must not contain spaces");
        }

        boolean schemeAdded = false;
        String candidate = input;
        if (!HAS_AUTHORITY_SCHEME.matcher(input).matches()) {
            var m = SCHEME_PREFIX.matcher(input);
            // "javascript:x", "mailto:x" are schemes; "example.com:8080/x" is a host with a port.
            if (m.matches() && !m.group(1).contains(".")) {
                throw new InvalidUrlException(InvalidUrlException.Code.UNSUPPORTED_SCHEME, "Only http and https links are supported");
            }
            if (input.startsWith("/") || !hostPart(input).contains(".")) {
                throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Not a recognizable web address");
            }
            candidate = "https://" + input;
            schemeAdded = true;
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Malformed URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidUrlException(InvalidUrlException.Code.UNSUPPORTED_SCHEME, "Only http and https links are supported");
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "URL has no host");
        }
        if (authority.contains("@")) {
            throw new InvalidUrlException(InvalidUrlException.Code.CREDENTIALS_NOT_ALLOWED, "Links with embedded credentials are not allowed");
        }

        String hostRaw;
        int port = -1;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Malformed IPv6 host");
            }
            hostRaw = authority.substring(0, close + 1);
            port = parsePort(authority.substring(close + 1));
        } else {
            int colon = authority.lastIndexOf(':');
            hostRaw = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? -1 : parsePort(authority.substring(colon));
        }

        String host = normalizeHost(hostRaw);
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            port = -1;
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        String path = uri.getRawPath();
        sb.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            sb.append('#').append(uri.getRawFragment());
        }
        try {
            return new ParsedUrl(input, new URI(sb.toString()), host, schemeAdded);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Malformed URL");
        }
    }

    private static String hostPart(String noScheme) {
        int end = noScheme.length();
        for (char c : new char[]{'/', '?', '#', ':'}) {
            int i = noScheme.indexOf(c);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return noScheme.substring(0, end);
    }

    private static int parsePort(String s) {
        if (s.isEmpty()) {
            return -1;
        }
        if (!s.matches(":\\d{1,5}")) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Invalid port");
        }
        int p = Integer.parseInt(s.substring(1));
        if (p < 1 || p > 65535) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Invalid port");
        }
        return p;
    }

    private static String normalizeHost(String hostRaw) {
        if (hostRaw.startsWith("[")) {
            return hostRaw.toLowerCase(Locale.ROOT);
        }
        String h = hostRaw;
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Invalid host name");
        }
        if (ascii.isEmpty() || ascii.length() > 253 || !HOST_LABELS.matcher(ascii).matches()) {
            throw new InvalidUrlException(InvalidUrlException.Code.INVALID_URL, "Invalid host name");
        }
        return ascii;
    }
}
