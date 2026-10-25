package com.neuronest.backend.pro;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real V18 migration on an embedded PostgreSQL (just enough of the earlier tables): a booking can never
 * cover blocked time and a block can never cover held or confirmed time, even when the block and the booking
 * arrive at the same moment from two connections.
 */
class BlockedSlotsMigrationSqlTest {

    private static final Instant SLOT = Instant.parse("2026-11-02T04:30:00Z"); // 10:00 IST
    private static final Duration STEP = Duration.ofMinutes(75);

    static EmbeddedPostgres postgres;
    static DataSource dataSource;
    static JdbcTemplate jdbc;
    UUID professional;

    @BeforeAll
    static void migrate() throws IOException {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create extension if not exists btree_gist");
        jdbc.execute("create table professionals (id uuid primary key default gen_random_uuid())");
        jdbc.execute("""
                create table booking_requests (id uuid primary key default gen_random_uuid(),
                  professional_id uuid not null references professionals(id), status text not null,
                  preferred_datetime timestamptz, ends_at timestamptz, blocked_until timestamptz)""");
        try (var in = BlockedSlotsMigrationSqlTest.class.getResourceAsStream("/db/migration/V18__professional_blocked_slots.sql")) {
            assertThat(in).as("V18 on the classpath").isNotNull();
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    @BeforeEach
    void professional() {
        jdbc.execute("truncate booking_requests, professional_blocked_slots, professionals cascade");
        professional = jdbc.queryForObject("insert into professionals default values returning id", UUID.class);
    }

    private static final String INSERT_BLOCK =
            "insert into professional_blocked_slots (professional_id, starts_at, blocked_until) values (?, ?, ?)";
    private static final String INSERT_BOOKING = """
            insert into booking_requests (professional_id, status, preferred_datetime, ends_at, blocked_until)
            values (?, ?, ?, ?, ?)""";

    private void block(Instant start) {
        jdbc.update(INSERT_BLOCK, professional, Timestamp.from(start), Timestamp.from(start.plus(STEP)));
    }

    private UUID booking(String status, Instant start, int sessions) {
        return jdbc.queryForObject(INSERT_BOOKING + " returning id", UUID.class, professional, status,
                Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(60L * sessions))),
                Timestamp.from(start.plus(STEP.multipliedBy(sessions))));
    }

    @Test
    void aHeldOrConfirmedBookingCannotCoverABlockedSlot() {
        block(SLOT);
        for (String status : new String[]{"AWAITING_PAYMENT", "CONFIRMED"}) {
            assertThatThrownBy(() -> booking(status, SLOT, 1)).isInstanceOf(DataIntegrityViolationException.class);
            // Two sessions from the slot before run into it as well.
            assertThatThrownBy(() -> booking(status, SLOT.minus(STEP), 2)).isInstanceOf(DataIntegrityViolationException.class);
        }
        // Right next to it is fine, and so is a request that does not hold time.
        booking("CONFIRMED", SLOT.minus(STEP), 1);
        booking("CONFIRMED", SLOT.plus(STEP), 1);
        booking("CANCELLED", SLOT, 1);
        // Unblocked: the time can be booked again.
        jdbc.update("delete from professional_blocked_slots");
        booking("AWAITING_PAYMENT", SLOT, 1);
    }

    @Test
    void aBlockCannotCoverHeldOrConfirmedTimeOrAnotherBlock() {
        UUID held = booking("AWAITING_PAYMENT", SLOT, 2);
        assertThatThrownBy(() -> block(SLOT)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> block(SLOT.plus(STEP))).isInstanceOf(DataIntegrityViolationException.class);
        // Paying for the hold keeps its time and passes the check.
        jdbc.update("update booking_requests set status = 'CONFIRMED' where id = ?", held);
        assertThatThrownBy(() -> block(SLOT.plus(STEP))).isInstanceOf(DataIntegrityViolationException.class);
        // Once released, the time can be blocked; twice is refused.
        jdbc.update("update booking_requests set status = 'EXPIRED' where id = ?", held);
        block(SLOT);
        assertThatThrownBy(() -> block(SLOT)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void whenABlockAndABookingRaceTheSecondOneWaitsAndIsRefused() throws Exception {
        // The block's transaction is open (not committed) when the booking arrives.
        assertSecondIsRefused(c -> insert(c, INSERT_BLOCK, professional, SLOT, SLOT.plus(STEP)),
                c -> insert(c, INSERT_BOOKING, professional, "AWAITING_PAYMENT", SLOT, SLOT.plus(Duration.ofMinutes(60)),
                        SLOT.plus(STEP)));
        assertThat(jdbc.queryForObject("select count(*) from booking_requests", Long.class)).isZero();

        // And the other way round: the booking first, then the block.
        professional();
        assertSecondIsRefused(c -> insert(c, INSERT_BOOKING, professional, "AWAITING_PAYMENT", SLOT,
                        SLOT.plus(Duration.ofMinutes(60)), SLOT.plus(STEP)),
                c -> insert(c, INSERT_BLOCK, professional, SLOT, SLOT.plus(STEP)));
        assertThat(jdbc.queryForObject("select count(*) from professional_blocked_slots", Long.class)).isZero();
    }

    @Test
    void confirmingAHeldBookingNeverWaitsForABlockElsewhereInTheDay() throws Exception {
        UUID held = booking("AWAITING_PAYMENT", SLOT, 1);
        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            // A block later that day holds the professional's row lock until it commits.
            insert(first, INSERT_BLOCK, professional, SLOT.plus(STEP.multipliedBy(2)), SLOT.plus(STEP.multipliedBy(3)));
            CompletableFuture<Integer> confirm = CompletableFuture.supplyAsync(() ->
                    jdbc.update("update booking_requests set status = 'CONFIRMED' where id = ?", held));
            assertThat(confirm.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            first.commit();
        }
    }

    // ---- two connections ----

    interface Statement {
        void run(Connection c) throws SQLException;
    }

    private static void insert(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                statement.setObject(i + 1, v instanceof Instant instant ? Timestamp.from(instant) : v);
            }
            statement.executeUpdate();
        }
    }

    // `first` runs in an open transaction; `second` starts on another connection, must wait for it, and is
    // refused with the exclusion_violation code once `first` commits.
    private void assertSecondIsRefused(Statement first, Statement second) throws Exception {
        try (Connection one = dataSource.getConnection(); Connection two = dataSource.getConnection()) {
            one.setAutoCommit(false);
            first.run(one);
            CompletableFuture<SQLException> other = CompletableFuture.supplyAsync(() -> {
                try {
                    second.run(two);
                    return null;
                } catch (SQLException e) {
                    return e;
                }
            });
            Thread.sleep(500);
            assertThat(other).as("the second one waits for the first").isNotDone();
            one.commit();
            SQLException refused = other.get(10, TimeUnit.SECONDS);
            assertThat((Throwable) refused).as("the second one is refused").isNotNull();
            assertThat(refused.getSQLState()).isEqualTo("23P01");
        }
    }
}
