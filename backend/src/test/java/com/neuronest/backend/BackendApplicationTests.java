package com.neuronest.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application context starts, against its own embedded PostgreSQL (never the real database:
 * see TestDatabaseIsolation) with the schema built from the entities, and with harmless test values for
 * the secrets the app needs. No knowledge-base ingestion, memory or background jobs.
 */
@SpringBootTest(properties = {
        "kb.enabled=false",
        "memory.enabled=false",
        "jobs.enabled=false",
        "bookings.hold-release.enabled=false",
        "notifications.reminder.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "app.jwt.secret=context-test-user-cookie-secret-0123456789abcdef",
        "GEMINI_API_KEY=context-test-not-a-key",
        "JWT_SECRET=context-test-user-cookie-secret-0123456789abcdef"})
class BackendApplicationTests {

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the embedded PostgreSQL for the context test.", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    DataSource dataSource;
    @Autowired
    ConfigurableEnvironment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void theContextUsesTheEmbeddedDatabaseAndCannotSeeTheRealCredentials() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql://localhost:")
                    .doesNotContain("neon.tech");
        }
        assertThat(environment.getProperty("spring.datasource.url")).doesNotContain("neon.tech");
        // The machine's DB_USER / DB_PASSWORD and the local secrets file are not visible to tests.
        assertThat(environment.getProperty("DB_USER")).isNull();
        assertThat(environment.getProperty("DB_PASSWORD")).isNull();
        assertThat(environment.getProperty("DB_URL")).isNull();
        assertThat(environment.getPropertySources().stream().map(s -> s.getName()))
                .noneMatch(name -> name.contains(TestDatabaseIsolation.SECRETS_FILE));
    }
}
