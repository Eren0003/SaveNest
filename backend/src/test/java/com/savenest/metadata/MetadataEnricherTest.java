package com.savenest.metadata;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses a fake fetcher; no live third-party sites. */
class MetadataEnricherTest {

    private static final FetchProperties PROPS = new FetchProperties(1, 1, 1, 100_000, 100_000, 3, List.of(80, 443), "t");

    interface Handler {
        Fetcher.Response handle(String url) throws FetchException;
    }

    private static MetadataEnricher with(Handler h) {
        return new MetadataEnricher((url, accept, max) -> h.handle(url), PROPS);
    }

    private static Fetcher.Response html(String url, String body) {
        return new Fetcher.Response(URI.create(url), 200, "text/html", StandardCharsets.UTF_8,
                body.getBytes(StandardCharsets.UTF_8), 0);
    }

    @Test
    void extractsOpenGraphAndKeepsTextPlain() {
        var e = with(u -> html(u, """
                <html><head><title>Fallback</title>
                <meta property="og:title" content="Great &lt;b&gt;Headphones&lt;/b&gt;">
                <meta property="og:description" content="Noise cancelling">
                <meta property="og:image" content="/img/p.jpg">
                <meta property="og:site_name" content="Shop">
                <script>alert(1)</script></head></html>"""));
        var o = e.enrich(new MetadataEnricher.Input("https://shop.example/p/1", "generic", "unknown", null, "fallback"));
        assertThat(o.status()).isEqualTo("ready");
        assertThat(o.metadata().title()).isEqualTo("Great Headphones");
        assertThat(o.metadata().imageUrl()).isEqualTo("https://shop.example/img/p.jpg");
        assertThat(o.metadata().siteName()).isEqualTo("Shop");
    }

    @Test
    void rejectsNonHttpImageUrls() {
        var e = with(u -> html(u, "<title>x</title><meta property=\"og:image\" content=\"javascript:alert(1)\">"));
        var o = e.enrich(new MetadataEnricher.Input("https://a.example/", "generic", "unknown", null, "fallback"));
        assertThat(o.metadata().imageUrl()).isNull();
        assertThat(o.status()).isEqualTo("partial");
    }

    @Test
    void blockedSiteIsBlockedNotRetried() {
        var e = with(u -> { throw new FetchException(FetchException.Category.ACCESS_DENIED, "no"); });
        var o = e.enrich(new MetadataEnricher.Input("https://a.example/", "generic", "unknown", null, "fallback"));
        assertThat(o.status()).isEqualTo("blocked");
        assertThat(o.transientError()).isFalse();
        assertThat(o.errorCode()).isEqualTo("access_denied");
    }

    @Test
    void timeoutIsTransient() {
        var e = with(u -> { throw new FetchException(FetchException.Category.TIMEOUT, "slow"); });
        var o = e.enrich(new MetadataEnricher.Input("https://a.example/", "generic", "unknown", null, "fallback"));
        assertThat(o.status()).isEqualTo("failed");
        assertThat(o.transientError()).isTrue();
    }

    @Test
    void youtubeUsesOembed() {
        var e = with(u -> {
            assertThat(u).startsWith("https://www.youtube.com/oembed?");
            return new Fetcher.Response(URI.create(u), 200, "application/json", null, """
                    {"title":"A video","author_name":"Chan","thumbnail_url":"https://i.ytimg.com/vi/x/hq.jpg"}"""
                    .getBytes(StandardCharsets.UTF_8), 0);
        });
        var o = e.enrich(new MetadataEnricher.Input("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "youtube", "video",
                "standard", "url_rule"));
        assertThat(o.metadata().title()).isEqualTo("A video");
        assertThat(o.metadata().imageUrl()).isEqualTo("https://i.ytimg.com/vi/x/hq.jpg");
        assertThat(o.status()).isEqualTo("ready");
    }

    @Test
    void shortLinkIsReclassifiedAfterResolution() {
        var e = with(u -> new Fetcher.Response(URI.create("https://www.amazon.in/dp/B0ABCDEFGH"), 200, "text/html",
                null, "<title>Thing</title>".getBytes(), 1));
        var o = e.enrich(new MetadataEnricher.Input("https://amzn.to/abc", "amazon", "short_link", null, "domain_default"));
        assertThat(o.resolvedUrl()).isEqualTo("https://www.amazon.in/dp/B0ABCDEFGH");
        assertThat(o.reclassified()).isNotNull();
        assertThat(o.reclassified().contentType()).isEqualTo("product");
        assertThat(o.reclassDedupeKey()).isNotNull();
    }

    @Test
    void imdbTitleRefinedToMovieByMetadata() {
        var e = with(u -> html(u, "<title>X</title><meta property=\"og:type\" content=\"video.movie\">"));
        var o = e.enrich(new MetadataEnricher.Input("https://www.imdb.com/title/tt0111161/", "imdb", "media_title",
                null, "url_rule"));
        assertThat(o.refinement()).isNotNull();
        assertThat(o.refinement().contentType()).isEqualTo("movie");
        assertThat(o.refinement().group()).isNull();
    }

    @Test
    void strongUrlRuleIsNotOverriddenByGenericArticleMetadata() {
        var e = with(u -> html(u, "<title>X</title><meta property=\"og:type\" content=\"article\">"));
        var o = e.enrich(new MetadataEnricher.Input("https://www.amazon.in/dp/B0ABCDEFGH", "amazon", "product", null,
                "url_rule"));
        assertThat(o.refinement()).isNull();
    }

    @Test
    void unknownPageRefinedToArticle() {
        var e = with(u -> html(u, "<title>X</title><script type=\"application/ld+json\">{\"@type\":\"BlogPosting\",\"headline\":\"Post\"}</script>"));
        var o = e.enrich(new MetadataEnricher.Input("https://blog.example/x", "generic", "unknown", null, "fallback"));
        assertThat(o.refinement().group()).isEqualTo("articles");
    }

    @Test
    void imageCandidateVerifiedByMimeAndMagic() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
        var e = with(u -> new Fetcher.Response(URI.create(u), 200, "image/png", null, png, 0));
        var o = e.enrich(new MetadataEnricher.Input("https://x.example/a.png", "generic", "image", "candidate", "url_rule"));
        assertThat(o.refinement().subtype()).isEqualTo("verified");
    }

    @Test
    void imageCandidateWithMismatchedBytesIsNotVerified() {
        var e = with(u -> new Fetcher.Response(URI.create(u), 200, "image/png", null, "<svg onload=x>....".getBytes(), 0));
        var o = e.enrich(new MetadataEnricher.Input("https://x.example/a.png", "generic", "image", "candidate", "url_rule"));
        assertThat(o.refinement()).isNull();
        assertThat(o.status()).isEqualTo("failed");
    }
}
