# SaveNest

Personal link library that auto-groups saved links (YouTube, Products, Movies & Series, Anime, Images, Memes, Articles, Social, Websites, Unsorted). Specification: `../SaveNest_Development_Prompt.md`.

## Status

| Milestone | State |
|---|---|
| 1. Scaffold, Docker Compose, PostgreSQL, Flyway, authentication | **Done** |
| 2. Pure Java URL classifier + tests | **Done** |
| 3. Save/list/edit APIs, duplicates, Next.js UI | **Done** |
| 4. Async metadata worker + SSRF-safe fetcher | **Done** |
| 5. Search, filters, tags, collections, favorites, progress | **Done** |
| 6. Archive/trash/restore/export, accessibility pass | Done |
| 7. Security tests, docs, deployment, benchmark, release checks | Done (Playwright E2E pending) |

See `PROGRESS.md` for details. Deployment: `docs/DEPLOYMENT.md`. Security review: `docs/SECURITY.md`. Benchmark: `docs/BENCHMARK.md`.

## Release checks

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/savenest_rc TEST_DATABASE_USERNAME=postgres ./scripts/release-check.sh
# add BENCHMARK=true (with a separate empty DB) to also run the 10k-bookmark benchmark
```

CI (`.github/workflows/ci.yml`) runs the backend tests against PostgreSQL 16, then packages the backend and runs the frontend typecheck and build.

## Metadata worker (Milestone 4)

- Saving inserts the bookmark with `metadata_status=pending` and enqueues a row in `metadata_jobs` (PostgreSQL queue). Saving never waits for or fails because of enrichment.
- The worker (`com.savenest.metadata.MetadataWorker`) claims jobs with `FOR UPDATE SKIP LOCKED` and a lease. It retries transient failures (timeouts, 5xx, 429) with capped exponential backoff and jitter, and dead-letters jobs after `WORKER_MAX_ATTEMPTS`. A sweep re-enqueues pending bookmarks whose job went missing.
- Extraction order: YouTube oEmbed (no key needed), then HTML Open Graph/Twitter/JSON-LD/title, then a hostname fallback. Scripts never run. Logins, paywalls and anti-bot blocks are never bypassed: 401/403/451 → `blocked`.
- Short links (`amzn.to`, etc.) are reclassified after safe resolution and the resolved URL is stored separately. Metadata only refines uncertain types (IMDb `media_title` → movie/series, unknown → article, image candidate → verified) and never overrides a strong URL rule or the user's group override.
- User-edited titles and descriptions are never overwritten. Results are applied under a row lock and only to existing, non-trashed rows, so a stale job cannot resurrect a deleted bookmark.
- **Separate process:** by default the worker runs inside the backend. For production, run a second instance of the same JAR with `WORKER_ENABLED=true` and set `WORKER_ENABLED=false` on the web instances.

### Restricted fetcher (`HttpSafeFetcher`)

- Only http/https on ports 80/443. No credentials, proxies, cookies or auth headers are sent.
- `localhost`, `*.local`, `*.internal` and single-label hosts are rejected.
- DNS is resolved through a filtering resolver. If **any** resolved address is loopback, private, link-local (including `169.254.169.254`), CGNAT, reserved, multicast, IPv6 ULA/link-local, or v4-mapped/NAT64/6to4-embedded private, the fetch is rejected. The connection uses only the validated addresses, which defeats DNS rebinding.
- Redirects are followed manually. Each hop is re-validated, up to `FETCH_MAX_REDIRECTS` hops.
- Connect, read and total timeouts apply. Body size is bounded after decompression, so gzip bombs are rejected. Content types come from an allowlist.
- Preview images are served via `GET /api/bookmarks/{id}/preview`. That endpoint is owner-checked, goes through the same fetcher, accepts raster types only (no SVG), and verifies magic bytes. The browser never contacts third-party hosts.
- **Deployment requirement:** also restrict the worker's network egress (firewall/security group) so it cannot reach internal networks even if application checks were bypassed.

## API (milestone 4 additions)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/bookmarks/{id}/refresh` | 202; re-queues enrichment, rate-limited (`RATE_LIMIT_REFRESH_PER_MINUTE`); 409 if trashed |
| POST | `/api/bookmarks/{id}/open` | records last-opened and open count |
| GET | `/api/bookmarks/{id}/preview` | proxied, validated preview image |

## Repository structure (target)

```text
savenest/
  docker-compose.yml       PostgreSQL (+ backend with --profile app)
  .env.example
  backend/                 Spring Boot 3, Java 21, Maven
    src/main/java/com/savenest/
      auth/                register/login/logout/me, CSRF endpoint
      user/                User entity + repository
      config/              Spring Security
      common/              error envelope, request IDs, exceptions
      ratelimit/           per-bucket rate limiter
      classifier/          (M2) pure URL classifier
      bookmark/ collection/ tag/   (M3, M5)
      metadata/            (M4) worker, SafeUrlFetcher, jsoup parser
    src/main/resources/db/migration/   Flyway SQL
  frontend/                (M6) Next.js App Router + TypeScript + Tailwind
  e2e/                     (M7) Playwright
```

## Prerequisites

- Java 21+, Maven 3.6+
- Docker (for PostgreSQL and Testcontainers integration tests)
- Node.js 20+ (frontend, milestone 6)

