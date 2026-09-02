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

**Revisions** are written as `HEAD`, a branch name, a full 40-character object
id, or an unambiguous abbreviation of one — at least **4** hexadecimal
characters, in either case. Wherever a full id is accepted, an abbreviation is
too: `ref`, `base`, `head`, a branch `startPoint`, and the `{sha}` in a commit
path.

A branch name always wins. A branch called `abcd` resolves to what it points at,
never to the object whose id begins `abcd`, because a name that happens to look
like a hash is still a name someone chose.

**Relative suffixes.** Any of those forms may be followed by `^n` — the n-th
parent, counting from one, so `^2` is a merge's second parent — or `~n`, n
generations back always by first parent. A bare `^` or `~` means one, `~0` and
`^0` mean the commit itself, and suffixes chain left to right: `main~2^2` is the
second parent of the commit two before `main`. The suffix is considered only
after the whole string has failed to name a commit outright.

| What you wrote | Answer |
|---|---|
| `HEAD~1`, `main^`, `<id>~2`, `<abbrev>^` | the commit that walk reaches |
| A parent the commit does not have | **404** |
| Further back than the root commit | **404** |
| A base that resolves to nothing | **404** |
| A suffix that is not readable, such as `~abc` | **400** |
| A suffix on an ambiguous abbreviation | **409**, as without one |

`^` and `~` are forbidden in branch names, so no name can be shadowed by this.
The `{sha}` in a commit path names an object and never a branch, so it accepts
`<id>~1` for the same reason it accepts `<id>`, and refuses `HEAD~1` for the same
reason it refuses `HEAD`.

| What you wrote | Answer |
|---|---|
| Full id, stored | the object |
| Full id, not stored | **404** |
| 4–39 hex, exactly one match | the object |
| 4–39 hex, no match | **404** |
| 4–39 hex, several matches | **409** — see below |
| Fewer than 4 characters | **400** |
| Not hexadecimal | **400** as a `{sha}`; treated as an unknown branch name as a `ref` |

An abbreviation that matches more than one object is a **409**, and the message
names the collisions rather than leaving you to guess how much longer to make it:

```
Object id prefix 'a1b2' is ambiguous: 3 objects match
(a1b2c3d4e5f6, a1b2aa77bb99, a1b2ff00dd11). Use more characters.
```

Candidates are abbreviated to 12 characters and listed in order; at most five are
named, and the true total is always stated.

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
array, newest first.

`paginate=true` optional: returns a page and a cursor instead of a bare array.
`cursor` optional: continues a paged walk, and implies `paginate`.

**The array is what you get unless you ask otherwise.** A request sending only
`ref`, `limit` and `path` behaves exactly as it always has — this endpoint is
public and anonymous, and changing its shape for callers who did not ask would
break them to spare one branch in the controller.

```json
{
  "commits": [ ... ],
  "hasMore": true,
  "nextCursor": "djE6YTFiMmMz..."
}
```

`hasMore` says whether any history remains. `nextCursor` is **omitted** when it
does not. Send that cursor back to get the next page; keep going until `hasMore`
is false. Do not infer the end from a short page — with a `path` filter a page
can be short because the search spent its budget, which is not the end of
anything.

The cursor is **opaque**. It records the commit the walk started from, so a
branch that moves midway does not reshuffle the history under a client halfway
through reading it — pages continue over the snapshot the walk began with. It is
not a capability: every page is authorised from scratch, so a private repository
answers 404 on page two exactly as on page one.

A cursor that is malformed, or that belongs to a different revision or a
different `path`, is a **400**. It is never silently ignored — restarting the
walk at the top would loop a paging client forever with nothing to indicate why.

`limit` is the page size when paginating, under the same clamp as before: 1 to
200, default 30.

`path` optional: the commits that touched one file or directory, newest first.
A directory counts as touched when anything beneath it changed. Leading and
trailing slashes are ignored, and a blank path is the repository root — whose
history is the whole history, not an error.

Two bounds apply when filtering, and they are not the same one. `limit` is how
many matching commits come back; the search itself looks back through **200**
commits from the revision. They have to differ: a file touched once, eighty
commits ago, would otherwise be reported as having no history because the last
thirty commits happened not to mention it.

So an empty array means **not touched within those 200 commits** — never "this
path has no history". A path that never existed answers the same way.

