package com.neuronest.backend;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps every test's Spring context away from the real database (registered for tests only, in
 * src/test/resources/META-INF/spring.factories). It runs after the configuration files are loaded and:
 * <ul>
 *   <li>removes the local secrets file (.env.properties) from the environment;</li>
 *   <li>hides the database variables of the machine running the tests (DB_*, SPRING_DATASOURCE_*,
 *       DATABASE_URL, PG*), so DB_USER and DB_PASSWORD can never be read;</li>
 *   <li>points spring.datasource.* at an address that cannot be reached, above application.properties
 *       (which names the real Neon database). A test that needs a database sets its own embedded one
 *       through @DynamicPropertySource or inline test properties, which come first; one that forgets
 *       fails to connect instead of reaching the real database.</li>
 * </ul>
 */
public class TestDatabaseIsolation implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "testDatabaseIsolation";
    static final String UNREACHABLE_URL = "jdbc:postgresql://127.0.0.1:1/tests-use-an-embedded-postgres";
    static final String SECRETS_FILE = ".env.properties";

    private static final List<String> HIDDEN_PREFIXES = List.of("DB_", "SPRING_DATASOURCE", "SPRING_FLYWAY", "PG");
    private static final List<String> HIDDEN_NAMES = List.of("DATABASE_URL");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        for (PropertySource<?> source : List.copyOf(sources.stream().toList())) {
            if (source.getName().contains(SECRETS_FILE)) sources.remove(source.getName());
        }

        String envName = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        PropertySource<?> env = sources.get(envName);
        if (env != null && env.getSource() instanceof Map<?, ?> values) {
            Map<String, Object> kept = new HashMap<>();
            values.forEach((key, value) -> {
                if (!hidden(String.valueOf(key))) kept.put(String.valueOf(key), value);
            });
            sources.replace(envName, new SystemEnvironmentPropertySource(envName, kept));
        }

        Map<String, Object> safe = Map.of(
                "spring.datasource.url", UNREACHABLE_URL,
                "spring.datasource.username", "tests-have-no-database-account",
                "spring.datasource.password", "tests-have-no-database-password");
        MapPropertySource isolation = new MapPropertySource(SOURCE_NAME, safe);
        if (sources.contains(envName)) {
            sources.addAfter(envName, isolation);
        } else {
            sources.addLast(isolation);
        }
    }

    static boolean hidden(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return HIDDEN_NAMES.contains(upper) || HIDDEN_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    // After the configuration files (ConfigDataEnvironmentPostProcessor) are loaded.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
