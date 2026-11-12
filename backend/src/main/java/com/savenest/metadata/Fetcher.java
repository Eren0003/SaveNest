package com.savenest.metadata;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.Set;

/** Restricted outbound HTTP GET. Implementations must enforce the SSRF controls in {@link HttpSafeFetcher}. */
public interface Fetcher {

    record Response(URI finalUrl, int status, String mimeType, Charset charset, byte[] body, int redirects) {
    }

    /**
     * @param acceptedMimeTypes lowercase MIME types; an entry ending in "/*" accepts the whole family
     * @param maxBytes          maximum decoded body size; larger responses fail with TOO_LARGE
     */
    Response get(String url, Set<String> acceptedMimeTypes, int maxBytes) throws FetchException;
}
