package com.savenest.metadata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Metadata worker settings. {@code enabled=false} lets the web process run without the worker,
 * so the worker can be deployed as a separate process from the same artifact.
 */
@ConfigurationProperties("savenest.worker")
public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2000") long pollIntervalMs,
        @DefaultValue("5") int batchSize,
        @DefaultValue("60") int leaseSeconds,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30") int backoffBaseSeconds,
        @DefaultValue("3600") int backoffMaxSeconds) {
}
