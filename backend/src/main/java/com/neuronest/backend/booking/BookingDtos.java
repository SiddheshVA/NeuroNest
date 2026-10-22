package com.neuronest.backend.booking;

import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Request and response shapes for /api/bookings. Entities are never serialized. A booking view never
// contains the professional's contact details, the admin note, or the user's own contact fields.
public final class BookingDtos {

    private BookingDtos() {}

    // Everything arrives as text so a wrong value gets a readable 400 from the service. The user and the
    // contact email are never taken from the body; a free-text preferredWindow is not accepted (unknown
    // fields are ignored). preferredMethod (PHONE or VIDEO) is for ONLINE requests only.
    public record CreateBookingRequest(
            String professionalId,
            String preferredMode,
            String contactName,
            String contactPhone,
            String slotStart,
            String alternateSlotStart,
            String note,
            Boolean consentGiven,
            String preferredMethod,
            Boolean shareLatestAssessment) {}

    public record ProfessionalSummary(UUID id, String fullName, String credentialType, String city) {
        static ProfessionalSummary from(Professional p) {
            return p == null ? null
                    : new ProfessionalSummary(p.getId(), p.getFullName(), p.getCredentialType().name(), p.getCity());
        }
    }

    public record BookingView(
            UUID id,
            String status,
            Instant createdAt,
            Instant expiresAt,
            String preferredMode,
            // Shown in Indian Standard Time, for example 2026-10-20T18:00+05:30.
            OffsetDateTime preferredDatetime,
            OffsetDateTime alternateDatetime,
            OffsetDateTime proposedDatetime,
            String professionalNote,
            Instant respondedAt,
            ProfessionalSummary professional,
            String preferredMethod,
            // The meeting details are only shown once the request is CONFIRMED; null otherwise.
            String meetingMethod,
            String meetingLink,
            String meetingAddress,
            // Exactly what the user chose to share with the professional, or null.
            SharedAssessment sharedAssessment,
            Instant assessmentSharedAt) {

        static BookingView from(Booking b, Professional p) {
            boolean confirmed = b.getStatus() == BookingStatus.CONFIRMED;
            return new BookingView(b.getId(), b.getStatus().name(), b.getCreatedAt(), b.getExpiresAt(),
                    b.getPreferredMode(), inIndia(b.getPreferredDatetime()), inIndia(b.getAlternateDatetime()),
                    inIndia(b.getProposedDatetime()), b.getProfessionalNote(), b.getRespondedAt(),
                    ProfessionalSummary.from(p), b.getPreferredMethod(),
                    confirmed ? b.getMeetingMethod() : null,
                    confirmed ? b.getMeetingLink() : null,
                    confirmed ? b.getMeetingAddress() : null,
                    b.getSharedAssessment(), b.getAssessmentSharedAt());
        }

        private static OffsetDateTime inIndia(Instant instant) {
            return instant == null ? null : instant.atZone(AvailabilityService.ZONE).toOffsetDateTime();
        }
    }

    public record BookingPage(List<BookingView> items, int page, int size, long totalElements, int totalPages) {}
}
