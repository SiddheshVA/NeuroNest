package com.neuronest.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestDatabaseIsolationTest {

    private static final String NEON = "jdbc:postgresql://ep-example.aws.neon.tech/neondb?sslmode=require";

    // An environment as the app would see it: the machine's variables, the secrets file and application.properties.
    private static StandardEnvironment realLooking() {
        StandardEnvironment environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "DB_USER", "real-user", "DB_PASSWORD", "real-password", "DATABASE_URL", NEON,
                        "SPRING_DATASOURCE_URL", NEON, "PGPASSWORD", "real-password", "PATH", "/usr/bin")));
        sources.addLast(new MapPropertySource(
                "Config resource 'file [.env.properties]' via location 'optional:file:.env.properties'",
                Map.of("DB_URL", NEON, "JWT_SECRET", "real-secret")));
        sources.addLast(new MapPropertySource("Config resource 'class path resource [application.properties]'", Map.of(
                "spring.datasource.url", NEON,
                "spring.datasource.username", "${DB_USER}",
                "spring.datasource.password", "${DB_PASSWORD}")));
        return environment;
    }

    @Test
    void aTestContextCannotReachOrReadTheRealDatabase() {
        StandardEnvironment environment = realLooking();

        new TestDatabaseIsolation().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(TestDatabaseIsolation.UNREACHABLE_URL);
        assertThat(environment.getProperty("spring.datasource.username")).doesNotContain("real");
        assertThat(environment.getProperty("spring.datasource.password")).doesNotContain("real");
        for (String hidden : new String[]{"DB_USER", "DB_PASSWORD", "DB_URL", "DATABASE_URL", "SPRING_DATASOURCE_URL",
                "PGPASSWORD", "JWT_SECRET"}) {
            assertThat(environment.getProperty(hidden)).as(hidden).isNull();
        }
        // Everything else from the machine is still there.
        assertThat(environment.getProperty("PATH")).isEqualTo("/usr/bin");
    }

    @Test
    void aTestsOwnDatabaseStillWins() {
        StandardEnvironment environment = realLooking();
        // What @DynamicPropertySource and inline test properties add: above everything else.
        environment.getPropertySources().addFirst(new MapPropertySource("Inlined Test Properties",
                Map.of("spring.datasource.url", "jdbc:postgresql://localhost:5555/postgres")));

        new TestDatabaseIsolation().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5555/postgres");
    }
}
