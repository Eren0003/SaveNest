# SaveNest benchmark

Source: `backend/src/test/java/com/savenest/benchmark/ApiBenchmarkTest.java` (opt-in, skipped unless `BENCHMARK=true`).

## Method

- Seeds two accounts with `BENCHMARK_ROWS` (default 10,000) fictitious bookmarks each via a single SQL `generate_series` insert, then `ANALYZE`. The second account checks that queries stay owner-scoped.
- Each scenario: 20 warm-up requests, then 100 timed requests, sequential, single client.
- Requests go through the full in-process stack (servlet filters, Spring Security, controllers, JPA, PostgreSQL over TCP localhost) via MockMvc. Network/proxy latency and Next.js are **not** included.
- The test fails if any GET p95 > 500 ms or save p95 > 800 ms (spec §16 targets).

```bash
createdb -U postgres -h localhost savenest_bench        # must be empty and disposable
cd backend
BENCHMARK=true TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/savenest_bench \
  TEST_DATABASE_USERNAME=postgres mvn test -Dtest=ApiBenchmarkTest
```

## Results (Milestone 7 run)

Environment: shared Linux dev container, 8 CPUs, OpenJDK 21.0.7, PostgreSQL 12.22 on the same host, default settings. 2 × 10,000 bookmarks.

| Scenario | p50 | p95 | max | Target p95 |
|---|---|---|---|---|
| `GET /api/bookmarks` 50 newest | 5.0 ms | 7.0 ms | 13.5 ms | 500 ms |
| group + platform filter | 3.8 ms | 5.0 ms | 19.1 ms | 500 ms |
| sort=title | 14.4 ms | 16.8 ms | 20.1 ms | 500 ms |
| search, 1 word | 5.7 ms | 7.0 ms | 17.4 ms | 500 ms |
| search, 2 words | 20.1 ms | 27.6 ms | 29.8 ms | 500 ms |
| search, no match (full scan of the account) | 53.2 ms | 54.8 ms | 70.5 ms | 500 ms |
| `GET /api/groups` counts | 5.2 ms | 6.1 ms | 9.0 ms | 500 ms |
| `POST /api/bookmarks` save | 3.1 ms | 3.7 ms | 11.6 ms | 800 ms |

## Interpretation

- All targets are met with a wide margin at 10,000 bookmarks per account.
- The worst case is a search that matches nothing: `to_tsvector` is computed for every row of the account (~5 µs/row). It scales linearly; around 100,000 bookmarks per account it would approach ~500 ms. If that becomes realistic, add a stored generated `tsvector` column with a GIN index (planned fix, not needed now).
- Numbers are from one unloaded machine with a single client; they are not a concurrency or production-capacity test.
