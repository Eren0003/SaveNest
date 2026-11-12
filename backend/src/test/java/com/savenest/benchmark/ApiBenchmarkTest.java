package com.savenest.benchmark;

import com.savenest.auth.AppUserPrincipal;
import com.savenest.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Repeatable API benchmark (spec section 16). Opt-in: runs only with {@code BENCHMARK=true}.
 * <p>
 * Seeds one account with {@code BENCHMARK_ROWS} (default 10,000) bookmarks plus a noise account of the same
 * size, then times requests through the full Spring MVC + security + JPA stack (MockMvc, in-process, no network).
 * Prints p50/p95/max and fails if a p95 exceeds the spec target, so regressions are visible.
 * <pre>
 * BENCHMARK=true TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/savenest_bench TEST_DATABASE_USERNAME=postgres \
 *   mvn test -Dtest=ApiBenchmarkTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "BENCHMARK", matches = "true")
@TestPropertySource(properties = {"savenest.worker.enabled=false", "savenest.rate-limit.bookmark-create-per-minute=100000"})
class ApiBenchmarkTest extends IntegrationTest {

    private static final int WARMUP = 20;
    private static final int RUNS = 100;
    private static final String[] WORDS = {"guitar", "headphones", "recipe", "kotlin", "travel", "anime", "camera",
            "postgres", "review", "trailer", "budget", "laptop", "garden", "history", "physics", "design"};

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID seed(int rows) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'B', 'x')",
                id, "bench-" + id + "@example.test", "bench-" + id + "@example.test");
        String words = "ARRAY['" + String.join("','", WORDS) + "']";
        jdbc.update("""
                INSERT INTO bookmarks (id, owner_id, original_url, normalized_url, dedupe_key, host, title, description,
                    detected_group, platform, content_type, class_confidence, class_source, class_rule_id,
                    class_rule_version, class_reason, is_favorite, lifecycle_state, metadata_status, created_at, updated_at)
                SELECT md5(random()::text || clock_timestamp()::text || g)::uuid, ?, 'https://example.org/item/' || g, 'https://example.org/item/' || g,
                    'hash:' || md5(?::text || g), 'example.org',
                    'Bookmark ' || g || ' ' || (%1$s)[1 + g %% 16] || ' ' || (%1$s)[1 + (g / 16) %% 16],
                    'Fictitious benchmark row about ' || (%1$s)[1 + (g / 7) %% 16],
                    (ARRAY['youtube','products','articles','websites','unsorted'])[1 + g %% 5],
                    (ARRAY['youtube','amazon','generic','flipkart','generic'])[1 + g %% 5],
                    'unknown', 'low', 'fallback', 'bench', 1, 'benchmark seed',
                    g %% 10 = 0, CASE WHEN g %% 20 = 0 THEN 'archived' ELSE 'active' END, 'ready',
                    now() - (g || ' minutes')::interval, now()
                FROM generate_series(1, ?) g
                """.formatted(words), id, id.toString(), rows);
        return id;
    }

    private record Stats(String name, double p50, double p95, double max) {
        @Override
        public String toString() {
            return "%-34s p50=%7.1f ms  p95=%7.1f ms  max=%7.1f ms".formatted(name, p50, p95, max);
        }
    }

    private Stats time(String name, IntFunction<RequestBuilder> request) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            mvc.perform(request.apply(-1 - i));
        }
        double[] ms = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t = System.nanoTime();
            int status = mvc.perform(request.apply(i)).andReturn().getResponse().getStatus();
            ms[i] = (System.nanoTime() - t) / 1e6;
            if (status >= 400) {
                throw new AssertionError(name + " returned " + status);
            }
        }
        Arrays.sort(ms);
        return new Stats(name, ms[RUNS / 2], ms[(int) Math.ceil(RUNS * 0.95) - 1], ms[RUNS - 1]);
    }

    @Test
    void referenceLoad() throws Exception {
        int rows = Integer.parseInt(System.getenv().getOrDefault("BENCHMARK_ROWS", "10000"));
        long t = System.nanoTime();
        UUID owner = seed(rows);
        seed(rows); // second account: queries must stay owner-scoped and not scan it
        jdbc.execute("ANALYZE bookmarks");
        System.out.printf("%nSeeded 2 x %d bookmarks in %.1f s%n", rows, (System.nanoTime() - t) / 1e9);

        RequestPostProcessor as = user(new AppUserPrincipal(owner, "bench@example.test", null));
        List<Stats> results = new ArrayList<>();
        results.add(time("GET /api/bookmarks (50, newest)", i -> get("/api/bookmarks?limit=50").with(as)));
        results.add(time("GET /api/bookmarks group+platform", i ->
                get("/api/bookmarks?limit=50&group=products&platform=amazon").with(as)));
        results.add(time("GET /api/bookmarks sort=title", i -> get("/api/bookmarks?limit=50&sort=title").with(as)));
        results.add(time("GET /api/bookmarks?q=<1 word>", i ->
                get("/api/bookmarks?limit=50&q=" + WORDS[Math.floorMod(i, 16)]).with(as)));
        results.add(time("GET /api/bookmarks?q=<2 words>", i ->
                get("/api/bookmarks?limit=50&q=" + WORDS[Math.floorMod(i, 16)] + "+" + WORDS[Math.floorMod(i + 3, 16)]).with(as)));
        results.add(time("GET /api/bookmarks?q=<no match>", i -> get("/api/bookmarks?limit=50&q=zzzznomatch").with(as)));
        results.add(time("GET /api/groups (counts)", i -> get("/api/groups").with(as)));
        results.add(time("POST /api/bookmarks (save)", i ->
                post("/api/bookmarks").with(as).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://bench.example.org/new/" + UUID.randomUUID() + "\"}")));

        System.out.printf("Rows per account: %d, warmup %d, runs %d, JVM %s, %d CPUs, PostgreSQL %s%n", rows, WARMUP, RUNS,
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                jdbc.queryForObject("SHOW server_version", String.class));
        results.forEach(System.out::println);

        List<String> failures = new ArrayList<>();
        for (Stats s : results) {
            double target = s.name().startsWith("POST") ? 800 : 500;
            if (s.p95() > target) {
                failures.add(s.name() + " p95 " + s.p95() + " ms > " + target + " ms");
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("; ", failures));
        }
    }
}
