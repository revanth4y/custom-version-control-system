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
| Tag name | 255 characters | **400** |
| Release title | 255 characters | **400** |
| Release body | 100,000 characters | **400** |

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

## Remote transfer

Registering a remote is the first thing in GitForge that makes **the server**
issue an outbound request on a caller's behalf. Until V2.0.13 nothing in
`server/src/main/java` made an outbound call at all, so this is a new class of
exposure rather than an extension of an existing one.

**What a remote may point at.** `RemoteUrl` refuses anything but `http` and
`https`, refuses a URL carrying credentials, caps the length, and — unless a
deployment explicitly sets `vcs.remote.allow-private-addresses` — refuses a host
where **any** resolved address is loopback, link-local, site-local, any-local or
multicast. Every address, not the first: a name with one public and one loopback
address would otherwise pass on whichever the resolver happened to return. A host
that cannot be resolved is refused rather than allowed.

The URL is re-validated on every outbound call, not only when the remote was
registered. Configuration outlives the check that admitted it.

> **This does not close DNS rebinding.** The name is resolved by the guard and
> again by the HTTP client, and nothing makes the two answers agree. Closing that
> needs the connection pinned to a vetted address, which belongs with deeper
> transport hardening. What is here makes the exposure bounded and visible; it is
> not a claim that outbound requests are safe against a determined attacker.

**What arrives is not trusted.** Every received object is re-hashed by the engine
before it is stored, so a peer cannot introduce content under an id that does not
describe it. Before any branch moves, the whole history beneath the proposed tip
is walked against the local store; if anything is missing the push is refused and
no reference moves. Ref names from a peer are validated by the same rules a local
name is, and the resolved path is checked against the remotes directory
independently of those rules.

**What a peer can spend.** At most 32 ids per read request, 500 objects and 8 MiB
per receive, and 10,000 objects per transfer — all below
`RequestSizeLimitFilter`'s 16 MiB ceiling, which still applies underneath.
Outbound calls carry a 10-second connect and 30-second read timeout, because a
peer that accepts a connection and then says nothing is a denial of service it
can perform by doing nothing.

**Authorization is the existing model, unchanged.** Advertisement and object
reads follow the ordinary read rule — anonymous on a public repository, invisible
on a private one. Registering, fetching, pushing and receiving are owner-only.
`SecurityConfig` needed no new rule, because reads are GETs and writes are POSTs.

**Credentials are not stored.** Pushing to a peer needs a token that peer will
accept; it is supplied per request and used for that call only. Storing peer
credentials is a separate problem with its own requirements, and a token in a
repository file is a token nobody remembers is there.

## Tags and releases

A tag name becomes a path under `refs/tags`, so an unvalidated one is a
filesystem write primitive — `../../objects/ab/cdef` would let a caller overwrite
an immutable object through the mutable ref API. `TagName` rejects control
characters, a forbidden character set, `@{`, `.` and `..` segments, segments
starting `.` or `-`, segments ending `.lock`, absolute paths, and empty segments.

**Containment is then checked again, independently.** `FileSystemRefStore`
resolves the path, normalizes it, and confirms it still starts inside the tags
root — exactly as it does for branches and remote-tracking refs. A naming rule
that turns out to be incomplete still cannot become a write outside the refs
directory.

Two further rules exist because tags take part in revision resolution: a name
that is exactly a full object id is refused outright rather than resolved by
precedence, and names are length-capped.

**Mutations are owner-only.** Creating and deleting a tag, and creating, editing
and deleting a release, all go through the same `forWrite` check every other
write uses. Reads follow ordinary repository visibility, so a private
repository's tags are invisible — reported as absent rather than forbidden, since
distinguishing the two would itself disclose the repository.

**A draft release is the owner's alone**, and a stranger asking for one directly
is told it does not exist rather than that it is forbidden.

A release stores the **name** of a tag and never an object id. Beyond keeping the
collector's root set complete, it means no request can steer the database into
holding a reference to arbitrary stored bytes.

**Tags are never transferred.** A fetch or push that encounters a tag object
refuses rather than walking it, on both the sending and the receiving side, so
V2.0.13's wire behaviour is unchanged and a peer cannot introduce a tag object
into this repository.

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
- **No tag signing, and no signature verification.** An annotated tag records who
  wrote it as plain text taken from the authenticated caller. It is evidence of
  who was signed in, not cryptographic proof of authorship.
- **A tag is immutable, but not undeletable.** The owner may delete one, and
  since there is no reflog the object it named may then be swept. Immutability
  prevents a silent re-point, not a deliberate removal.
- **Garbage collection is manual and irreversible.** An object written before a
  merge was refused stays on disk — unreferenced, but present — until the owner
  runs a sweep. Reporting what is collectible follows ordinary read visibility, so
  on a public repository an anonymous caller can see how many unreferenced objects
  exist and how large they are; collecting is owner-only. There is no reflog, so a
  swept object is gone.
- **Remote transfer is not rate limited**, and reads on a public repository are
  anonymous, so a peer can ask for objects as often as it likes. The same is
  already true of every other repository read.
- **DNS rebinding is not closed** for outbound remote requests; see *Remote
  transfer* above.
- **No encryption at rest.** Object storage is a plain directory tree; anyone with
  filesystem access can read every repository, private ones included.
- **Single instance.** The rate limiter's counters and any in-process state are
  per-instance, and the object store is a local directory. Running several copies
  needs shared storage and a shared limiter.
