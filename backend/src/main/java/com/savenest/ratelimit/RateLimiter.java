package com.savenest.ratelimit;

import com.savenest.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window in-memory rate limiter keyed by bucket + subject (IP or user ID).
 * Single-instance only; see README for the multi-instance limitation.
 */
@Component
public class RateLimiter {

    public enum Bucket { AUTH, BOOKMARK_CREATE, METADATA_REFRESH, CLASSIFY, EXPORT }

    private record Window(long startMillis, int count) {
    }

    private final Map<Bucket, Integer> limitsPerMinute = new EnumMap<>(Bucket.class);
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxTrackedKeys;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimiter(@Value("${savenest.rate-limit.auth-per-minute:10}") int authPerMinute,
                       @Value("${savenest.rate-limit.bookmark-create-per-minute:30}") int createPerMinute,
                       @Value("${savenest.rate-limit.refresh-per-minute:10}") int refreshPerMinute,
                       @Value("${savenest.rate-limit.classify-per-minute:60}") int classifyPerMinute,
                       @Value("${savenest.rate-limit.export-per-minute:3}") int exportPerMinute,
                       @Value("${savenest.rate-limit.max-tracked-keys:100000}") int maxTrackedKeys) {
        this(Map.of(Bucket.AUTH, authPerMinute, Bucket.BOOKMARK_CREATE, createPerMinute,
                Bucket.METADATA_REFRESH, refreshPerMinute, Bucket.CLASSIFY, classifyPerMinute,
                Bucket.EXPORT, exportPerMinute), maxTrackedKeys, Clock.systemUTC());
    }

    RateLimiter(Map<Bucket, Integer> limits, int maxTrackedKeys, Clock clock) {
        this.limitsPerMinute.putAll(limits);
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
    }

    /** @throws ApiException 429 when the subject exceeded the bucket's per-minute limit. */
    public void check(Bucket bucket, String subject) {
        long now = clock.millis();
        if (windows.size() > maxTrackedKeys) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= 60_000);
        }
        int limit = limitsPerMinute.getOrDefault(bucket, 60);
        Window w = windows.compute(bucket + ":" + subject, (k, old) ->
                old == null || now - old.startMillis() >= 60_000 ? new Window(now, 1) : new Window(old.startMillis(), old.count() + 1));
        if (w.count() > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests, try again shortly");
        }
    }
}
