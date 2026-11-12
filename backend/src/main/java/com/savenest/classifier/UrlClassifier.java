package com.savenest.classifier;

import com.savenest.classifier.Classification.Confidence;
import com.savenest.classifier.Classification.Source;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, deterministic, network-free rule-based classifier.
 * Priority: exact resource rule, then source-domain default, then generic hints, then Unsorted.
 * User overrides are applied by callers via {@link Classification#withUserOverride}.
 */
public final class UrlClassifier {

    public static final int RULE_VERSION = 1;

    private static final Pattern YT_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern ASIN = Pattern.compile("[A-Za-z0-9]{10}");
    private static final Pattern IMAGE_EXT = Pattern.compile("(?i).*\\.(jpe?g|png|gif|webp|avif|bmp|svg)$");
    private static final Set<String> MEME_SUBREDDITS = Set.of(
            "memes", "dankmemes", "me_irl", "meirl", "wholesomememes", "programmerhumor", "memeeconomy", "funny");
    private static final List<String> AMAZON = List.of(
            "amazon.com", "amazon.in", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.it", "amazon.es",
            "amazon.ca", "amazon.com.au", "amazon.co.jp", "amazon.com.mx", "amazon.com.br", "amazon.ae", "amazon.sg");
    private static final List<String> ARTICLE_HOSTS = List.of(
            "medium.com", "dev.to", "substack.com", "hashnode.dev", "wikipedia.org", "developer.mozilla.org");

    private UrlClassifier() {
    }

    public static Classification classify(String rawUrl) {
        return classify(UrlParser.parse(rawUrl));
    }

    public static Classification classify(ParsedUrl u) {
        Classification c;
        if ((c = youtube(u)) != null) return c;
        if ((c = products(u)) != null) return c;
        if ((c = moviesSeries(u)) != null) return c;
        if ((c = anime(u)) != null) return c;
        if ((c = reddit(u)) != null) return c;
        if ((c = social(u)) != null) return c;
        if ((c = images(u)) != null) return c;
        if ((c = articles(u)) != null) return c;
        if ((c = websites(u)) != null) return c;
        return result(GroupId.UNSORTED, "generic", "unknown", null, null, Confidence.LOW, Source.FALLBACK,
                "fallback.unsorted", "No rule matched this URL");
    }

    // ---- YouTube ---------------------------------------------------------

    private static Classification youtube(ParsedUrl u) {
        if (u.host().equals("youtu.be")) {
            List<String> s = u.segments();
            if (!s.isEmpty() && YT_ID.matcher(s.get(0)).matches()) {
                return yt("video", "standard", s.get(0), "youtube.short_link", "youtu.be short link with video ID");
            }
            return ytDefault();
        }
        if (!u.hostIsAny("youtube.com", "youtube-nocookie.com")) {
            return null;
        }
        List<String> s = u.segments();
        String first = s.isEmpty() ? "" : s.get(0);
        String second = s.size() > 1 ? s.get(1) : "";
        switch (first) {
            case "watch" -> {
                String v = u.query("v");
                if (v != null && YT_ID.matcher(v).matches()) {
                    return yt("video", "standard", v, "youtube.watch", "YouTube watch URL with video ID");
                }
            }
            case "shorts" -> {
                if (YT_ID.matcher(second).matches()) {
                    return yt("video", "short", second, "youtube.shorts", "YouTube Shorts path");
                }
            }
            case "live" -> {
                if (YT_ID.matcher(second).matches()) {
                    return yt("video", "live", second, "youtube.live", "YouTube live path");
                }
            }
            case "embed", "v" -> {
                if (YT_ID.matcher(second).matches()) {
                    return yt("video", "standard", second, "youtube.embed", "YouTube embed path");
                }
            }
            case "playlist" -> {
                String list = u.query("list");
                if (list != null && !list.isEmpty()) {
                    return yt("playlist", null, list, "youtube.playlist", "YouTube playlist URL");
                }
            }
            case "channel", "c", "user" -> {
                if (!second.isEmpty()) {
                    return yt("channel", null, second, "youtube.channel", "YouTube channel path");
                }
            }
            default -> {
                if (first.startsWith("@") && first.length() > 1) {
                    return yt("channel", null, first, "youtube.handle", "YouTube @handle channel");
                }
            }
        }
        return ytDefault();
    }

    private static Classification yt(String type, String subtype, String id, String rule, String reason) {
        return result(GroupId.YOUTUBE, "youtube", type, subtype, id, Confidence.HIGH, Source.URL_RULE, rule, reason);
    }

    private static Classification ytDefault() {
        return result(GroupId.YOUTUBE, "youtube", "page", null, null, Confidence.MEDIUM, Source.DOMAIN_DEFAULT,
                "youtube.domain", "YouTube domain without a recognized resource path");
    }

    // ---- Products ---------------------------------------------------------

    private static Classification products(ParsedUrl u) {
        List<String> s = u.segments();
        if (u.hostIsAny(AMAZON.toArray(String[]::new))) {
            String asin = amazonAsin(s);
            if (asin != null) {
                return result(GroupId.PRODUCTS, "amazon", "product", null, asin, Confidence.HIGH, Source.URL_RULE,
                        "amazon.product", "Amazon product path with ASIN");
            }
            return shoppingPage("amazon", "amazon.domain", "Amazon page that is not a specific product");
        }
        if (u.hostIsAny("amzn.to", "amzn.in", "a.co")) {
            return result(GroupId.PRODUCTS, "amazon", "short_link", null, null, Confidence.LOW, Source.DOMAIN_DEFAULT,
                    "amazon.short_link", "Unresolved Amazon short link; product not confirmed until resolved");
        }
        if (u.hostIs("flipkart.com")) {
            int p = s.indexOf("p");
            if (p >= 1 && p + 1 < s.size()) {
                return result(GroupId.PRODUCTS, "flipkart", "product", null, s.get(p + 1), Confidence.HIGH,
                        Source.URL_RULE, "flipkart.product", "Flipkart product path /<slug>/p/<item-id>");
            }
            return shoppingPage("flipkart", "flipkart.domain", "Flipkart page that is not a specific product");
        }
        if (u.hostIsAny("dl.flipkart.com", "fkrt.it")) {
            return result(GroupId.PRODUCTS, "flipkart", "short_link", null, null, Confidence.LOW, Source.DOMAIN_DEFAULT,
                    "flipkart.short_link", "Unresolved Flipkart short link");
        }
        if (u.hostIs("myntra.com")) {
            if (s.size() >= 2 && s.get(s.size() - 1).equals("buy") && s.get(s.size() - 2).matches("\\d+")) {
                return product("myntra", s.get(s.size() - 2), "myntra.product", "Myntra product path");
            }
            return shoppingPage("myntra", "myntra.domain", "Myntra page that is not a specific product");
        }
        if (u.hostIs("ajio.com")) {
            int p = s.indexOf("p");
            if (p >= 0 && p + 1 < s.size()) {
                return product("ajio", s.get(p + 1), "ajio.product", "AJIO product path");
            }
            return shoppingPage("ajio", "ajio.domain", "AJIO page that is not a specific product");
        }
        if (u.hostIs("meesho.com")) {
            int p = s.indexOf("p");
            if (p >= 1 && p + 1 < s.size()) {
                return product("meesho", s.get(p + 1), "meesho.product", "Meesho product path");
            }
            return shoppingPage("meesho", "meesho.domain", "Meesho page that is not a specific product");
        }
        if (u.hostIsAny("ebay.com", "ebay.in", "ebay.co.uk", "ebay.de", "ebay.com.au")) {
            if (s.size() >= 2 && s.get(0).equals("itm")) {
                String id = s.get(s.size() - 1);
                return product("ebay", id.matches("\\d+") ? id : null, "ebay.product", "eBay item path");
            }
            return shoppingPage("ebay", "ebay.domain", "eBay page that is not a specific item");
        }
        return null;
    }

    private static String amazonAsin(List<String> s) {
        for (int i = 0; i < s.size() - 1; i++) {
            String seg = s.get(i);
            String next = s.get(i + 1);
            if ((seg.equals("dp") || (seg.equals("product") && i > 0 && s.get(i - 1).equals("gp")))
                    && ASIN.matcher(next).matches()) {
                return next.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static Classification product(String platform, String id, String rule, String reason) {
        return result(GroupId.PRODUCTS, platform, "product", null, id, Confidence.HIGH, Source.URL_RULE, rule, reason);
    }

    private static Classification shoppingPage(String platform, String rule, String reason) {
        return result(GroupId.PRODUCTS, platform, "shopping_page", null, null, Confidence.MEDIUM,
                Source.DOMAIN_DEFAULT, rule, reason);
    }

    // ---- Movies & Series / Anime ------------------------------------------

    private static Classification moviesSeries(ParsedUrl u) {
        List<String> s = u.segments();
        if (u.hostIs("imdb.com")) {
            if (s.size() >= 2 && s.get(0).equals("title") && s.get(1).matches("tt\\d+")) {
                return result(GroupId.MOVIES_SERIES, "imdb", "media_title", null, s.get(1), Confidence.HIGH,
                        Source.URL_RULE, "imdb.title", "IMDb title page (movie or series unknown until metadata)");
            }
            return website("imdb", "imdb.domain", "IMDb page that is not a title");
        }
        if (u.hostIs("themoviedb.org") && s.size() >= 2 && (s.get(0).equals("movie") || s.get(0).equals("tv"))) {
            String id = s.get(1).split("-", 2)[0];
            boolean movie = s.get(0).equals("movie");
            return result(GroupId.MOVIES_SERIES, "tmdb", movie ? "movie" : "series", null, id, Confidence.HIGH,
                    Source.URL_RULE, movie ? "tmdb.movie" : "tmdb.tv", "TMDB " + (movie ? "movie" : "TV") + " path");
        }
        if (u.hostIs("letterboxd.com") && s.size() >= 2 && s.get(0).equals("film")) {
            return result(GroupId.MOVIES_SERIES, "letterboxd", "movie", null, s.get(1), Confidence.HIGH,
                    Source.URL_RULE, "letterboxd.film", "Letterboxd film path");
        }
        if (u.hostIs("netflix.com")) {
            int t = s.indexOf("title");
            if (t >= 0 && t + 1 < s.size()) {
                return result(GroupId.MOVIES_SERIES, "netflix", "media_title", null, s.get(t + 1), Confidence.HIGH,
                        Source.URL_RULE, "netflix.title", "Netflix title path");
            }
            return website("netflix", "netflix.domain", "Streaming-service page, not a specific title");
        }
        if (u.hostIsAny("primevideo.com", "hotstar.com", "disneyplus.com")) {
            String platform = u.hostIs("primevideo.com") ? "prime_video" : u.hostIs("hotstar.com") ? "hotstar" : "disney_plus";
            return website(platform, platform + ".domain", "Streaming-service page; specific title not confirmed");
        }
        return null;
    }

    private static Classification anime(ParsedUrl u) {
        List<String> s = u.segments();
        if (u.hostIs("myanimelist.net")) {
            if (s.size() >= 2 && s.get(0).equals("anime") && s.get(1).matches("\\d+")) {
                return result(GroupId.ANIME, "myanimelist", "anime", null, s.get(1), Confidence.HIGH,
                        Source.URL_RULE, "myanimelist.anime", "MyAnimeList anime path");
            }
            if (s.size() >= 2 && s.get(0).equals("manga") && s.get(1).matches("\\d+")) {
                return result(GroupId.WEBSITES, "myanimelist", "manga", null, s.get(1), Confidence.HIGH,
                        Source.URL_RULE, "myanimelist.manga", "MyAnimeList manga is not anime; no Manga group exists");
            }
            return website("myanimelist", "myanimelist.domain", "MyAnimeList page that is not an anime entry");
        }
        if (u.hostIs("anilist.co")) {
            if (s.size() >= 2 && s.get(0).equals("anime") && s.get(1).matches("\\d+")) {
                return result(GroupId.ANIME, "anilist", "anime", null, s.get(1), Confidence.HIGH,
                        Source.URL_RULE, "anilist.anime", "AniList anime path");
            }
            if (s.size() >= 2 && s.get(0).equals("manga")) {
                return result(GroupId.WEBSITES, "anilist", "manga", null, s.get(1), Confidence.HIGH,
                        Source.URL_RULE, "anilist.manga", "AniList manga is not anime; no Manga group exists");
            }
            return website("anilist", "anilist.domain", "AniList page that is not an anime entry");
        }
        if (u.hostIs("crunchyroll.com")) {
            int w = s.indexOf("series");
            if (w >= 0 && w + 1 < s.size()) {
                return result(GroupId.ANIME, "crunchyroll", "anime", null, s.get(w + 1), Confidence.HIGH,
                        Source.URL_RULE, "crunchyroll.series", "Crunchyroll series path");
            }
            return website("crunchyroll", "crunchyroll.domain", "Streaming-service page, not a specific anime");
        }
        return null;
    }

    // ---- Social / Memes ---------------------------------------------------

    private static Classification reddit(ParsedUrl u) {
        if (!u.hostIsAny("reddit.com", "redd.it")) {
            return null;
        }
        List<String> s = u.segments();
        if (s.size() >= 4 && s.get(0).equals("r") && s.get(2).equals("comments")) {
            String sub = s.get(1).toLowerCase(Locale.ROOT);
            if (MEME_SUBREDDITS.contains(sub)) {
                return result(GroupId.MEMES, "reddit", "post", null, s.get(3), Confidence.HIGH, Source.URL_RULE,
                        "reddit.meme_post", "Post in meme community r/" + s.get(1));
            }
            return result(GroupId.SOCIAL, "reddit", "post", null, s.get(3), Confidence.HIGH, Source.URL_RULE,
                    "reddit.post", "Reddit post (not a known meme community)");
        }
        return result(GroupId.SOCIAL, "reddit", "page", null, null, Confidence.MEDIUM, Source.DOMAIN_DEFAULT,
                "reddit.domain", "Reddit page");
    }

    private static Classification social(ParsedUrl u) {
        List<String> s = u.segments();
        if (u.hostIs("instagram.com")) {
            if (s.size() >= 2 && (s.get(0).equals("p") || s.get(0).equals("reel"))) {
                return result(GroupId.SOCIAL, "instagram", s.get(0).equals("p") ? "post" : "reel", null, s.get(1),
                        Confidence.HIGH, Source.URL_RULE, "instagram.post", "Instagram post path");
            }
            return result(GroupId.SOCIAL, "instagram", "profile_or_page", null, null, Confidence.MEDIUM,
                    Source.DOMAIN_DEFAULT, "instagram.domain", "Instagram page");
        }
        if (u.hostIsAny("x.com", "twitter.com")) {
            if (s.size() >= 3 && s.get(1).equals("status")) {
                return result(GroupId.SOCIAL, "x", "post", null, s.get(2), Confidence.HIGH, Source.URL_RULE,
                        "x.status", "X/Twitter status path");
            }
            return result(GroupId.SOCIAL, "x", "profile_or_page", null, null, Confidence.MEDIUM,
                    Source.DOMAIN_DEFAULT, "x.domain", "X/Twitter page");
        }
        if (u.hostIs("pinterest.com") || u.host().matches("([a-z]{2}\\.)?pinterest\\.[a-z.]+") || u.hostIs("pin.it")) {
            if (s.size() >= 2 && s.get(0).equals("pin")) {
                return result(GroupId.IMAGES, "pinterest", "image_post", null, s.get(1), Confidence.MEDIUM,
                        Source.URL_RULE, "pinterest.pin", "Pinterest pin");
            }
            return result(GroupId.SOCIAL, "pinterest", "profile_or_page", null, null, Confidence.MEDIUM,
                    Source.DOMAIN_DEFAULT, "pinterest.domain", "Pinterest page, not an individual image");
        }
        if (u.hostIsAny("facebook.com", "fb.watch", "threads.net", "tiktok.com", "linkedin.com", "bsky.app")) {
            String platform = u.host().replaceFirst("^(www\\.|m\\.)", "").split("\\.")[0];
            return result(GroupId.SOCIAL, platform, "post_or_page", null, null, Confidence.MEDIUM,
                    Source.DOMAIN_DEFAULT, "social.domain", "Known social platform");
        }
        if (u.hostIsAny("imgur.com", "i.imgur.com", "9gag.com")) {
            boolean meme = u.hostIs("9gag.com");
            return result(meme ? GroupId.MEMES : GroupId.IMAGES, meme ? "9gag" : "imgur", meme ? "post" : "image_post",
                    null, null, Confidence.MEDIUM, Source.DOMAIN_DEFAULT, meme ? "9gag.domain" : "imgur.domain",
                    meme ? "Meme-focused platform" : "Image hosting platform");
        }
        return null;
    }

    // ---- Generic hints ----------------------------------------------------

    private static Classification images(ParsedUrl u) {
        if (IMAGE_EXT.matcher(u.path()).matches()) {
            return result(GroupId.IMAGES, "generic", "image", "candidate", null, Confidence.MEDIUM, Source.URL_RULE,
                    "generic.image_extension", "Path ends with an image extension; MIME type not yet verified");
        }
        return null;
    }

    private static Classification articles(ParsedUrl u) {
        if (u.hostIsAny(ARTICLE_HOSTS.toArray(String[]::new)) && u.segments().size() >= 1) {
            return result(GroupId.ARTICLES, platformFromHost(u.host()), "article", null, null, Confidence.MEDIUM,
                    Source.DOMAIN_DEFAULT, "article.domain", "Known article/blog platform");
        }
        return null;
    }

    private static Classification websites(ParsedUrl u) {
        if (u.hostIsAny("github.com", "spotify.com", "google.com", "apple.com", "microsoft.com")) {
            return website(platformFromHost(u.host()), "website.known", "Known service website");
        }
        return null;
    }

    private static Classification website(String platform, String rule, String reason) {
        return result(GroupId.WEBSITES, platform, "website", null, null, Confidence.MEDIUM, Source.DOMAIN_DEFAULT, rule, reason);
    }

    private static String platformFromHost(String host) {
        String[] parts = host.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : host;
    }

    private static Classification result(GroupId g, String platform, String type, String subtype, String id,
                                         Confidence conf, Source src, String rule, String reason) {
        return new Classification(g, platform, type, subtype, id, conf, src, rule, RULE_VERSION, reason);
    }
}
