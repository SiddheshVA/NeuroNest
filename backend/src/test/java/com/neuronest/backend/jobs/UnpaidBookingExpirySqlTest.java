package com.neuronest.backend.jobs;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The unpaid-booking expiry SQL against a real PostgreSQL (embedded, no Docker), with the V10 columns and
// constraint: the grace period, paid bookings, and a booking whose verify holds the row lock.
class UnpaidBookingExpirySqlTest {

    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final Duration GRACE = Duration.ofMinutes(15);
    private static final Instant ACCEPTED_AT = NOW.minus(Duration.ofHours(30));

    static EmbeddedPostgres postgres;
    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static JdbcBookingJobStore store;

    @BeforeAll
    static void createSchema() throws IOException {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcBookingJobStore(new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("""
                create table booking_requests (
                  id uuid primary key default gen_random_uuid(),
                  status text not null check (status in
                    ('PENDING','AWAITING_PAYMENT','CONFIRMED','DECLINED','ALTERNATE_PROPOSED','CANCELLED','EXPIRED')),
                  responded_at timestamptz, responded_by text check (responded_by in ('PROFESSIONAL','ADMIN','USER')),
                  expires_at timestamptz, payment_due_at timestamptz, payment_expired_at timestamptz,
                  expiry_notified_at timestamptz, updated_at timestamptz not null default now())""");
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("truncate booking_requests");
    }

    private UUID booking(String status, Duration dueIn) {
        return jdbc.queryForObject("""
                insert into booking_requests (status, responded_by, responded_at, expires_at, payment_due_at)
                values (?, 'PROFESSIONAL', ?, ?, ?) returning id""", UUID.class,
                status, Timestamp.from(ACCEPTED_AT), Timestamp.from(NOW.plus(Duration.ofHours(30))),
                Timestamp.from(NOW.plus(dueIn)));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from booking_requests where id = ?", String.class, id);
    }

    @Test
    void onlyUnpaidBookingsPastTheGracePeriodExpireAndOnlyOnce() {
        UUID late = booking("AWAITING_PAYMENT", Duration.ofMinutes(-16));
        UUID inGrace = booking("AWAITING_PAYMENT", Duration.ofMinutes(-14));
        UUID notDue = booking("AWAITING_PAYMENT", Duration.ofHours(3));
        UUID paid = booking("CONFIRMED", Duration.ofHours(-3));
        UUID pending = booking("PENDING", Duration.ofHours(-3));

        assertThat(store.expireUnpaidBookings(NOW, NOW.minus(GRACE), 100)).containsExactly(late);
        assertThat(store.expireUnpaidBookings(NOW, NOW.minus(GRACE), 100)).isEmpty();

        assertThat(status(late)).isEqualTo("EXPIRED");
        // The professional's acceptance is kept (for the admin's response metrics); the expiry has its own column.
        assertThat(jdbc.queryForObject("select responded_by = 'PROFESSIONAL' and responded_at = ? "
                        + "and payment_expired_at = ? from booking_requests where id = ?", Boolean.class,
                Timestamp.from(ACCEPTED_AT), Timestamp.from(NOW), late)).isTrue();
        assertThat(status(inGrace)).isEqualTo("AWAITING_PAYMENT");
        assertThat(status(notDue)).isEqualTo("AWAITING_PAYMENT");
        assertThat(status(paid)).isEqualTo("CONFIRMED");
        assertThat(status(pending)).isEqualTo("PENDING");
    }

    @Test
    void theUsersEmailRetryFindsAnUnpaidExpiryButNotAnAnsweredOne() {
        UUID unpaid = booking("AWAITING_PAYMENT", Duration.ofHours(-1));
        store.expireUnpaidBookings(NOW, NOW.minus(GRACE), 100);
        // An answered request that someone else closed, which the jobs never email.
        UUID declined = booking("DECLINED", Duration.ofHours(-1));
        jdbc.update("update booking_requests set status = 'EXPIRED', payment_due_at = null where id = ?", declined);

        Instant later = NOW.plus(Duration.ofMinutes(5));
        assertThat(store.claimExpiryEmailRetries(later, later.minus(Duration.ofHours(24)), later.minus(Duration.ofMinutes(2)),
                100)).containsExactly(unpaid);
        assertThat(store.claimExpiryEmailRetries(later, later.minus(Duration.ofHours(24)), later.minus(Duration.ofMinutes(2)),
                100)).isEmpty();
    }

    @Test
    void aBookingBeingVerifiedIsSkippedAndOnceConfirmedIsNeverExpired() throws Exception {
        UUID beingPaid = booking("AWAITING_PAYMENT", Duration.ofHours(-1));

        // A verify in progress: it holds the row lock (as findByIdForUpdate does) and then confirms.
        try (Connection verify = dataSource.getConnection()) {
            verify.setAutoCommit(false);
            try (PreparedStatement lock = verify.prepareStatement("select id from booking_requests where id = ? for update")) {
                lock.setObject(1, beingPaid);
                lock.executeQuery();
            }
            assertThat(store.expireUnpaidBookings(NOW, NOW.minus(GRACE), 100)).isEmpty();
            try (PreparedStatement confirm = verify.prepareStatement(
                    "update booking_requests set status = 'CONFIRMED' where id = ?")) {
                confirm.setObject(1, beingPaid);
                confirm.executeUpdate();
            }
            verify.commit();
        }

        assertThat(store.expireUnpaidBookings(NOW, NOW.minus(GRACE), 100)).isEmpty();
        assertThat(status(beingPaid)).isEqualTo("CONFIRMED");
    }
}
