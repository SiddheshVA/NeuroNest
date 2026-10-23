package com.neuronest.backend.notification;

import java.util.UUID;

/**
 * Published by the booking services after a change is saved. Only the type and the booking id travel
 * with it; the listener reads everything else after the transaction has committed.
 */
public record BookingEvent(Type type, UUID bookingId) {

    public enum Type {
        // AWAITING_PAYMENT: the time is agreed (the professional confirmed, or the user accepted a suggested
        // time) and the user has to pay. CONFIRMED: the payment was verified.
        REQUEST_CREATED, AWAITING_PAYMENT, CONFIRMED, PROPOSED, DECLINED, CANCELLED_BY_USER,
        // From the background jobs: a request expired, or an agreed booking was not paid in time (user and
        // professional), the one retry of the user's expiry email on the next run, and the 24-hour
        // reminder to the professional.
        EXPIRED, EXPIRY_RETRY, REMINDER,
        // An admin closed a request: the user gets the same email as a normal expiry.
        CLOSED_BY_ADMIN
    }
}
