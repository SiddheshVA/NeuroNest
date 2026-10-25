package com.neuronest.backend.jobs;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The session-reminder claim against a real PostgreSQL (embedded, no Docker), after the real V17 migration:
// which confirmed sessions are due, the "booked less than 10 minutes before" rule, and once only.
class SessionReminderSqlTest {

    private static final Instant NOW = Instant.parse("2026-10-29T14:05:00Z");
    private static final long LEAD = 10;

    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static JdbcBookingJobStore store;

    @BeforeAll
    static void createSchema() throws IOException {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new JdbcBookingJobStore(new NamedParameterJdbcTemplate(postgres.getPostgresDatabase()));
        jdbc.execute("""
                create table booking_requests (
                  id uuid primary key default gen_random_uuid(), status text not null,
                  preferred_datetime timestamptz, created_at timestamptz not null default now(),
                  updated_at timestamptz not null default now())""");
        try (var in = SessionReminderSqlTest.class.getResourceAsStream("/db/migration/V17__session_notifications.sql")) {
            assertThat(in).as("V17 on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    private UUID booking(String status, Duration startsIn, Duration bookedAgo) {
        return jdbc.queryForObject("""
                insert into booking_requests (status, preferred_datetime, created_at) values (?, ?, ?) returning id""",
                UUID.class, status, Timestamp.from(NOW.plus(startsIn)), Timestamp.from(NOW.minus(bookedAgo)));
    }

    @Test
    void confirmedSessionsAboutTenMinutesAwayAreClaimedOnce() {
        Duration days = Duration.ofDays(2);
        UUID due = booking("CONFIRMED", Duration.ofMinutes(10), days);
        UUID slightlyLate = booking("CONFIRMED", Duration.ofMinutes(8), days);
        UUID tooEarly = booking("CONFIRMED", Duration.ofMinutes(11), days);
        UUID missed = booking("CONFIRMED", Duration.ofMinutes(5), days);
        UUID held = booking("AWAITING_PAYMENT", Duration.ofMinutes(10), days);
        // Booked 9.5 minutes before it starts: no reminder.
        UUID bookedLate = booking("CONFIRMED", Duration.ofMinutes(9), Duration.ofSeconds(30));

        Instant to = NOW.plus(Duration.ofMinutes(LEAD));
        var claimed = store.claimSessionReminders(NOW, to.minus(SessionReminderJob.LATEST), to, LEAD, 50);

        assertThat(claimed).containsExactlyInAnyOrder(due, slightlyLate);
        assertThat(jdbc.queryForObject("select count(*) from booking_requests where session_reminder_sent", Long.class))
                .isEqualTo(2);
        for (UUID notClaimed : new UUID[]{tooEarly, missed, held, bookedLate}) {
            assertThat(jdbc.queryForObject("select session_reminder_sent from booking_requests where id = ?",
                    Boolean.class, notClaimed)).isFalse();
        }
        // The next minute's run sends nothing again.
        assertThat(store.claimSessionReminders(NOW, to.minus(SessionReminderJob.LATEST), to, LEAD, 50)).isEmpty();
        // New bookings default to no WhatsApp consent.
        assertThat(jdbc.queryForObject("select whatsapp_opt_in from booking_requests where id = ?", Boolean.class, due))
                .isFalse();
    }
}
