-- SaveNest V2: bookmarks. Effective group = COALESCE(group_override, detected_group); no third stored value.

CREATE TABLE bookmarks (
    id                    UUID PRIMARY KEY,
    owner_id              UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    original_url          VARCHAR(2200) NOT NULL,
    normalized_url        VARCHAR(2200) NOT NULL,
    resolved_url          VARCHAR(2200),
    canonical_url         VARCHAR(2200),
    dedupe_key            VARCHAR(400)  NOT NULL,
    host                  VARCHAR(255)  NOT NULL,
    title                 VARCHAR(500),
    title_edited          BOOLEAN       NOT NULL DEFAULT FALSE,
    description           VARCHAR(5000),
    description_edited    BOOLEAN       NOT NULL DEFAULT FALSE,
    notes                 VARCHAR(10000),
    detected_group        VARCHAR(20)   NOT NULL,
    group_override        VARCHAR(20),
    platform              VARCHAR(40)   NOT NULL,
    content_type          VARCHAR(40)   NOT NULL,
    subtype               VARCHAR(40),
    resource_id           VARCHAR(200),
    class_confidence      VARCHAR(10)   NOT NULL,
    class_source          VARCHAR(20)   NOT NULL,
    class_rule_id         VARCHAR(100)  NOT NULL,
    class_rule_version    INT           NOT NULL,
    class_reason          VARCHAR(500)  NOT NULL,
    is_favorite           BOOLEAN       NOT NULL DEFAULT FALSE,
    lifecycle_state       VARCHAR(10)   NOT NULL DEFAULT 'active',
    progress_state        VARCHAR(20)   NOT NULL DEFAULT 'none',
    deleted_at            TIMESTAMPTZ,
    metadata_status       VARCHAR(10)   NOT NULL DEFAULT 'pending',
    metadata_error_code   VARCHAR(40),
    metadata_fetched_at   TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_opened_at        TIMESTAMPTZ,
    open_count            INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_bookmarks_lifecycle CHECK (lifecycle_state IN ('active', 'archived')),
    CONSTRAINT ck_bookmarks_metadata CHECK (metadata_status IN ('pending', 'fetching', 'ready', 'partial', 'failed', 'blocked')),
    CONSTRAINT ck_bookmarks_progress CHECK (progress_state IN
        ('none', 'to_watch', 'watching', 'watched', 'to_read', 'reading', 'read', 'wishlist', 'purchased'))
);

-- One non-trashed bookmark per owner and dedupe key; enforced by the database, not just the UI.
CREATE UNIQUE INDEX uq_bookmarks_owner_dedupe_active
    ON bookmarks (owner_id, dedupe_key) WHERE deleted_at IS NULL;

CREATE INDEX ix_bookmarks_owner_created ON bookmarks (owner_id, created_at DESC, id DESC);
CREATE INDEX ix_bookmarks_owner_detected_group ON bookmarks (owner_id, detected_group);
CREATE INDEX ix_bookmarks_owner_group_override ON bookmarks (owner_id, group_override) WHERE group_override IS NOT NULL;
CREATE INDEX ix_bookmarks_owner_platform ON bookmarks (owner_id, platform);
CREATE INDEX ix_bookmarks_owner_state ON bookmarks (owner_id, is_favorite, lifecycle_state, deleted_at);