## Setup & run

```bash
cp .env.example .env            # edit passwords
docker compose up -d postgres   # PostgreSQL on 127.0.0.1:5432
cd backend
set -a; . ../.env; set +a
mvn spring-boot:run             # Flyway migrates automatically on startup
```

Or run everything in containers: `docker compose --profile app up --build`.

Migrations live in `backend/src/main/resources/db/migration` and are applied by Flyway at startup (`ddl-auto: validate` — Hibernate never alters the schema).

## Verify authentication (curl)

```bash
# 1. get CSRF cookie + token
curl -s -c jar -b jar http://localhost:8080/api/auth/csrf
TOKEN=$(awk '/XSRF-TOKEN/ {print $7}' jar)
# 2. register (auto-logs-in)
curl -s -c jar -b jar -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.test","password":"demo-password-123","displayName":"Demo"}' \
  http://localhost:8080/api/auth/register
# 3. token rotates after login — re-read it, then:
curl -s -b jar http://localhost:8080/api/auth/me
```

## URL classifier (Milestone 2)

Pure, network-free rule-based classifier in `backend/src/main/java/com/savenest/classifier/`:

- `UrlParser` – trims input, adds `https://` to scheme-less hosts, rejects non-http(s) schemes, embedded credentials, bad hosts; lowercases/punycodes hosts, drops default ports, never rewrites paths.
- `UrlClassifier` – exact-host/subdomain allowlists (no substring matching: `youtube.com.attacker.example` is Unsorted), then path/query rules. Returns group, platform, content type, subtype, resource ID, confidence, source, rule ID/version, reason.
- `UrlIdentity` – conservative normalized URL (strips only listed tracking params) and dedupe key (`platform:type:resourceId`, else SHA-256 of normalized URL).

| Method | Path | Notes |
|---|---|---|
| POST | `/api/classify` | `{"url": "..."}` → preview classification; auth + CSRF; rate-limited per user; 422 for invalid URLs |
| GET | `/api/groups` | Navigation groups in display order (counts/platform filters arrive with bookmarks in Milestone 3) |

```bash
TOKEN=$(awk '/XSRF-TOKEN/ {print $7}' jar)
curl -s -b jar -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"youtu.be/dQw4w9WgXcQ"}' http://localhost:8080/api/classify
```

Note: over plain HTTP set `COOKIE_SECURE=false`, otherwise curl/browsers will not send the session/CSRF cookies.

## Tests

```bash
cd backend && mvn test
```

Integration tests use Testcontainers (`postgres:16-alpine`) and are **skipped automatically when Docker is unavailable**; unit tests always run. Without Docker, point them at an empty, disposable PostgreSQL database instead:

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/savenest_it TEST_DATABASE_USERNAME=postgres mvn test
```

## API (milestone 1)

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/auth/csrf` | public | Sets `XSRF-TOKEN` cookie, returns header name + token |
| POST | `/api/auth/register` | public + CSRF | 201, starts session; rate-limited per IP |
| POST | `/api/auth/login` | public + CSRF | rate-limited per IP; generic error on failure |
| POST | `/api/auth/logout` | session + CSRF | 204 |
| GET | `/api/auth/me` | session | current user DTO |
| GET | `/actuator/health` | public | health check |

Errors always use `{"error":{"code","message","requestId",...}}`. Status codes: 401 no session, 403 CSRF failure, 409 conflict, 422 validation, 429 rate limit.

## Design decisions (spec conflicts)

The spec (§10, §12) names a TypeScript stack (Prisma, Auth.js + Google OAuth, Zod, Node worker). The request explicitly mandates Java/Spring Boot, so:

- **Auth**: Spring Security email/password with bcrypt (strength 12) instead of Auth.js/Google OAuth. Spec §12 says "use the auth library's routes instead of duplicating password endpoints" — the endpoints under `/api/auth` are the only password endpoints and delegate to Spring Security's `AuthenticationManager`. OAuth can be added later via `spring-boot-starter-oauth2-client`.
- **Sessions**: server-side, persisted in PostgreSQL via Spring Session JDBC (tables owned by Flyway), cookie `SAVENEST_SESSION` is `HttpOnly`, `SameSite=Lax`, `Secure` (configurable only for localhost). Session ID rotated on login (fixation protection); password hash erased from the session principal.
- **CSRF**: Spring Security double-submit cookie (`XSRF-TOKEN` → `X-XSRF-TOKEN` header) on every non-GET request, including login/register (more secure choice: prevents login CSRF). Token is rotated after authentication.
- **Validation**: Jakarta Bean Validation instead of Zod; password minimum 12 chars.
- **Migrations**: Flyway SQL instead of Prisma.
- **Request IDs** are always server-generated; client-supplied `X-Request-Id` is ignored.
- **Rate limiting** is in-memory per instance (limitation: not shared across replicas; use a shared store such as Redis or PostgreSQL before horizontal scaling).

## Known limitations (milestone 1)

- No OAuth / email magic link yet; no password reset. Account deletion is implemented
  (`DELETE /api/account?confirm=true` with the current password; UI under Settings).
- Rate limiter keys on `remoteAddr`; behind a proxy set `FORWARD_HEADERS_STRATEGY=native` only with a trusted proxy.
