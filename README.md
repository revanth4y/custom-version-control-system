# GitForge

A repository host built on a version control engine written from first
principles — content-addressable storage, SHA-1 object identity, Merkle trees,
a commit DAG, branches and three-way merge — with **no Git library involved**.

The point of the project is the engine. The web application exists to make it
visible: you can watch a commit graph assemble itself from real parent links,
watch a three-way merge fail five different ways, and read the byte-for-byte
diff the engine produced. Nothing on any screen is simulated.

```
┌──────────┐    ┌───────────────────────────────┐    ┌────────────────┐
│  React   │───▶│  Spring Boot                  │───▶│  PostgreSQL    │
│  SPA     │    │  ┌─────────────────────────┐  │    │  accounts,     │
└──────────┘    │  │ VCS engine (plain Java) │  │    │  repos, issues │
                │  └───────────┬─────────────┘  │    └────────────────┘
                └──────────────┼────────────────┘
                               ▼
                    ┌──────────────────────┐
                    │ object store on disk │
                    │ objects/ refs/ HEAD  │
                    └──────────────────────┘
```

## What it does

**Version control.** Commit files, browse any revision, create and delete
branches, move HEAD, view a commit's line-level diff, compare two revisions, and
merge one branch into another — resolving cleanly, fast-forwarding, or reporting
exactly what conflicted and why.

**Repository hosting.** Accounts, public and private repositories, an issue
tracker with comments, contribution calendars, and per-repository insights
counted straight out of the object store.

**The web application.** A repository browser with a light and a dark scheme,
laid out for a phone as readily as a desktop: browse any revision, read a file
with its syntax highlighted, and see which commit last touched each path in a
directory. Every value shown comes from the API.

## The engine

Everything below is implemented with the Java standard library. No JGit, no
`git` subprocess, no VCS dependency of any kind.

### Content-addressable storage

An object's name is the SHA-1 of its canonical form:

```
<type> <length>\0<payload>
```

`blob 5\0hello` hashes to `b6fc4c62...`. Two consequences follow, and most of the
engine rests on them: identical content always produces the identical id, so
storing the same file twice stores it once; and an id can be verified, because
re-hashing the payload must reproduce the name it was filed under. On disk,
objects live at `objects/<first two hex>/<remaining 38>`, zlib-compressed —
compression is a storage concern and never enters the hash, so the scheme could
change tomorrow without rewriting a single id.

SHA-1 is implemented from the specification in `vcs/hash/Sha1.java`, and checked
against published test vectors.

### Blobs, trees and the Merkle property

A **blob** is file content with no name attached. A **tree** is a directory
listing — mode, name, and the id of a blob or a subtree — sorted canonically so
that the same directory always serialises identically. A **commit** points at one
root tree, carries zero or more parents, and records who wrote it and when.

Because a tree's id covers every byte beneath it, two trees with the same id are
identical all the way down. The differ exploits this directly: when both sides of
a comparison show the same subtree id, the entire subtree is skipped without a
single object being read. That is the Merkle property doing real work, not a
diagram.

### The commit DAG

Commits form a directed acyclic graph through their parent links. A merge commit
has two parents; the graph converges and diverges accordingly.

`vcs/graph/CommitGraph.java` offers both traversals because they answer different
questions. **BFS** visits in order of distance, which is what reachability and
common-ancestor work need. **DFS** follows one line of descent to its end, which
is what you want when tracing where a change came from. Ancestry (`isAncestor`)
short-circuits on a match rather than materialising the whole ancestor set.

**Merge bases** are the lowest common ancestors: commits reachable from both
tips, minus any that another candidate can itself reach. The result is sorted by
object id — arbitrary, but stable and symmetric, so swapping the arguments cannot
change the answer.

### Branches and HEAD

A branch is a file under `refs/heads/` containing an object id. HEAD is a file
naming a branch. Committing writes the new object, then moves the branch; a
failure part-way leaves the branch where it was. Branch names are validated
against rules of our own (`vcs/ref/BranchName.java`), which permit
`feature/login` and `bugfix/auth/token` and reject the shapes that would make a
ref ambiguous.

### Diffing

**Tree diff** walks two trees together and reports added, deleted and modified
paths, skipping any subtree whose ids match.

**Line diff** is Myers' O(ND) shortest-edit-script algorithm, walking the edit
graph by increasing distance and reconstructing the path backwards. Output is
grouped into hunks with three lines of context, merging hunks that would
otherwise overlap. It is bounded on purpose: files over 20,000 lines, or pairs
needing more than 5,000 edits, are reported as changed without a line diff rather
than being allowed to consume the server. Binary content — anything holding a NUL
byte or failing to decode strictly as UTF-8 — is never line-diffed.

