# SaveNest Development Progress

Tracks completed work and current status. Source spec: `../SaveNest_Development_Prompt.md`.

**Overall: All 7 milestones done, plus account deletion (spec §14). 160 backend tests pass against a real PostgreSQL (no Docker; via `TEST_DATABASE_URL`), the benchmark is the only skipped test, and the 10k benchmark meets every target. Frontend type-checks and builds (13 routes). Still to do: browser click-through, Playwright E2E, a11y audit.**

| # | Milestone | Status |
|---|-----------|--------|
| 1 | Scaffold, auth, migrations, protected routes | Done (integration tests pass) |
| 2 | URL classifier, navigation groups | Done |
| 3 | Save/list/edit APIs, bookmark cards (first UI) | Done (builds; not run end-to-end) |
| 4 | Background metadata worker with fallbacks | Done (unit and DB integration tests pass) |
| 5 | Search, filters, tags, collections, favorites, progress | Done (tests pass; not run in a browser) |
| 6 | Archive, trash, restore, export, accessibility pass | Done (tests pass; full a11y audit pending browser run) |
| 7 | Security tests, deployment docs, benchmarks, release checks | Done (E2E/browser items remain) |

## Milestone 1: Foundation (done)
- Spring Boot + Maven backend (`backend/pom.xml`), `Dockerfile`, `docker-compose.yml` (PostgreSQL), `.env.example`, `.gitignore`, `README.md`.
- Flyway migration `V1__users_and_sessions.sql` (users, sessions).
- Auth: `GET /api/auth/csrf`, `POST /api/auth/register`, login, logout, `GET /api/auth/me`.
- Security: session cookies, CSRF (`XSRF-TOKEN` / `X-XSRF-TOKEN`), all routes protected except public auth endpoints, auth rate limiting.
- Common: `ApiError`, `ApiException`, `GlobalExceptionHandler`, `DataResponse`, `RequestIdFilter`.

## Milestone 2: Classifier (done)
- `classifier/`: `UrlParser`, `UrlClassifier`, `UrlIdentity`, `Classification`, `GroupId` (10 groups).
- Rules only, no network. Exact or subdomain matching, scheme and credential rejection, tracking-parameter stripping, canonical duplicate keys.
- Endpoints: `POST /api/classify` (auth, CSRF, rate-limited, 422 on invalid URL), `GET /api/groups`.

## Milestone 3: Bookmark APIs (done)
- Migration `V2__bookmarks.sql`: bookmarks table, partial unique index on (owner, dedupe key) for non-trashed rows, list/filter indexes.
- `bookmark/`: `Bookmark`, `BookmarkRepository`, `BookmarkService`, `BookmarkController`, `BookmarkDto`.
- Endpoints: `POST/GET /api/bookmarks`, `GET/PATCH/DELETE /api/bookmarks/{id}` (DELETE = trash), `GET /api/groups` (real counts + platform breakdown).
- Duplicate saves return 409 with `existingId`; concurrent races resolved by the unique index. Owner-scoped lookups return 404 for others' data.
- Cursor pagination, filters (view, group, platform, favorite), progress-state validation per content type.
- Not yet done: restore/permanent delete (Milestone 6), integration tests for bookmarks.

## Milestone 3: Frontend (done)
- `frontend/`: Next.js 15 App Router + React 19 + TypeScript + Tailwind, `package-lock.json` committed.
- `/api/*` is proxied to the backend via `next.config.mjs` rewrites (`BACKEND_URL`), so session and `XSRF-TOKEN` cookies are same-origin. CSP and security headers are set.
- `lib/api.ts`: typed client, CSRF double-submit header with one retry after token rotation, error envelope -> `ApiError`.
- Pages: `/login`, `/register`, `/` dashboard, `/all`, `/favorites`, `/groups/[id]` (platform chips from real counts, `?platform=` in URL), `/bookmarks/[id]`, `/archive`, `/trash`.
- Components: `AppShell`, `SaveDialog`, `BookmarkCard`, `BookmarkList` (cursor "Load more").
- Not yet: search box (`/` shortcut), tags/collections (M5), restore/permanent delete UI (M6), Playwright tests, grid/list toggle and sorting.

