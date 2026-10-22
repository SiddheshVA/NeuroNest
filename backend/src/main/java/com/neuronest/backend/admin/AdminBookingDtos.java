package com.neuronest.backend.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

// Shapes for the admin's booking overview and actions. A booking item never contains the user's email or
// phone, the request note, the professional's note, the shared assessment, the meeting link or address,
// the user id or the session id.
public final class AdminBookingDtos {

    private AdminBookingDtos() {}

    public record DashboardSummary(
            long reviewQueue,
            long reviewQueueOver48h,
            long unansweredOver48h,
            long pendingTotal,
            long awaitingUserTotal,
            long upcomingConfirmed,
            long professionalsVerifiedActive,
            long professionalsNeedsInfo,
            long registrationsExpiringIn30Days,
            Last30Days last30Days) {}

    /** Requests created in the last 30 days, and what has happened to them. */
    public record Last30Days(
            long requestsCreated,
            long answered,
            long expired,
            long cancelledByUser,
            double responseRatePercent,
            Double medianHoursToFirstResponse) {}

    public record AdminBookingItem(
            UUID id,
            String status,
            Instant createdAt,
            Instant expiresAt,
            // From creation until the first response, or until now when there has been none.
            long hoursWaiting,
            UUID professionalId,
            String professionalName,
            String contactName,
            String preferredMode,
            String preferredMethod,
            // Shown in Indian Standard Time.
            OffsetDateTime scheduledOrRequestedTime,
            OffsetDateTime proposedDatetime,
            String respondedBy,
            Instant respondedAt,
            Instant adminNudgedAt,
            String adminNote) {}

    public record ResponsivenessItem(
            UUID professionalId,
            String fullName,
            long received,
            long answered,
            long expired,
            long pendingNow,
            Double medianHoursToFirstResponse,
            Instant lastAnsweredAt) {}

    public record CloseRequest(String note) {}

    public record StatusNoteRequest(String note) {}
}
