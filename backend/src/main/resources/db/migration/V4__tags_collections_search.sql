-- SaveNest V4: tags, collections, membership tables, full-text search.

CREATE TABLE tags (
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            VARCHAR(50)  NOT NULL,
    normalized_name VARCHAR(50)  NOT NULL,
    color           VARCHAR(7),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tags_owner_name UNIQUE (owner_id, normalized_name)
);

CREATE TABLE collections (
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    normalized_name VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    icon            VARCHAR(40),
    color           VARCHAR(7),
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_collections_owner_name UNIQUE (owner_id, normalized_name)
);

-- Deleting a tag/collection/bookmark removes only the membership rows, never the other side.
CREATE TABLE bookmark_tags (
    bookmark_id UUID NOT NULL REFERENCES bookmarks (id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (bookmark_id, tag_id)
);
CREATE INDEX ix_bookmark_tags_tag ON bookmark_tags (tag_id, bookmark_id);

CREATE TABLE bookmark_collections (
    bookmark_id   UUID        NOT NULL REFERENCES bookmarks (id) ON DELETE CASCADE,
    collection_id UUID        NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bookmark_id, collection_id)
);
CREATE INDEX ix_bookmark_collections_collection ON bookmark_collections (collection_id, bookmark_id);

-- Cross-owner isolation enforced by the database as a backstop to service-level checks.
CREATE FUNCTION enforce_bookmark_tag_owner() RETURNS trigger AS $$
BEGIN
    IF (SELECT owner_id FROM bookmarks WHERE id = NEW.bookmark_id)
       IS DISTINCT FROM (SELECT owner_id FROM tags WHERE id = NEW.tag_id) THEN
        RAISE EXCEPTION 'tag and bookmark belong to different owners' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bookmark_tags_owner BEFORE INSERT OR UPDATE ON bookmark_tags
    FOR EACH ROW EXECUTE FUNCTION enforce_bookmark_tag_owner();

CREATE FUNCTION enforce_bookmark_collection_owner() RETURNS trigger AS $$
BEGIN
    IF (SELECT owner_id FROM bookmarks WHERE id = NEW.bookmark_id)
       IS DISTINCT FROM (SELECT owner_id FROM collections WHERE id = NEW.collection_id) THEN
        RAISE EXCEPTION 'collection and bookmark belong to different owners' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bookmark_collections_owner BEFORE INSERT OR UPDATE ON bookmark_collections
    FOR EACH ROW EXECUTE FUNCTION enforce_bookmark_collection_owner();

-- Full-text match over a bookmark's own text columns (tags and collection names are matched in the query).
-- 'simple' config avoids language-specific stemming/stop-word surprises for URLs and names.
-- Evaluated on the owner's rows only (owner_id index); a stored tsvector + GIN index is the next step
-- if the 10,000-bookmark benchmark (Milestone 7) shows it is needed.
CREATE FUNCTION bookmark_text_match(title text, description text, notes text, host text, platform text, q text)
    RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE AS
$$
SELECT to_tsvector('simple',
           coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(notes, '') || ' ' ||
           coalesce(host, '') || ' ' || coalesce(platform, ''))
       @@ to_tsquery('simple', q)
$$;

-- Filter support.
CREATE INDEX ix_bookmarks_owner_content_type ON bookmarks (owner_id, content_type);
CREATE INDEX ix_bookmarks_owner_last_opened ON bookmarks (owner_id, last_opened_at DESC NULLS LAST, id DESC);
