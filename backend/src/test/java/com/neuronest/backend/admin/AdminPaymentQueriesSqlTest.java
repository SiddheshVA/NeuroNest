package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.DashboardSummary;
import com.neuronest.backend.admin.AdminBookingDtos.RefundNeededItem;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.professional.AvailabilityService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The admin SQL for payments against a real PostgreSQL (embedded, no Docker): accepted bookings count as
// answered while waiting for payment and after expiring unpaid, an unanswered expiry stays unanswered,
// the awaiting-payment count, and the refunds-needed list.
class AdminPaymentQueriesSqlTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static AdminBookingQueries queries;

    @BeforeAll
    static void createSchema() throws IOException {
        postgres = EmbeddedPostgres.start();
        var dataSource = postgres.getPostgresDatabase();
        jdbc = new JdbcTemplate(dataSource);
        queries = new AdminBookingQueries(new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("""
                create table professionals (
                  id uuid primary key default gen_random_uuid(), full_name text not null,
                  verified boolean not null default false, active boolean not null default false,
                  is_sample_data boolean not null default false, registration_expiry date,
                  verification_status text not null default 'DRAFT', submitted_at timestamptz)""");
        jdbc.execute("""
                create table booking_requests (
                  id uuid primary key default gen_random_uuid(),
                  professional_id uuid not null references professionals(id), contact_name text not null,
                  preferred_mode text, preferred_method text, preferred_datetime timestamptz, proposed_datetime timestamptz,
                  admin_note text,
                  status text not null check (status in
                    ('PENDING','AWAITING_PAYMENT','CONFIRMED','DECLINED','ALTERNATE_PROPOSED','CANCELLED','EXPIRED')),
                  responded_at timestamptz, responded_by text check (responded_by in ('PROFESSIONAL','ADMIN','USER')),
                  expires_at timestamptz, created_at timestamptz not null, admin_nudged_at timestamptz,
                  payment_due_at timestamptz, payment_expired_at timestamptz)""");
        jdbc.execute("""
                create table payments (
                  id uuid primary key default gen_random_uuid(), booking_id uuid not null references booking_requests(id),
                  razorpay_order_id text not null unique, razorpay_payment_id text, amount_paise bigint not null,
                  currency text not null, status text not null check (status in ('CREATED','PAID','FAILED')),
                  created_at timestamptz not null default now(), paid_at timestamptz)""");
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("truncate payments, booking_requests, professionals cascade");
    }

    private UUID professional(String name) {
        return jdbc.queryForObject("""
                insert into professionals (full_name, verification_status, verified, active)
                values (?, 'VERIFIED', true, true) returning id""", UUID.class, name);
    }

    /** Created 2 days ago; answered `respondedAfter` hours after creation (or never); a payment deadline from now. */
    private UUID booking(UUID professionalId, String status, String respondedBy, Integer respondedAfterHours,
                         Duration paymentDueIn) {
        Instant created = NOW.minus(Duration.ofDays(2));
        return jdbc.queryForObject("""
                insert into booking_requests (professional_id, contact_name, preferred_datetime, status, responded_by,
                  responded_at, expires_at, created_at, payment_due_at)
                values (?, 'Ravi Kumar', ?, ?, ?, ?, ?, ?, ?) returning id""", UUID.class,
                professionalId, ts(NOW.plus(Duration.ofDays(3))), status, respondedBy,
                respondedAfterHours == null ? null : ts(created.plus(Duration.ofHours(respondedAfterHours))),
                ts(NOW.plus(Duration.ofDays(1))), ts(created), paymentDueIn == null ? null : ts(NOW.plus(paymentDueIn)));
    }

    private void payment(UUID bookingId, String orderId, String status, Instant paidAt) {
        jdbc.update("""
                insert into payments (booking_id, razorpay_order_id, razorpay_payment_id, amount_paise, currency, status, paid_at)
                values (?, ?, ?, 150000, 'INR', ?, ?)""", bookingId, orderId,
                paidAt == null ? null : "pay_" + orderId, status, paidAt == null ? null : ts(paidAt));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    @Test
    void acceptedBookingsCountAsAnsweredWhetherPaidWaitingOrExpiredUnpaid() {
        UUID asha = professional("Dr. Asha Rao");
        booking(asha, "AWAITING_PAYMENT", "PROFESSIONAL", 4, Duration.ofHours(10));
        // Deadline passed, still in the grace period: answered, but not counted as waiting for payment.
        booking(asha, "AWAITING_PAYMENT", "PROFESSIONAL", 2, Duration.ofMinutes(-5));
        booking(asha, "EXPIRED", "PROFESSIONAL", 10, Duration.ofHours(-3));
        booking(asha, "CONFIRMED", "PROFESSIONAL", 6, Duration.ofHours(-30));
        // A suggestion the user accepted, then did not pay.
        booking(asha, "EXPIRED", "USER", 8, Duration.ofHours(-1));
        // Expired with no response at all, and one closed by an admin: unanswered.
        booking(asha, "EXPIRED", null, 48, null);
        booking(asha, "EXPIRED", "ADMIN", 30, null);

        DashboardSummary summary = queries.summary(NOW, TODAY);

        assertThat(summary.awaitingPaymentTotal()).isEqualTo(1);
        // Paid only: the CONFIRMED one is in the future.
        assertThat(summary.upcomingConfirmed()).isEqualTo(1);
        assertThat(summary.last30Days().requestsCreated()).isEqualTo(7);
        assertThat(summary.last30Days().answered()).isEqualTo(5);
        assertThat(summary.last30Days().expired()).isEqualTo(2);
        // Median of 2, 4, 6, 8, 10 hours: the acceptance times, untouched by the payment outcome.
        assertThat(summary.last30Days().medianHoursToFirstResponse()).isEqualTo(6.0);

        var row = queries.responsiveness(NOW.minus(Duration.ofDays(30)), NOW, 0, 20).items().get(0);
        assertThat(row.received()).isEqualTo(7);
        assertThat(row.answered()).isEqualTo(5);
        assertThat(row.expired()).isEqualTo(2);
        assertThat(row.medianHoursToFirstResponse()).isEqualTo(6.0);
    }

    @Test
    void theRefundsListHasOnlyPaidPaymentsForBookingsThatAreNotConfirmed() {
        UUID asha = professional("Dr. Asha Rao");
        UUID expired = booking(asha, "EXPIRED", "PROFESSIONAL", 2, Duration.ofHours(-2));
        UUID cancelled = booking(asha, "CANCELLED", "USER", 3, Duration.ofHours(5));
        UUID confirmed = booking(asha, "CONFIRMED", "PROFESSIONAL", 1, Duration.ofHours(5));
        UUID waiting = booking(asha, "AWAITING_PAYMENT", "PROFESSIONAL", 1, Duration.ofHours(5));
        payment(cancelled, "order_cancelled", "PAID", NOW.minus(Duration.ofHours(1)));
        payment(expired, "order_expired", "PAID", NOW.minus(Duration.ofHours(2)));
        payment(confirmed, "order_confirmed", "PAID", NOW.minus(Duration.ofHours(3)));
        payment(waiting, "order_waiting", "CREATED", null);
        payment(expired, "order_expired_unpaid", "CREATED", null);

        var first = queries.refundsNeeded(0, 1);
        var second = queries.refundsNeeded(1, 1);

        assertThat(first.total()).isEqualTo(2);
        // Oldest payment first.
        RefundNeededItem oldest = first.items().get(0);
        assertThat(oldest.bookingId()).isEqualTo(expired);
        assertThat(oldest.bookingStatus()).isEqualTo("EXPIRED");
        assertThat(oldest.razorpayOrderId()).isEqualTo("order_expired");
        assertThat(oldest.razorpayPaymentId()).isEqualTo("pay_order_expired");
        assertThat(oldest.amountPaise()).isEqualTo(150_000);
        assertThat(oldest.currency()).isEqualTo("INR");
        assertThat(oldest.paidAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(oldest.professionalName()).isEqualTo("Dr. Asha Rao");
        assertThat(second.items()).singleElement().extracting(RefundNeededItem::bookingId).isEqualTo(cancelled);
    }

    @Test
    void theServicePagesTheRefundsListLikeTheOtherAdminLists() {
        AdminBookingService service = new AdminBookingService(queries, event -> { },
                Clock.fixed(NOW, AvailabilityService.ZONE));
        UUID asha = professional("Dr. Asha Rao");
        payment(booking(asha, "EXPIRED", "PROFESSIONAL", 2, Duration.ofHours(-2)), "order_1", "PAID", NOW);

        var page = service.refundsNeeded(0, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThatThrownBy(() -> service.refundsNeeded(-1, 20)).isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> service.refundsNeeded(0, 0)).isInstanceOf(ApiValidationException.class);
    }
}
