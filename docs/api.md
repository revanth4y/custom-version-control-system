# API reference

All routes are under `/api/v1`. Request and response bodies are JSON.

In production the API shares its origin with the application — nginx proxies
`/api` to the server — so `/api/v1/...` is a relative path from the browser. In
development the server is at `http://localhost:8080`.

## Conventions

**Authentication** is a bearer token: `Authorization: Bearer <jwt>`. Many reads
work without one; where a route is marked *anonymous*, an unauthenticated caller
sees exactly what a stranger is allowed to see.

**Reads address a resource by its natural name** — `owner/name`, an issue's
`number`. **Writes to issues, comments and repository metadata address it by
UUID.** That asymmetry is real and a client has to carry both.

**Errors** all have one shape:

```json
{
  "timestamp": "2026-08-21T10:20:30.012Z",
  "status": 404,
  "code": "NOT_FOUND",
  "message": "Repository not found",
  "path": "/api/v1/repositories/octocat/nope"
}
```

| Status | Code | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST` | Malformed, missing or unconvertible input |
| 400 | `MALFORMED_REQUEST` | The body is not valid JSON |
| 400 | `VALIDATION_FAILED` | A field failed its constraints |
| 401 | `UNAUTHENTICATED` | No token, or an invalid one |
| 403 | `FORBIDDEN` | Readable, but not yours to change |
| 404 | `NOT_FOUND` | Absent — or present and not yours to see |
| 405 | `METHOD_NOT_ALLOWED` | With an `Allow` header |
| 409 | `CONFLICT` | Name already taken |
| 413 | `PAYLOAD_TOO_LARGE` | A request body over 16 MB, or a file over 10 MB asked for by `GET /blob` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | With an `Accept` header |
| 429 | `TOO_MANY_REQUESTS` | With a `Retry-After` header |
| 500 | `STORAGE_ERROR` | An object could not be read |

**A private repository answers 404, not 403**, to everyone but its owner. A
repository you may read but not write answers 403, because its existence is not a
secret.

---

## Health

### `GET /health` · anonymous

```json
{ "status": "UP", "database": "UP", "storage": "UP" }
```

200 when both dependencies answer, 503 otherwise. Readiness, not liveness: it
checks that the database is reachable and the storage root is writable. Used by
the container healthcheck.

---

## Authentication

### `POST /auth/signup`

```json
{ "username": "octocat", "email": "octocat@example.com", "password": "correct-horse-battery" }
```

Username: letters, digits and single hyphens, up to 39 characters. Password: 8 to
72 characters. **201** with an `AuthResponse`; **409** if the username or email is
taken.

### `POST /auth/login`

```json
{ "email": "octocat@example.com", "password": "correct-horse-battery" }
```

**200** with an `AuthResponse`; **401** for a wrong password *or* an unknown
account — the two are indistinguishable, deliberately.

Both endpoints share one rate limiter: ten failures per address in fifteen
minutes, then **429** with `Retry-After`. A success clears the count.

**`AuthResponse`**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 3600,
  "user": { "id": "...", "username": "octocat", "displayName": null, "bio": null, "createdAt": "..." }
}
```

### `GET /auth/me`

The authenticated user.

---

## Users

### `GET /users/{username}` · anonymous

```json
{ "id": "...", "username": "forge-demo", "displayName": null, "bio": null, "createdAt": "..." }
```

### `PATCH /users/me`

`{ "displayName": "...", "bio": "..." }` — partial; omitted fields are unchanged.

### `DELETE /users/me`

**204**. The account's issues and comments survive with a null author, which is
why `authorUsername` is nullable everywhere it appears.

### `GET /users/{username}/repositories` · anonymous

An array of `RepoResponse`. Anonymous callers and other users see only public
repositories; the owner sees all of theirs.

### `GET /users/{username}/contributions` · anonymous

| Parameter | Default |
|---|---|
| `from` | 364 days before `to` |
| `to` | today (UTC) |

```json
{
  "from": "2025-08-22",
  "to": "2026-08-21",
  "total": 94,
  "days": [ { "date": "2025-08-22", "count": 0 }, { "date": "2025-08-23", "count": 2 } ]
}
```

