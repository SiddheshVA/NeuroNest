package com.neuronest.backend.booking;

import com.fasterxml.jackson.annotation.JsonProperty;
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
            Instant assessmentSharedAt,
            // The deadline to pay while the status is AWAITING_PAYMENT; null otherwise.
            Instant paymentDueAt) {

        static BookingView from(Booking b, Professional p) {
            boolean confirmed = b.getStatus() == BookingStatus.CONFIRMED;
            return new BookingView(b.getId(), b.getStatus().name(), b.getCreatedAt(), b.getExpiresAt(),
                    b.getPreferredMode(), inIndia(b.getPreferredDatetime()), inIndia(b.getAlternateDatetime()),
                    inIndia(b.getProposedDatetime()), b.getProfessionalNote(), b.getRespondedAt(),
                    ProfessionalSummary.from(p), b.getPreferredMethod(),
                    confirmed ? b.getMeetingMethod() : null,
                    confirmed ? b.getMeetingLink() : null,
                    confirmed ? b.getMeetingAddress() : null,
                    b.getSharedAssessment(), b.getAssessmentSharedAt(),
                    b.getStatus() == BookingStatus.AWAITING_PAYMENT ? b.getPaymentDueAt() : null);
        }

        static OffsetDateTime inIndia(Instant instant) {
            return instant == null ? null : instant.atZone(AvailabilityService.ZONE).toOffsetDateTime();
        }
    }

    public record BookingPage(List<BookingView> items, int page, int size, long totalElements, int totalPages) {}

    /**
     * The signed-in user's next confirmed appointment. The meeting details are the ones the professional
     * set for this user; the professional's own contact details are never included.
     */
    public record NextAppointment(
            UUID id,
            // Shown in Indian Standard Time, for example 2026-10-20T18:00+05:30.
            OffsetDateTime scheduledTime,
            String preferredMode,
            String meetingMethod,
            String meetingLink,
            String meetingAddress,
            ProfessionalSummary professional) {

        static NextAppointment from(Booking b, Professional p) {
            return new NextAppointment(b.getId(), BookingView.inIndia(b.getPreferredDatetime()), b.getPreferredMode(),
                    b.getMeetingMethod(), b.getMeetingLink(), b.getMeetingAddress(), ProfessionalSummary.from(p));
        }
    }

    /**
     * An agreed booking the user still has to pay for, so the dashboard can show "Pay now". No meeting
     * details: those are only shown once it is paid and CONFIRMED.
     */
    public record AwaitingPayment(
            UUID id,
            // Shown in Indian Standard Time, for example 2026-10-20T18:00+05:30.
            OffsetDateTime scheduledTime,
            Instant paymentDueAt,
            String preferredMode,
            ProfessionalSummary professional) {

        static AwaitingPayment from(Booking b, Professional p) {
            return new AwaitingPayment(b.getId(), BookingView.inIndia(b.getPreferredDatetime()), b.getPaymentDueAt(),
                    b.getPreferredMode(), ProfessionalSummary.from(p));
        }
    }

    /**
     * GET /api/bookings/summary: the next (paid) appointment, the bookings waiting for payment, and counts
     * of open requests that have not expired.
     */
    public record BookingSummary(NextAppointment nextAppointment, List<AwaitingPayment> awaitingPayment,
                                 long waitingForProfessional, long needsYourAnswer) {}

    /**
     * POST /api/bookings/{id}/payment/order: what the Razorpay checkout needs. amount is in paise and
     * always comes from the professional's fee in the database. keyId is the public key, never the secret.
     */
    public record PaymentOrder(String razorpayOrderId, long amount, String currency, String keyId) {}

    /** POST /api/bookings/{id}/payment/verify: the three values the Razorpay checkout hands back, as it names them. */
    public record VerifyPaymentRequest(
            @JsonProperty("razorpay_order_id") String razorpayOrderId,
            @JsonProperty("razorpay_payment_id") String razorpayPaymentId,
            @JsonProperty("razorpay_signature") String razorpaySignature) {}
}