## Milestone 4: Metadata worker (done)
- Migration `V3__metadata_jobs.sql`: bookmark columns `preview_image_url`, `favicon_url`, `site_name`, `metadata_json`; `metadata_jobs` table (status queued/running/done/dead, attempts, `next_run_at`, lease `locked_until`/`locked_by`, `last_error`). A partial unique index allows one open job per bookmark, plus due/lease/dead indexes.
- `metadata/`:
  - `HttpSafeFetcher`: SSRF-restricted. Filtering DNS resolver that rejects if any candidate address is non-public; the connection uses only validated addresses. Manual redirect re-validation with a hop limit, port allowlist (80/443), timeouts, size limits after decompression, MIME allowlist, no cookies/auth/proxy.
  - `AddressPolicy`: IPv4/IPv6 blocklists (loopback, private, link-local/cloud metadata, CGNAT, reserved, multicast, ULA, v4-mapped/NAT64/6to4) and internal hostnames.
  - `HtmlMetadataExtractor` (jsoup, OG/Twitter/JSON-LD/title, plain text only, http(s)-only URLs) and `ImageSniffer` (magic bytes, no SVG).
  - `MetadataEnricher` (no DB, fake-fetcher testable): YouTube oEmbed, HTML, short-link reclassification, metadata refinement under rule priority.
  - `MetadataApplier`: row-locked apply that preserves edited title/description and the group override, never resurrects trashed or deleted rows, and keeps the old dedupe key if the resolved one is already saved.
  - `MetadataJobQueue`: `FOR UPDATE SKIP LOCKED` claim, lease reclaim, worker-guarded transitions, dead-letter, orphan sweep, purge.
  - `MetadataWorker`: scheduled, bounded thread pool, exponential backoff with jitter, URL-free logs, `WORKER_ENABLED` toggle so it can run as a separate process.
  - `PreviewImageController`: `GET /api/bookmarks/{id}/preview`, owner-checked same-origin image proxy.
- Bookmark: save enqueues a job (save never fails on enqueue). `PATCH` takes a row lock. New `POST /api/bookmarks/{id}/refresh` (rate-limited, 202, 409 if trashed) and `POST /api/bookmarks/{id}/open`. The DTO now exposes preview, favicon, site name, resolved URL, fetch time, error code and edit flags.
- Frontend: cards show the proxied preview image (lazy, fixed aspect, placeholder fallback) and status badges for pending/fetching/partial/failed/blocked. Lists poll pending items for up to about 2 minutes. Open records the action. The detail page has a Preview section with status, an explanation of the error, the resolved URL, fetched-at time, Retry metadata, and an open count. The edit form sends only changed text fields, so untouched titles are not frozen as "edited".
- Config: `savenest.worker.*` and `savenest.fetch.*` in `application.yml`, documented in `.env.example` and the README.

## Test status
| Tests | Result |
|---|---|
| `RateLimiterTest` | 2 passed |
| `UrlClassifierTest` | 52 passed |
| `BookmarkServiceProgressTest` | 2 passed |
| `AddressPolicyTest` | 38 passed |
| `HttpSafeFetcherTest` (local server: private/IPv6/metadata targets, redirect-to-private, mixed DNS, redirect limit, ports, oversize, gzip bomb, MIME, no header leakage) | 10 passed |
| `MetadataEnricherTest` (fake fetcher: OG, plain text, blocked vs transient, oEmbed, short-link reclassify, IMDb refine, rule priority, image MIME + magic) | 11 passed |
| `MetadataWorkerIntegrationTest` (single open job, edits preserved, no resurrection, cross-owner, lease reclaim) | 5 skipped (no Docker); not verified |
| `AuthIntegrationTest`, `MetadataWorkerIntegrationTest`, `OrganizeIntegrationTest` | Pass (7 + 5 + 11) against local PostgreSQL 12 via `TEST_DATABASE_URL`; Testcontainers path still unverified |
| Frontend `tsc --noEmit` | Passed |
| Frontend `next build` | Passed (12 routes) |
| `SecurityIntegrationTest` (M7) | 12 passed |
| `ApiBenchmarkTest` (M7, opt-in) | Passed; all p95 targets met at 10k rows |
| End-to-end (browser vs. backend + DB) | Not run |

## Known gaps / notes
- Classifier lives in the backend, not a separate `packages/classifier`.
- Local runs need `COOKIE_SECURE=false` over plain HTTP.
- Auth is email/password via the Spring backend, not Auth.js/Google OAuth as the spec suggests.
- Worker runs in-process by default; run it separately with `WORKER_ENABLED`. Spring scheduling plus a PostgreSQL queue replaces the spec's Node worker.
- Images are proxied on demand, not stored in S3 (object storage is deferred). Favicons are stored but not displayed yet.
- No optional provider API keys (TMDB, etc.); only keyless YouTube oEmbed.
- Network egress restriction for the worker must be configured at deploy time (Milestone 7 docs).
- Frontend run: `cd frontend && npm install && npm run dev` (backend on :8080, `COOKIE_SECURE=false`).

