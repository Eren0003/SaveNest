-- SaveNest V3: metadata enrichment fields and a PostgreSQL-backed durable job queue.

ALTER TABLE bookmarks
    ADD COLUMN preview_image_url VARCHAR(2200),
    ADD COLUMN favicon_url       VARCHAR(2200),
    ADD COLUMN site_name         VARCHAR(200),
    ADD COLUMN metadata_json     VARCHAR(20000);

CREATE TABLE metadata_jobs (
    id            BIGSERIAL PRIMARY KEY,
    bookmark_id   UUID         NOT NULL REFERENCES bookmarks (id) ON DELETE CASCADE,
    owner_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status        VARCHAR(10)  NOT NULL DEFAULT 'queued',
    attempts      INT          NOT NULL DEFAULT 0,
    next_run_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_until  TIMESTAMPTZ,
    locked_by     VARCHAR(100),
    last_error    VARCHAR(40),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_metadata_jobs_status CHECK (status IN ('queued', 'running', 'done', 'dead'))
);

-- At most one open job per bookmark: enqueue is idempotent (ON CONFLICT DO NOTHING).
CREATE UNIQUE INDEX uq_metadata_jobs_open
    ON metadata_jobs (bookmark_id) WHERE status IN ('queued', 'running');

CREATE INDEX ix_metadata_jobs_due ON metadata_jobs (next_run_at) WHERE status = 'queued';
CREATE INDEX ix_metadata_jobs_lease ON metadata_jobs (locked_until) WHERE status = 'running';
-- Failed-job visibility.
CREATE INDEX ix_metadata_jobs_dead ON metadata_jobs (updated_at) WHERE status = 'dead';
