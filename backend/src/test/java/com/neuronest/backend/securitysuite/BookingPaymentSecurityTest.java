package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.payment.PaymentStatus;
import com.neuronest.backend.professional.Professional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Booking payments over HTTP, with real repositories and Razorpay mocked: only the booking's own user can
 * start or verify a payment, the amount comes from the stored fee, the verify body uses Razorpay's own
 * field names, and without keys the endpoints answer 503 instead of the app failing to start.
 */
class BookingPaymentSecurityTest extends SecuritySuiteBase {

    private Professional asha;
    private Booking ofOwner;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        asha.setConsultationFee(1200);
        asha = professionals.saveAndFlush(asha);
        User owner = user("user_owner", "owner@example.com");
        user("user_other", "other@example.com");
        ownerToken = SecurityTestTokens.clerk("user_owner", "owner@example.com");
        otherToken = SecurityTestTokens.clerk("user_other", "other@example.com");
        ofOwner = booking(owner.getId(), asha.getId(), BookingStatus.AWAITING_PAYMENT,
                Instant.now().plus(Duration.ofDays(3)), Instant.now().plus(Duration.ofDays(2)));
        ofOwner.setPaymentDueAt(Instant.now().plus(Duration.ofHours(24)));
        ofOwner = bookings.saveAndFlush(ofOwner);
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token))).andReturn();
    }

    private MockHttpServletRequestBuilder order(UUID id) {
        return post("/api/bookings/" + id + "/payment/order");
    }

    private MockHttpServletRequestBuilder verifyRequest(UUID id, String body) {
        return post("/api/bookings/" + id + "/payment/verify").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static final String VERIFY_BODY =
            "{\"razorpay_order_id\":\"order_E2E\",\"razorpay_payment_id\":\"pay_E2E\",\"razorpay_signature\":\"sig_E2E\"}";

    private void configured() {
        when(razorpay.isConfigured()).thenReturn(true);
        when(razorpay.keyId()).thenReturn("rzp_test_suite");
        when(razorpay.createOrder(anyLong(), anyString(), anyString())).thenReturn("order_E2E");
        when(razorpay.isValidSignature("order_E2E", "pay_E2E", "sig_E2E")).thenReturn(true);
    }

    @Test
    void withoutKeysThePaymentEndpointsAnswer503AndNothingElseBreaks() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                order(ofOwner.getId()), verifyRequest(ofOwner.getId(), VERIFY_BODY)}) {
            MvcResult result = as(ownerToken, request);
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(result.getResponse().getContentAsString())
                    .isEqualTo("{\"error\":\"Payments are not configured. Please try again later.\"}");
        }
        assertThat(as(ownerToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/bookings/" + ofOwner.getId())).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theOwnerPaysAndTheBookingIsConfirmed() throws Exception {
        configured();

        MvcResult created = as(ownerToken, order(ofOwner.getId()));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(created.getResponse().getContentAsString()).toString()).isEqualTo(
                "{\"razorpayOrderId\":\"order_E2E\",\"amount\":120000,\"currency\":\"INR\",\"keyId\":\"rzp_test_suite\"}");
        verify(razorpay).createOrder(120_000, "INR", ofOwner.getId().toString());

        for (int i = 0; i < 2; i++) {
            MvcResult verified = as(ownerToken, verifyRequest(ofOwner.getId(), VERIFY_BODY));
            assertThat(verified.getResponse().getStatus()).isEqualTo(200);
            JsonNode view = JSON.readTree(verified.getResponse().getContentAsString());
            assertThat(view.get("status").asString()).isEqualTo("CONFIRMED");
            // The receipt comes from the PAID payment row.
            assertThat(view.get("paidAmount").asLong()).isEqualTo(120_000);
            assertThat(view.get("paidCurrency").asString()).isEqualTo("INR");
        }
        assertThat(bookings.findById(ofOwner.getId())).get().extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(payments.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(p.getRazorpayPaymentId()).isEqualTo("pay_E2E");
            assertThat(p.getAmountPaise()).isEqualTo(120_000);
        });
    }

    @Test
    void theAmountShownBeforePayingIsTheAmountTheOrderCharges() throws Exception {
        configured();
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bookings/" + ofOwner.getId());
        var summary = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bookings/summary");

        var shown = JSON.readTree(as(ownerToken, get).getResponse().getContentAsString());
        var listed = JSON.readTree(as(ownerToken, summary).getResponse().getContentAsString()).get("awaitingPayment").get(0);
        var order = JSON.readTree(as(ownerToken, order(ofOwner.getId())).getResponse().getContentAsString());

        assertThat(order.get("amount").asLong()).isEqualTo(120_000);
        assertThat(shown.get("amount").asLong()).isEqualTo(order.get("amount").asLong());
        assertThat(listed.get("amount").asLong()).isEqualTo(order.get("amount").asLong());
        assertThat(shown.get("currency").asString()).isEqualTo(order.get("currency").asString()).isEqualTo("INR");
        assertThat(listed.get("currency").asString()).isEqualTo("INR");

        // After a fee change the existing order is reused, and what is shown still matches it.
        asha.setConsultationFee(3000);
        professionals.saveAndFlush(asha);
        var again = JSON.readTree(as(ownerToken, get).getResponse().getContentAsString());
        var reordered = JSON.readTree(as(ownerToken, order(ofOwner.getId())).getResponse().getContentAsString());
        assertThat(again.get("amount").asLong()).isEqualTo(reordered.get("amount").asLong()).isEqualTo(120_000);
    }

    @Test
    void anExpiredUnpaidBookingStillShowsItsPaymentDeadline() throws Exception {
        ofOwner.setStatus(BookingStatus.EXPIRED);
        ofOwner = bookings.saveAndFlush(ofOwner);

        var view = JSON.readTree(as(ownerToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/bookings/" + ofOwner.getId())).getResponse().getContentAsString());

        assertThat(view.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(view.get("paymentDueAt").isNull()).isFalse();
        assertThat(view.get("amount").isNull()).isTrue();
    }

    @Test
    void anotherUserGetsTheSame404AsAMissingBooking() throws Exception {
        configured();
        String missing = as(otherToken, order(UUID.randomUUID())).getResponse().getContentAsString();

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                order(ofOwner.getId()), verifyRequest(ofOwner.getId(), VERIFY_BODY)}) {
            MvcResult result = as(otherToken, request);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEqualTo(missing);
        }
        verify(razorpay, never()).createOrder(anyLong(), anyString(), anyString());
        assertThat(payments.findAll()).isEmpty();
        assertThat(bookings.findById(ofOwner.getId())).get().extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.AWAITING_PAYMENT);
    }
}
