package com.neuronest.backend.wellness;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real V21 migration on an embedded PostgreSQL (after a minimal users table, which predates the
 * migrations) and checks its constraints, the history index and the account-deletion cascade. The rest of the
 * suite builds its schema from the entities, so this is what covers the SQL file itself.
 */
class WellnessMigrationSqlTest {

    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws IOException {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        jdbc.execute("create table users (id uuid primary key default gen_random_uuid(), email varchar not null unique)");
        try (var in = WellnessMigrationSqlTest.class.getResourceAsStream("/db/migration/V21__wellness_checkin.sql")) {
            assertThat(in).as("V21 on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    private static UUID user(String email) {
        return jdbc.queryForObject("insert into users (email) values (?) returning id", UUID.class, email);
    }

    private static void checkin(UUID userId, String segment, String band, double probability, String confidence,
                                String sleepClass, boolean inRange) {
        jdbc.update("""
                insert into wellness_checkin (user_id, segment, mood_band, mood_probability, mood_confidence,
                  sleep_class, sleep_in_range, inputs, result, model_versions)
                values (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)""",
                userId, segment, band, probability, confidence, sleepClass, inRange);
    }

    private static int count(UUID userId) {
        return jdbc.queryForObject("select count(*) from wellness_checkin where user_id = ?", Integer.class, userId);
    }

    @Test
    void aValidCheckinIsStoredWithDefaults() {
        UUID u = user("valid@example.com");
        checkin(u, "student", "Moderate", 0.42, "medium", "insomnia_like", true);
        checkin(u, "both", "Low", 0.0, "low", null, false);
        assertThat(count(u)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from wellness_checkin where user_id = ? and id is not null"
                + " and created_at is not null", Integer.class, u)).isEqualTo(2);
    }

    @Test
    void valuesOutsideTheContractAreRejected() {
        UUID u = user("constraints@example.com");
        assertThatThrownBy(() -> checkin(u, "retired", "Low", 0.1, "low", null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> checkin(u, "student", "Severe", 0.1, "low", null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> checkin(u, "student", "Low", 1.5, "low", null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> checkin(u, "student", "Low", 0.1, "certain", null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> checkin(u, "student", "Low", 0.1, "low", "Insomnia", true))
                .as("raw dataset label names are not codes").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> checkin(u, "student", "Low", 0.1, "low", "apnea_like", false))
                .as("no sleep class when sleep was out of range").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> checkin(UUID.randomUUID(), "student", "Low", 0.1, "low", null, false))
                .as("the user must exist").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(u)).isZero();
    }

    @Test
    void deletingAUserDeletesOnlyTheirCheckins() {
        UUID a = user("a@example.com");
        UUID b = user("b@example.com");
        checkin(a, "student", "High", 0.9, "high", "none", true);
        checkin(a, "student", "Low", 0.1, "high", null, false);
        checkin(b, "working", "Moderate", 0.2, "medium", "apnea_like", true);

        jdbc.update("delete from users where id = ?", a);

        assertThat(count(a)).isZero();
        assertThat(count(b)).isEqualTo(1);
    }

    // Production runs spring.jpa.hibernate.ddl-auto=validate: the entity must match the migrated table, or the
    // app would refuse to start once V21 is applied.
    @Test
    void theEntityValidatesAgainstTheMigratedTable() {
        var settings = new java.util.HashMap<String, Object>();
        settings.put("hibernate.connection.url", postgres.getJdbcUrl("postgres", "postgres"));
        settings.put("hibernate.connection.username", "postgres");
        settings.put("hibernate.connection.password", "postgres");
        settings.put("hibernate.hbm2ddl.auto", "validate");
        var registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder().applySettings(settings).build();
        try (var factory = new org.hibernate.boot.MetadataSources(registry)
                .addAnnotatedClass(WellnessCheckin.class).buildMetadata().buildSessionFactory()) {
            assertThat(factory.isOpen()).isTrue();
        } finally {
            org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void historyIndexIsOnUserAndNewestFirst() {
        String def = jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'wellness_checkin_user_created_idx'",
                String.class);
        assertThat(def).contains("(user_id, created_at DESC)");
    }
}
