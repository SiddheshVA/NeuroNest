package com.neuronest.backend.pro;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.SharedAssessment;
import com.neuronest.backend.professional.AvailabilityService;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Request and response shapes for /api/pro/bookings. Entities are never serialized. A view never contains
// the user id, the Clerk id, the session id or the admin note, and the user's email and phone only
// while a confirmed appointment is current (see ProBookingView.from).
public final class ProBookingDtos {

    private ProBookingDtos() {}

    /** How long after a confirmed appointment's scheduled time the professional can still see the contact details. */
    public static final Duration CONTACT_VISIBLE_FOR = Duration.ofDays(7);

    // Everything arrives as text so a wrong value gets a readable 400 from the service.
    public record ConfirmRequest(String chosenSlot, String meetingMethod, String meetingLink, String meetingAddress,
                                 String note) {}

    public record DeclineRequest(String note) {}

    // proposedSlot is ISO-8601 with an offset, for example 2026-10-20T18:00:00+05:30.
    public record ProposeRequest(String proposedSlot, String meetingMethod, String meetingLink, String meetingAddress,
                                 String note) {}

    public record ProBookingView(
            UUID id,
            String status,
            Instant createdAt,
            Instant expiresAt,
            String contactName,
            String preferredMode,
            String preferredMethod,
            // Shown in Indian Standard Time, for example 2026-10-20T18:00+05:30.
            OffsetDateTime preferredDatetime,
            OffsetDateTime alternateDatetime,
            OffsetDateTime proposedDatetime,
            String note,
            String professionalNote,
            // Exactly what the user chose to share, or null.
            SharedAssessment sharedAssessment,
            Instant assessmentSharedAt,
            String meetingMethod,
            String meetingLink,
            String meetingAddress,
            Instant respondedAt,
            // Only for a CONFIRMED booking whose scheduled time is at most 7 days in the past; null otherwise.
            String contactEmail,
            String contactPhone) {

        static ProBookingView from(Booking b, Instant now) {
            boolean contactVisible = contactVisible(b, now);
            return new ProBookingView(b.getId(), b.getStatus().name(), b.getCreatedAt(), b.getExpiresAt(),
                    b.getContactName(), b.getPreferredMode(), b.getPreferredMethod(),
                    inIndia(b.getPreferredDatetime()), inIndia(b.getAlternateDatetime()),
                    inIndia(b.getProposedDatetime()), b.getNote(), b.getProfessionalNote(),
                    b.getSharedAssessment(), b.getAssessmentSharedAt(),
                    b.getMeetingMethod(), b.getMeetingLink(), b.getMeetingAddress(), b.getRespondedAt(),
                    contactVisible ? b.getContactEmail() : null,
                    contactVisible ? b.getContactPhone() : null);
        }

        static boolean contactVisible(Booking b, Instant now) {
            if (b.getStatus() != BookingStatus.CONFIRMED) return false;
            Instant scheduled = b.scheduledTime();
            return scheduled != null && !scheduled.isBefore(now.minus(CONTACT_VISIBLE_FOR));
        }

        private static OffsetDateTime inIndia(Instant instant) {
            return instant == null ? null : instant.atZone(AvailabilityService.ZONE).toOffsetDateTime();
        }
    }

    public record ProBookingPage(List<ProBookingView> items, int page, int size, long totalElements, int totalPages) {}

    /** A CONFIRMED appointment. The contact details follow the same rule as ProBookingView. */
    public record ProAppointmentView(
            UUID id,
            String contactName,
            String preferredMode,
            String meetingMethod,
            String meetingLink,
            String meetingAddress,
            OffsetDateTime scheduledTime,
            String professionalNote,
            String contactEmail,
            String contactPhone) {

        static ProAppointmentView from(Booking b, Instant now) {
            ProBookingView view = ProBookingView.from(b, now);
            return new ProAppointmentView(view.id(), view.contactName(), view.preferredMode(), view.meetingMethod(),
                    view.meetingLink(), view.meetingAddress(), view.preferredDatetime(), view.professionalNote(),
                    view.contactEmail(), view.contactPhone());
        }
    }

    public record ProAppointmentPage(List<ProAppointmentView> items, int page, int size, long totalElements,
                                     int totalPages) {}

    /** pending: PENDING requests that have not expired. awaitingUser: ALTERNATE_PROPOSED requests that have not expired. */
    public record ProBookingSummary(long pending, long awaitingUser) {}
}