### Three-way merge

Given two branch tips, the engine finds a merge base and compares all three
trees. Where only one side changed a path, that side wins. Where both changed it
the same way, they agree. Where both changed it differently, it is a conflict,
classified as one of five kinds:

| Kind | Meaning |
|---|---|
| `CONTENT` | The path existed in the base and both sides changed it differently |
| `ADD_ADD` | Absent from the base; both sides created it with different content |
| `MODIFY_DELETE` | One side changed it, the other removed it |
| `MODE` | The content agrees but the modes changed incompatibly |
| `TYPE` | One side has a file where the other has a directory |

A conflicted merge writes **nothing**: no objects are persisted, and no branch
moves. The whole result is computed before anything is committed. Every conflict
is found in one pass rather than stopping at the first, so you see the full
picture before deciding what to do.

Merging into a branch that is already an ancestor reports `ALREADY_UP_TO_DATE`.
Merging when your branch has not moved is a `FAST_FORWARDED` — the branch pointer
advances, no merge commit is made.

## The interface

The web application is where the engine becomes legible, so it shows real values
and nothing else. Where the backend has no answer, the interface says nothing
rather than inventing one.

**One design system.** A single palette drives both schemes — light, dark, or
whatever the operating system currently says — applied through CSS custom
properties. Contrast is not a claim: `frontend/src/theme/contrast.js` implements
WCAG 2.1 relative luminance with alpha compositing, and the palette suite checks
every text, link, button and graph colour against the surface it actually sits
on. It has failed a real pairing and forced a change more than once.

**A shell that fits the window.** The header carries the brand, the global
navigation, the theme control and the account menu, held to the same column as
the page beneath it so nothing drifts out of alignment on a wide display. Below
the breakpoint the navigation folds into a menu rather than dropping items.
Layouts are verified at 1440, 1024 and 390 pixels in both schemes.

**Repository list.** One component renders a repository wherever it appears —
the dashboard and a profile draw the same card — showing its name, visibility,
description and when it last changed.

**Repository overview.** The file listing carries the commit that last touched
each path, resolved by the server in the same request that lists the directory
rather than one request per file. Above it sits the revision's latest commit;
beside it, what the repository is made of — commits, branches, files, stored
objects and contributors, counted out of the object store.

**Code browser.** Source is highlighted for twenty-two languages, with the
grammars loaded on demand, so a reader who never opens a file never downloads a
highlighter. The colours are the palette's own, and highlighting wraps spans
around the text without changing a byte of it. **Raw** shows a file without
numbering or colour — and for Markdown, the source behind the rendered document.
**Copy** takes the exact contents. Binary files are described rather than
printed, executables are labelled from their real mode, and an empty file says
so.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 18, Vite 5, Primer React, React Router 6, highlight.js |
| Backend | Java 21, Spring Boot 4.1, Spring Security 7, Spring Data JPA |
| Database | PostgreSQL 16, Flyway migrations |
| Object storage | Plain filesystem, one directory per repository |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers, Vitest |

The engine lives under `com.gitforge.vcs` and depends on nothing from Spring —
not the web layer, not persistence. It is exercised by unit tests that never
start an application context, which is what keeps that boundary honest.

## Security model

Authentication is a stateless JWT (HS256); the signing secret must be supplied
from the environment and the application refuses to start without one long
enough.

**Authorization lives in the service layer**, never in URL patterns. Every route
to a repository's storage goes through `VcsRepositoryProvider`, which resolves
the repository through the access rules first. There is no other way to obtain a
repository handle, so an endpoint added later cannot forget the check — it has
nothing to call that skips it.

**Private repositories answer 404, never 403.** A 403 would confirm that a
repository exists, which is itself a disclosure. A repository you can read but
cannot write answers 403, because its existence is not a secret.

Authentication attempts are rate-limited per address (ten failures in fifteen
minutes), request bodies are capped at 16 MB, and a single commit may carry at
most 10 MB per file, 12 MB in total, across at most 500 changes. The production
frontend ships a Content-Security-Policy as a real header.

Full detail in [docs/security.md](docs/security.md).

## Running it

Requires Docker. Copy the environment template and set a signing secret:

```bash
cp .env.example .env
```

```bash
openssl rand -base64 48
```

Put that value in `JWT_SECRET`, then start everything:

```bash
docker compose up -d --build
```

GitForge is at **http://localhost** — nginx serves the React build and proxies
`/api` to the server, so the browser makes same-origin requests.

### Demo data