## Milestone 5: Search, tags, collections (done)
- Migration `V4__tags_collections_search.sql`: `tags`, `collections` (unique per owner on a normalized name), `bookmark_tags`, `bookmark_collections` (cascade on delete, reverse indexes), triggers rejecting cross-owner links at the database level, SQL function `bookmark_text_match`, and content-type and last-opened indexes.
- Backend `organize/`: `TagService` (case-insensitive, race-safe get-or-create with `ON CONFLICT`, rename, merge, delete, counts), `CollectionService` (CRUD, owner-scoped lookups; foreign ids give 404), `OrganizeController` (`/api/tags`, `/api/tags/{id}/merge`, `/api/collections`).
- `BookmarkSearch`: filters (group, platform, content type, subtype, progress, favorite, tags with AND semantics, collection, saved-date range), sorts (newest, oldest, title, opened) with keyset cursors that embed the sort, and search.
- Search: each word must match as a prefix in title, description, notes, domain or platform (PostgreSQL full-text), or in a tag or collection name. Input is reduced to letters and digits, so it cannot inject query syntax. Max 200 characters and 8 words.
- `POST/PATCH /api/bookmarks` accept `tagNames` and `collectionIds` (max 20 each). `GET /api/bookmarks` accepts `q`, `sort`, `contentType`, `subtype`, `progress`, `tag` (repeatable), `collection`, `from`, `to`. Responses include `tags` and `collections`.
- Frontend: `LibraryToolbar` (debounced search, sort, filters, grid/list toggle persisted in localStorage; state in the URL), `/collections/[id]`, `/tags` (rename, merge on name clash, delete), tag and collection pickers in the save dialog and detail page, collections in the sidebar, tag and collection chips on cards, `/` focuses search, `N` opens save.
- Fixes found by running against a real database: `HttpSafeFetcher` had two constructors and the app could not start (now `@Autowired`); the auth test used a session mechanism the app does not use (now cookie-based); `IntegrationTest` can use `TEST_DATABASE_URL` instead of Docker.

