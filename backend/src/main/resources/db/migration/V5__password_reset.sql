-- SaveNest V5: "forgot password" one-time codes and database-backed throttling.

-- One row per code sent. The code itself is never stored: only an HMAC-SHA256 keyed with
-- PASSWORD_RESET_SECRET (and salted with the row id). Same for the short-lived reset token.
CREATE TABLE password_reset_requests (
    id                      UUID PRIMARY KEY,
    user_id                 UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash               VARCHAR(64)  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at              TIMESTAMPTZ  NOT NULL,
    attempt_count           INT          NOT NULL DEFAULT 0,
    used_at                 TIMESTAMPTZ,          -- code verified (single use)
    invalidated_at          TIMESTAMPTZ,          -- superseded, too many attempts, or reset done
    reset_token_hash        VARCHAR(64),
    reset_token_expires_at  TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,          -- password actually changed
    CONSTRAINT uq_password_reset_token UNIQUE (reset_token_hash),
    CONSTRAINT ck_password_reset_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX ix_password_reset_user_created ON password_reset_requests (user_id, created_at DESC);

-- Throttling events keyed by an HMAC of the normalized email or client IP, recorded for
-- registered and unregistered emails alike so that limits do not reveal account existence.
CREATE TABLE password_reset_events (
    id            BIGSERIAL PRIMARY KEY,
    subject_hash  VARCHAR(64)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_password_reset_event_kind CHECK (kind IN ('code_request', 'verify_failure'))
);
CREATE INDEX ix_password_reset_events_lookup ON password_reset_events (subject_hash, kind, created_at);