Paginating removes that ambiguity, which is the main reason to prefer it for
file history. The 200 becomes a budget per page rather than the end of the
search: an empty page carrying a `nextCursor` means the budget ran out and there
is more to look through, while an empty page with `hasMore: false` means nothing
reachable from the revision ever touched that path.

A `ref` that names nothing is a **404**, not an empty array. It used to be the
latter, which read exactly like a branch nobody had committed to, so a misspelled
name or an over-shortened id looked like an answer. **One exception:** a
repository with no commits at all has a HEAD naming a branch that does not exist
yet — that is what an empty repository is — so asking for that branch answers
`200 []` rather than 404.

Two limits are worth stating plainly:

- **History stops at a rename.** There is no rename detection, so a move is a
  delete of one path and an addition of another, and nothing pairs them. The new
  path's history begins at the move.
- **A deleted file keeps its history.** The commit that removed it touched that
  path, so it heads the list rather than emptying it.

For the single most recent commit against every entry in a directory, the tree
listing with `withLastCommit=true` still answers in one request and is the
cheaper question to ask.

Every commit in these responses carries both signatures — `authorName`,
`authorEmail`, `timestamp`, and `committerName`, `committerEmail`,
`committerTimestamp`. The stored object holds two, and the engine allows them to
differ; commits written through this API set them the same.

### `GET /repositories/{owner}/{name}/commits/{sha}` · anonymous

