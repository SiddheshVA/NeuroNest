package com.neuronest.backend.professional;

import java.util.List;
import java.util.UUID;

// Public directory shapes. Only listing details meant for visitors: no contact details, registration
// number, verification internals or timestamps. The entity is never serialized directly.
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
            String photoUrl,
            String registrationBody,
            boolean isSampleData,
            String credentialsLabel) {

        static PublicProfessional from(Professional p) {
            return new PublicProfessional(p.getId(), p.getFullName(), p.getCredentialType().name(),
                    p.getQualifications(), p.getBio(), List.copyOf(p.getSpecialties()), List.copyOf(p.getLanguages()),
                    p.getCity(), p.getState(), p.getConsultationMode().name(), p.getConsultationFee(),
                    p.getYearsExperience(), p.getAvailabilityNote(), p.getPhotoUrl(),
                    p.getRegistrationBody() == null ? null : p.getRegistrationBody().name(), p.isSampleData(),
                    credentialsLabel(p));
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

    public record PublicProfessionalMeta(List<String> cities, List<String> languages) {}

    public record PublicPage<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
}