Every day in the window is present, including empty ones. The range may span at
most 366 days. Counted from real commit timestamps on every request; nothing is
pre-aggregated.

Commits are attributed by author email **within the subject's own repositories**.
A repository whose storage cannot be read is skipped rather than failing the
request.

---

## Repositories

### `POST /repositories`

```json
{ "name": "gitforge-engine", "description": "...", "visibility": "PUBLIC" }
```

Name: letters, digits, `.`, `_`, `-`, starting with a letter or digit, up to 100
characters. Visibility: `PUBLIC` or `PRIVATE`. **201**; **409** if you already own
one by that name — names are unique per owner, not globally.

### `GET /repositories` · anonymous

Paginated: `page`, `size`. Returns public repositories.

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 8, "totalPages": 1, "last": true }
```

### `GET /repositories/{owner}/{name}` · anonymous

```json
{
  "id": "5b0ef225-...",
  "name": "dag-demo",
  "description": "Branched and merged history, for the commit graph.",
  "visibility": "PUBLIC",
  "ownerUsername": "forge-demo",
  "createdAt": "...",
  "updatedAt": "..."
}
```

### `PATCH /repositories/{id}` · `DELETE /repositories/{id}`

Owner only. **403** if you can read it but do not own it.

`DELETE` answers **204** and removes the repository entirely: the record, its
issues and comments, and **the repository's object-store directory**. Storage
is keyed by the repository id, so nothing else is touched and a rename never
moved it in the first place. The record is removed first, so should the
directory survive a failure it is unreachable rather than half-attached to a
repository that still appears to exist.

---

## Contents

### `GET /repositories/{owner}/{name}/tree` · anonymous

| Parameter | |
|---|---|
| `ref` | optional; branch, `HEAD`, or a full commit id. Defaults to `HEAD` |
| `path` | optional; the directory. Defaults to the root |
| `withLastCommit` | optional, default `false`; see below |

```json
{
  "ref": "HEAD",
  "path": "",
  "entries": [
    { "name": "src", "path": "src", "type": "dir", "mode": "40000", "id": "..." },
    { "name": "README.md", "path": "README.md", "type": "file", "mode": "100644", "id": "..." }
  ]
}
```

`type` is `dir` or `file`. **Entries carry no size** — a tree records a name, a
mode and an object id, and nothing else. Read the blob if you need its length.

An empty repository has no tree to list, so this answers **404**.

With `withLastCommit=true`, every entry gains the commit that last touched it:

```json
{
  "name": "src", "path": "src", "type": "dir", "mode": "40000", "id": "...",
  "lastCommit": {
    "sha": "43d128d708ff43b8ab079c6c7a7fd2181cc38cf9",
    "shortSha": "43d128d",
    "message": "Refactor the object store
",
    "authorName": "forge-demo",
    "timestamp": "2026-07-15T09:00:00Z"
  }
}
```

A directory counts as touched when anything beneath it changed. Attribution
follows the same order the history endpoint returns, so the commit named here is
the one at the top of that path's history.

The search is **bounded to the 200 most recent commits** reachable from `ref`. A
path not touched within that window has **no `lastCommit` field** — render it as
unknown rather than assuming. The field is likewise absent from every entry when
the parameter is omitted or false, so the response is byte-identical to what it
was before the parameter existed.

### `GET /repositories/{owner}/{name}/blob` · anonymous

`path` is **required** — omitting it is a 400. `ref` is optional.

A file over **10 MB** is refused with **413** `PAYLOAD_TOO_LARGE` rather than
returned. It is the same bound every write is held to, so nothing this API
accepted can become unreadable through it; content that large can only have
been written underneath the API.

```json
{
  "path": "README.md",
  "id": "8ab686e...",
  "mode": "100644",
  "size": 412,
  "binary": false,
  "encoding": "utf-8",
  "content": "# gitforge-engine\n..."
}
```

Binary content comes back `"encoding": "base64"`. Text is anything holding no NUL
byte that decodes strictly as UTF-8 — the same rule the differ applies.

### `PUT /repositories/{owner}/{name}/contents`

Writes one file as one commit. Owner only. The **10 MB** limit on a single
file applies here exactly as it does to `POST /commits`, and a file over it is
a **400** having written nothing.

```json
{ "branch": "main", "message": "Update the README", "path": "README.md", "content": "...", "encoding": "utf-8", "mode": "100644" }
```

---

## Commits

### `POST /repositories/{owner}/{name}/commits`

Owner only.

```json
{
  "branch": "main",
  "message": "Add the object model",
  "changes": [
    { "operation": "PUT", "path": "src/Blob.java", "content": "final class Blob {}\n", "encoding": "utf-8", "mode": "100644" },
    { "operation": "DELETE", "path": "old.txt" }
  ]
}
```

`encoding` is `utf-8` (default) or `base64`. Limits, applied to **decoded** bytes
before anything is written:

| Limit | Value | Failure |
|---|---|---|
| Request body | 16 MB | **413** |
| One file | 10 MB | **400** on `POST /commits` and `PUT /contents`; **413** reading it back |
| One commit, total | 12 MB | **400** |
| Changes per commit | 500 | **400** |

A rejected commit writes no object and moves no branch.

**201** with a `CommitSummaryResponse`:

```json
{
  "sha": "43d128d708ff43b8ab079c6c7a7fd2181cc38cf9",
  "shortSha": "43d128d",
  "message": "Add the object model",
  "authorName": "forge-demo",
  "authorEmail": "forge-demo@gitforge.test",
  "timestamp": "2026-07-15T09:00:00Z",
  "parents": ["a83a439..."],
  "tree": "ecbc866...",
  "merge": false
}
```

### `GET /repositories/{owner}/{name}/commits` · anonymous

`ref` optional, `limit` optional — **clamped to 200**, default 30. Returns an
array, newest first. There is no cursor.

History cannot be filtered by path: sending `path` is a **400**. The parameter
is refused rather than ignored, because silently returning the whole branch
would be indistinguishable from a file that changed in every commit. To learn
which commit last touched an entry, read `lastCommit` from the tree listing
with `withLastCommit=true`.

### `GET /repositories/{owner}/{name}/commits/{sha}` · anonymous

The commit and its diff against its first parent. `sha` must be a full 40
characters; abbreviations are a 400.

### `GET /repositories/{owner}/{name}/commits/{sha}/diff` · anonymous

Just the diff. `path` optional, to narrow to one file.

### `GET /repositories/{owner}/{name}/compare` · anonymous

`base` and `head` are both **required** — omitting either is a 400 naming it.

---

## Diffs

### `GET /repositories/{owner}/{name}/diff` · anonymous

`base` and `head` required, `path` optional.

```json
{
  "filesChanged": 1,
  "totalAdditions": 1,
  "totalDeletions": 1,
  "files": [
    {
      "path": "a.txt",
      "status": "MODIFIED",
      "binary": false,
      "additions": 1,
      "deletions": 1,
      "hunks": [
        {
          "header": "@@ -1,3 +1,3 @@",
          "lines": [
            { "type": "CONTEXT", "content": "one",   "oldNumber": 1, "newNumber": 1 },
            { "type": "REMOVED", "content": "two",   "oldNumber": 2 },
            { "type": "ADDED",   "content": "TWO",                   "newNumber": 2 },
            { "type": "CONTEXT", "content": "three", "oldNumber": 3, "newNumber": 3 }
          ]
        }
      ]
    }
  ]
}
```

`status` is `ADDED`, `DELETED` or `MODIFIED`. A file may be reported as changed
with **no hunks** — because it is binary, or over 20,000 lines, or needs more than
5,000 edits, or because the commit touches more than 100 files. That is a
deliberate bound, not a failure.

---

## Branches and HEAD

### `GET /repositories/{owner}/{name}/branches` · anonymous

```json
[ { "name": "main", "commit": "43d128d...", "head": true, "tip": { "message": "...", "authorName": "...", "timestamp": "..." } } ]
```

### `POST /repositories/{owner}/{name}/branches`

`{ "name": "feature/login", "startPoint": "main" }` — owner only. Slashes are
allowed. `startPoint` is a branch, `HEAD`, or a full commit id.

### `DELETE /repositories/{owner}/{name}/branches?name=feature/login`

Owner only. `name` is a **required** query parameter.

### `GET` / `PUT /repositories/{owner}/{name}/head`

Read or move HEAD. `PUT` takes `{ "branch": "main" }`; owner only.

---

## Merge

### `POST /repositories/{owner}/{name}/merge`

Owner only. **Only `ourBranch` can move**; the branch being merged in is never
touched.

```json
{ "ourBranch": "main", "theirBranch": "feature/login", "message": "optional" }
```

**200** for a merge that completed in any form, **409** for a conflict.

```json
{ "outcome": "MERGED", "head": "...", "mergeCommit": "...", "tree": "...", "conflicts": [], "cleanlyMerged": [] }
```

`outcome` is `ALREADY_UP_TO_DATE`, `FAST_FORWARDED`, `MERGED` or `CONFLICTED`.
Fields that do not apply are absent rather than filled with placeholders — a
fast-forward has no merge commit, a conflict has neither commit nor tree.

```json
{
  "outcome": "CONFLICTED",
  "conflicts": [
    { "kind": "CONTENT", "path": "shared.txt", "base": { "mode": "100644", "id": "..." }, "ours": { ... }, "theirs": { ... } }
  ],
  "cleanlyMerged": [ { "path": "other.txt", "status": "ADDED" } ]
}
```

`kind` is one of `CONTENT`, `ADD_ADD`, `MODIFY_DELETE`, `MODE`, `TYPE`. **A
conflicted merge writes nothing and moves nothing** — every conflict is reported
in one pass, and `cleanlyMerged` shows what *would* have applied.

---

## Insights

### `GET /repositories/{owner}/{name}/insights` · anonymous

```json
{
  "commits": 16,
  "branches": 6,
  "files": 6,
  "storedObjects": 45,
  "contributors": [ { "name": "forge-demo", "email": "forge-demo@gitforge.test", "commits": 16 } ],
  "activity": [ { "date": "2026-08-20", "count": 3 } ]
}
```

Counted from the object store on every request. `storedObjects` is a direct
window onto content-addressed storage — blobs, trees and commits, deduplicated by
identity.

---

## Issues

### `POST /repositories/{owner}/{name}/issues`

`{ "title": "...", "body": "..." }` — title required, ≤200; body ≤20,000.
**Read access is enough**: anyone who can see the repository may file an issue.
Numbers are assigned per repository under a row lock, so concurrent filings cannot
collide.

### `GET /repositories/{owner}/{name}/issues` · anonymous

`status` optional: `OPEN` or `CLOSED`. Anything else is a 400 naming the accepted
values. Returns **every** issue, newest number first — there is no pagination.

```json
[ { "id": "...", "number": 3, "title": "...", "body": "...", "status": "OPEN",
    "authorUsername": "forge-viewer", "createdAt": "...", "updatedAt": "..." } ]
```

`authorUsername` is **null** once the author's account is deleted.

### `GET /repositories/{owner}/{name}/issues/{number}` · anonymous

### `PATCH /issues/{id}`

`{ "title": ..., "body": ..., "status": ... }` — partial. Closing is
`{"status":"CLOSED"}` alone. The issue's **author or the repository owner**.

### `DELETE /issues/{id}`

Same permission. **204**.

---

## Comments

### `GET /repositories/{owner}/{name}/issues/{number}/comments` · anonymous

Oldest first.

```json
[ { "id": "...", "body": "...", "authorUsername": "forge-demo", "createdAt": "...", "updatedAt": "..." } ]
```

`updatedAt` later than `createdAt` is how a client knows it was edited; there is
no separate flag.

### `POST /repositories/{owner}/{name}/issues/{number}/comments`

`{ "body": "..." }` — required, ≤20,000. Any authenticated user who can read the
repository may comment.

### `PATCH /issue-comments/{id}` · `DELETE /issue-comments/{id}`

The comment's **author or the repository owner**, the latter acting as moderator.
Note the path: `issue-comments`, not `comments`.
