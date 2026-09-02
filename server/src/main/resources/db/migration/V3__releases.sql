-- Releases: a published note attached to a tag.
--
-- The association is the tag NAME, deliberately not an object id. Object ids
-- live in the filesystem store and nowhere else, and a database row holding one
-- would become a reference the garbage collector cannot see. A name costs a
-- lookup and keeps that invariant intact.
--
-- The author reference clears rather than cascades, mirroring issues and their
-- comments: deleting an account must not erase what was published.

CREATE TABLE releases (
    id           UUID        PRIMARY KEY,
    repo_id      UUID        NOT NULL REFERENCES repos (id) ON DELETE CASCADE,
    author_id    UUID                 REFERENCES users (id) ON DELETE SET NULL,
    tag_name     VARCHAR(255) NOT NULL,
    name         VARCHAR(255) NOT NULL,
    body         TEXT,
    draft        BOOLEAN     NOT NULL,
    prerelease   BOOLEAN     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,

    -- One release per tag. Two notes about the same released point would leave
    -- no way to say which one describes it.
    CONSTRAINT uq_releases_repo_tag UNIQUE (repo_id, tag_name)
);

-- Releases are always read as one repository's list, newest first.
CREATE INDEX ix_releases_repo ON releases (repo_id, created_at DESC);
