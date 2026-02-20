# GitForge

A GitHub-style application whose repository features are powered by a version
control system implemented from first principles — content-addressable storage,
SHA-1 object identity, Merkle trees, a commit DAG, branches and three-way merge —
with no Git library involved.

> **Status: Phase 1 of 10 complete.** The application platform (accounts,
> repositories, issues, authentication and authorization) is built and tested.
> The version control engine is the subject of the phases that follow; nothing in
> this repository simulates or fakes it in the meantime.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 18, Vite, React Router |
| Backend | Java 21, Spring Boot 4, Spring Security, Spring Data JPA |
| Database | PostgreSQL 16, Flyway migrations |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers |

## Running it

Requires Docker and Node 18+.

```bash
cp .env.example .env
```

Set `JWT_SECRET` in `.env` to a random value of at least 32 characters:

```bash
openssl rand -base64 48
```

Start PostgreSQL and the API:

```bash
docker compose up -d --build
```

Then the frontend:

```bash
cd frontend && npm install && npm run dev
```

The app is at http://localhost:5173 and the API at http://localhost:8080.

### Ports

PostgreSQL is published on host port **5433**, not 5432, so it does not collide
with a natively installed PostgreSQL.

## Tests

```bash
cd server && ./mvnw verify
```

Unit tests run under Surefire; integration tests (`*IT`) run under Failsafe
against a real PostgreSQL instance started by Testcontainers, so the Flyway
migrations and every security rule are exercised as they are in production.

```bash
cd frontend && npm run lint && npm run build
```

## Architecture

```
server/src/main/java/com/gitforge/
  common/      error envelope, shared response types
  config/      CORS
  security/    JWT issuing/verification, authentication filter, filter chain
  auth/        registration and sign-in
  user/        profiles
  repo/        repository metadata and access rules
  issue/       issue lifecycle
```

The version control engine will live under `com.gitforge.vcs` with no dependency
on Spring's web or persistence layers, so it can be tested without starting an
application context.

### Design notes

**Authorization lives in the service layer**, not in controllers or URL patterns,
so an endpoint added later cannot accidentally bypass it.

**Private repositories return 404, never 403.** A 403 would confirm that a
repository exists, which is itself a disclosure.

**Repository names are unique per owner**, not globally, so two people may each
own a repository called `portfolio`.

**Flyway owns the schema.** Hibernate runs with `ddl-auto: validate` and may only
verify that the entities match the migrations.

**Issue numbers are assigned under a row lock** on the owning repository, so
concurrent submissions cannot collide on the same number.

## Known environment issue

On some Windows hosts, Java NIO cannot create its selector wakeup pipe because
AF_UNIX sockets are unavailable, and embedded Tomcat fails to start with
`Unable to establish loopback connection`. Running the server in Docker — as
above — avoids this entirely. Tests are unaffected. The host-level fix is
`netsh winsock reset` from an elevated prompt, followed by a reboot.
