package com.savenest.metadata;

import com.savenest.classifier.InvalidUrlException;
import com.savenest.classifier.ParsedUrl;
import com.savenest.classifier.UrlParser;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSRF-restricted fetcher (OWASP SSRF Prevention cheat sheet):
 * <ul>
 *   <li>http/https only, no embedded credentials, allowlisted ports, internal hostnames rejected;</li>
 *   <li>DNS is resolved once by a filtering {@link DnsResolver}; if <em>any</em> candidate address is
 *       non-public the request fails, and the connection uses exactly the validated addresses, so DNS
 *       rebinding between check and connect is not possible;</li>
 *   <li>redirects are followed manually; every hop is re-parsed and re-validated, with a hop limit;</li>
 *   <li>no proxies, cookies, auth headers or caller headers; bounded timeouts, body and decompressed size;</li>
 *   <li>accepted content types are allowlisted per call.</li>
 * </ul>
 * Network-level egress restriction for the worker is still required in deployment (see README).
 */
@Component
public class HttpSafeFetcher implements Fetcher, AutoCloseable {

    private final FetchProperties props;
    private final AddressPolicy policy;
    private final CloseableHttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpSafeFetcher(FetchProperties props) {
        this(props, AddressPolicy.STRICT, SystemDefaultDnsResolver.INSTANCE);
    }

