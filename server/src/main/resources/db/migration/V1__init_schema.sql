-- GitForge baseline schema.
--
-- Covers only the application/metadata domain (users, repositories, issues).
-- Version-control objects (blobs, trees, commits, branches) are content-addressed
-- on the filesystem and arrive in later migrations from Phase 2 onward.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(39)  NOT NULL,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(80),
    bio           VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- Usernames and emails are compared case-insensitively, so uniqueness is
-- enforced on the folded value rather than the stored form.
CREATE UNIQUE INDEX ux_users_username_lower ON users (LOWER(username));
CREATE UNIQUE INDEX ux_users_email_lower    ON users (LOWER(email));

CREATE TABLE repos (
    id          UUID         PRIMARY KEY,
    owner_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    visibility  VARCHAR(10)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_repos_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE'))
);

-- A repository name is unique per owner, not globally: two users may each
-- own a repository called "portfolio".
CREATE UNIQUE INDEX ux_repos_owner_name_lower ON repos (owner_id, LOWER(name));
CREATE INDEX ix_repos_owner ON repos (owner_id);

CREATE TABLE issues (
    id         UUID         PRIMARY KEY,
    repo_id    UUID         NOT NULL REFERENCES repos (id) ON DELETE CASCADE,
    author_id  UUID                  REFERENCES users (id) ON DELETE SET NULL,
    number     INTEGER      NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT,
    status     VARCHAR(10)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_issues_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ux_issues_repo_number UNIQUE (repo_id, number)
);

CREATE INDEX ix_issues_repo_status ON issues (repo_id, status);
