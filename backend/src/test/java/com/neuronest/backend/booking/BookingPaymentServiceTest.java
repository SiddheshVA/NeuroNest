package com.neuronest.backend.booking;

import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingDtos.PaymentOrder;
import com.neuronest.backend.booking.BookingDtos.VerifyPaymentRequest;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.notification.BookingEvent;
import com.neuronest.backend.payment.Payment;
import com.neuronest.backend.payment.PaymentRepository;
import com.neuronest.backend.payment.PaymentStatus;
import com.neuronest.backend.payment.RazorpayGateway;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BookingPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, AvailabilityService.ZONE);
    private static final String KEY_ID = "rzp_test_key_id";
    private static final String SECRET = "test_key_secret_for_unit_tests";

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    // The real signature check with a test secret; only the Razorpay API call (createOrder) is stubbed.
    private final RazorpayGateway gateway = spy(new RazorpayGateway(KEY_ID, SECRET));
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BookingPaymentService service = new BookingPaymentService(bookings, paymentRepository, professionals,
            gateway, events, CLOCK);

    private final List<Booking> bookingStore = new ArrayList<>();
    private final List<Payment> paymentStore = new ArrayList<>();
    private final UUID userId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private Professional professional;

    @BeforeEach
    void setUp() {
        professional = new Professional();
        ReflectionTestUtils.setField(professional, "id", professionalId);
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(CredentialType.PSYCHOLOGIST);
        professional.setConsultationFee(1500);
        when(professionals.findById(professionalId)).thenAnswer(inv -> Optional.of(professional));

        doAnswer(inv -> inv.getArgument(0)).when(bookings).saveAndFlush(any());
        doAnswer(inv -> bookingStore.stream().filter(b -> b.getId().equals(inv.getArgument(0))).findFirst())
                .when(bookings).findByIdForUpdate(any());
        doAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (!paymentStore.contains(p)) paymentStore.add(p);
            return p;
        }).when(paymentRepository).saveAndFlush(any());
        doAnswer(inv -> paymentStore.stream().filter(p -> p.getBookingId().equals(inv.getArgument(0))
                        && p.getStatus() == inv.getArgument(1)).findFirst())
                .when(paymentRepository).findFirstByBookingIdAndStatusOrderByCreatedAtDesc(any(), any());
        doAnswer(inv -> paymentStore.stream().filter(p -> p.getRazorpayOrderId().equals(inv.getArgument(0))).findFirst())
                .when(paymentRepository).findByRazorpayOrderIdForUpdate(any());
        doReturn("order_TEST123").when(gateway).createOrder(anyLong(), anyString(), anyString());
    }

    private Booking booking(BookingStatus status, UUID owner) {
        Booking b = new Booking();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setProfessionalId(professionalId);
        b.setUserId(owner);
        b.setStatus(status);
        b.setPreferredDatetime(NOW.plus(Duration.ofDays(2)));
        b.setPaymentDueAt(NOW.plus(Duration.ofHours(24)));
        bookingStore.add(b);
        return b;
    }

    private Booking awaiting() {
        return booking(BookingStatus.AWAITING_PAYMENT, userId);
    }

    // Razorpay's checkout signature, computed here independently of RazorpayGateway.
    private static String sign(String orderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }

    private static VerifyPaymentRequest signed(String orderId, String paymentId) throws Exception {
        return new VerifyPaymentRequest(orderId, paymentId, sign(orderId, paymentId));
    }

    // ---- creating the order ----

    @Test
    void theAmountIsTheProfessionalsFeeFromTheDatabaseInPaise() {
        Booking b = awaiting();

        PaymentOrder order = service.createOrder(userId, b.getId().toString());

        assertThat(order).isEqualTo(new PaymentOrder("order_TEST123", 150_000, "INR", KEY_ID));
        verify(gateway).createOrder(150_000, "INR", b.getId().toString());
        verify(professionals).findById(professionalId);
        assertThat(paymentStore).singleElement().satisfies(p -> {
            assertThat(p.getBookingId()).isEqualTo(b.getId());
            assertThat(p.getAmountPaise()).isEqualTo(150_000);
            assertThat(p.getCurrency()).isEqualTo("INR");
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CREATED);
            assertThat(p.getCreatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void anUnpaidOrderIsReusedInsteadOfCreatingAnother() {
        Booking b = awaiting();
        PaymentOrder first = service.createOrder(userId, b.getId().toString());
        // A later fee change does not change the order already made.
        professional.setConsultationFee(9999);

        PaymentOrder second = service.createOrder(userId, b.getId().toString());

        assertThat(second).isEqualTo(first);
        verify(gateway, times(1)).createOrder(anyLong(), anyString(), anyString());
        assertThat(paymentStore).hasSize(1);
    }

    @Test
    void aMissingOrZeroFeeIsAClearError() {
        Booking b = awaiting();
        for (Integer fee : new Integer[] {null, 0}) {
            professional.setConsultationFee(fee);
            assertThatThrownBy(() -> service.createOrder(userId, b.getId().toString()))
                    .isInstanceOf(ConflictException.class).hasMessage(BookingPaymentService.NO_FEE_MESSAGE);
        }
        verify(gateway, never()).createOrder(anyLong(), anyString(), anyString());
        assertThat(paymentStore).isEmpty();
    }

    @Test
    void anOrderNeedsAnAwaitingPaymentBookingBeforeItsDeadline() {
        for (BookingStatus status : BookingStatus.values()) {
            if (status == BookingStatus.AWAITING_PAYMENT) continue;
            Booking b = booking(status, userId);
            assertThatThrownBy(() -> service.createOrder(userId, b.getId().toString()))
                    .as(status.name()).isInstanceOf(ConflictException.class)
                    .hasMessage(BookingPaymentService.NOT_AWAITING_MESSAGE);
        }
        Booking late = awaiting();
        late.setPaymentDueAt(NOW);
        assertThatThrownBy(() -> service.createOrder(userId, late.getId().toString()))
                .isInstanceOf(ConflictException.class).hasMessage(BookingPaymentService.PAST_DUE_MESSAGE);
        verify(gateway, never()).createOrder(anyLong(), anyString(), anyString());
    }

    @Test
    void anotherUsersBookingIsNotFound() throws Exception {
        Booking theirs = booking(BookingStatus.AWAITING_PAYMENT, UUID.randomUUID());
        String id = theirs.getId().toString();

        assertThatThrownBy(() -> service.createOrder(userId, id))
                .isInstanceOf(BookingExceptions.NotFoundException.class);
        assertThatThrownBy(() -> service.createOrder(userId, "not-a-uuid"))
                .isInstanceOf(BookingExceptions.NotFoundException.class);

        // Their order, correctly signed, still cannot be verified by someone else.
        paymentStore.add(new Payment(theirs.getId(), "order_THEIRS", 150_000, "INR", NOW));
        assertThatThrownBy(() -> service.verify(userId, id, signed("order_THEIRS", "pay_1")))
                .isInstanceOf(BookingExceptions.NotFoundException.class);
        assertThat(theirs.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(paymentStore.get(0).getStatus()).isEqualTo(PaymentStatus.CREATED);
        verify(gateway, never()).createOrder(anyLong(), anyString(), anyString());
    }

    @Test
    void withoutKeysThePaymentEndpointsSayPaymentsAreNotConfigured() {
        BookingPaymentService unconfigured = new BookingPaymentService(bookings, paymentRepository, professionals,
                new RazorpayGateway(" ", null), events, CLOCK);
        Booking b = awaiting();

        assertThatThrownBy(() -> unconfigured.createOrder(userId, b.getId().toString()))
                .isInstanceOf(RazorpayGateway.NotConfiguredException.class)
                .hasMessage("Payments are not configured. Please try again later.");
        assertThatThrownBy(() -> unconfigured.verify(userId, b.getId().toString(), signed("order_1", "pay_1")))
                .isInstanceOf(RazorpayGateway.NotConfiguredException.class);
    }

    // ---- verifying ----

    @Test
    void aValidSignatureMarksThePaymentPaidAndConfirmsTheBooking() throws Exception {
        Booking b = awaiting();
        service.createOrder(userId, b.getId().toString());

        BookingView view = service.verify(userId, b.getId().toString(), signed("order_TEST123", "pay_ABC"));

        assertThat(view.status()).isEqualTo("CONFIRMED");
        assertThat(view.paymentDueAt()).isNull();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        Payment p = paymentStore.get(0);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.getRazorpayPaymentId()).isEqualTo("pay_ABC");
        assertThat(p.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    void aWrongOrMissingSignatureIsRejected() throws Exception {
        Booking b = awaiting();
        service.createOrder(userId, b.getId().toString());
        String id = b.getId().toString();
        String good = sign("order_TEST123", "pay_ABC");

        List<VerifyPaymentRequest> bad = List.of(
                // Signed for a different payment id, for a different order, with another secret, or tampered.
                new VerifyPaymentRequest("order_TEST123", "pay_OTHER", good),
                new VerifyPaymentRequest("order_TEST123", "pay_ABC", sign("order_OTHER", "pay_ABC")),
                new VerifyPaymentRequest("order_TEST123", "pay_ABC", good.substring(1) + "0"),
                new VerifyPaymentRequest("order_TEST123", "pay_ABC", good.toUpperCase()),
                new VerifyPaymentRequest("order_TEST123", "pay_ABC", "not-a-signature"));
        for (VerifyPaymentRequest r : bad) {
            assertThatThrownBy(() -> service.verify(userId, id, r))
                    .isInstanceOf(ApiValidationException.class).hasMessage(BookingPaymentService.NOT_VERIFIED_MESSAGE);
        }
        for (VerifyPaymentRequest r : List.of(new VerifyPaymentRequest("order_TEST123", "pay_ABC", null),
                new VerifyPaymentRequest("order_TEST123", "pay_ABC", "  "),
                new VerifyPaymentRequest(null, "pay_ABC", good))) {
            assertThatThrownBy(() -> service.verify(userId, id, r)).isInstanceOf(ApiValidationException.class)
                    .hasMessage("razorpay_order_id, razorpay_payment_id and razorpay_signature are required.");
        }
        assertThatThrownBy(() -> service.verify(userId, id, null)).isInstanceOf(ApiValidationException.class);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(paymentStore.get(0).getStatus()).isEqualTo(PaymentStatus.CREATED);
        verify(paymentRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void anOrderOfAnotherBookingIsRejected() throws Exception {
        Booking mine = awaiting();
        Booking myOther = awaiting();
        paymentStore.add(new Payment(myOther.getId(), "order_OTHER_BOOKING", 150_000, "INR", NOW));

        assertThatThrownBy(() -> service.verify(userId, mine.getId().toString(), signed("order_OTHER_BOOKING", "pay_1")))
                .isInstanceOf(ApiValidationException.class).hasMessage(BookingPaymentService.NOT_VERIFIED_MESSAGE);
        // And an order id we never created.
        assertThatThrownBy(() -> service.verify(userId, mine.getId().toString(), signed("order_UNKNOWN", "pay_1")))
                .isInstanceOf(ApiValidationException.class);
        assertThat(mine.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(myOther.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(paymentStore.get(0).getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void verifyingTwiceIsSafe() throws Exception {
        Booking b = awaiting();
        service.createOrder(userId, b.getId().toString());
        VerifyPaymentRequest r = signed("order_TEST123", "pay_ABC");

        BookingView first = service.verify(userId, b.getId().toString(), r);
        BookingView second = service.verify(userId, b.getId().toString(), r);

        assertThat(second).isEqualTo(first);
        assertThat(second.status()).isEqualTo("CONFIRMED");
        // The confirmation emails' event goes out once, on the call that actually confirmed.
        verify(events, times(1)).publishEvent(new BookingEvent(BookingEvent.Type.CONFIRMED, b.getId()));
        verifyNoMoreInteractions(events);
        // The payment was saved twice in all: once created, once paid.
        verify(paymentRepository, times(2)).saveAndFlush(any());
        verify(bookings, times(1)).saveAndFlush(any());
        assertThat(paymentStore).singleElement().satisfies(p -> {
            assertThat(p.getRazorpayPaymentId()).isEqualTo("pay_ABC");
            assertThat(p.getPaidAt()).isEqualTo(NOW);
        });

        // A different, also genuine, payment on the same paid order is refused and changes nothing.
        assertThatThrownBy(() -> service.verify(userId, b.getId().toString(), signed("order_TEST123", "pay_SECOND")))
                .isInstanceOf(ConflictException.class).hasMessage("This booking has already been paid.");
        assertThat(paymentStore.get(0).getRazorpayPaymentId()).isEqualTo("pay_ABC");
    }

    @Test
    void aGenuinePaymentForABookingNoLongerAwaitingPaymentIsKeptButDoesNotConfirm() throws Exception {
        for (BookingStatus status : List.of(BookingStatus.EXPIRED, BookingStatus.CANCELLED)) {
            Booking b = awaiting();
            Payment p = new Payment(b.getId(), "order_" + status, 150_000, "INR", NOW);
            paymentStore.add(p);
            // Expired or cancelled while the user was in the checkout.
            b.setStatus(status);

            assertThatThrownBy(() -> service.verify(userId, b.getId().toString(), signed("order_" + status, "pay_" + status)))
                    .isInstanceOf(BookingExceptions.PaidButNotConfirmedException.class)
                    .hasMessageContaining("will be refunded");
            assertThat(b.getStatus()).isEqualTo(status);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(p.getRazorpayPaymentId()).isEqualTo("pay_" + status);
            verify(paymentRepository).saveAndFlush(p);

            // Calling it again gives the same answer and changes nothing.
            assertThatThrownBy(() -> service.verify(userId, b.getId().toString(), signed("order_" + status, "pay_" + status)))
                    .isInstanceOf(BookingExceptions.PaidButNotConfirmedException.class);
            assertThat(b.getStatus()).isEqualTo(status);
        }
        verify(bookings, never()).saveAndFlush(any());
        // Not confirmed, so no confirmation email.
        verifyNoInteractions(events);
    }

    @Test
    void thePaidButNotConfirmedErrorDoesNotRollBackTheSavedPayment() throws Exception {
        var verify = BookingPaymentService.class.getMethod("verify", UUID.class, String.class, VerifyPaymentRequest.class);
        var tx = verify.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx.noRollbackFor()).containsExactly(BookingExceptions.PaidButNotConfirmedException.class);
    }
}
