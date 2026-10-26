package com.neuronest.backend.booking;

import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingDtos.PaymentOrder;
import com.neuronest.backend.booking.BookingDtos.VerifyPaymentRequest;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
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
    private final BookingService bookingService;
    private final PaymentAmounts amounts;
    private final CommissionSplit commission;
    private final Clock clock;

    @Autowired
    public BookingPaymentService(BookingRepository bookings, PaymentRepository payments,
                                 ProfessionalRepository professionals, RazorpayGateway gateway,
                                 BookingService bookingService, PaymentAmounts amounts, CommissionSplit commission) {
        this(bookings, payments, professionals, gateway, bookingService, amounts, commission,
                Clock.system(AvailabilityService.ZONE));
    }

    BookingPaymentService(BookingRepository bookings, PaymentRepository payments, ProfessionalRepository professionals,
                          RazorpayGateway gateway, BookingService bookingService, PaymentAmounts amounts,
                          CommissionSplit commission, Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.professionals = professionals;
        this.gateway = gateway;
        this.bookingService = bookingService;
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

        PaymentAmounts.Amount amount = PaymentAmounts.forBooking(b, professionals.findById(b.getProfessionalId()).orElse(null))
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
            // The same call again (or the webhook got there first): answer as the first one did.
            if (b.getStatus() == BookingStatus.CONFIRMED) return view(b);
            throw new BookingExceptions.PaidButNotConfirmedException();
        }

        Booking saved = markPaidAndConfirm(b, payment, paymentId);
        if (saved == null) throw new BookingExceptions.PaidButNotConfirmedException();
        return view(saved);
    }

    // ---- The Razorpay webhook (RazorpayWebhookService; the signature is already checked) ----

    /**
     * A captured payment reported by Razorpay's webhook, so a paid booking is confirmed even when the
     * checkout tab was closed before its callback ran. Takes the same locks in the same order as verify
     * (booking, then payment), so the two can never both confirm; a repeated or late call changes nothing.
     */
    @Transactional
    public void confirmFromWebhook(String orderId, String paymentId) {
        if (orderId == null || paymentId == null) return;
        Optional<UUID> bookingId = payments.findBookingIdByRazorpayOrderId(orderId);
        if (bookingId.isEmpty()) {
            log.warn("Razorpay webhook: payment {} for unknown order {}", paymentId, orderId);
            return;
        }
        Booking b = bookings.findByIdForUpdate(bookingId.get()).orElse(null);
        Payment payment = payments.findByRazorpayOrderIdForUpdate(orderId).orElse(null);
        if (b == null || payment == null) return;
        if (payment.getStatus() == PaymentStatus.PAID) {
            if (!paymentId.equals(payment.getRazorpayPaymentId())) {
                log.warn("MANUAL REFUND NEEDED: second payment {} on already paid order {} for booking {}",
                        paymentId, orderId, b.getId());
            }
            return;
        }
        markPaidAndConfirm(b, payment, paymentId);
    }

    /**
     * A failed payment attempt reported by the webhook: the order is marked FAILED (a later successful
     * attempt on it still confirms the booking). The hold itself is released by the user cancelling or
     * by the hold-release job once payment_due_at has passed.
     */
    @Transactional
    public void recordFailedFromWebhook(String orderId) {
        if (orderId == null) return;
        payments.findByRazorpayOrderIdForUpdate(orderId)
                .filter(p -> p.getStatus() == PaymentStatus.CREATED)
                .ifPresent(p -> {
                    p.setStatus(PaymentStatus.FAILED);
                    payments.saveAndFlush(p);
                });
    }

    // Marks the locked payment PAID and, if the booking is still held and its session has not started,
    // CONFIRMED. Returns the confirmed booking, or null when the payment arrived after the hold ended or once
    // the session had already started (a last-minute slot): kept PAID for a manual refund either way, and the
    // admin's refunds-needed list shows it (a PAID payment whose booking is not CONFIRMED).
    private Booking markPaidAndConfirm(Booking b, Payment payment, String paymentId) {
        Instant now = clock.instant();
        // The commission is fixed now, at the configured percent, and stored with the payment.
        CommissionSplit.Split split = commission.split(payment.getAmountPaise());
        payment.markPaid(paymentId, now, split.percent(), split.commissionPaise(), split.professionalSharePaise());
        payments.saveAndFlush(payment);

        if (b.getStatus() != BookingStatus.AWAITING_PAYMENT) {
            log.warn("MANUAL REFUND NEEDED: payment {} (order {}) for booking {} arrived while the booking was {}",
                    paymentId, payment.getRazorpayOrderId(), b.getId(), b.getStatus().name());
            return null;
        }
        if (b.getPreferredDatetime() != null && !now.isBefore(b.getPreferredDatetime())) {
            // The hold is capped at the slot's start, so this is a payment that came in after the hold ended,
            // during the hold-release grace period. The hold-release job releases the booking.
            log.warn("MANUAL REFUND NEEDED: payment {} (order {}) for booking {} arrived after its session had started",
                    paymentId, payment.getRazorpayOrderId(), b.getId());
            return null;
        }
        b.setStatus(BookingStatus.CONFIRMED);
        Booking saved = bookings.saveAndFlush(b);
        // Only here, where the booking actually becomes CONFIRMED: a repeated verify or webhook returns
        // earlier, so the Meet link and the confirmation emails are handled once (after the commit).
        bookingService.onPaymentConfirmed(saved.getId());
        return saved;
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
