# Architecture

How GitForge is put together, and why the seams fall where they do.

## Three containers

```
                        ┌──────────────────────────────────────┐
   browser ── :80 ─────▶│ nginx                                │
                        │   /      → React build (static)      │
                        │   /api/  → proxy_pass server:8080    │
                        └───────────────┬──────────────────────┘
                                        │
                        ┌───────────────▼──────────────────────┐
                        │ Spring Boot                          │
                        │   web · security · services          │
                        │   ┌──────────────────────────────┐   │
                        │   │ com.gitforge.vcs             │   │
                        │   │ plain Java, no Spring         │   │
                        │   └──────────────┬───────────────┘   │
                        └──────────────────┼───────────────────┘
                                 │         │
                    ┌────────────▼───┐  ┌──▼─────────────────────┐
                    │ PostgreSQL     │  │ object store on disk   │
                    │ accounts       │  │ <repo-uuid>/objects/   │
                    │ repositories   │  │ <repo-uuid>/refs/heads │
                    │ issues         │  │ <repo-uuid>/HEAD       │
                    │ comments       │  └────────────────────────┘
                    └────────────────┘
```

The browser only ever talks to nginx. The API shares its origin, which is why
production needs no CORS: there is no cross-origin request to permit.

## Two kinds of state, kept apart

**PostgreSQL** holds what the platform knows: who has an account, which
repositories exist, who owns them, and the issues filed against them. Flyway owns
the schema; Hibernate runs with `ddl-auto: validate` and may only confirm that
the entities match the migrations.

**The object store** holds what version control knows: blobs, trees, commits and
refs. It is a plain directory tree, one subdirectory per repository, named by the
repository's UUID.

Nothing is duplicated across the two. A repository's description is in Postgres;
its history is on disk. The database never stores a commit, and the object store
never learns who owns anything.

The consequence worth knowing: the object store is the only state that cannot be
rebuilt. Lose the database and you have orphaned histories; lose the storage
volume and the commits are gone.

## Module map

```
com.gitforge
  ├── auth/          registration, sign-in, JWT issuing
  ├── user/          profiles
  ├── repo/          repository metadata and the access rules
  ├── issue/         issues and their comments
  ├── security/      JWT verification, filter chain, rate limiting
  ├── common/
  │     ├── error/   the error envelope and every exception mapping
  │     └── web/     request size limit filter, health endpoint
  ├── config/        CORS
  ├── demo/          development-only dataset seeding
  ├── vcsapi/        the HTTP surface of the engine
  └── vcs/           ── the engine ───────────────────────────────
        ├── hash/        SHA-1
        ├── object/      Blob, Tree, Commit, ObjectId, framing
        ├── storage/     the object store
        ├── tree/        building, walking and updating trees
        ├── ref/         branches and HEAD
        ├── graph/       the commit DAG
        ├── diff/        tree diff and Myers line diff
        ├── merge/       three-way merge and conflict classification
        ├── repository/  the engine's own facade
        └── worktree/    checkout (not used by the web application)
```

`com.gitforge.vcs` imports nothing from Spring. `VcsConfig` is the single seam:
it reads a configured path and hands the factory to the container. The engine can
therefore be tested without an application context — and its tests are, which is
what keeps the boundary from quietly eroding.

`vcsapi` is the other half of that seam: DTOs, authorization, and HTTP concerns
live there so the engine never learns what a request is.

## The chokepoint

Every path to a repository's storage goes through `VcsRepositoryProvider`:

```java
VcsRepository forRead(String owner, String name, User viewer)
VcsRepository forWrite(String owner, String name, User viewer)
```

Both resolve the repository through `RepoService` — applying visibility and
ownership — before touching the filesystem. Because there is no other way to
obtain a `VcsRepository`, a controller added next year cannot forget the check:
there is nothing to call that skips it. That is a structural guarantee rather
than a convention someone has to remember.

The storage id is always derived from the database row, never from the request. A
client names `owner/name`; the identifier that reaches the filesystem is the
repository's own UUID. Path traversal in a repository name cannot escape, because
the name never becomes a path.

## Request lifecycle

A commit, end to end:

1. **nginx** matches `/api/` and proxies to the server.
2. **`RequestSizeLimitFilter`** (order −200, ahead of security) rejects a body
   over 16 MB with 413, checking the declared length and then counting the stream.
3. **`JwtAuthenticationFilter`** verifies the bearer token and populates the
   security context. No token is fine — many reads are anonymous.
4. **`SecurityConfig`** allows the route: reads under `/repositories/**` are
   public, writes require authentication.
5. **`CommitController`** binds and validates the request body.
6. **`CommitApiService`** decodes every change, measures it against the per-file,
   per-commit and change-count limits, and refuses before the engine is touched.
7. **`VcsRepositoryProvider.forWrite`** resolves the repository and applies
   ownership.
8. **The engine** writes blobs, builds trees, writes the commit, then moves the
   branch. Objects first, ref last: a failure part-way leaves unreferenced
   objects, never a branch pointing at a commit that was not written.
9. **`GlobalExceptionHandler`** maps anything thrown to the error envelope.

## Errors

One shape, everywhere:

```json
{
  "timestamp": "2026-08-21T10:20:30.012Z",
  "status": 400,
  "code": "BAD_REQUEST",
  "message": "Required parameter 'base' is missing",
  "path": "/api/v1/repositories/forge-demo/dag-demo/diff"
}
```

`GlobalExceptionHandler` maps every failure that can reach it, including the ones
Spring raises before a controller runs — an unmatched route, a wrong method, an
unconvertible query parameter, a missing one. Each has a test. The catch-all
exists but should never be the reason a client sees a 500: anything that reaches
it is a bug, and it logs accordingly.

## Frontend

```
src/
  routes/       route table, lazy imports, auth guards
  pages/        one component per screen
  components/
    common/     Markdown, ModalDialog, ErrorBoundary, loading/empty/error states
    layout/     AppShell, PageContainer
    repository/ header, tabs, file tree
    commit/     graph, history rows
    diff/       viewer, hunks, lines
    merge/      outcome and conflict presentation
    issues/     list, detail, comments
    branch/     selector and list
    contributions/  the calendar
  services/     one axios client, one module per resource
  utils/        pure logic, unit-tested
  theme/        GitForge design tokens over Primer
```

State is React hooks — no state library. Data fetching is per-page: a hook calls
a service, holds loading and error, and renders through `AsyncBoundary`.

**Pure logic lives in `utils/`** and is tested without a DOM: commit graph lane
assignment, diff row construction, merge outcome interpretation, issue filtering,
contribution binning, branch name validation. 185 tests, no React Testing Library
— the logic worth testing was extracted rather than tested through components.

**Every route is `React.lazy`**, and React, Primer and the markdown renderer are
split into their own vendor chunks. A visitor landing on the sign-in page does
not download the diff viewer.

## Demo data

`com.gitforge.demo` builds the demonstration dataset through the application's own
services, so it passes the same validation and authorization as anything a user
creates. Commits go one level deeper, to the engine, because only there can the
author's timestamp be chosen — a contribution calendar with every commit on one
afternoon demonstrates nothing.

It is gated twice: the `demo` Spring profile *and* `gitforge.demo.reset`.
Production compose sets neither, and the seeder re-checks the profile at runtime
rather than trusting the annotation, because the cost of being wrong is the whole
database. See the README for how to run it.
