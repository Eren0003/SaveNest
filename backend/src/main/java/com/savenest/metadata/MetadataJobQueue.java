package com.savenest.metadata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Durable PostgreSQL job queue. Claiming uses {@code FOR UPDATE SKIP LOCKED} plus a lease, so multiple
 * workers never process the same job concurrently and a crashed worker's job is reclaimed after the lease.
 * Every state transition is guarded by {@code locked_by} so a worker whose lease expired cannot clobber
 * a newer owner's result.
 */
@Repository
public class MetadataJobQueue {

    public record Job(long id, UUID bookmarkId, UUID ownerId, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public MetadataJobQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: at most one open (queued/running) job per bookmark. Returns true if a job was created. */
    public boolean enqueue(UUID bookmarkId, UUID ownerId) {
        return jdbc.update("""
                INSERT INTO metadata_jobs (bookmark_id, owner_id) VALUES (?, ?)
                ON CONFLICT (bookmark_id) WHERE status IN ('queued', 'running') DO NOTHING
                """, bookmarkId, ownerId) > 0;
    }

    public List<Job> claim(String workerId, int batch, int leaseSeconds, int maxAttempts) {
        // Expired leases at max attempts are dead-lettered rather than retried forever.
        jdbc.update("""
                UPDATE metadata_jobs SET status = 'dead', last_error = 'lease_expired', locked_by = NULL,
                       locked_until = NULL, updated_at = now()
                WHERE status = 'running' AND locked_until < now() AND attempts >= ?
                """, maxAttempts);
        return jdbc.query("""
                UPDATE metadata_jobs j SET status = 'running', attempts = j.attempts + 1, locked_by = ?,
                       locked_until = now() + make_interval(secs => ?), updated_at = now()
                WHERE j.id IN (
                    SELECT id FROM metadata_jobs
                    WHERE (status = 'queued' AND next_run_at <= now())
                       OR (status = 'running' AND locked_until < now())
                    ORDER BY next_run_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING j.id, j.bookmark_id, j.owner_id, j.attempts
                """, (rs, i) -> new Job(rs.getLong(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getInt(4)), workerId, leaseSeconds, batch);
    }

    public boolean complete(long id, String workerId) {
        return jdbc.update("""
                UPDATE metadata_jobs SET status = 'done', locked_by = NULL, locked_until = NULL, last_error = NULL,
                       updated_at = now()
                WHERE id = ? AND locked_by = ? AND status = 'running'
                """, id, workerId) > 0;
    }

    public boolean retry(long id, String workerId, long delaySeconds, String error) {
        return jdbc.update("""
                UPDATE metadata_jobs SET status = 'queued', locked_by = NULL, locked_until = NULL, last_error = ?,
                       next_run_at = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ? AND locked_by = ? AND status = 'running'
                """, error, (double) delaySeconds, id, workerId) > 0;
    }

    public boolean deadLetter(long id, String workerId, String error) {
        return jdbc.update("""
                UPDATE metadata_jobs SET status = 'dead', locked_by = NULL, locked_until = NULL, last_error = ?,
                       updated_at = now()
                WHERE id = ? AND locked_by = ? AND status = 'running'
                """, error, id, workerId) > 0;
    }

    /** Re-enqueues pending bookmarks whose job was lost (e.g. crash between insert and enqueue). */
    public int enqueueOrphans(int olderThanSeconds, int limit) {
        return jdbc.update("""
                INSERT INTO metadata_jobs (bookmark_id, owner_id)
                SELECT b.id, b.owner_id FROM bookmarks b
                WHERE b.metadata_status = 'pending' AND b.deleted_at IS NULL
                  AND b.created_at < now() - make_interval(secs => ?)
                  AND NOT EXISTS (SELECT 1 FROM metadata_jobs j WHERE j.bookmark_id = b.id
                                  AND j.status IN ('queued', 'running'))
                ORDER BY b.created_at
                LIMIT ?
                ON CONFLICT (bookmark_id) WHERE status IN ('queued', 'running') DO NOTHING
                """, (double) olderThanSeconds, limit);
    }

    public long countByStatus(String status) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM metadata_jobs WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    /** Drops finished jobs after a retention period; dead jobs are kept longer for inspection. */
    public int purgeFinished() {
        return jdbc.update("""
                DELETE FROM metadata_jobs
                WHERE (status = 'done' AND updated_at < now() - interval '1 day')
                   OR (status = 'dead' AND updated_at < now() - interval '30 days')
                """);
    }
}