    HttpSafeFetcher(FetchProperties props, AddressPolicy policy, DnsResolver dns) {
        this.props = props;
        this.policy = policy;
        DnsResolver filtering = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] all = dns.resolve(host);
                if (all == null || all.length == 0) throw new UnknownHostException(host);
                for (InetAddress a : all) {
                    if (!policy.isAllowedAddress(a)) throw new BlockedAddressException(host);
                }
                return all;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(filtering)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(props.connectTimeoutMs()))
                        .setSocketTimeout(Timeout.ofMilliseconds(props.readTimeoutMs()))
                        .build())
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(2)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(cm)
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .setProxy(null) // never use system/env proxies
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(props.readTimeoutMs()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(props.connectTimeoutMs()))
                        .setRedirectsEnabled(false)
                        .build())
                .setUserAgent(props.userAgent())
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    /** Thrown inside DNS resolution; unwrapped to BLOCKED_TARGET. */
    static final class BlockedAddressException extends UnknownHostException {
        BlockedAddressException(String host) {
            super("Destination is not a public address: " + host);
        }
    }

    @Override
    public Response get(String url, Set<String> accepted, int maxBytes) throws FetchException {
        long deadline = System.currentTimeMillis() + props.totalTimeoutMs();
        URI current = validate(url);
        for (int hop = 0; ; hop++) {
            if (System.currentTimeMillis() > deadline) {
                throw new FetchException(FetchException.Category.TIMEOUT, "Total fetch time exceeded");
            }
            HttpGet req = new HttpGet(current);
            req.setHeader(HttpHeaders.ACCEPT, String.join(", ", accepted) + ", */*;q=0.1");
            req.setHeader(HttpHeaders.ACCEPT_LANGUAGE, "en;q=0.9, *;q=0.5");
            AtomicReference<String> location = new AtomicReference<>();
            URI requestUri = current;
            final int hopNo = hop;
            try {
                Response r = client.execute(req, res -> {
                    int code = res.getCode();
                    if (code >= 300 && code < 400 && code != 304) {
                        Header loc = res.getFirstHeader(HttpHeaders.LOCATION);
                        location.set(loc == null ? "" : loc.getValue());
                        return null;
                    }
                    if (code >= 400) {
                        return new Response(requestUri, code, null, null, new byte[0], hopNo);
                    }
                    HttpEntity entity = res.getEntity();
                    if (entity == null) {
                        return new Response(requestUri, code, null, null, new byte[0], hopNo);
                    }
                    ContentType ct = parseType(entity.getContentType());
                    String mime = ct == null ? "" : ct.getMimeType().toLowerCase(Locale.ROOT);
                    if (!accepts(accepted, mime)) {
                        return new Response(requestUri, -1, mime, null, new byte[0], hopNo);
                    }
                    if (entity.getContentLength() > maxBytes) {
                        return new Response(requestUri, -2, mime, null, new byte[0], hopNo);
                    }
                    byte[] body = readBounded(entity.getContent(), maxBytes, deadline);
                    if (body == null) {
                        return new Response(requestUri, -2, mime, null, new byte[0], hopNo);
                    }
                    Charset cs = ct != null && ct.getCharset() != null ? ct.getCharset() : null;
                    return new Response(requestUri, code, mime, cs, body, hopNo);
                });
                if (r == null) {
                    if (hop >= props.maxRedirects()) {
                        throw new FetchException(FetchException.Category.TOO_MANY_REDIRECTS, "Redirect limit reached");
                    }
                    String loc = location.get();
                    if (loc == null || loc.isBlank()) {
                        throw new FetchException(FetchException.Category.HTTP_ERROR, "Redirect without Location");
                    }
                    URI next;
                    try {
                        next = current.resolve(loc.strip());
                    } catch (IllegalArgumentException e) {
                        throw new FetchException(FetchException.Category.INVALID_URL, "Malformed redirect");
                    }
                    current = validate(next.toString());
                    continue;
                }
                return check(r);
            } catch (FetchException e) {
                throw e;
            } catch (BlockedAddressException e) {
                throw new FetchException(FetchException.Category.BLOCKED_TARGET, "Destination not allowed");
            } catch (UnknownHostException e) {
                if (e.getCause() instanceof BlockedAddressException || e instanceof BlockedAddressException) {
                    throw new FetchException(FetchException.Category.BLOCKED_TARGET, "Destination not allowed");
                }
                throw new FetchException(FetchException.Category.NETWORK, "Host could not be resolved");
            } catch (InterruptedIOException e) {
                throw new FetchException(FetchException.Category.TIMEOUT, "Fetch timed out");
            } catch (IOException e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                if (root instanceof BlockedAddressException) {
                    throw new FetchException(FetchException.Category.BLOCKED_TARGET, "Destination not allowed");
                }
                throw new FetchException(FetchException.Category.NETWORK, "Network error");
            }
        }
    }

    private static Response check(Response r) throws FetchException {
        int s = r.status();
        if (s == -1) throw new FetchException(FetchException.Category.UNSUPPORTED_TYPE, "Content type not accepted");
        if (s == -2) throw new FetchException(FetchException.Category.TOO_LARGE, "Response too large");
        if (s == 401 || s == 403 || s == 451) throw new FetchException(FetchException.Category.ACCESS_DENIED, "Access denied by site");
        if (s == 404 || s == 410) throw new FetchException(FetchException.Category.NOT_FOUND, "Not found");
        if (s == 429) throw new FetchException(FetchException.Category.RATE_LIMITED, "Rate limited by site");
        if (s >= 500) throw new FetchException(FetchException.Category.SERVER_ERROR, "Server error " + s);
        if (s >= 400) throw new FetchException(FetchException.Category.HTTP_ERROR, "HTTP " + s);
        return r;
    }

    /** Re-parses with the same parser used for saving, then applies port and hostname/literal-IP policy. */
    URI validate(String url) throws FetchException {
        ParsedUrl p;
        try {
            p = UrlParser.parse(url);
        } catch (InvalidUrlException e) {
            throw new FetchException(FetchException.Category.INVALID_URL, "Invalid URL");
        }
        URI u = p.uri();
        int port = u.getPort() == -1 ? (u.getScheme().equals("https") ? 443 : 80) : u.getPort();
        if (!props.allowedPorts().contains(port)) {
            throw new FetchException(FetchException.Category.BLOCKED_TARGET, "Port not allowed");
        }
        String host = p.host();
        boolean literal = host.startsWith("[") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (literal) {
            try {
                InetAddress a = InetAddress.getByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
                if (!policy.isAllowedAddress(a)) {
                    throw new FetchException(FetchException.Category.BLOCKED_TARGET, "Destination not allowed");
                }
            } catch (UnknownHostException e) {
                throw new FetchException(FetchException.Category.INVALID_URL, "Invalid IP literal");
            }
        } else if (!policy.isAllowedHostname(host)) {
            throw new FetchException(FetchException.Category.BLOCKED_TARGET, "Destination not allowed");
        }
        // Fragments are never sent to servers.
        return URI.create(u.toString().split("#", 2)[0]);
    }

    private static ContentType parseType(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return ContentType.parse(header);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean accepts(Set<String> accepted, String mime) {
        for (String a : accepted) {
            if (a.endsWith("/*") ? mime.startsWith(a.substring(0, a.length() - 1)) : a.equals(mime)) return true;
        }
        return false;
    }

    /** Reads the (already decompressed) stream; returns null if more than max bytes would be read. */
    private static byte[] readBounded(InputStream in, int max, long deadline) throws IOException {
        try (in) {
            byte[] buf = new byte[Math.min(max + 1, 64 * 1024)];
            byte[] out = new byte[Math.min(max, 64 * 1024)];
            int len = 0, n;
            while ((n = in.read(buf)) != -1) {
                if (len + n > max) return null;
                if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("deadline");
                if (len + n > out.length) out = Arrays.copyOf(out, Math.min(max, Math.max(out.length * 2, len + n)));
                System.arraycopy(buf, 0, out, len, n);
                len += n;
            }
            return Arrays.copyOf(out, len);
        }
    }

    static Charset charsetOr(Charset c) {
        return c != null ? c : StandardCharsets.UTF_8;
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