A fresh database is empty. To fill it with the demonstration dataset:

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --force-recreate server
```

> ⚠️ **This deletes everything first** — every account, repository, issue,
> comment and stored object — and then rebuilds a known state. It is guarded by
> two independent gates, the `demo` Spring profile and `gitforge.demo.reset`,
> and the production compose file sets neither. Do not point it at anything you
> care about.

It is idempotent: running it repeatedly leaves exactly what running it once
leaves. Sign in as `forge-demo@gitforge.test` or `forge-viewer@gitforge.test`
with the password `gitforge-demo-2026`.

Once it has run, put the server back on the ordinary configuration:

```bash
docker compose up -d --force-recreate server
```

The overlay is baked into the container it created, so until you do, **every
restart of that container reseeds** — including `docker compose restart`.

Set `DEMO_EPOCH` to a fixed instant and the dataset becomes reproducible byte for
byte, object ids included — which is content addressing being exactly as
advertised:

```bash
DEMO_EPOCH=2026-01-01T00:00:00Z docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --force-recreate server
```

Left unset it dates from now, so the contribution calendars show recent activity.

### Local development

Backend and database in Docker, frontend on Vite with hot reload:

```bash
docker compose up -d postgres server
```

```bash
cd frontend && npm install && npm run dev
```

The dev server is at http://localhost:5173 and talks to the API at
http://localhost:8080 cross-origin, which is why `CORS_ALLOWED_ORIGINS` lists it.
Production does not need CORS at all.

### Ports

| Port | Service |
|---|---|
| 80 | nginx — the application |
| 8080 | API, published for development and tests |
| 5433 | PostgreSQL, off 5432 to avoid colliding with a local install |

Set `FRONTEND_PORT` if something already owns port 80.

## Tests

```bash
cd server && ./mvnw verify
```

Unit tests run under Surefire; integration tests (`*IT`) run under Failsafe
against a real PostgreSQL started by Testcontainers, so the Flyway migrations and
every security rule are exercised as they are in production.

```bash
cd frontend && npm run lint && npm test && npm run build
```

## Known limitations

These are real and current, not planned work described as done.

**Abbreviated object ids are not accepted.** Revisions resolve as `HEAD`, a
branch name, or a full 40-character id. There is no prefix search over the object
store, so a 7-character id fails.

**History is capped at 200 commits per request, with no cursor.** `GET /commits`
clamps `limit` to 200. The commit graph pages client-side within that, which
holds up to the cap and not past it. Longer histories need a real cursor.

**No rename detection.** A renamed file appears as a delete and an add. Pairing
them means comparing content similarity across both sides, which the tree differ
does not attempt.

**Merges resolve at file level, not line level.** A conflict tells you the path,
the kind, and the object on each side. It does not write conflict markers into
the file or offer an editor to resolve them in.

**Criss-cross histories pick one merge base.** When two branches have merged from
each other in both directions there are genuinely several lowest common
ancestors. The engine sorts them by object id and takes the first, rather than
merging the bases together recursively as Git's `recursive` strategy does. The
result is deterministic and symmetric, but for those histories it is one valid
answer among several rather than the best one.

**Contributions count only your own repositories.** Commits you authored in
someone else's repository do not appear on your calendar; finding them would mean
walking every repository on the server for every profile view, which needs an
author index that does not exist.

**Issue lists are not paginated.** Every issue in a repository is returned in one
response and filtered in the browser.

**No stars, forks, watchers or language detection.** Nothing counts them and
nothing infers a language from a file, so the interface does not show them. A
`LICENSE` is a file like any other; it is not parsed into metadata.

**No blame, and no raw file endpoint.** `GET /blob` answers with JSON, so "raw"
is a view in the browser rather than a URL that serves the bytes.

**No garbage collection.** Objects left unreachable — by a deleted branch, say —
stay on disk. Nothing reads them, and nothing reclaims them.

**Single-node storage.** The object store is a local filesystem directory. There
is no replication and no sharding.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | System shape, module map, request lifecycle, frontend structure |
| [docs/vcs-design.md](docs/vcs-design.md) | The engine in depth, and where it diverges from Git |
| [docs/api.md](docs/api.md) | Every endpoint, with real request and response shapes |
| [docs/security.md](docs/security.md) | Authentication, authorization, limits and headers |

## Known environment issue

On some Windows hosts, Java NIO cannot create its selector wakeup pipe because
AF_UNIX sockets are unavailable, and embedded Tomcat fails to start with
`Unable to establish loopback connection`. Running the server in Docker — as
above — avoids this entirely. Tests are unaffected. The host-level fix is
`netsh winsock reset` from an elevated prompt, followed by a reboot.
