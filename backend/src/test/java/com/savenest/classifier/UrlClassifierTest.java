package com.savenest.classifier;

import com.savenest.classifier.Classification.Confidence;
import com.savenest.classifier.Classification.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlClassifierTest {

    @ParameterizedTest(name = "{0} -> {1}/{2}/{3}/{4}")
    @CsvSource(nullValues = "-", value = {
            // YouTube: standard, mobile, short link, Shorts, live, playlist, channel
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ,           youtube, youtube, video, standard",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s,        youtube, youtube, video, standard",
            "https://youtu.be/dQw4w9WgXcQ,                          youtube, youtube, video, standard",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ,            youtube, youtube, video, short",
            "https://www.youtube.com/live/dQw4w9WgXcQ,              youtube, youtube, video, live",
            "https://www.youtube.com/playlist?list=PLabc123,        youtube, youtube, playlist, -",
            "https://www.youtube.com/@SomeHandle,                   youtube, youtube, channel, -",
            "https://www.youtube.com/channel/UCabc,                 youtube, youtube, channel, -",
            // Products vs shopping pages
            "https://www.amazon.in/dp/B0ABCDEFGH,                   products, amazon, product, -",
            "https://www.amazon.com/gp/product/B0ABCDEFGH,          products, amazon, product, -",
            "https://www.amazon.in/Some-Name/dp/B0ABCDEFGH/ref=x,   products, amazon, product, -",
            "https://www.amazon.in/s?k=headphones,                  products, amazon, shopping_page, -",
            "https://www.amazon.in/,                                products, amazon, shopping_page, -",
            "https://www.flipkart.com/some-phone/p/itm123abc?pid=X, products, flipkart, product, -",
            "https://www.flipkart.com/search?q=phone,               products, flipkart, shopping_page, -",
            "https://www.myntra.com/tshirts/brand/name/12345/buy,   products, myntra, product, -",
            "https://www.ebay.com/itm/123456789,                    products, ebay, product, -",
            // Movies, series, anime
            "https://www.imdb.com/title/tt0111161/,                 movies_series, imdb, media_title, -",
            "https://www.themoviedb.org/movie/278-the-shawshank,    movies_series, tmdb, movie, -",
            "https://www.themoviedb.org/tv/1399,                    movies_series, tmdb, series, -",
            "https://www.netflix.com/,                              websites, netflix, website, -",
            "https://myanimelist.net/anime/5114/Fullmetal,          anime, myanimelist, anime, -",
            "https://myanimelist.net/manga/2/Berserk,               websites, myanimelist, manga, -",
            "https://anilist.co/anime/21/,                          anime, anilist, anime, -",
            // Social vs memes vs images
            "https://www.reddit.com/r/memes/comments/abc123/funny/, memes, reddit, post, -",
            "https://www.reddit.com/r/java/comments/abc123/post/,   social, reddit, post, -",
            "https://www.instagram.com/p/Cabc123/,                  social, instagram, post, -",
            "https://www.pinterest.com/someuser/,                   social, pinterest, profile_or_page, -",
            "https://example.org/photo.jpg,                         images, generic, image, candidate",
            // Fallback
            "https://unknown.example/some-page,                     unsorted, generic, unknown, -",
    })
    void classifiesExamples(String url, String group, String platform, String type, String subtype) {
        Classification c = UrlClassifier.classify(url);
        assertThat(c.groupId().id()).isEqualTo(group);
        assertThat(c.platform()).isEqualTo(platform);
        assertThat(c.contentType()).isEqualTo(type);
        assertThat(c.subtype()).isEqualTo(subtype);
        assertThat(c.ruleVersion()).isEqualTo(UrlClassifier.RULE_VERSION);
        assertThat(c.reason()).isNotBlank();
    }

    @Test
    void youtubeVideoIdIsSameAcrossFormats() {
        var a = UrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&utm_source=x");
        var b = UrlParser.parse("youtu.be/dQw4w9WgXcQ");
        var c = UrlParser.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ");
        String ka = UrlIdentity.dedupeKey(a, UrlClassifier.classify(a));
        assertThat(UrlIdentity.dedupeKey(b, UrlClassifier.classify(b))).isEqualTo(ka);
        assertThat(UrlIdentity.dedupeKey(c, UrlClassifier.classify(c))).isEqualTo(ka);
    }

    @Test
    void playlistDistinctFromVideo() {
        var v = UrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1");
        var p = UrlParser.parse("https://www.youtube.com/playlist?list=PL1");
        assertThat(UrlIdentity.dedupeKey(v, UrlClassifier.classify(v)))
                .isNotEqualTo(UrlIdentity.dedupeKey(p, UrlClassifier.classify(p)));
    }

    @Test
    void shortLinksBeforeResolutionAreProvisional() {
        Classification c = UrlClassifier.classify("https://amzn.to/3abcXYZ");
        assertThat(c.groupId()).isEqualTo(GroupId.PRODUCTS);
        assertThat(c.contentType()).isEqualTo("short_link");
        assertThat(c.confidence()).isEqualTo(Confidence.LOW);
        // After resolution, the final URL is reclassified.
        assertThat(UrlClassifier.classify("https://www.amazon.in/dp/B0ABCDEFGH").contentType()).isEqualTo("product");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://youtube.com.attacker.example/watch?v=dQw4w9WgXcQ",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://amazon.in.evil.test/dp/B0ABCDEFGH",
            "https://evil.test/youtube.com/watch?v=dQw4w9WgXcQ",
    })
    void spoofedHostsAreNotTrusted(String url) {
        Classification c = UrlClassifier.classify(url);
        assertThat(c.groupId()).isNotIn(GroupId.YOUTUBE, GroupId.PRODUCTS);
    }

    @Test
    void uppercaseHostTrailingDotAndUnicodeDomain() {
        assertThat(UrlClassifier.classify("HTTPS://WWW.YOUTUBE.COM./watch?v=dQw4w9WgXcQ").groupId())
                .isEqualTo(GroupId.YOUTUBE);
        ParsedUrl u = UrlParser.parse("https://bücher.example/Path");
        assertThat(u.host()).isEqualTo("xn--bcher-kva.example");
        assertThat(u.path()).isEqualTo("/Path"); // case-sensitive path preserved
    }

    @Test
    void normalizationIsConservative() {
        ParsedUrl u = UrlParser.parse("HTTPS://Example.COM:443/A/b/?utm_source=x&id=7&fbclid=y#frag");
        assertThat(UrlIdentity.normalizedUrl(u)).isEqualTo("https://example.com/A/b/?id=7#frag");
        assertThat(u.input()).contains("utm_source"); // original preserved
        // trailing slash is NOT removed (could be significant)
        assertThat(UrlIdentity.normalizedUrl(UrlParser.parse("https://example.com/a")))
                .isNotEqualTo(UrlIdentity.normalizedUrl(UrlParser.parse("https://example.com/a/")));
    }

    @Test
    void schemeLessInputGetsHttps() {
        ParsedUrl u = UrlParser.parse("  www.youtube.com/watch?v=dQw4w9WgXcQ  ");
        assertThat(u.schemeAdded()).isTrue();
        assertThat(u.uri().toString()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(UrlParser.parse("example.com:8080/x").uri().getPort()).isEqualTo(8080);
    }

    @ParameterizedTest
    @CsvSource({
            "javascript:alert(1),            UNSUPPORTED_SCHEME",
            "ftp://example.com/file,         UNSUPPORTED_SCHEME",
            "file:///etc/passwd,             UNSUPPORTED_SCHEME",
            "mailto:a@b.com,                 UNSUPPORTED_SCHEME",
            "https://user:pass@example.com/, CREDENTIALS_NOT_ALLOWED",
            "https://user@example.com/,      CREDENTIALS_NOT_ALLOWED",
            "not a url,                      INVALID_URL",
            "localhost,                      INVALID_URL",
            "https://,                       INVALID_URL",
            "https://exa_mple.com/,          INVALID_URL",
    })
    void rejectsInvalidInput(String url, InvalidUrlException.Code code) {
        assertThatThrownBy(() -> UrlParser.parse(url))
                .isInstanceOfSatisfying(InvalidUrlException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void userOverrideTakesPrecedence() {
        Classification c = UrlClassifier.classify("https://www.instagram.com/p/Cabc123/").withUserOverride(GroupId.MEMES);
        assertThat(c.groupId()).isEqualTo(GroupId.MEMES);
        assertThat(c.source()).isEqualTo(Source.USER_OVERRIDE);
        assertThat(c.platform()).isEqualTo("instagram");
    }

    @Test
    void unknownFallbackIsLowConfidence() {
        Classification c = UrlClassifier.classify("https://unknown.example/some-page");
        assertThat(c.source()).isEqualTo(Source.FALLBACK);
        assertThat(c.confidence()).isEqualTo(Confidence.LOW);
    }
}
