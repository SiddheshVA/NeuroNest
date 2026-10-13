package com.neuronest.backend.admin;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.neuronest.backend.professional.Professional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Request/response shapes for /api/admin/professionals. The JPA entity is never serialized directly.
public final class AdminProfessionalDtos {

    private AdminProfessionalDtos() {}

    // Body of POST and PUT. Enums arrive as strings so unknown values get a readable 400 from the
    // service instead of a Jackson parse error. Verification fields and "active" are deliberately
    // absent: if a client sends them they are ignored.
    public record ProfessionalRequest(
            String fullName,
            String credentialType,
            String qualifications,
            String registrationBody,
            String registrationNumber,
            String registrationCouncil,
            Integer registrationYear,
            LocalDate registrationExpiry,
            String bio,
            List<String> specialties,
            List<String> languages,
            String city,
            String state,
            String consultationMode,
            Integer consultationFee,
            Integer yearsExperience,
            String contactEmail,
            String contactPhone,
            String availabilityNote,
            String photoUrl,
            Boolean isSampleData) {}

    public record ActiveRequest(Boolean active) {}

    public record ProfessionalSummary(
            UUID id,
            String fullName,
            String credentialType,
            String city,
            String verificationStatus,
            boolean verified,
            boolean active,
            boolean isSampleData,
            LocalDate registrationExpiry,
            Instant createdAt) {

        static ProfessionalSummary from(Professional p) {
            return new ProfessionalSummary(p.getId(), p.getFullName(), p.getCredentialType().name(), p.getCity(),
                    p.getVerificationStatus().name(), p.isVerified(), p.isActive(), p.isSampleData(),
                    p.getRegistrationExpiry(), p.getCreatedAt());
        }
    }

    public record ProfessionalDetail(
            UUID id,
            String fullName,
            String credentialType,
            String qualifications,
            String registrationBody,
            String registrationNumber,
            String registrationCouncil,
            Integer registrationYear,
            LocalDate registrationExpiry,
            String bio,
            List<String> specialties,
            List<String> languages,
            String city,
            String state,
            String consultationMode,
            Integer consultationFee,
            Integer yearsExperience,
            String contactEmail,
            String contactPhone,
            String availabilityNote,
            String photoUrl,
            boolean isSampleData,
            boolean active,
            String verificationStatus,
            boolean verified,
            String verificationSource,
            String verifiedBy,
            Instant verifiedAt,
            String verificationNotes,
            LocalDate nextRecheckDate,
            Instant submittedAt,
            Instant createdAt,
            Instant updatedAt) {

        static ProfessionalDetail from(Professional p) {
            return new ProfessionalDetail(p.getId(), p.getFullName(), p.getCredentialType().name(),
                    p.getQualifications(), p.getRegistrationBody() == null ? null : p.getRegistrationBody().name(),
                    p.getRegistrationNumber(), p.getRegistrationCouncil(), p.getRegistrationYear(),
                    p.getRegistrationExpiry(), p.getBio(), List.copyOf(p.getSpecialties()),
                    List.copyOf(p.getLanguages()), p.getCity(), p.getState(), p.getConsultationMode().name(),
                    p.getConsultationFee(), p.getYearsExperience(), p.getContactEmail(), p.getContactPhone(),
                    p.getAvailabilityNote(), p.getPhotoUrl(), p.isSampleData(), p.isActive(),
                    p.getVerificationStatus().name(), p.isVerified(), p.getVerificationSource(), p.getVerifiedBy(),
                    p.getVerifiedAt(), p.getVerificationNotes(), p.getNextRecheckDate(), p.getSubmittedAt(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    // PUT response: the full record plus whether the edit sent a VERIFIED listing back for review.
    public record ProfessionalUpdateResult(@JsonUnwrapped ProfessionalDetail professional, boolean reReviewRequired) {}

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
}