The commit and its diff against its first parent. `sha` is a full 40 characters
or an unambiguous abbreviation of at least 4; see **Revisions** above.

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
    {
      "kind": "CONTENT", "path": "shared.txt",
      "base": { "mode": "100644", "id": "..." }, "ours": { ... }, "theirs": { ... },
      "regions": [ { "base": { "start": 3, "end": 4 }, "ours": { ... }, "theirs": { ... } } ]
    }
  ],
  "cleanlyMerged": [ { "path": "other.txt", "status": "ADDED" } ]
}
```

`kind` is one of `CONTENT`, `ADD_ADD`, `MODIFY_DELETE`, `MODE`, `TYPE`. **A
conflicted merge writes nothing and moves nothing** — every conflict is reported
in one pass, and `cleanlyMerged` shows what *would* have applied.

Where both sides changed the same text file, the engine merges it line by line
first, so edits that do not meet no longer conflict at all. `regions` names the
stretches that still do, as half-open one-based line ranges into the base, ours
and theirs. An empty range on a side — `start` equal to `end` — says that side
contributes no lines there, which is how a deletion reads.

`regions` is **omitted entirely rather than sent empty** wherever the line-level
question was never asked: binary content, a directory on either side, a path with
no base to measure against, and files past the engine's bounds. Its absence means
nothing was established, not that nothing conflicts. A client that ignores the
field sees exactly what it saw before.

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

## Remotes

Synchronising with a repository on another GitForge server. Reads are GETs and
follow ordinary read visibility; everything that writes is owner-only.

### `GET /repositories/{owner}/{name}/remote-refs` · anonymous

Every branch and its tip, for a peer deciding what to fetch.

```json
{ "refs": [ { "branch": "main", "commit": "4dc91d5e…" } ] }
```

Branches only. HEAD says which branch a repository has checked out, and its own
remote-tracking refs are its record of a third party — neither is a peer's
business, and passing the second on would let one peer's view propagate as
though it were ours.

### `GET /repositories/{owner}/{name}/remote-objects?id=…&id=…` · anonymous

The named objects. At most **32 ids** per request.

```json
{ "objects": [ { "id": "4dc91d5e…", "type": "commit", "payload": "<base64>" } ] }
```

`payload` is the **canonical, uncompressed** form. An id is the SHA-1 of exactly
those bytes, so a receiver recomputes it rather than trusting the sender. An id
naming nothing is simply absent from the answer: the caller must compare what it
asked for against what came back anyway.

### `GET /repositories/{owner}/{name}/remote-objects/missing?id=…` · anonymous

Which of the offered ids this repository does not hold, so a push carries what is
needed rather than everything reachable.

```json
{ "missing": ["9b2c4d1a…"] }
```

### `POST /repositories/{owner}/{name}/remote-objects/receive` · owner only

Objects, and optionally a branch to move onto one of them.

```json
{ "objects": [ … ], "branch": "main", "commit": "4dc91d5e…" }
```

`branch` and `commit` are optional together. A push that does not fit in one
request sends earlier batches with neither and asks for the move only on the
last, so the move is decided once against a store that already holds everything.

Every object is verified before it is stored. The branch moves only after the
whole history beneath the proposed tip is proven present **and** the move is a
fast-forward.

| Case | Answer |
| --- | --- |
| Accepted | **200** with what was stored |
| An object that does not hash to its id | **422** |
| History beneath the tip incomplete | **422**, and no ref moves |
| Not a fast-forward | **409**, and no ref moves |
| Anonymous, or not the owner | **401** / **403** |

### `GET` / `POST` / `DELETE /repositories/{owner}/{name}/remotes`

List, register and forget. Registering takes `{"name": …, "url": …}` and answers
**201**; forgetting takes `?name=` and answers **204**.

A URL must be `http` or `https`, must not carry credentials, and must not resolve
to a loopback, link-local, site-local or any-local address unless the deployment
sets `vcs.remote.allow-private-addresses`. A refused URL is **422**.

Forgetting a remote leaves its tracking refs and their objects exactly where they
are. Removing a name is not a request to reclaim storage — that is what
`POST /gc` is for.

### `POST /repositories/{owner}/{name}/remotes/{remote}/fetch` · owner only

Brings the remote's history in and updates remote-tracking refs.

```json
{ "updatedRefs": ["origin/main"], "receivedObjects": 12, "skippedBranches": [] }
```

Nothing local moves: a fetch is not a merge, and no branch is created. A branch
name the remote offers that this repository will not track is reported in
`skippedBranches` rather than failing the fetch. Re-fetching an unchanged remote
receives zero objects.

**Fetched objects are garbage-collection roots.** An object reachable only
through `origin/main` survives a sweep; forget the remote's tracking refs and it
becomes collectible.

### `POST /repositories/{owner}/{name}/remotes/{remote}/push` · owner only

Sends one branch to the remote, fast-forward only.

```json
{ "branch": "main", "token": "<a token the peer accepts>" }
```

The token authenticates this server to the peer for that call only and is never
stored. **One branch per push**, and no force push: there is no reflog here, so a
push that would drop commits is refused (**409**) rather than resolved.

---

## Garbage collection

Objects become unreachable when the last reference to them goes — a deleted
branch, a merge that was computed and refused. Nothing removes them on its own.

### `GET /repositories/{owner}/{name}/gc` · anonymous

What a collection would remove. Removes nothing.

```json
{
  "storedObjects": 45,
  "reachableObjects": 41,
  "roots": 3,
  "unreachableObjects": 4,
  "unreachableBytes": 512,
  "unreachable": [ { "id": "4dc91d5e…", "type": "commit", "bytes": 174 } ],
  "collectedObjects": 0,
  "collected": [],
  "reclaimedBytes": 0,
  "retained": [],
  "temporaryFiles": [],
  "truncated": false,
  "collectionPerformed": false,
  "checkedAt": "2026-09-01T16:00:00Z",
  "durationMs": 3
}
```

`roots` counts every reference the traversal started from: each branch, HEAD, and
the working tree's recorded tree where there is one. Overlaps are counted rather
than folded together, so the figure says how many references were consulted.

`retained` lists unreachable objects that were deliberately kept, each with a
reason. `damaged` is the only one: an object that will not read back cannot be
identified, and unidentifiable bytes are not deleted.

`temporaryFiles` names staging files found in the object store. They are reported
and never removed — one is either a write happening at this moment or the residue
of a process that was killed mid-write, and nothing on disk distinguishes the two.

`truncated` is true when the store holds more objects than one sweep will
consider. Unlike `/integrity`, which truncates and reports what it managed, a
truncated sweep examines nothing and collects nothing: a reachability calculation
that stopped early would call reachable objects garbage.

### `POST /repositories/{owner}/{name}/gc` · owner only

Removes every object no reference reaches, and answers with the same shape —
`collectionPerformed` true, `collected` naming what went, `reclaimedBytes` saying
what that freed.

Idempotent. A second call finds nothing, and a sweep of a repository with no
garbage is a no-op.

Deleting a branch never triggers this, and neither does committing, merging, or
starting the application. Reclaiming storage is something somebody asks for.

There is no reflog: a collected object is gone.

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
