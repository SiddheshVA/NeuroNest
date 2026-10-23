package com.neuronest.backend.booking;

import java.util.Set;

// booking_requests.status (the table's CHECK constraint lists exactly these).
public enum BookingStatus {
    // AWAITING_PAYMENT: the time is agreed and the user has until payment_due_at to pay. CONFIRMED means
    // paid and confirmed.
    PENDING, CONFIRMED, DECLINED, ALTERNATE_PROPOSED, CANCELLED, EXPIRED, AWAITING_PAYMENT;

    /** Waiting for a reply: these count towards the open-request limits until they expire. */
    public static final Set<BookingStatus> OPEN = Set.of(PENDING, ALTERNATE_PROPOSED);

    /** A time that is agreed: these block their slot everywhere availability is worked out. */
    public static final Set<BookingStatus> BLOCKS_SLOT = Set.of(AWAITING_PAYMENT, CONFIRMED);
}
