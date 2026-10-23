package com.neuronest.backend.pro;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Shapes for the professional's home summary and days off. Entities are never serialized.
public final class ProHomeDtos {

    private ProHomeDtos() {}

    // Dates as text (YYYY-MM-DD, India's calendar) so a wrong value gets a readable 400.
    public record DayOffRequest(String dateFrom, String dateTo, String reason) {}

    /** The reason is private: it only ever goes back to the professional who wrote it. */
    public record DayOffView(UUID id, LocalDate dateFrom, LocalDate dateTo, String reason, Instant createdAt) {}

    /**
     * A saved day-off entry and the bookings that fall on those dates. Nothing is cancelled or changed:
     * the professional decides what to do with them.
     */
    public record DayOffCreated(UUID id, LocalDate dateFrom, LocalDate dateTo, String reason, Instant createdAt,
                                long affectedConfirmed, long affectedAwaitingPayment, long affectedPending) {}

    public record NextAppointment(UUID id, String contactName, OffsetDateTime scheduledTime, String meetingMethod) {}

    /**
     * A booking the professional accepted that is waiting for the user's payment. No contact details:
     * those follow ProBookingView.contactVisible (CONFIRMED only).
     */
    public record AwaitingPaymentItem(UUID id, String contactName, OffsetDateTime scheduledTime, String meetingMethod,
                                      Instant paymentDueAt) {}

    public record Warning(String code, String message) {}

    public record ProDashboard(
            String verificationStatus,
            boolean active,
            String accountStatus,
            long pendingRequests,
            long awaitingUser,
            long upcomingConfirmed,
            // Accepted, waiting for the user's payment (deadline not passed). Not in upcomingConfirmed or
            // nextAppointment, which are paid bookings only.
            long awaitingPayment,
            List<AwaitingPaymentItem> awaitingPaymentBookings,
            NextAppointment nextAppointment,
            LocalDate registrationExpiry,
            Long registrationDaysLeft,
            boolean hasWeeklyHours,
            long daysOffCount,
            List<Warning> warnings) {}
}
