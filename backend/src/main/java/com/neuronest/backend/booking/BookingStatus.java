package com.neuronest.backend.booking;

import java.util.Set;

// booking_requests.status (the table's CHECK constraint lists exactly these).
public enum BookingStatus {
    PENDING, CONFIRMED, DECLINED, ALTERNATE_PROPOSED, CANCELLED, EXPIRED;

    /** Waiting for a reply: these count towards the open-request limits until they expire. */
    public static final Set<BookingStatus> OPEN = Set.of(PENDING, ALTERNATE_PROPOSED);
}
