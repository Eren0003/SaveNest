package com.savenest.classifier;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.common.ApiException;
import com.savenest.common.DataResponse;
import com.savenest.ratelimit.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ClassifyController {

    public record ClassifyRequest(@NotBlank @Size(max = UrlParser.MAX_LENGTH + 100) String url) {
    }

    public record ClassifyResponse(String interpretedUrl, boolean schemeAdded, String normalizedUrl,
                                   String dedupeKey, Classification classification) {
    }

    private final RateLimiter rateLimiter;

    public ClassifyController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /** Lightweight URL-only preview classification; no network access. */
    @PostMapping("/classify")
    public DataResponse<ClassifyResponse> classify(@Valid @RequestBody ClassifyRequest body,
                                                   @AuthenticationPrincipal AppUserPrincipal user) {
        rateLimiter.check(RateLimiter.Bucket.CLASSIFY, user.getUserId().toString());
        try {
            ParsedUrl url = UrlParser.parse(body.url());
            Classification c = UrlClassifier.classify(url);
            return DataResponse.of(new ClassifyResponse(url.uri().toString(), url.schemeAdded(),
                    UrlIdentity.normalizedUrl(url), UrlIdentity.dedupeKey(url, c), c));
        } catch (InvalidUrlException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.code().name().toLowerCase(), e.getMessage());
        }
    }

}
