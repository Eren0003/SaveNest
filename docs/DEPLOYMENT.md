# SaveNest deployment guide

## Topology

```text
 browser ──TLS──> reverse proxy (nginx / Caddy / cloud LB)
                     │  /        -> web     (Next.js, port 3000)
                     │  /api/*   -> web     (Next.js rewrites /api/* to backend; keeps cookies same-origin)
                     ▼
                  web ───────> backend (Spring Boot, port 8080, WORKER_ENABLED=false)
                                   │
 worker (same JAR, WORKER_ENABLED=true, no inbound port) ──┤
                                   ▼
                              PostgreSQL 14+ (data, sessions, metadata job queue)
```

| Component | Artifact | Scaling | Notes |
|---|---|---|---|
| web | `frontend/Dockerfile` | stateless, N replicas | `BACKEND_URL` is baked in at build time (rewrites) |
| backend (API) | `backend/Dockerfile` | N replicas, see rate-limit note | `WORKER_ENABLED=false` |
| worker | same image as backend | 1+ replicas (jobs are claimed with `FOR UPDATE SKIP LOCKED` + leases) | `WORKER_ENABLED=true`, no ingress, **restricted egress** |
| queue | PostgreSQL table `metadata_jobs` | — | no Redis needed |
| database | PostgreSQL (tested on 12 and 16) | managed service recommended | Flyway migrates on backend start |
| object storage | not used | — | preview images are proxied on demand (`/api/bookmarks/{id}/preview`) |

## Required configuration

| Variable | Where | Production value |
|---|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | backend, worker | from secret store; least-privilege role owning the schema |
| `COOKIE_SECURE` | backend | `true` (default). Never `false` behind HTTPS |
| `FORWARD_HEADERS_STRATEGY` | backend | `native` **only** if the backend is reachable solely through your trusted proxy; otherwise `none` (clients could spoof IPs and evade rate limits) |
| `WORKER_ENABLED` | backend `false`, worker `true` | |
| `SESSION_TIMEOUT`, `BCRYPT_STRENGTH`, `RATE_LIMIT_*` | backend | defaults are reasonable |
| `FETCH_*` | worker, backend (preview proxy) | defaults are reasonable |
| `BACKEND_URL` | web build | internal URL of the backend, e.g. `http://backend:8080` |

Keep secrets out of images and source control; inject them at runtime (`.env` is git-ignored, `.env.example` has placeholders only).

## TLS and proxy

- Terminate TLS at the proxy; redirect HTTP to HTTPS; enable HSTS (`Strict-Transport-Security: max-age=31536000; includeSubDomains`).
- Route all traffic for the site to the web service. The backend should not be publicly reachable; only web (and health checks) need to reach it.
- Limit request body size at the proxy too (e.g. `client_max_body_size 64k` in nginx) to match the backend limit.
- The session cookie (`SAVENEST_SESSION`) is `HttpOnly; Secure; SameSite=Lax`; the CSRF cookie (`XSRF-TOKEN`) is readable by the page by design (double-submit).

## Worker network isolation (required)

The fetcher already blocks private, loopback, link-local, metadata and reserved addresses after DNS resolution and on every redirect. As defence in depth, the worker **and** the backend (it serves the preview proxy) must also be restricted at the network level:

- Egress allow: TCP 80/443 to the public internet, plus PostgreSQL and DNS.
- Egress deny: RFC 1918, 100.64.0.0/10, 169.254.0.0/16 (cloud metadata), fc00::/7, fe80::/10, and your VPC/cluster CIDRs.
- On AWS, require IMDSv2 with hop limit 1, or block 169.254.169.254 via security group / NetworkPolicy.
- Kubernetes example (adapt CIDRs):

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: savenest-worker-egress }
spec:
  podSelector: { matchLabels: { app: savenest-worker } }
  policyTypes: [Egress]
  egress:
    - to: [{ podSelector: { matchLabels: { app: postgres } } }]
      ports: [{ port: 5432 }]
    - to: [{ namespaceSelector: {}, podSelector: { matchLabels: { k8s-app: kube-dns } } }]
      ports: [{ port: 53, protocol: UDP }]
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, 169.254.0.0/16, 127.0.0.0/8]
      ports: [{ port: 80 }, { port: 443 }]
```

## Rate limiting with replicas

The limiter is in-memory per instance. With N API replicas the effective limit is up to N times the configured value. Either keep N small, use sticky sessions, or enforce per-IP limits at the proxy (e.g. nginx `limit_req` on `/api/auth/` and `POST /api/bookmarks`).

## Health checks and observability

- Liveness/readiness: `GET /actuator/health` (no details exposed). Only the `health` actuator is enabled.
- Every response has a server-generated `X-Request-Id`, also logged via MDC and returned in error bodies.
- Logs never contain full bookmark URLs (worker logs use bookmark IDs).
- Watch `metadata_jobs`: `SELECT status, count(*) FROM metadata_jobs GROUP BY status;` — growth in `dead` or old `queued` rows means the worker is failing or down.

## Database, migrations and backups

- Flyway runs on backend start (`ddl-auto: validate`; Hibernate never changes the schema). Run a single backend instance first when deploying a release with new migrations, or run `mvn flyway:migrate` / the JAR once as a job.
- Migrations are forward-only and additive so far. Do not edit an applied migration; add a new `V<n>__*.sql`.
- Backups: enable PITR on a managed database, or nightly `pg_dump -Fc` with retention (e.g. 7 daily + 4 weekly) stored off-host and encrypted. Test a restore every release:
  ```bash
  pg_dump -Fc "$DB" > savenest-$(date +%F).dump
  createdb savenest_restore_test && pg_restore -d savenest_restore_test savenest-YYYY-MM-DD.dump
  ```
- Users can also export their own data (`/settings` → JSON/CSV).

## Release and rollback

1. Run `./scripts/release-check.sh` (CI runs the same tests on every push).
2. Back up the database (or confirm a recent PITR point).
3. Build and tag images with the version/commit, e.g. `savenest-backend:0.1.0`, `savenest-web:0.1.0`.
4. Deploy backend + worker (migrations run), verify `/actuator/health`, then deploy web.
5. Smoke test: register/login, save a YouTube URL, check it appears under YouTube, open `/settings` export.

Rollback:
- **No new migration in the release:** redeploy the previous image tags.
- **With a new migration:** previous code usually still works because migrations are additive (new columns/tables are ignored). If not, restore the pre-release backup and redeploy previous tags. Flyway has no automatic undo; write an explicit `V<n+1>` fix-forward migration in preference to restoring when possible.

## Local full stack

```bash
cp .env.example .env    # set passwords
docker compose --profile app up --build
# web: http://localhost:3000   backend: http://localhost:8080 (COOKIE_SECURE=false for plain HTTP)
```

## Known deployment limitations

- Email/password auth only; no OAuth or password reset yet. Account deletion is available
  (`DELETE /api/account?confirm=true`, Settings page): it removes all owned rows and the user's sessions.
- In-memory rate limits (see above).
- No object storage; preview images are fetched on demand through the restricted proxy on each view (browser-cached privately).
