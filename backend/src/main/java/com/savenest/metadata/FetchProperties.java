package com.savenest.metadata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Limits for the restricted outbound fetcher. All outbound requests to user URLs go through it. */
@ConfigurationProperties("savenest.fetch")
public record FetchProperties(
        @DefaultValue("4000") int connectTimeoutMs,
        @DefaultValue("8000") int readTimeoutMs,
        @DefaultValue("15000") int totalTimeoutMs,
        @DefaultValue("1048576") int maxHtmlBytes,
        @DefaultValue("2097152") int maxImageBytes,
        @DefaultValue("5") int maxRedirects,
        @DefaultValue({"80", "443"}) List<Integer> allowedPorts,
        @DefaultValue("SaveNestBot/0.1 (+metadata preview; respects blocking)") String userAgent) {
}
