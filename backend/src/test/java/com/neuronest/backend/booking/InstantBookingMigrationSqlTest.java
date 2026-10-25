package com.neuronest.backend.booking;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real V15 and V16 migrations on an embedded PostgreSQL, over rows made the old way (just enough of
 * the earlier tables), and checks what they do to them and the no-overlap constraint (on blocked_until since
 * V16). The rest of the suite builds its schema from the entities, so this is what covers the SQL files.
 */
class InstantBookingMigrationSqlTest {

    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.now();
    static UUID professional;
    static UUID oldHold, recentHold, pending, confirmedA, confirmedOverlapping;

    @BeforeAll
    static void migrate() throws IOException {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        jdbc.execute("""
                create table professionals (id uuid primary key default gen_random_uuid(),
                  session_minutes integer, buffer_minutes integer not null default 0)""");
        jdbc.execute("""
                create table booking_requests (id uuid primary key default gen_random_uuid(),
                  professional_id uuid not null references professionals(id), status text not null,
                  preferred_datetime timestamptz, proposed_datetime timestamptz, payment_due_at timestamptz,
                  payment_expired_at timestamptz, expiry_notified_at timestamptz, responded_at timestamptz,
                  responded_by text, created_at timestamptz not null default now(),
                  updated_at timestamptz not null default now())""");
        professional = jdbc.queryForObject(
                "insert into professionals (session_minutes, buffer_minutes) values (60, 0) returning id", UUID.class);
        Instant day = NOW.plus(Duration.ofDays(3));
        // Agreed 30 minutes ago with the old 24-hour window: older than 10 minutes, so released.
        oldHold = row("AWAITING_PAYMENT", day, NOW.minus(Duration.ofMinutes(30)), NOW.plus(Duration.ofHours(23)));
        // Agreed 3 minutes ago: kept, with at most 10 minutes from then.
        recentHold = row("AWAITING_PAYMENT", day.plus(Duration.ofHours(2)), NOW.minus(Duration.ofMinutes(3)),
                NOW.plus(Duration.ofHours(24)));
        pending = row("PENDING", day.plus(Duration.ofHours(4)), null, null);
        confirmedA = row("CONFIRMED", day.plus(Duration.ofHours(6)), NOW.minus(Duration.ofDays(1)), null);
        // Starts 30 minutes into confirmedA (possible before, if the session length changed): left uncovered.
        confirmedOverlapping = row("CONFIRMED", day.plus(Duration.ofHours(6)).plus(Duration.ofMinutes(30)),
                NOW.minus(Duration.ofHours(1)), null);
        run("V15__instant_booking_hold.sql");
        run("V16__session_grid_blocked_until.sql");
    }

