package com.savenest.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Polls the job queue and enriches bookmarks. Runs outside request handlers; can be disabled in the web
 * process ({@code WORKER_ENABLED=false}) and run as its own process from the same artifact.
 * Logs never contain URLs, only bookmark IDs and error categories.
 */
@Component
@ConditionalOnProperty(name = "savenest.worker.enabled", havingValue = "true", matchIfMissing = true)
public class MetadataWorker {

    private static final Logger log = LoggerFactory.getLogger(MetadataWorker.class);

    private final MetadataJobQueue queue;
    private final MetadataApplier applier;
    private final MetadataEnricher enricher;
    private final WorkerProperties props;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService pool;

    public MetadataWorker(MetadataJobQueue queue, MetadataApplier applier, MetadataEnricher enricher,
                          WorkerProperties props) {
        this.queue = queue;
        this.applier = applier;
        this.enricher = enricher;
        this.props = props;
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.batchSize()), r -> {
            Thread t = new Thread(r, "metadata-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${savenest.worker.poll-interval-ms:2000}", initialDelay = 3000)
    public void poll() {
        try {
            var jobs = queue.claim(workerId, props.batchSize(), props.leaseSeconds(), props.maxAttempts());
            if (jobs.isEmpty()) return;
            var futures = jobs.stream().map(j -> pool.submit(() -> process(j))).toList();
            for (var f : futures) {
                try {
                    f.get(props.leaseSeconds(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    f.cancel(true); // lease will expire and the job is reclaimed
                }
            }
        } catch (RuntimeException e) {
            log.warn("metadata poll failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void maintenance() {
        try {
            int orphans = queue.enqueueOrphans(120, 500);
            int purged = queue.purgeFinished();
            if (orphans > 0 || purged > 0) {
                log.info("metadata maintenance: requeued={} purged={} dead={}", orphans, purged, queue.countByStatus("dead"));
            }
        } catch (RuntimeException e) {
            log.warn("metadata maintenance failed: {}", e.getClass().getSimpleName());
        }
    }

    void process(MetadataJobQueue.Job job) {
        try {
            var input = applier.begin(job.bookmarkId(), job.ownerId());
            if (input.isEmpty()) {
                queue.complete(job.id(), workerId); // deleted or trashed: nothing to do
                return;
            }
            MetadataEnricher.Outcome o = enricher.enrich(input.get());
            boolean finalAttempt = job.attempts() >= props.maxAttempts();
            applier.apply(job.bookmarkId(), job.ownerId(), o, finalAttempt);
            if (o.transientError() && !finalAttempt) {
                queue.retry(job.id(), workerId, backoff(job.attempts()), o.errorCode());
            } else if (o.transientError()) {
                queue.deadLetter(job.id(), workerId, o.errorCode());
                log.info("metadata job dead-lettered bookmark={} error={}", job.bookmarkId(), o.errorCode());
            } else {
                queue.complete(job.id(), workerId);
            }
            log.debug("metadata job done bookmark={} status={} error={}", job.bookmarkId(), o.status(), o.errorCode());
        } catch (RuntimeException e) {
            boolean finalAttempt = job.attempts() >= props.maxAttempts();
            String code = "internal_" + e.getClass().getSimpleName();
            if (code.length() > 40) code = code.substring(0, 40);
            if (finalAttempt) {
                queue.deadLetter(job.id(), workerId, code);
            } else {
                queue.retry(job.id(), workerId, backoff(job.attempts()), code);
            }
            log.warn("metadata job error bookmark={} error={}", job.bookmarkId(), e.getClass().getSimpleName());
        }
    }

    /** Exponential backoff with jitter, capped. */
    long backoff(int attempts) {
        long base = (long) props.backoffBaseSeconds() << Math.min(attempts - 1, 16);
        long capped = Math.min(base, props.backoffMaxSeconds());
        return capped / 2 + (long) (Math.random() * (capped / 2 + 1));
    }
}