## Milestone 6: Lifecycle and export (done, except a full accessibility audit)
- `POST /api/bookmarks/{id}/restore`: row-locked; idempotent; rechecks duplicates (409 `duplicate_bookmark` with the active copy's `existingId`; races caught by the partial unique index). Archive, favorite, progress and tags are preserved.
- `DELETE /api/bookmarks/{id}/permanent?confirm=true`: only for trashed bookmarks (409 `not_in_trash` otherwise); without `confirm=true` it returns 422 `confirmation_required`. Tag/collection links and jobs cascade.
- `DELETE /api/trash?confirm=true`: empties the caller's trash and returns `{deleted}`.
- `GET /api/export?format=json|csv` (`ExportController`): rate-limited (`EXPORT` bucket), `no-store`, attachment. JSON is versioned and includes tags, collections and all bookmarks in every state. CSV is RFC 4180 with a UTF-8 BOM, and formula-injection cells (`= + - @`) are prefixed with `'`.
- Frontend: `ConfirmDialog` (native modal `<dialog>`: focus trap, Escape, focus returns, Cancel is focused first). Trash cards have Restore and Delete forever. The Trash page has Empty trash. The detail page has Restore from trash. New `/settings` page with JSON/CSV download, linked in the sidebar.
- Accessibility: reviewed the existing skip link, `aria-live` status region, focus-visible outline, reduced motion and labelled actions; restore/delete results are announced. A full WCAG audit (axe and screen reader) is still to do, together with the browser click-through.
- Tests: `LifecycleIntegrationTest` (5) and `ExportCsvTest` (2). `MetadataWorkerIntegrationTest` now clears open jobs before each test, because jobs queued by other test classes in the shared database could fill its bounded claim.
- Totals: 145 backend tests pass against PostgreSQL. Frontend `tsc` and `next build` (13 routes) pass.

## Milestone 7: Security, deployment, benchmark, release (done)
- `SecurityIntegrationTest` (12, full HTTP stack + PostgreSQL) covers: every `/api/**` endpoint returns 401 without a session; unknown/actuator paths are denied; authenticated writes without (or with an invalid) CSRF token get 403; cross-user GET/PATCH/DELETE/restore/permanent/refresh/open/preview return 404 and change nothing; empty-trash only affects the caller's rows; foreign tags/collections cannot be edited, merged, attached or filtered on, and tag names resolve only within the caller's account; duplicate responses never leak another user's id; JSON/CSV export is owner-scoped and `no-store`; dangerous schemes, credentials, bad sort/view/cursor, oversize search and malformed JSON are rejected; `' OR 1=1 --` is harmless; notes containing `<script>` come back as JSON data with `nosniff`; security headers are set; client `X-Request-Id` is ignored; 8 concurrent saves of URL variants create exactly one bookmark.
- `ApiBenchmarkTest` (opt-in, `BENCHMARK=true`): seeds 2 × 10,000 bookmarks and measures p50/p95/max. It fails if a GET p95 is above 500 ms or the save p95 is above 800 ms. Observed p95: list 7 ms, title sort 17 ms, search 7 to 28 ms (55 ms for a no-match full scan), groups 6 ms, save 4 ms. A GIN tsvector index is not needed yet; see `docs/BENCHMARK.md`.
- `docs/DEPLOYMENT.md`: topology (web, API, worker, PostgreSQL), environment variables, TLS/HSTS/proxy, required worker egress rules (with a Kubernetes NetworkPolicy example), rate limits with replicas, health checks, migrations, backups and restore tests, release and rollback.
- `docs/SECURITY.md`: maps each spec §14 / OWASP SSRF control to its implementation and test, and lists open risks.
- `scripts/release-check.sh`: checks secrets and `.gitignore`, production config defaults (secure cookie, no stack traces, health-only actuator), backend tests (warns if integration tests were skipped), package, and frontend typecheck and build. The benchmark is optional.
- `.github/workflows/ci.yml`: backend tests against a PostgreSQL 16 service (fails if integration tests are skipped), package, and frontend `npm ci`, typecheck and build.
- `frontend/Dockerfile`, `.dockerignore` files, and a `web` service in `docker-compose.yml` (`--profile app`) for the full local stack. `.gitignore` now also covers `.env*.local` and `tsconfig.tsbuildinfo`.
- Not verified here: Docker image builds (no Docker in this environment) and the GitHub Actions run.

## Post-milestone: account deletion (done)
- `AccountService` + `AccountController`: `DELETE /api/account?confirm=true` with `{"password": "..."}`.
  - Re-authentication with the current password (403 `invalid_password` on mismatch; the session stays valid and nothing is deleted). Without `confirm=true` it returns 422 `confirmation_required`. CSRF and session required like every other write.
  - Deletes queued `metadata_jobs` first (so no worker can claim a job for a row about to disappear), then the user's `spring_session` rows (Spring Session JDBC has no FK to `users`), then the user; bookmarks, tags, collections and all membership rows cascade from `users.id`.
  - Returns a `{bookmarks, tags, collections, metadataJobs, sessions}` summary and logs it (ids only, no URLs). The caller is signed out and the CSRF cookie cleared in the same response.
  - No private data is left behind elsewhere: preview images are proxied on demand and never stored, and the optional shared metadata cache was never implemented.
- Frontend: `/settings` is now a client page with an Account section, the existing export links (now via `api.exportUrl`), and a "Delete your account" danger zone — labelled password field, `role="alert"` errors, and the accessible `ConfirmDialog` before the irreversible call; on success it announces and redirects to `/login`.
- Tests: `AccountDeletionIntegrationTest` (3) — full cascade with a second account untouched; wrong password changes nothing; over HTTP: missing confirm → 422, no session → 401, no CSRF → 403, success → 200 and the session no longer authenticates.
- Verification run here: `mvn test` with `TEST_DATABASE_URL` → 161 tests, 0 failures, 1 skipped (the opt-in benchmark); frontend `tsc --noEmit` and `next build` (13 routes) pass.
- Docs updated: `README.md`, `docs/SECURITY.md` (control + test row), `docs/DEPLOYMENT.md`.

## Next steps
1. Click through the UI end-to-end in a browser; add Playwright tests (spec §17 E2E list, including the mobile viewport) and an axe accessibility audit.
2. Before a public release: dependency scanning (`npm audit`, OWASP dependency-check or Dependabot), a nonce-based CSP, and shared rate limits if running multiple API replicas.
3. Later-release features (spec §2): import, browser extension, public collections, and so on.

_Update this file at the end of every milestone._
