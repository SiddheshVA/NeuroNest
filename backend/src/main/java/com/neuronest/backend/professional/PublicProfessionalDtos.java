package com.neuronest.backend.professional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Public directory shapes. Only listing details meant for visitors: no contact details, registration
// number, verification internals or timestamps. The weekly hours and session length are public (they
// drive booking); the buffer between sessions is not. The entity is never serialized directly.
public final class PublicProfessionalDtos {

    private PublicProfessionalDtos() {}

    public record PublicProfessional(
            UUID id,
            String fullName,
            String credentialType,
            String qualifications,
            String bio,
            List<String> specialties,
            List<String> languages,
            String city,
            String state,
            String consultationMode,
            Integer consultationFee,
            Integer yearsExperience,
            String availabilityNote,
            Map<String, List<Map<String, String>>> weeklyHours,
            Integer sessionMinutes,
            String photoUrl,
            String registrationBody,
            boolean isSampleData,
            String credentialsLabel,
            // True only when the listing can take booking requests.
            boolean bookable) {

        static PublicProfessional from(Professional p) {
            return new PublicProfessional(p.getId(), p.getFullName(), p.getCredentialType().name(),
                    p.getQualifications(), p.getBio(), List.copyOf(p.getSpecialties()), List.copyOf(p.getLanguages()),
                    p.getCity(), p.getState(), p.getConsultationMode().name(), p.getConsultationFee(),
                    p.getYearsExperience(), p.getAvailabilityNote(), WeeklyHours.copy(p.getWeeklyHours()),
                    p.getSessionMinutes(), p.getPhotoUrl(),
                    p.getRegistrationBody() == null ? null : p.getRegistrationBody().name(), p.isSampleData(),
                    credentialsLabel(p), bookable(p));
        }

        // Only built from visible listings, so this adds: real (not sample), active, verified, and with
        // weekly hours and a session length.
        static boolean bookable(Professional p) {
            return !p.isSampleData() && p.isActive() && p.isVerified()
                    && p.getVerificationStatus() == VerificationStatus.VERIFIED
                    && p.getSessionMinutes() != null && WeeklyHours.hasAnyRange(p.getWeeklyHours());
        }

        static String credentialsLabel(Professional p) {
            if (p.isSampleData()) {
                return "Sample listing";
            }
            return p.getCredentialType() == CredentialType.COUNSELOR
                    ? "Credentials reviewed by admin"
                    : "Registration verified";
        }
    }

    /** GET /api/professionals/{id}/availability. Slots are ISO date-times with the +05:30 offset. */
    public record PublicAvailability(Integer sessionMinutes, int bufferMinutes, String timezone,
                                     List<AvailabilityDay> days) {}

    public record AvailabilityDay(LocalDate date, List<String> slots) {}

    public record PublicProfessionalMeta(List<String> cities, List<String> languages) {}

    public record PublicPage<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
}
