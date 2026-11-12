package com.savenest.ratelimit;

import com.savenest.common.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private final RateLimiter limiter = new RateLimiter(Map.of(RateLimiter.Bucket.AUTH, 3), 1000, clock);

    @Test
    void blocksAfterLimitAndResetsAfterWindow() {
        for (int i = 0; i < 3; i++) limiter.check(RateLimiter.Bucket.AUTH, "1.2.3.4");
        assertThatThrownBy(() -> limiter.check(RateLimiter.Bucket.AUTH, "1.2.3.4"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("rate_limited"));
        now.addAndGet(60_000);
        assertThatNoException().isThrownBy(() -> limiter.check(RateLimiter.Bucket.AUTH, "1.2.3.4"));
    }

    @Test
    void subjectsAreIndependent() {
        for (int i = 0; i < 3; i++) limiter.check(RateLimiter.Bucket.AUTH, "a");
        assertThatNoException().isThrownBy(() -> limiter.check(RateLimiter.Bucket.AUTH, "b"));
    }
}
