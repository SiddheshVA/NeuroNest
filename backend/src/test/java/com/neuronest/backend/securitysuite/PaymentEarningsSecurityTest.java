package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.CommissionSplit;
import com.neuronest.backend.payment.Payment;
import com.neuronest.backend.professional.Professional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Earnings and commission over HTTP with real repositories: a professional sees only their own paid,
 * confirmed bookings and never a user's details; the admin sees the totals and every paid payment; legacy
 * confirmed bookings without a payment and paid-but-not-confirmed payments never count; and each area
 * accepts only its own kind of sign-in.
 */
class PaymentEarningsSecurityTest extends SecuritySuiteBase {

    // The same answer /api/pro/dashboard gives a sign-in without a professional account.
    private static final String NO_PRO_ACCOUNT = "{\"error\":\"Please sign in with a professional account to continue.\"}";

    private String ashaToken;
    private String raviToken;
    private String userToken;
    private Booking ashaThisMonth;
    private Booking ashaRefundNeeded;

    @BeforeEach
    void data() {
        Professional asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        Professional ravi = listed("Dr. Ravi Shah");
        account(ravi, "pro_ravi");
        User person = user("user_person", "person@example.com");
        ashaToken = SecurityTestTokens.clerk("pro_asha", asha.getContactEmail());
        raviToken = SecurityTestTokens.clerk("pro_ravi", ravi.getContactEmail());
        userToken = SecurityTestTokens.clerk("user_person", "person@example.com");
        Instant now = Instant.now();
        Instant future = now.plus(Duration.ofDays(3));

        // Asha: paid now (this month) and paid 40 days ago (an earlier month).
        ashaThisMonth = booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, future, null);
        paid(ashaThisMonth, "order_a1", 150_000, now.minus(Duration.ofMinutes(5)));
        paid(booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, now.minus(Duration.ofDays(39)), null),
                "order_a2", 80_000, now.minus(Duration.ofDays(40)));
        // Never counted: paid but the booking expired (refund needed), a legacy confirmed booking with no
        // payment, and an unpaid order.
        ashaRefundNeeded = booking(person.getId(), asha.getId(), BookingStatus.EXPIRED, future, null);
        paid(ashaRefundNeeded, "order_a3", 50_000, now.minus(Duration.ofMinutes(1)));
        booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, future.plus(Duration.ofDays(1)), null);
        Booking unpaid = booking(person.getId(), asha.getId(), BookingStatus.AWAITING_PAYMENT, future, null);
        payments.saveAndFlush(new Payment(unpaid.getId(), "order_a4", 70_000, "INR", now));
        // Ravi: one paid, confirmed booking.
        paid(booking(person.getId(), ravi.getId(), BookingStatus.CONFIRMED, future, null), "order_r1", 200_000,
                now.minus(Duration.ofMinutes(10)));
    }

    private void paid(Booking b, String orderId, long amount, Instant paidAt) {
        Payment p = new Payment(b.getId(), orderId, amount, "INR", paidAt.minus(Duration.ofMinutes(1)));
        CommissionSplit.Split split = CommissionSplit.split(amount, 10);
        p.markPaid("pay_" + orderId, paidAt, split.percent(), split.commissionPaise(), split.professionalSharePaise());
        payments.saveAndFlush(p);
    }

    private MvcResult call(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(String path, String token) throws Exception {
        MvcResult result = call(path, token);
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static void assertTotals(JsonNode totals, long gross, long commission, long share, long sessions) {
        assertThat(totals.get("grossPaise").asLong()).isEqualTo(gross);
        assertThat(totals.get("commissionPaise").asLong()).isEqualTo(commission);
        assertThat(totals.get("professionalSharePaise").asLong()).isEqualTo(share);
        assertThat(totals.get("paidSessions").asLong()).isEqualTo(sessions);
    }

    // ---- /api/pro/earnings ----

    @Test
    void aProfessionalSeesOnlyTheirOwnPaidConfirmedBookingsAndNoUserDetails() throws Exception {
        JsonNode asha = ok("/api/pro/earnings", ashaToken);

        assertTotals(asha.get("allTime"), 230_000, 23_000, 207_000, 2);
        assertTotals(asha.get("thisMonth"), 150_000, 15_000, 135_000, 1);
        JsonNode items = asha.get("payments").get("items");
        assertThat(items).hasSize(2);
        assertThat(asha.get("payments").get("totalElements").asLong()).isEqualTo(2);
        // Newest payment first.
        assertThat(items.get(0).get("bookingId").asString()).isEqualTo(ashaThisMonth.getId().toString());
        assertThat(items.get(0).get("amountPaise").asLong()).isEqualTo(150_000);
        assertThat(items.get(0).get("commissionPaise").asLong()).isEqualTo(15_000);
        assertThat(items.get(0).get("professionalSharePaise").asLong()).isEqualTo(135_000);
        assertThat(items.get(1).get("amountPaise").asLong()).isEqualTo(80_000);

        String body = asha.toString();
        assertThat(allKeys(body)).doesNotContain("contactName", "contactEmail", "contactPhone", "userId", "sessionId",
                "note", "razorpayPaymentId");
        assertThat(body).doesNotContain("Ravi Kumar", "person.private@example.com", "9876543210", "order_r1",
                ashaRefundNeeded.getId().toString());

        // Ravi sees only his own payment, never Asha's.
        JsonNode ravi = ok("/api/pro/earnings", raviToken);
        assertTotals(ravi.get("allTime"), 200_000, 20_000, 180_000, 1);
        assertThat(ravi.toString()).doesNotContain(ashaThisMonth.getId().toString());
    }

    @Test
    void aSignedInUserWhoIsNotAProfessionalIsRejected() throws Exception {
        MvcResult result = call("/api/pro/earnings", userToken);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(NO_PRO_ACCOUNT);
        assertThat(call("/api/pro/earnings", SecurityTestTokens.admin()).getResponse().getStatus()).isEqualTo(401);
        assertThat(call("/api/pro/earnings", null).getResponse().getStatus()).isEqualTo(401);
    }

    // ---- /api/admin/payments ----

    @Test
    void theAdminSeesTheTotalsAndEveryPaidPaymentWithItsBookingStatus() throws Exception {
        JsonNode summary = ok("/api/admin/payments/summary", SecurityTestTokens.admin());
        assertTotals(summary.get("allTime"), 430_000, 43_000, 387_000, 3);
        assertTotals(summary.get("thisMonth"), 350_000, 35_000, 315_000, 2);
        assertThat(summary.get("month").asString()).matches("\\d{4}-\\d{2}");

        JsonNode list = ok("/api/admin/payments?size=10", SecurityTestTokens.admin());
        JsonNode items = list.get("items");
        assertThat(list.get("totalElements").asLong()).isEqualTo(4);
        // Newest first: the refund-needed payment (1 minute ago) is listed but not counted.
        assertThat(items.get(0).get("bookingId").asString()).isEqualTo(ashaRefundNeeded.getId().toString());
        assertThat(items.get(0).get("bookingStatus").asString()).isEqualTo("EXPIRED");
        assertThat(items.get(0).get("counted").asBoolean()).isFalse();
        assertThat(items.get(1).get("professionalName").asString()).isEqualTo("Dr. Asha Rao");
        assertThat(items.get(1).get("counted").asBoolean()).isTrue();
        assertThat(allKeys(list.toString())).doesNotContain("contactEmail", "contactPhone", "userId", "contactName");
    }

    @Test
    void usersAndProfessionalsAreRejectedOnTheAdminPaymentEndpoints() throws Exception {
        for (String path : new String[] {"/api/admin/payments/summary", "/api/admin/payments"}) {
            for (String token : new String[] {userToken, ashaToken, null}) {
                assertThat(call(path, token).getResponse().getStatus()).as(path).isEqualTo(401);
            }
        }
    }

    @Test
    void withNoPaymentsEverythingIsZero() throws Exception {
        jdbc.execute("TRUNCATE payments");
        JsonNode asha = ok("/api/pro/earnings", ashaToken);
        assertTotals(asha.get("allTime"), 0, 0, 0, 0);
        assertThat(asha.get("payments").get("items")).isEmpty();
        assertTotals(ok("/api/admin/payments/summary", SecurityTestTokens.admin()).get("allTime"), 0, 0, 0, 0);
    }
}
