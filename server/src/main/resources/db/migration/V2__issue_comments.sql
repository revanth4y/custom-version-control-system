-- Discussion on issues.
--
-- The author reference clears rather than cascades, mirroring issues: deleting
-- an account must not erase a conversation other people took part in.

CREATE TABLE issue_comments (
    id         UUID        PRIMARY KEY,
    issue_id   UUID        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    author_id  UUID                 REFERENCES users (id)  ON DELETE SET NULL,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Comments are always read as one issue's thread, in the order they were written.
CREATE INDEX ix_issue_comments_issue ON issue_comments (issue_id, created_at);