    private static void run(String migration) throws IOException {
        try (var in = InstantBookingMigrationSqlTest.class.getResourceAsStream("/db/migration/" + migration)) {
            assertThat(in).as(migration + " on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // A booking made the new way: a meeting of `sessions` x 60 minutes, blocked for `sessions` x 75.
    private static void insert(String status, Instant start, int sessions) {
        jdbc.update("""
                insert into booking_requests (professional_id, status, preferred_datetime, ends_at, blocked_until,
                  session_count) values (?, ?, ?, ?, ?, ?)""", professional, status, Timestamp.from(start),
                Timestamp.from(start.plus(Duration.ofMinutes(60L * sessions))),
                Timestamp.from(start.plus(Duration.ofMinutes(75L * sessions))), sessions);
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    private static UUID row(String status, Instant at, Instant respondedAt, Instant dueAt) {
        return jdbc.queryForObject("""
                insert into booking_requests (professional_id, status, preferred_datetime, responded_at, payment_due_at,
                  created_at) values (?, ?, ?, ?, ?, ?) returning id""", UUID.class, professional, status,
                Timestamp.from(at), respondedAt == null ? null : Timestamp.from(respondedAt),
                dueAt == null ? null : Timestamp.from(dueAt),
                Timestamp.from(respondedAt == null ? NOW.minus(Duration.ofHours(1)) : respondedAt));
    }

    private static Map<String, Object> get(UUID id) {
        return jdbc.queryForMap("select * from booking_requests where id = ?", id);
    }

    @Test
    void anOldHoldIsReleasedQuietlyAndARecentOneKeepsAtMostTenMinutes() {
        Map<String, Object> old = get(oldHold);
        assertThat(old.get("status")).isEqualTo("EXPIRED");
        assertThat(old.get("payment_expired_at")).isNotNull();
        assertThat(old.get("expiry_notified_at")).as("no expiry email afterwards").isNotNull();

        Map<String, Object> recent = get(recentHold);
        assertThat(recent.get("status")).isEqualTo("AWAITING_PAYMENT");
        Instant due = ((Timestamp) recent.get("payment_due_at")).toInstant();
        assertThat(due).isBetween(NOW.plus(Duration.ofMinutes(6)), NOW.plus(Duration.ofMinutes(8)));
    }

    @Test
    void requestsWaitingForApprovalAreClosedQuietly() {
        Map<String, Object> row = get(pending);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(row.get("expiry_notified_at")).isNotNull();
    }

    @Test
    void upcomingBookingsGetAnEndTimeUnlessTheyAlreadyOverlap() {
        assertThat(get(confirmedA).get("ends_at")).isNotNull();
        assertThat(get(confirmedA).get("session_count")).isEqualTo(1);
        assertThat(get(confirmedOverlapping).get("ends_at")).as("skipped, not failing the migration").isNull();
        assertThat(get(recentHold).get("ends_at")).isNotNull();
    }

    @Test
    void oldBookingsKeepTheirTimesAndBlockExactlyUntilTheirStoredEnd() {
        for (UUID id : new UUID[]{confirmedA, recentHold}) {
            Map<String, Object> row = get(id);
            assertThat(row.get("blocked_until")).isEqualTo(row.get("ends_at"));
        }
        // A 60-minute session with no buffer: ends_at (and so blocked_until) stays start + 60 minutes.
        Map<String, Object> a = get(confirmedA);
        Instant oldStart = ((Timestamp) a.get("preferred_datetime")).toInstant();
        assertThat(((Timestamp) a.get("ends_at")).toInstant()).isEqualTo(oldStart.plus(Duration.ofMinutes(60)));
        assertThat(get(confirmedOverlapping).get("blocked_until")).isNull();

        // Still guarded: a new booking starting inside the old one is refused, one at its end is allowed.
        assertThatThrownBy(() -> insert("CONFIRMED", oldStart.plus(Duration.ofMinutes(45)), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        insert("AWAITING_PAYMENT", oldStart.plus(Duration.ofMinutes(60)), 1);
    }

    @Test
    void theDatabaseRefusesTwoHeldOrConfirmedBookingsWhoseBlockedTimeOverlaps() {
        Instant start = NOW.plus(Duration.ofDays(10));
        // Two sessions: meeting until start + 120 minutes, blocked until start + 150.
        insert("CONFIRMED", start, 2);

        // Starting inside the meeting, or inside the breaks blocked after it: refused, held or confirmed.
        for (String status : new String[]{"AWAITING_PAYMENT", "CONFIRMED"}) {
            for (long minutes : new long[]{75, 120}) {
                assertThatThrownBy(() -> insert(status, start.plus(Duration.ofMinutes(minutes)), 1))
                        .as(status + " at +" + minutes).isInstanceOf(DataIntegrityViolationException.class);
            }
        }
        // Starting exactly at blocked_until: allowed. A cancelled or expired row never blocks.
        insert("AWAITING_PAYMENT", start.plus(Duration.ofMinutes(150)), 1);
        // blocked_until can never be before the meeting ends.
        assertThatThrownBy(() -> jdbc.update("""
                insert into booking_requests (professional_id, status, preferred_datetime, ends_at, blocked_until)
                values (?, 'CANCELLED', ?, ?, ?)""", professional, Timestamp.from(start),
                Timestamp.from(start.plus(Duration.ofHours(1))), Timestamp.from(start.plus(Duration.ofMinutes(30)))))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                insert into booking_requests (professional_id, status, preferred_datetime, ends_at)
                values (?, 'CANCELLED', ?, ?)""", professional, Timestamp.from(start),
                Timestamp.from(start.plus(Duration.ofHours(1))));
        // At most four sessions.
        assertThatThrownBy(() -> jdbc.update("""
                insert into booking_requests (professional_id, status, preferred_datetime, ends_at, session_count)
                values (?, 'CANCELLED', ?, ?, 5)""", professional, Timestamp.from(start),
                Timestamp.from(start.plus(Duration.ofHours(5)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aWebhookEventIdIsStoredOnlyOnce() {
        String sql = "insert into razorpay_webhook_events (event_id, event_type) values ('evt_1', 'payment.captured') "
                + "on conflict (event_id) do nothing";
        assertThat(jdbc.update(sql)).isEqualTo(1);
        assertThat(jdbc.update(sql)).isZero();
    }
}
