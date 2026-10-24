package com.neuronest.backend.review;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real V14 migration on an embedded PostgreSQL (after just enough of the earlier tables) and checks
 * its constraints and defaults. The rest of the suite builds its schema from the entities, so this is what
 * covers the SQL file itself.
 */
class ReviewMigrationSqlTest {

    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws IOException {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        jdbc.execute("create table professionals (id uuid primary key default gen_random_uuid(), full_name text not null)");
        jdbc.execute("""
                create table booking_requests (id uuid primary key default gen_random_uuid(),
                  professional_id uuid not null references professionals(id))""");
        try (var in = ReviewMigrationSqlTest.class.getResourceAsStream("/db/migration/V14__professional_reviews.sql")) {
            assertThat(in).as("V14 on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    private static UUID professional() {
        return jdbc.queryForObject("insert into professionals (full_name) values ('P') returning id", UUID.class);
    }

    private static UUID booking(UUID professionalId) {
        return jdbc.queryForObject("insert into booking_requests (professional_id) values (?) returning id",
                UUID.class, professionalId);
    }

    private static void review(UUID bookingId, UUID professionalId, int rating, String status) {
        jdbc.update("insert into professional_reviews (booking_id, professional_id, user_id, rating, status)"
                + " values (?, ?, ?, ?, ?)", bookingId, professionalId, UUID.randomUUID(), rating, status);
    }

    @Test
    void existingProfessionalsStartUnratedWithACountOfZero() {
        UUID p = professional();
        Map<String, Object> row = jdbc.queryForMap("select rating_avg, rating_count from professionals where id = ?", p);
        assertThat(row.get("rating_avg")).isNull();
        assertThat(row.get("rating_count")).isEqualTo(0);
    }

    @Test
    void ratingStatusAndOneReviewPerBookingAreEnforced() {
        UUID p = professional();
        UUID b = booking(p);
        assertThatThrownBy(() -> review(b, p, 0, "VISIBLE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> review(b, p, 6, "VISIBLE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> review(b, p, 3, "DELETED")).isInstanceOf(DataIntegrityViolationException.class);
        review(b, p, 3, "VISIBLE");
        assertThatThrownBy(() -> review(b, p, 4, "VISIBLE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> review(UUID.randomUUID(), p, 4, "VISIBLE"))
                .as("the booking must exist").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into professional_reviews (booking_id, professional_id, user_id,"
                + " rating, comment) values (?, ?, ?, 4, ?)", booking(p), p, UUID.randomUUID(), "x".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theRatingRefreshCountsVisibleReviewsOnly() {
        UUID p = professional();
        review(booking(p), p, 5, "VISIBLE");
        review(booking(p), p, 4, "VISIBLE");
        review(booking(p), p, 1, "HIDDEN");
        // The same statement as ProfessionalReviewRepository.refreshRating.
        jdbc.update("""
                UPDATE professionals SET
                  rating_avg = (SELECT round(avg(r.rating)::numeric, 2) FROM professional_reviews r
                                WHERE r.professional_id = ? AND r.status = 'VISIBLE'),
                  rating_count = (SELECT count(*) FROM professional_reviews r
                                  WHERE r.professional_id = ? AND r.status = 'VISIBLE')
                WHERE id = ?""", p, p, p);
        Map<String, Object> row = jdbc.queryForMap("select rating_avg, rating_count from professionals where id = ?", p);
        assertThat(row.get("rating_avg").toString()).isEqualTo("4.50");
        assertThat(row.get("rating_count")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from professional_reviews where rating = 1 and professional_id = ?",
                String.class, p)).isEqualTo("HIDDEN");
    }
}
