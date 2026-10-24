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
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Paying for an AWAITING_PAYMENT booking with Razorpay: creating the order and verifying the checkout's
// signature, which confirms the booking. Only the booking's own user may do either; anyone else's booking
// gets the same 404 as a missing one. The amount always comes from the professional's fee in the database.
// Nothing here logs the key secret, a signature or the user's details.
@Service
public class BookingPaymentService {

    static final String NOT_AWAITING_MESSAGE = "This booking is not waiting for payment.";
    static final String PAST_DUE_MESSAGE = "The time to pay for this booking has passed.";
    static final String NO_FEE_MESSAGE = "This professional has not set a consultation fee, so this booking cannot "
            + "be paid online.";
    static final String NOT_VERIFIED_MESSAGE = "The payment could not be verified.";

    private static final Logger log = LoggerFactory.getLogger(BookingPaymentService.class);

    /**
     * Whether booking this professional needs a payment. consultation_fee may be empty or 0 (a free
     * session); then an agreed time goes straight to CONFIRMED.
     */
    public static boolean requiresPayment(Professional p) {
        return PaymentAmounts.fromFee(p).isPresent();
    }

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final ProfessionalRepository professionals;
    private final RazorpayGateway gateway;
    private final ApplicationEventPublisher events;
    private final PaymentAmounts amounts;
    private final CommissionSplit commission;
    private final Clock clock;

    @Autowired
    public BookingPaymentService(BookingRepository bookings, PaymentRepository payments,
                                 ProfessionalRepository professionals, RazorpayGateway gateway,
                                 ApplicationEventPublisher events, PaymentAmounts amounts, CommissionSplit commission) {
        this(bookings, payments, professionals, gateway, events, amounts, commission,
                Clock.system(AvailabilityService.ZONE));
    }

    BookingPaymentService(BookingRepository bookings, PaymentRepository payments, ProfessionalRepository professionals,
                          RazorpayGateway gateway, ApplicationEventPublisher events, PaymentAmounts amounts,
                          CommissionSplit commission, Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.professionals = professionals;
        this.gateway = gateway;
        this.events = events;
        this.amounts = amounts;
        this.commission = commission;
        this.clock = clock;
    }

    // ---- Creating the order ----

    // The booking row is locked, so two quick clicks reuse one order instead of creating two.
    @Transactional
    public PaymentOrder createOrder(UUID userId, String id) {
        requireConfigured();
        Booking b = lockedOwnBooking(userId, id);
        Instant now = clock.instant();
        if (b.getStatus() != BookingStatus.AWAITING_PAYMENT) throw new ConflictException(NOT_AWAITING_MESSAGE);
        if (b.getPaymentDueAt() == null || !b.getPaymentDueAt().isAfter(now)) {
            throw new ConflictException(PAST_DUE_MESSAGE);
        }

        // The same rule the booking view and the summary use to show the amount (PaymentAmounts).
        Optional<Payment> unpaid = amounts.unpaidOrder(b);
        if (unpaid.isPresent()) return order(unpaid.get());

        PaymentAmounts.Amount amount = PaymentAmounts.fromFee(professionals.findById(b.getProfessionalId()).orElse(null))
                .orElseThrow(() -> new ConflictException(NO_FEE_MESSAGE));
        String orderId = gateway.createOrder(amount.paise(), amount.currency(), b.getId().toString());
        return order(payments.saveAndFlush(new Payment(b.getId(), orderId, amount.paise(), amount.currency(), now)));
    }

    private PaymentOrder order(Payment p) {
        return new PaymentOrder(p.getRazorpayOrderId(), p.getAmountPaise(), p.getCurrency(), gateway.keyId());
    }

    // ---- Verifying ----

    // The booking row and then the payment row are locked, so two verify calls run one after the other:
    // the second sees the payment already PAID with the same payment id and returns the same answer.
    // PaidButNotConfirmedException must not roll back: the PAID payment has to be kept for the refund.
    @Transactional(noRollbackFor = BookingExceptions.PaidButNotConfirmedException.class)
    public BookingView verify(UUID userId, String id, VerifyPaymentRequest r) {
        requireConfigured();
        String orderId = trimToNull(r == null ? null : r.razorpayOrderId());
        String paymentId = trimToNull(r == null ? null : r.razorpayPaymentId());
        String signature = trimToNull(r == null ? null : r.razorpaySignature());
        if (orderId == null || paymentId == null || signature == null) {
            throw new ApiValidationException("razorpay_order_id, razorpay_payment_id and razorpay_signature are required.");
        }
        Booking b = lockedOwnBooking(userId, id);
        if (!gateway.isValidSignature(orderId, paymentId, signature)) {
            throw new ApiValidationException(NOT_VERIFIED_MESSAGE);
        }
        Payment payment = payments.findByRazorpayOrderIdForUpdate(orderId)
                .filter(p -> b.getId().equals(p.getBookingId()))
                .orElseThrow(() -> new ApiValidationException(NOT_VERIFIED_MESSAGE));

        if (payment.getStatus() == PaymentStatus.PAID) {
            if (!paymentId.equals(payment.getRazorpayPaymentId())) {
                // A second successful payment on an order that was already paid.
                log.warn("MANUAL REFUND NEEDED: second payment {} on already paid order {} for booking {}",
                        paymentId, orderId, b.getId());
                throw new ConflictException("This booking has already been paid.");
            }
            // The same call again: answer as the first one did.
            if (b.getStatus() == BookingStatus.CONFIRMED) return view(b);
            throw new BookingExceptions.PaidButNotConfirmedException();
        }

        Instant now = clock.instant();
        // The commission is fixed now, at the configured percent, and stored with the payment.
        CommissionSplit.Split split = commission.split(payment.getAmountPaise());
        payment.markPaid(paymentId, now, split.percent(), split.commissionPaise(), split.professionalSharePaise());
        payments.saveAndFlush(payment);

        if (b.getStatus() != BookingStatus.AWAITING_PAYMENT) {
            log.warn("MANUAL REFUND NEEDED: payment {} (order {}) for booking {} arrived while the booking was {}",
                    paymentId, orderId, b.getId(), b.getStatus().name());
            throw new BookingExceptions.PaidButNotConfirmedException();
        }
        b.setStatus(BookingStatus.CONFIRMED);
        Booking saved = bookings.saveAndFlush(b);
        // Only here, where the booking actually becomes CONFIRMED: a repeated verify returns earlier, so
        // the confirmation emails go out once (after the commit, BookingNotificationListener).
        events.publishEvent(new BookingEvent(BookingEvent.Type.CONFIRMED, saved.getId()));
        return view(saved);
    }

    // ---- Lookups ----

    private void requireConfigured() {
        if (!gateway.isConfigured()) throw new RazorpayGateway.NotConfiguredException();
    }

    private Booking lockedOwnBooking(UUID userId, String id) {
        return parseId(id).flatMap(bookings::findByIdForUpdate)
                .filter(found -> userId.equals(found.getUserId()))
                .orElseThrow(() -> new BookingExceptions.NotFoundException("This booking request was not found."));
    }

    // A confirmed booking's view includes what was paid (its receipt).
    private BookingView view(Booking b) {
        return BookingView.from(b, professionals.findById(b.getProfessionalId()).orElse(null), null,
                amounts.paid(b).orElse(null));
    }

    private static Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
