package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.AdminBookingItem;
import com.neuronest.backend.admin.AdminBookingDtos.CloseRequest;
import com.neuronest.backend.admin.AdminBookingDtos.DashboardSummary;
import com.neuronest.backend.admin.AdminBookingDtos.ResponsivenessItem;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.notification.BookingEvent;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.PublicProfessionalRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The admin booking SQL against a real PostgreSQL with a known set of rows. Skipped when Docker is not
// available on the machine.
@Testcontainers(disabledWithoutDocker = true)
class AdminBookingQueriesTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final Clock CLOCK = Clock.fixed(NOW, AvailabilityService.ZONE);
    private static final String PRIVATE = "PRIVATE-VALUE";

    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static AdminBookingQueries queries;

    private final List<BookingEvent> events = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher publisher = event -> events.add((BookingEvent) event);
    private AdminBookingService service;

    @BeforeAll
    static void createSchema() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        queries = new AdminBookingQueries(new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("""
                create table professionals (
                  id uuid primary key default gen_random_uuid(), full_name text not null,
                  credential_type text not null default 'PSYCHOLOGIST',
                  verified boolean not null default false, active boolean not null default false,
                  is_sample_data boolean not null default false, registration_expiry date,
                  verification_status text not null default 'DRAFT', submitted_at timestamptz,
                  updated_at timestamptz not null default now())""");
        jdbc.execute("""
                create table professional_accounts (
                  id uuid primary key default gen_random_uuid(), professional_id uuid not null references professionals(id),
                  status text not null default 'ACTIVE')""");
        jdbc.execute("""
                create table booking_requests (
                  id uuid primary key default gen_random_uuid(),
                  professional_id uuid not null references professionals(id), session_id uuid, user_id uuid not null,
                  contact_name text not null, contact_email text not null, contact_phone text,
                  preferred_mode text, preferred_method text, preferred_datetime timestamptz, alternate_datetime timestamptz,
                  proposed_datetime timestamptz, note text, professional_note text, admin_note text,
                  status text not null check (status in ('PENDING','CONFIRMED','DECLINED','ALTERNATE_PROPOSED','CANCELLED','EXPIRED')),
                  responded_at timestamptz, responded_by text check (responded_by in ('PROFESSIONAL','ADMIN','USER')),
                  expires_at timestamptz, created_at timestamptz not null, updated_at timestamptz not null default now(),
                  meeting_link text, meeting_address text, shared_assessment jsonb,
                  admin_nudged_at timestamptz)""");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("truncate booking_requests, professional_accounts, professionals cascade");
        service = new AdminBookingService(queries, publisher, CLOCK);
    }

    private UUID professional(String name, String status, boolean active, boolean sample) {
        return jdbc.queryForObject("""
                insert into professionals (full_name, verification_status, verified, active, is_sample_data)
                values (?, ?, ?, ?, ?) returning id""", UUID.class, name, status, "VERIFIED".equals(status), active, sample);
    }

    /** A request created `age` ago; respondedAfter (from creation) and expiresIn (from now) may be null. */
    private UUID request(UUID professionalId, String status, String respondedBy, Duration age, Duration respondedAfter,
                         Duration expiresIn) {
        Instant created = NOW.minus(age);
        return jdbc.queryForObject("""
                insert into booking_requests (professional_id, user_id, session_id, contact_name, contact_email, contact_phone,
                  preferred_mode, preferred_method, preferred_datetime, note, professional_note, status, responded_by,
                  responded_at, expires_at, created_at, meeting_link, meeting_address, shared_assessment)
                values (?, ?, ?, 'Ravi Kumar', ?, ?, 'ONLINE', 'VIDEO', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{"score": 17}'::jsonb)
                returning id""", UUID.class,
                professionalId, UUID.randomUUID(), UUID.randomUUID(), PRIVATE + "@example.com", "+91" + "9876543210",
                ts(NOW.plus(Duration.ofDays(2))), PRIVATE + " note", PRIVATE + " professional note", status, respondedBy,
                respondedAfter == null ? null : ts(created.plus(respondedAfter)),
                expiresIn == null ? null : ts(NOW.plus(expiresIn)), ts(created),
                "https://meet.example.com/" + PRIVATE, PRIVATE + " address");
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    // ---- summary ----

    @Test
    void theSummaryMatchesAKnownSetOfRowsAndLeavesOutSampleData() {
        UUID asha = professional("Dr. Asha Rao", "VERIFIED", true, false);
        UUID sample = professional("Sample Listing", "VERIFIED", true, true);
        UUID submittedOld = professional("Old Submission", "SUBMITTED", false, false);
        jdbc.update("update professionals set submitted_at = ? where id = ?", ts(NOW.minus(Duration.ofHours(60))), submittedOld);
        UUID submittedNew = professional("New Submission", "SUBMITTED", false, false);
        jdbc.update("update professionals set submitted_at = ? where id = ?", ts(NOW.minus(Duration.ofHours(2))), submittedNew);
        professional("Sample Submission", "SUBMITTED", false, true);
        professional("Needs Info", "NEEDS_INFO", false, false);
        professional("Verified Hidden", "VERIFIED", false, false);
        UUID expiring = professional("Expiring Soon", "VERIFIED", true, false);
        jdbc.update("update professionals set registration_expiry = ? where id = ?", TODAY.plusDays(20), expiring);
        UUID later = professional("Expiring Later", "VERIFIED", true, false);
        jdbc.update("update professionals set registration_expiry = ? where id = ?", TODAY.plusDays(40), later);

        // Pending: one waiting 50 hours, one waiting 2 hours, one past its deadline (not counted).
        request(asha, "PENDING", null, Duration.ofHours(50), null, Duration.ofHours(22));
        request(asha, "PENDING", null, Duration.ofHours(2), null, Duration.ofHours(70));
        request(asha, "PENDING", null, Duration.ofHours(80), null, Duration.ofHours(-8));
        // Answered by the professional after 4 and 10 hours, and a suggestion the user accepted (6 hours).
        request(asha, "DECLINED", "PROFESSIONAL", Duration.ofDays(5), Duration.ofHours(4), Duration.ofDays(-2));
        request(asha, "ALTERNATE_PROPOSED", "PROFESSIONAL", Duration.ofDays(1), Duration.ofHours(10), Duration.ofHours(50));
        request(asha, "CONFIRMED", "USER", Duration.ofDays(3), Duration.ofHours(6), Duration.ofDays(1));
        // Expired by the jobs, closed by an admin, cancelled by the user, and one from 40 days ago.
        request(asha, "EXPIRED", null, Duration.ofDays(6), Duration.ofDays(3), Duration.ofDays(-3));
        request(asha, "EXPIRED", "ADMIN", Duration.ofDays(4), Duration.ofDays(1), Duration.ofDays(-1));
        request(asha, "CANCELLED", "USER", Duration.ofDays(2), Duration.ofHours(1), Duration.ofDays(1));
        request(asha, "DECLINED", "PROFESSIONAL", Duration.ofDays(40), Duration.ofHours(1), Duration.ofDays(-37));
        // Sample data is left out everywhere.
        for (int i = 0; i < 3; i++) request(sample, "PENDING", null, Duration.ofHours(60), null, Duration.ofHours(10));
        request(sample, "CONFIRMED", "PROFESSIONAL", Duration.ofDays(1), Duration.ofHours(1), Duration.ofDays(1));

        DashboardSummary s = queries.summary(NOW, TODAY);

        assertThat(s.reviewQueue()).isEqualTo(2);
        assertThat(s.reviewQueueOver48h()).isEqualTo(1);
        assertThat(s.unansweredOver48h()).isEqualTo(1);
        assertThat(s.pendingTotal()).isEqualTo(2);
        assertThat(s.awaitingUserTotal()).isEqualTo(1);
        assertThat(s.upcomingConfirmed()).isEqualTo(1);
        assertThat(s.professionalsVerifiedActive()).isEqualTo(3);
        assertThat(s.professionalsNeedsInfo()).isEqualTo(1);
        assertThat(s.registrationsExpiringIn30Days()).isEqualTo(1);
        // Created in the last 30 days: 9 rows; answered: declined, proposed, accepted.
        assertThat(s.last30Days().requestsCreated()).isEqualTo(9);
        assertThat(s.last30Days().answered()).isEqualTo(3);
        assertThat(s.last30Days().expired()).isEqualTo(2);
        assertThat(s.last30Days().cancelledByUser()).isEqualTo(1);
        assertThat(s.last30Days().responseRatePercent()).isEqualTo(33.3);
        assertThat(s.last30Days().medianHoursToFirstResponse()).isEqualTo(6.0);
    }

    @Test
    void anEmptyPeriodHasAZeroRateAndNoMedian() {
        DashboardSummary s = queries.summary(NOW, TODAY);
        assertThat(s.last30Days().requestsCreated()).isZero();
        assertThat(s.last30Days().responseRatePercent()).isZero();
        assertThat(s.last30Days().medianHoursToFirstResponse()).isNull();
    }

    // ---- list ----

    @Test
    void theListFiltersNewestFirstAndNeverReadsPrivateColumns() {
        UUID asha = professional("Dr. Asha Rao", "VERIFIED", true, false);
        UUID ravi = professional("Dr. Ravi Shah", "VERIFIED", true, false);
        UUID sample = professional("Sample Listing", "VERIFIED", true, true);
        UUID old = request(asha, "PENDING", null, Duration.ofHours(50), null, Duration.ofHours(22));
        UUID fresh = request(asha, "PENDING", null, Duration.ofHours(2), null, Duration.ofHours(70));
        UUID overdue = request(asha, "PENDING", null, Duration.ofHours(80), null, Duration.ofHours(-8));
        UUID declined = request(ravi, "DECLINED", "PROFESSIONAL", Duration.ofHours(30), Duration.ofHours(5), Duration.ofHours(40));
        request(sample, "PENDING", null, Duration.ofHours(60), null, Duration.ofHours(10));

        PageResult all = page(service.list(null, null, null, 0, 20));
        assertThat(all.ids).containsExactly(fresh, declined, old, overdue);
        assertThat(service.list("pending", null, null, 0, 20).items()).extracting(AdminBookingItem::id)
                .containsExactly(fresh, old, overdue);
        assertThat(service.list(null, 48, null, 0, 20).items()).extracting(AdminBookingItem::id).containsExactly(old);
        assertThat(service.list(null, null, ravi, 0, 20).items()).extracting(AdminBookingItem::id).containsExactly(declined);
        assertThat(service.list(null, null, null, 1, 3).items()).extracting(AdminBookingItem::id).containsExactly(overdue);
        assertThat(service.list(null, null, null, 0, 3).totalElements()).isEqualTo(4);

        AdminBookingItem item = service.list(null, null, ravi, 0, 20).items().get(0);
        assertThat(item.professionalName()).isEqualTo("Dr. Ravi Shah");
        assertThat(item.contactName()).isEqualTo("Ravi Kumar");
        assertThat(item.hoursWaiting()).isEqualTo(5);
        assertThat(item.respondedBy()).isEqualTo("PROFESSIONAL");
        assertThat(item.toString()).doesNotContain(PRIVATE, "9876543210");
        assertThat(all.items.toString()).doesNotContain(PRIVATE, "9876543210", "score");

        assertThatThrownBy(() -> service.list("WAITING", null, null, 0, 20)).isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("PENDING, CONFIRMED, DECLINED, ALTERNATE_PROPOSED, CANCELLED, EXPIRED");
    }

    private record PageResult(List<UUID> ids, List<AdminBookingItem> items) {}

    private static PageResult page(AdminProfessionalDtos.PageResponse<AdminBookingItem> page) {
        return new PageResult(page.items().stream().map(AdminBookingItem::id).toList(), page.items());
    }

    // ---- responsiveness ----

    @Test
    void responsivenessListsWorstFirstAndLeavesOutSampleData() {
        UUID good = professional("Dr. Good", "VERIFIED", true, false);
        UUID poor = professional("Dr. Poor", "VERIFIED", true, false);
        UUID worst = professional("Dr. Worst", "VERIFIED", true, false);
        UUID sample = professional("Sample Listing", "VERIFIED", true, true);
        professional("Dr. Nothing", "VERIFIED", true, false);
        request(good, "DECLINED", "PROFESSIONAL", Duration.ofDays(2), Duration.ofHours(2), Duration.ofDays(-1));
        request(good, "CONFIRMED", "PROFESSIONAL", Duration.ofDays(3), Duration.ofHours(4), Duration.ofDays(-1));
        request(poor, "CONFIRMED", "PROFESSIONAL", Duration.ofDays(2), Duration.ofHours(10), Duration.ofDays(-1));
        request(poor, "EXPIRED", null, Duration.ofDays(5), Duration.ofDays(3), Duration.ofDays(-2));
        request(poor, "PENDING", null, Duration.ofHours(3), null, Duration.ofHours(60));
        request(worst, "EXPIRED", null, Duration.ofDays(5), Duration.ofDays(3), Duration.ofDays(-2));
        request(worst, "EXPIRED", null, Duration.ofDays(6), Duration.ofDays(3), Duration.ofDays(-3));
        request(worst, "DECLINED", "PROFESSIONAL", Duration.ofDays(60), Duration.ofHours(1), Duration.ofDays(-57));
        request(sample, "EXPIRED", null, Duration.ofDays(5), Duration.ofDays(3), Duration.ofDays(-2));

        List<ResponsivenessItem> items = service.responsiveness(30, 0, 20).items();

        assertThat(items).extracting(ResponsivenessItem::fullName).containsExactly("Dr. Worst", "Dr. Poor", "Dr. Good");
        ResponsivenessItem w = items.get(0);
        assertThat(w.received()).isEqualTo(2);
        assertThat(w.answered()).isZero();
        assertThat(w.expired()).isEqualTo(2);
        assertThat(w.medianHoursToFirstResponse()).isNull();
        assertThat(w.lastAnsweredAt()).isNull();
        ResponsivenessItem p = items.get(1);
        assertThat(p.received()).isEqualTo(3);
        assertThat(p.answered()).isEqualTo(1);
        assertThat(p.pendingNow()).isEqualTo(1);
        assertThat(p.medianHoursToFirstResponse()).isEqualTo(10.0);
        ResponsivenessItem g = items.get(2);
        assertThat(g.medianHoursToFirstResponse()).isEqualTo(3.0);
        assertThat(g.lastAnsweredAt()).isEqualTo(NOW.minus(Duration.ofDays(2)).plus(Duration.ofHours(2)));
        // With 90 days the older request counts too.
        assertThat(service.responsiveness(90, 0, 20).items()).filteredOn(i -> i.fullName().equals("Dr. Worst"))
                .singleElement().satisfies(i -> assertThat(i.received()).isEqualTo(3));
        assertThatThrownBy(() -> service.responsiveness(91, 0, 20)).isInstanceOf(ApiValidationException.class);
    }

    // ---- nudge ----

    @Test
    void aNudgeWorksOnceIn12HoursAndOnlyForAWaitingRequest() {
        UUID asha = professional("Dr. Asha Rao", "VERIFIED", true, false);
        UUID pending = request(asha, "PENDING", null, Duration.ofHours(30), null, Duration.ofHours(40));
        UUID overdue = request(asha, "PENDING", null, Duration.ofHours(80), null, Duration.ofHours(-8));
        UUID answered = request(asha, "DECLINED", "PROFESSIONAL", Duration.ofHours(30), Duration.ofHours(2), Duration.ofHours(40));

        AdminBookingItem item = service.nudge(pending);

        assertThat(item.adminNudgedAt()).isEqualTo(NOW);
        assertThat(events).containsExactly(new BookingEvent(BookingEvent.Type.REMINDER, pending));
        assertThatThrownBy(() -> service.nudge(pending)).isInstanceOf(ConflictException.class)
                .hasMessage("This professional was reminded recently.");
        assertThatThrownBy(() -> service.nudge(overdue)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("still waiting");
        assertThatThrownBy(() -> service.nudge(answered)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("still waiting");
        assertThatThrownBy(() -> service.nudge(UUID.randomUUID())).isInstanceOf(AdminBookingNotFoundException.class);
        assertThat(events).hasSize(1);

        // 12 hours later it may be sent again.
        AdminBookingService later = new AdminBookingService(queries, publisher,
                Clock.fixed(NOW.plus(Duration.ofHours(12)), AvailabilityService.ZONE));
        later.nudge(pending);
        assertThat(events).hasSize(2);
    }

    // ---- close ----

    @Test
    void closeExpiresTheRequestAsAdminOnceAndPublishesTheExpiryPath() {
        UUID asha = professional("Dr. Asha Rao", "VERIFIED", true, false);
        UUID pending = request(asha, "PENDING", null, Duration.ofHours(30), null, Duration.ofHours(40));
        UUID proposed = request(asha, "ALTERNATE_PROPOSED", "PROFESSIONAL", Duration.ofHours(30), Duration.ofHours(2), Duration.ofHours(40));
        UUID confirmed = request(asha, "CONFIRMED", "PROFESSIONAL", Duration.ofHours(30), Duration.ofHours(2), Duration.ofHours(40));

        AdminBookingItem item = service.close(pending, new CloseRequest("  The professional is on leave.  "));

        assertThat(item.status()).isEqualTo("EXPIRED");
        assertThat(item.respondedBy()).isEqualTo("ADMIN");
        assertThat(item.respondedAt()).isEqualTo(NOW);
        assertThat(item.adminNote()).isEqualTo("The professional is on leave.");
        assertThat(events).containsExactly(new BookingEvent(BookingEvent.Type.CLOSED_BY_ADMIN, pending));
        assertThatThrownBy(() -> service.close(pending, new CloseRequest("Closing it a second time.")))
                .isInstanceOf(ConflictException.class);
        assertThat(service.close(proposed, new CloseRequest("No reply from the person.")).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> service.close(confirmed, new CloseRequest("Already confirmed one.")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.close(UUID.randomUUID(), new CloseRequest("Unknown request id.")))
                .isInstanceOf(AdminBookingNotFoundException.class);
        assertThatThrownBy(() -> service.close(confirmed, new CloseRequest("short"))).isInstanceOf(ApiValidationException.class);
        assertThat(events).hasSize(2);
    }

    @Test
    void twoSimultaneousClosesActOnce() throws Exception {
        UUID asha = professional("Dr. Asha Rao", "VERIFIED", true, false);
        UUID pending = request(asha, "PENDING", null, Duration.ofHours(30), null, Duration.ofHours(40));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CyclicBarrier together = new CyclicBarrier(2);
        List<String> outcomes = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String note = "Closed by admin number " + i + ".";
            threads.add(new Thread(() -> {
                try {
                    together.await();
                    tx.executeWithoutResult(s -> service.close(pending, new CloseRequest(note)));
                    outcomes.add("closed");
                } catch (ConflictException e) {
                    outcomes.add("conflict");
                } catch (Exception e) {
                    outcomes.add("error " + e.getClass().getSimpleName());
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join(20_000);

        assertThat(outcomes).containsExactlyInAnyOrder("closed", "conflict");
        assertThat(events).hasSize(1);
    }

    // ---- suspension and the public directory ----

    @Test
    void aSuspendedProfessionalIsNotPubliclyVisibleAndAReinstatedOneIs() {
        UUID p = professional("Dr. Asha Rao", "VERIFIED", true, false);
        assertThat(visibleIds()).contains(p);
        // The values AdminSuspensionService.suspend sets.
        jdbc.update("update professionals set verification_status = 'SUSPENDED', verified = false, active = false where id = ?", p);
        assertThat(visibleIds()).doesNotContain(p);
        // The values reinstate sets.
        jdbc.update("update professionals set verification_status = 'VERIFIED', verified = true, active = true where id = ?", p);
        assertThat(visibleIds()).contains(p);
    }

    private List<UUID> visibleIds() {
        return new NamedParameterJdbcTemplate(dataSource).queryForList(
                "SELECT p.id FROM professionals p WHERE " + PublicProfessionalRepository.VISIBLE,
                new MapSqlParameterSource("today", TODAY), UUID.class);
    }
}
