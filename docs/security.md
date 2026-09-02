# Security model

What is defended, how, and what is not.

## Authentication

Stateless JWT, HS256. `JwtService` issues a token carrying the user's id as
subject, an issuer, and an expiry (one hour by default). `JwtAuthenticationFilter`
verifies the signature and expiry on each request and populates the security
context; there is no session and no server-side token store.

**The signing secret is never defaulted.** `JwtProperties.secret` has no
fallback, and the application refuses to start if it is absent or shorter than
the algorithm requires. A build-time default would risk shipping a known signing
key, which is the kind of thing that survives into production because it works.

Passwords are BCrypt. Login runs the hash comparison **even when no account
matched**, against a well-formed hash that nothing matches, so response timing
does not reveal whether an email is registered.

### Known limitations

**No refresh tokens.** A token expires after an hour and the user signs in again.

**No revocation.** A stateless token is valid until it expires; signing out
discards it client-side but cannot invalidate it. Revocation needs server-side
state, which is the thing statelessness bought away.

**The token is held in browser storage,** which means an XSS bug would expose it.
The CSP is the mitigation; a `HttpOnly` cookie would be a different design, with
CSRF to answer for instead.

## Authorization

**Authorization lives in the service layer**, never in URL patterns. Security
rules on paths decide only whether a request may proceed anonymously; whether
*this* caller may see *this* repository is decided where the repository is loaded.

```java
Repo requireReadable(String owner, String name, User viewer)   // 404 if not visible
Repo requireWritable(String owner, String name, User viewer)   // 404, then 403
```

### The chokepoint

Every route to repository storage goes through `VcsRepositoryProvider`:

```java
VcsRepository forRead(String owner, String name, User viewer)
VcsRepository forWrite(String owner, String name, User viewer)
```

Both resolve through `RepoService` first. There is no other way to obtain a
`VcsRepository`, so an endpoint added later cannot bypass the check — it has
nothing to call that skips it. That is a structural guarantee, not a convention.

### 404 before 403

**A private repository answers 404 to everyone but its owner.** A 403 would
confirm that it exists, which is itself a disclosure — `octocat/acquisition-plans`
returning "forbidden" tells you something.

Where the resource *is* visible, 403 is correct and used: committing to someone
else's public repository is forbidden, and its existence was never a secret.

The same ordering applies to issues and comments: existence is hidden first,
permission reported second.

```
not visible          → 404
visible, not yours   → 403
```

### Who may do what

| Action | Permission |
|---|---|
| Read a public repository | anyone, including anonymous |
| Read a private repository | its owner |
| Commit, branch, merge, edit repository | its owner |
| File an issue | any authenticated user who can read it |
| Comment | any authenticated user who can read it |
| Edit or delete an issue | its author, or the repository owner |
| Edit or delete a comment | its author, or the repository owner |

The repository owner is the moderator of their own repository's discussion.

### Anonymous reads

Public repositories are readable without an account: browsing code, history,
branches, diffs, issues, comments, profiles and insights. Every one of those
routes resolves through the same `requireReadable`, so a private repository is
absent from all of them, not just from the ones someone remembered to guard.

Anonymous callers may not write anything. An unknown path answers **401 rather
than 404** to an anonymous caller, because the filter chain rejects before
routing — which also means it does not reveal which endpoints exist.

## Input limits

Sized so that one request cannot make the server do unbounded work.

| Limit | Value | Response |
|---|---|---|
| Request body | 16 MB | **413** |
| One file in a commit | 10 MB | **400** |
| Total content in a commit | 12 MB | **400** |
| Changes in a commit | 500 | **400** |
| Commit history per request | 200 | clamped |
| Contribution range | 366 days | **400** |
| Line diff | 20,000 lines / 5,000 edits | reported without hunks |
| Files with hunks per diff | 100 | reported without hunks |

413 is the transport refusal, before the body is parsed; 400 is the application
refusing something it has read and understood.

`RequestSizeLimitFilter` runs at order −200, **ahead of Spring Security**: there
is no point authenticating a body that will be refused. It checks the declared
`Content-Length` first, which rejects the common case without reading anything,
and then counts the stream as it is consumed — because a client can understate the
length, or use chunked encoding and declare nothing at all. Tomcat's
`max-http-form-post-size` does not apply to JSON, so without this there was no
limit whatsoever.

Commit limits are applied to **decoded** content, before any object is persisted.
A rejected commit writes nothing and moves no branch.

## Rate limiting

`AuthAttemptLimiter` counts failed authentication attempts per remote address:
**ten failures in a fifteen-minute window**, then 429 with `Retry-After`.

- **Only failures count**, and a success clears the record entirely, so mistyping
  a password twice and then getting it right costs nothing.
- **Login and signup share one counter.** A rejected signup reveals whether an
  address is registered — the same oracle a failed login gives, reached through a
  different door.
