# GitForge threat model

Who might attack this, where they would push, and what stops them. Written
during the V2.0.18 hardening pass, from the code as it stands rather than from a
category list — every gap named below was reproduced before it was called a gap,
and every one marked closed has a test that fails when the fix is removed.

It is deliberately short. A threat model nobody rereads is a document; this one
is meant to be checked against the code when the code changes.

## Attackers

| | Who | What they already have |
|---|---|---|
| A1 | Unauthenticated internet caller | The public API surface |
| A2 | Authenticated user | An account, and a token |
| A3 | Malicious repository owner | Full control of one repository's content and configuration |
| A4 | Malicious remote peer | A URL a GitForge server has been pointed at |
| A5 | Local unprivileged user | A shell on the machine running the CLI |
| A6 | Malicious filesystem state | Write access under the storage root |
| A7 | Resource-exhaustion caller | Any of the above, plus patience |

## Threats

| Threat | Attacker | Entry point | Impact | Existing defence | Gap found | Mitigation |
|---|---|---|---|---|---|---|
| Reach an internal service through a remote | A2/A4 | `POST /remotes`, then fetch | Read cloud metadata, reach internal hosts | `RemoteUrl` refuses non-public addresses | **Yes — critical.** The client followed redirects on GET, so a validated public host could redirect the server anywhere | Redirects refused; 3xx reported as a failed transfer |
| Reach a private IPv6 network | A2/A4 | Remote URL | Same as above, over IPv6 | The JDK's four address questions | **Yes — high.** `fc00::/7` is matched by none of them, so every private IPv6 address passed | Ranges named explicitly, including CGNAT, NAT64, 6to4 and IPv4-compatible forms |
| Read another user's stored token | A5 | The CLI credentials file | Account takeover for the token's lifetime | `rw-------`, where POSIX exists | **Yes — high.** On Windows the call was a no-op and its failure was discarded; the file was readable by four principals | Owner-only on POSIX and ACL alike, established before the token is written, verified by reading it back, and refused if it cannot be done |
| Forge what a CLI user sees | A3 | Repository name, commit message, issue body | A person believes a private repository is public, or a command succeeded | Redaction, but nothing about control characters | **Yes — medium.** Untrusted strings reached the terminal unchanged | Control and bidirectional characters made visible at the single output gate; JSON escapes them properly |
| Crash the server with one object file | A6 | A tampered object under the storage root | Process death, denial of service | Hash verification after reading | **Yes — medium.** Decompression was unbounded, so the crash came first | Inflation bounded at 64 MiB; a bomb is a corrupt object, not an OOM |
| Ship a known vulnerable dependency | Supply chain | Any release | Whatever the advisory says | Nothing asked | **Yes — critical.** Three critical Tomcat advisories were present and unnoticed | Every resolved artifact scanned against OSV.dev in CI; the build fails on findings |
| Read a private repository | A1/A2 | Any read endpoint | Disclosure | `requireReadable`, 404 rather than 403 | No | — |
| Write to somebody else's repository | A2 | Any mutating endpoint | Tampering | `VcsRepositoryProvider` is the only route to a handle, and it authorises | No | — |
| Substitute a repository id | A2 | Path or body | Cross-tenant access | The storage id comes from the database row, never the request | No | — |
| Guess a password | A1 | `POST /auth/login` | Account takeover | BCrypt, plus a bounded per-address limiter | Partial — blocking was silent | One log line when an address starts being refused |
| Learn whether an account exists | A1 | Login timing | Enumeration | A verification against a fixed hash runs even when no account matched | No | — |
| Forge or replay a token | A1 | `Authorization` header | Impersonation | HS256 with a required 256-bit secret, issuer checked, expiry checked, no unsigned tokens accepted | No | — |
| Exhaust memory with one request | A7 | Any body | Denial of service | 16 MiB request cap, checked twice; per-batch, per-object and per-page ceilings | No | — |
| Inject SQL | A1/A2 | Any parameter | Full data disclosure | Derived queries and three JPQL statements, all with named parameters; no concatenation anywhere | No | — |
| Read a stack trace or internal path | A1 | Any failure | Reconnaissance | Internal failures logged, generic message returned; parser messages never echoed | No | — |

## What is not closed

**DNS rebinding.** `RemoteUrl` resolves the host, and the connection resolves it
again; nothing makes the two answers agree. Closing it means pinning the socket
to a vetted address, which is a transport change rather than a validation one.
Refusing redirects removes the easy version of this attack — the one that needs
no timing at all — but not the attack.

**Time of check to time of use, on the filesystem.** The CLI sandbox decides
where a path leads and the caller then opens it. A link swapped in between the
two would not be seen. No library closes this; it needs the operating system.

**A privileged local account.** An administrator on Windows may take ownership of
the credentials file, and `root` may read `rw-------`. Both are true and neither
is a defect: the file is protected against other users, not against the machine's
owner.

**The development database password.** `docker-compose.yml` defaults
`POSTGRES_PASSWORD` to `gitforge_dev`. It is a development convenience and it is
documented as one, but a deployment that never sets it inherits it. It was left
alone deliberately: PostgreSQL applies the password only when the data directory
is first initialised, so changing the default would not change any existing
deployment's password while breaking every developer's running volume. The
safe fix is a deployment-time requirement, not a new default.
