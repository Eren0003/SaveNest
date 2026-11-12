package com.savenest.metadata;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs a local HTTP server. "public.test" is mapped to loopback by a fake DNS and a test policy that treats
 * only 127.0.0.1 as public; everything else (incl. the real policy's ranges) stays blocked.
 */
class HttpSafeFetcherTest {

    private HttpServer server;
    private int port;
    private HttpSafeFetcher fetcher;

    private static final Set<String> HTML = Set.of("text/html");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/page", ex -> {
            byte[] b = "<html><head><title>Hi</title></head></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0); // chunked, no length header
            byte[] chunk = new byte[1024];
            for (int i = 0; i < 100; i++) ex.getResponseBody().write(chunk);
            ex.close();
        });
        server.createContext("/gzipbomb", ex -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(new byte[500_000]);
            }
            byte[] b = bos.toByteArray();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/json", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("{}".getBytes());
            ex.close();
        });
        server.createContext("/forbidden", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        server.createContext("/redirect-private", ex -> redirect(ex, "http://10.0.0.5:" + port + "/page"));
        server.createContext("/redirect-metadata", ex -> redirect(ex, "http://169.254.169.254/latest/meta-data/"));
        server.createContext("/redirect-v6", ex -> redirect(ex, "http://[::1]:" + port + "/page"));
        server.createContext("/redirect-internal", ex -> redirect(ex, "http://private.test:" + port + "/page"));
        server.createContext("/redirect-ok", ex -> redirect(ex, "/page"));
        server.createContext("/loop", ex -> redirect(ex, "/loop"));
        server.createContext("/echo-headers", ex -> {
            boolean leaked = ex.getRequestHeaders().containsKey("Cookie") || ex.getRequestHeaders().containsKey("Authorization");
            byte[] b = ("<title>" + (leaked ? "leaked" : "clean") + "</title>").getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();

        InetAddress loop = InetAddress.getByName("127.0.0.1");
        Map<String, InetAddress[]> dns = Map.of(
                "public.test", new InetAddress[]{loop},
                "private.test", new InetAddress[]{InetAddress.getByName("10.1.2.3")},
                "mixed.test", new InetAddress[]{loop, InetAddress.getByName("192.168.0.10")});
        DnsResolver fakeDns = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] a = dns.get(host);
                if (a == null) throw new UnknownHostException(host);
                return a;
            }

            public String resolveCanonicalHostname(String host) { return host; }
        };
        AddressPolicy testPolicy = new AddressPolicy() {
            public boolean isAllowedAddress(InetAddress a) { return a.equals(loop); }
            public boolean isAllowedHostname(String host) { return AddressPolicy.STRICT.isAllowedHostname(host); }
        };
        var props = new FetchProperties(2000, 2000, 5000, 10_000, 10_000, 3, List.of(80, 443, port), "test");
        fetcher = new HttpSafeFetcher(props, testPolicy, fakeDns);
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange ex, String to) throws java.io.IOException {
        ex.getResponseHeaders().add("Location", to);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(0);
        fetcher.close();
    }

    private String url(String path) {
        return "http://public.test:" + port + path;
    }

    private FetchException.Category fail(String u) {
        try {
            fetcher.get(u, HTML, 10_000);
            throw new AssertionError("expected failure for " + u);
        } catch (FetchException e) {
            return e.category();
        }
    }

    @Test
    void fetchesPublicPage() throws Exception {
        var r = fetcher.get(url("/page"), HTML, 10_000);
        assertThat(r.status()).isEqualTo(200);
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("<title>Hi</title>");
    }

    @Test
    void followsSafeRedirectAndReportsFinalUrl() throws Exception {
        var r = fetcher.get(url("/redirect-ok"), HTML, 10_000);
        assertThat(r.finalUrl().getPath()).isEqualTo("/page");
        assertThat(r.redirects()).isEqualTo(1);
    }

    @Test
    void blocksPrivateLoopbackAndMetadataTargetsDirectly() {
        assertThat(fail("http://127.0.0.2:" + port + "/page")).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        assertThat(fail("http://169.254.169.254/latest/meta-data/")).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        assertThat(fail("http://[::1]:" + port + "/page")).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        assertThat(fail("http://localhost:" + port + "/page")).isEqualTo(FetchException.Category.BLOCKED_TARGET);
    }

    @Test
    void blocksHostnamesResolvingToPrivateAddresses() {
        assertThat(fail("http://private.test:" + port + "/page")).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        // One bad candidate address is enough to reject (DNS rebinding / round-robin defence).
        assertThat(fail("http://mixed.test:" + port + "/page")).isEqualTo(FetchException.Category.BLOCKED_TARGET);
    }

    @Test
    void blocksRedirectsToPrivateTargets() {
        assertThat(fail(url("/redirect-private"))).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        assertThat(fail(url("/redirect-metadata"))).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        assertThat(fail(url("/redirect-v6"))).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        assertThat(fail(url("/redirect-internal"))).isEqualTo(FetchException.Category.BLOCKED_TARGET);
    }

    @Test
    void enforcesRedirectLimit() {
        assertThat(fail(url("/loop"))).isEqualTo(FetchException.Category.TOO_MANY_REDIRECTS);
    }

    @Test
    void rejectsDisallowedPortsAndSchemes() {
        assertThat(fail("http://public.test:6379/")).isEqualTo(FetchException.Category.BLOCKED_TARGET);
        assertThat(fail("file:///etc/passwd")).isEqualTo(FetchException.Category.INVALID_URL);
        assertThat(fail("http://user:pw@public.test:" + port + "/page")).isEqualTo(FetchException.Category.INVALID_URL);
    }

    @Test
    void rejectsOversizedAndDecompressionBombs() {
        assertThat(fail(url("/big"))).isEqualTo(FetchException.Category.TOO_LARGE);
        assertThat(fail(url("/gzipbomb"))).isEqualTo(FetchException.Category.TOO_LARGE);
    }

    @Test
    void rejectsUnacceptedContentTypeAndMapsAccessDenied() {
        assertThat(fail(url("/json"))).isEqualTo(FetchException.Category.UNSUPPORTED_TYPE);
        assertThat(fail(url("/forbidden"))).isEqualTo(FetchException.Category.ACCESS_DENIED);
    }

    @Test
    void neverSendsCookiesOrAuthHeaders() throws Exception {
        var r = fetcher.get(url("/echo-headers"), HTML, 10_000);
        assertThat(new String(r.body())).contains("clean");
    }
}
