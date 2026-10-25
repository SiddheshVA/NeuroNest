package com.neuronest.backend.booking;

import com.neuronest.backend.payment.Payment;
import com.neuronest.backend.payment.PaymentRepository;
import com.neuronest.backend.payment.PaymentStatus;
import com.neuronest.backend.professional.Professional;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * THE amount a booking costs, in one place: the payment order endpoint charges it, and the booking view
 * and the summary show it, so the shown amount can never differ from the charged one. An unpaid order
 * already made for the booking keeps its amount (the order endpoint reuses it); otherwise it is the
 * professional's consultation_fee (whole rupees, per session) in paise times the booking's number of
 * sessions. Empty for a free session (fee empty or 0).
 */
@Component
public class PaymentAmounts {

    public static final String CURRENCY = "INR";

    public record Amount(long paise, String currency) {}

    private final PaymentRepository payments;

    public PaymentAmounts(PaymentRepository payments) {
        this.payments = payments;
    }

    /** What paying for this booking would charge now. */
    public Optional<Amount> due(Booking b, Professional p) {
        Optional<Payment> unpaid = unpaidOrder(b);
        if (unpaid.isPresent()) return unpaid.map(o -> new Amount(o.getAmountPaise(), o.getCurrency()));
        return forBooking(b, p);
    }

    /** The price of this booking from the fee: fee per session times the number of sessions. */
    public static Optional<Amount> forBooking(Booking b, Professional p) {
        int sessions = Math.max(1, b.getSessionCount());
        return fromFee(p).map(perSession -> new Amount(perSession.paise() * sessions, perSession.currency()));
    }

    /** The newest unpaid (CREATED) order for this booking, which the order endpoint returns instead of a new one. */
    public Optional<Payment> unpaidOrder(Booking b) {
        return payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(b.getId(), PaymentStatus.CREATED);
    }

    /**
     * What was actually paid for a CONFIRMED booking, from its PAID payment row (a receipt). Empty for any
     * other status, a free session, or an older confirmed booking made before payments existed.
     */
    public Optional<Amount> paid(Booking b) {
        if (b.getStatus() != BookingStatus.CONFIRMED) return Optional.empty();
        return payments.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(b.getId(), PaymentStatus.PAID)
                .map(p -> new Amount(p.getAmountPaise(), p.getCurrency()));
    }

    /** The professional's fee in paise, or empty when it is empty or 0 (a free session). */
    public static Optional<Amount> fromFee(Professional p) {
        Integer fee = p == null ? null : p.getConsultationFee();
        return fee == null || fee <= 0 ? Optional.empty() : Optional.of(new Amount(fee * 100L, CURRENCY));
    }
}