- **The address comes from the connection**, never from `X-Forwarded-For`, which
  any client can set to whatever it likes. Behind a reverse proxy the deployment
  must set `server.forward-headers-strategy` so the connection reports the real
  client; otherwise every caller shares one bucket.
- **The table is bounded** at 10,000 addresses. An unbounded map keyed by remote
  address is itself a way to exhaust memory. Expired entries go first; if that is
  not enough the least recently seen are evicted, rather than clearing the table
  wholesale — a full reset would let an attacker wipe their own record by flooding
  from other addresses.
- **The refusal reveals nothing.** The same message whether the account exists or
  not.

In-memory and per-instance: it does not survive a restart, and several instances
behind a load balancer would each keep their own count.

## Browser-facing headers

The production nginx sends the Content-Security-Policy as a **real header**:

```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
img-src 'self' data:; font-src 'self'; connect-src 'self';
object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
```

- **`script-src` has no `'unsafe-inline'`.** The build emits one external module
  script and no inline script, so it does not need it.
- **`style-src` must allow inline styles.** Primer is built on styled-components,
  which writes a `<style>` element at runtime; without it the application renders
  unstyled. Removing that would mean threading a nonce through
  styled-components — a redesign, not a header.
- **`img-src data:`** is required by the generated avatars, which are SVG data
  URIs.
- **`connect-src 'self'`** suffices because the API shares the origin.
- **`frame-ancestors 'none'`** works because this is a header. In a `<meta>` tag
  the browser ignores it — the static build carries the rest of the policy as a
  meta tag for anyone serving `dist` directly, and deliberately omits this
  directive rather than emitting one that does nothing.

Also sent: `X-Content-Type-Options: nosniff`, `Referrer-Policy:
strict-origin-when-cross-origin`, `Permissions-Policy` denying geolocation,
microphone and camera, and `server_tokens off`.

The API sends `X-Frame-Options: DENY` on its own responses.

## CORS

Origins are configured, never wildcarded, and credentials are not allowed from
unlisted ones. Methods and headers are enumerated rather than opened.

**Production does not need CORS at all** — nginx proxies the API onto the
application's own origin, so no preflight ever happens. The configuration exists
for development, where Vite serves on `:5173` and the API answers on `:8080`, and
for deployments that put the two on separate hosts.

## Injection and escaping

**SQL** goes through Spring Data JPA with bound parameters. There is no string
concatenation into a query.

**Path traversal** cannot escape a repository, structurally: a client names
`owner/name`, and the identifier that reaches the filesystem is the repository's
UUID from the database row. The name never becomes a path. Within a repository,
paths are resolved through the tree structure rather than the filesystem, so
`../../etc/passwd` is simply a path that no tree contains — answered 404, not
because it was filtered but because it is not there.

**Stored content is data, never markup.** Issue titles and bodies are stored
verbatim and returned as JSON; the API never renders HTML. The client renders
markdown through `react-markdown`, which escapes by default and is not given
`rehype-raw`, so `<script>` in an issue body is text.

## Secrets

Nothing sensitive is committed. `.gitignore` excludes `.env` and `.env.*` while
keeping `.env.example`; the tracked example files hold placeholders.

Every secret is environment-driven: `JWT_SECRET`, `POSTGRES_PASSWORD`,
`DATABASE_*`. The application fails at startup rather than falling back to a
default signing key.

`server.error.include-message` and `include-stacktrace` are `never`, so a
stack trace cannot reach a client through Spring's default error path.

## The demo seeder

`com.gitforge.demo` deletes the entire database and storage root before rebuilding
its dataset. It is gated twice — the `demo` Spring profile **and**
`gitforge.demo.reset=true` — and production compose sets neither, so no
combination of environment variables alone can arm it. The seeder re-checks the
active profile at runtime rather than trusting the annotation.

The demo accounts share a password that is published in the repository. That is
intentional: they exist to be signed into while showing the application, and the
profile that creates them cannot run against a real deployment.

## What is not defended

Stated plainly, because a security document that lists only wins is not useful.

- **No CSRF tokens** — none are needed while authentication is a bearer token
  rather than a cookie, but that ties the design to header auth.
- **No token revocation**, as above.
- **No audit log.** Who deleted a repository is not recorded anywhere.
- **No 2FA, no password reset, no email verification.** Accounts are
  username/email/password and nothing else.
- **Rate limiting covers authentication only.** Repository reads are not
  throttled; an anonymous client can poll them freely.
- **Garbage collection is manual and irreversible.** An object written before a
  merge was refused stays on disk — unreferenced, but present — until the owner
  runs a sweep. Reporting what is collectible follows ordinary read visibility, so
  on a public repository an anonymous caller can see how many unreferenced objects
  exist and how large they are; collecting is owner-only. There is no reflog, so a
  swept object is gone.
- **No encryption at rest.** Object storage is a plain directory tree; anyone with
  filesystem access can read every repository, private ones included.
- **Single instance.** The rate limiter's counters and any in-process state are
  per-instance, and the object store is a local directory. Running several copies
  needs shared storage and a shared limiter.
