package com.savenest.support;

import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class: real PostgreSQL with Flyway migrations applied.
 * <ul>
 *   <li>If {@code TEST_DATABASE_URL} is set (with optional {@code TEST_DATABASE_USERNAME}/{@code _PASSWORD}),
 *       that database is used. It must be empty and disposable: Flyway migrates it and tests insert rows.</li>
 *   <li>Otherwise a PostgreSQL container is started via Testcontainers; tests are skipped without Docker.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "savenest.security.bcrypt-strength=4",
        "savenest.rate-limit.auth-per-minute=1000",
        "server.servlet.session.cookie.secure=false"
})
public abstract class IntegrationTest {

    private static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static synchronized void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("TEST_DATABASE_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () -> env("TEST_DATABASE_PASSWORD", ""));
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available and TEST_DATABASE_URL not set; skipping integration test");
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }
}
