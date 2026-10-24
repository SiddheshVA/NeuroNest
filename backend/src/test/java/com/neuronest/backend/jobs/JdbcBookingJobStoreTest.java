package com.neuronest.backend.jobs;

import com.neuronest.backend.professional.PublicProfessionalRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The jobs' SQL against a real PostgreSQL, with the columns and constraints the claims rely on. Proves
// that the claims are atomic (two transactions at once never get the same row). Skipped when Docker is
// not available on the machine.
@Testcontainers(disabledWithoutDocker = true)
class JdbcBookingJobStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static JdbcBookingJobStore store;

    @BeforeAll
    static void createSchema() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcBookingJobStore(new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("""
                create table professionals (
                  id uuid primary key default gen_random_uuid(), full_name text not null,
                  credential_type text not null default 'PSYCHOLOGIST',
                  verified boolean not null default false, active boolean not null default false,
                  is_sample_data boolean not null default false, registration_expiry date,
                  verification_status text not null default 'DRAFT'
                    check (verification_status in ('DRAFT','SUBMITTED','VERIFIED','NEEDS_INFO','REJECTED','EXPIRED','SUSPENDED')),
                  verification_notes text, verified_at timestamptz, updated_at timestamptz not null default now())""");
        jdbc.execute("""
                create table professional_accounts (
                  id uuid primary key default gen_random_uuid(), professional_id uuid not null references professionals(id),
                  status text not null default 'ACTIVE')""");
        jdbc.execute("""
                create table verification_log (
                  id uuid primary key default gen_random_uuid(),
                  professional_id uuid not null references professionals(id) on delete cascade,
                  action text not null check (action in
                    ('SUBMITTED','VERIFIED','NEEDS_INFO','REJECTED','EXPIRED','SUSPENDED','REINSTATED')),
                  from_status text, to_status text, actor text not null, source text, note text,
                  created_at timestamptz not null default now())""");
        jdbc.execute("""
                create table booking_requests (
                  id uuid primary key default gen_random_uuid(), professional_id uuid not null references professionals(id),
                  status text not null check (status in
                    ('PENDING','AWAITING_PAYMENT','CONFIRMED','DECLINED','ALTERNATE_PROPOSED','CANCELLED','EXPIRED')),
                  responded_at timestamptz, responded_by text check (responded_by in ('PROFESSIONAL','ADMIN','USER')),
                  expires_at timestamptz, updated_at timestamptz not null default now(),
                  reminder_sent_at timestamptz null, expiry_notified_at timestamptz null,
                  payment_due_at timestamptz null, payment_expired_at timestamptz null)""");
        jdbc.execute("create index idx_booking_requests_status_expires_at on booking_requests (status, expires_at)");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("truncate booking_requests, verification_log, professional_accounts, professionals cascade");
    }

    private UUID professional(String status, LocalDate registrationExpiry, boolean sample) {
        return jdbc.queryForObject("""
                insert into professionals (full_name, verification_status, verified, active, is_sample_data, registration_expiry)
                values ('Dr. Test', ?, ?, true, ?, ?) returning id""", UUID.class,
                status, "VERIFIED".equals(status), sample, registrationExpiry);
    }

    private UUID request(UUID professionalId, String status, Duration expiresIn) {
        return jdbc.queryForObject("insert into booking_requests (professional_id, status, expires_at) values (?, ?, ?) returning id",
                UUID.class, professionalId, status, Timestamp.from(NOW.plus(expiresIn)));
    }

    private String status(UUID bookingId) {
        return jdbc.queryForObject("select status from booking_requests where id = ?", String.class, bookingId);
    }

    @Test
    void overdueOpenRequestsExpireOnceAndNothingElseIsTouched() {
        UUID p = professional("VERIFIED", null, false);
        UUID pending = request(p, "PENDING", Duration.ofMinutes(-1));
        UUID proposed = request(p, "ALTERNATE_PROPOSED", Duration.ofHours(-3));
        UUID future = request(p, "PENDING", Duration.ofHours(2));
        UUID confirmed = request(p, "CONFIRMED", Duration.ofHours(-3));

        assertThat(store.expireOverdueRequests(NOW, 100)).containsExactlyInAnyOrder(pending, proposed);
        assertThat(store.expireOverdueRequests(NOW, 100)).isEmpty();

        assertThat(status(pending)).isEqualTo("EXPIRED");
        assertThat(status(proposed)).isEqualTo("EXPIRED");
        assertThat(status(future)).isEqualTo("PENDING");
        assertThat(status(confirmed)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select responded_by from booking_requests where id = ?", String.class, pending)).isNull();
        assertThat(jdbc.queryForObject("select responded_at from booking_requests where id = ?", Timestamp.class, pending)
                .toInstant()).isEqualTo(NOW);
    }

    @Test
    void aRequestToADisabledAccountExpiresTheSameWay() {
        UUID p = professional("VERIFIED", null, false);
        jdbc.update("insert into professional_accounts (professional_id, status) values (?, 'DISABLED')", p);
        UUID pending = request(p, "PENDING", Duration.ofMinutes(-1));

        assertThat(store.expireOverdueRequests(NOW, 100)).containsExactly(pending);
    }

    @Test
    void atMostTheBatchSizeIsClaimed() {
        UUID p = professional("VERIFIED", null, false);
        for (int i = 0; i < 5; i++) request(p, "PENDING", Duration.ofMinutes(-1 - i));
        assertThat(store.expireOverdueRequests(NOW, 3)).hasSize(3);
        assertThat(store.expireOverdueRequests(NOW, 3)).hasSize(2);
    }

    @Test
    void twoConcurrentTransactionsNeverClaimTheSameRow() throws Exception {
        UUID p = professional("VERIFIED", null, false);
        for (int i = 0; i < 20; i++) request(p, "PENDING", Duration.ofMinutes(-1 - i));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        List<UUID> first = new ArrayList<>();
        List<UUID> second = new ArrayList<>();

        // The first transaction claims 10 rows and stays open while the second one claims.
        Thread a = new Thread(() -> tx.executeWithoutResult(s -> {
            first.addAll(store.expireOverdueRequests(NOW, 10));
            firstClaimed.countDown();
            try {
                secondDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Thread b = new Thread(() -> {
            try {
                firstClaimed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tx.executeWithoutResult(s -> second.addAll(store.expireOverdueRequests(NOW, 100)));
            secondDone.countDown();
        });
        a.start();
        b.start();
        a.join(20_000);
        b.join(20_000);

        Set<UUID> overlap = new HashSet<>(first);
        overlap.retainAll(second);
        assertThat(first).hasSize(10);
        assertThat(second).hasSize(10);
        assertThat(overlap).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from booking_requests where status = 'EXPIRED'", Integer.class))
                .isEqualTo(20);
    }

    @Test
    void theExpiryEmailRetryIsClaimedOnceAndOnlyWithin24Hours() {
        UUID p = professional("VERIFIED", null, false);
        UUID recent = request(p, "PENDING", Duration.ofMinutes(-1));
        store.expireOverdueRequests(NOW, 100);
        UUID old = request(p, "PENDING", Duration.ofDays(-3));
        store.expireOverdueRequests(NOW.minus(Duration.ofDays(2)), 100);
        UUID cancelled = request(p, "CANCELLED", Duration.ofMinutes(-1));
        jdbc.update("update booking_requests set responded_by = 'USER', responded_at = ? where id = ?",
                Timestamp.from(NOW), cancelled);

        Instant later = NOW.plus(Duration.ofMinutes(10));
        assertThat(store.claimExpiryEmailRetries(later, later.minus(Duration.ofHours(24)), later.minus(Duration.ofMinutes(2)), 100))
                .containsExactly(recent);
        assertThat(store.claimExpiryEmailRetries(later, later.minus(Duration.ofHours(24)), later.minus(Duration.ofMinutes(2)), 100))
                .isEmpty();
        assertThat(old).isNotNull();
    }

    @Test
    void remindersAreClaimedOnceAndOnlyWithinTheNext24Hours() {
        UUID p = professional("VERIFIED", null, false);
        UUID soon = request(p, "PENDING", Duration.ofHours(23));
        request(p, "PENDING", Duration.ofHours(25));
        request(p, "ALTERNATE_PROPOSED", Duration.ofHours(5));
        request(p, "PENDING", Duration.ofMinutes(-5));

        assertThat(store.claimReminders(NOW, NOW.plus(Duration.ofHours(24)), 100)).containsExactly(soon);
        assertThat(store.claimReminders(NOW, NOW.plus(Duration.ofHours(24)), 100)).isEmpty();
    }

    @Test
    void anExpiredRegistrationBecomesExpiredOnceWithOneLogRowAndLeavesTheDirectory() {
        UUID expired = professional("VERIFIED", TODAY.minusDays(1), false);
        jdbc.update("insert into professional_accounts (professional_id) values (?)", expired);
        UUID current = professional("VERIFIED", TODAY, false);
        UUID sample = professional("VERIFIED", TODAY.minusDays(30), true);
        assertThat(visibleIds()).contains(current, sample);

        assertThat(store.expireRegistrations(TODAY, NOW, 100)).containsExactly(expired);
        assertThat(store.expireRegistrations(TODAY, NOW, 100)).isEmpty();

        var row = jdbc.queryForMap("select verification_status, verified, active, verification_notes from professionals where id = ?", expired);
        assertThat(row.get("verification_status")).isEqualTo("EXPIRED");
        assertThat(row.get("verified")).isEqualTo(false);
        assertThat(row.get("active")).isEqualTo(false);
        assertThat(row.get("verification_notes")).isEqualTo("Registration expiry date passed.");
        var log = jdbc.queryForList("select action, from_status, to_status, actor, note from verification_log where professional_id = ?", expired);
        assertThat(log).hasSize(1);
        assertThat(log.get(0)).containsEntry("action", "EXPIRED").containsEntry("from_status", "VERIFIED")
                .containsEntry("to_status", "EXPIRED").containsEntry("actor", "SYSTEM")
                .containsEntry("note", "Registration expiry date passed.");

        // The sample listing without an account and the one still in date are untouched.
        assertThat(jdbc.queryForObject("select verification_status from professionals where id = ?", String.class, sample))
                .isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select verification_status from professionals where id = ?", String.class, current))
                .isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from verification_log", Integer.class)).isEqualTo(1);
        assertThat(visibleIds()).doesNotContain(expired).contains(current);
    }

    @Test
    void pendingRequestsOfAnExpiredRegistrationAreNotCancelled() {
        UUID expired = professional("VERIFIED", TODAY.minusDays(1), false);
        UUID pending = request(expired, "PENDING", Duration.ofHours(10));
        store.expireRegistrations(TODAY, NOW, 100);
        assertThat(status(pending)).isEqualTo("PENDING");
    }

    // The public directory's own visibility rule.
    private List<UUID> visibleIds() {
        return new NamedParameterJdbcTemplate(dataSource).queryForList(
                "SELECT p.id FROM professionals p WHERE " + PublicProfessionalRepository.VISIBLE,
                new MapSqlParameterSource("today", TODAY), UUID.class);
    }
}
