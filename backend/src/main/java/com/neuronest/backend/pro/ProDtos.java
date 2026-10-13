package com.neuronest.backend.pro;

import com.neuronest.backend.pro.ProExceptions.MissingItem;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalDocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Request/response shapes for /api/pro/**. Entities are never serialized directly. Documents never
// include their storage path, and the professional's view omits admin-internal fields (verifiedBy,
// verificationSource, isSampleData).
public final class ProDtos {

    private ProDtos() {}

    public record RegisterRequest(String fullName, String credentialType, String consultationMode) {}

    public record AccountView(String status, String email, Instant createdAt) {
        static AccountView from(ProfessionalAccount a) {
            return new AccountView(a.getStatus().name(), a.getEmail(), a.getCreatedAt());
        }
    }

    public record ProfileView(
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
            String verificationStatus,
            boolean verified,
            boolean active,
            Instant submittedAt,
            Instant verifiedAt,
            LocalDate nextRecheckDate,
            Instant createdAt,
            Instant updatedAt) {

        static ProfileView from(Professional p) {
            return new ProfileView(p.getId(), p.getFullName(), p.getCredentialType().name(), p.getQualifications(),
                    p.getRegistrationBody() == null ? null : p.getRegistrationBody().name(), p.getRegistrationNumber(),
                    p.getRegistrationCouncil(), p.getRegistrationYear(), p.getRegistrationExpiry(), p.getBio(),
                    List.copyOf(p.getSpecialties()), List.copyOf(p.getLanguages()), p.getCity(), p.getState(),
                    p.getConsultationMode().name(), p.getConsultationFee(), p.getYearsExperience(),
                    p.getContactEmail(), p.getContactPhone(), p.getAvailabilityNote(), p.getPhotoUrl(),
                    p.getVerificationStatus().name(), p.isVerified(), p.isActive(), p.getSubmittedAt(),
                    p.getVerifiedAt(), p.getNextRecheckDate(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record DocumentView(UUID id, String docType, String originalFilename, Long sizeBytes, Instant uploadedAt) {
        static DocumentView from(ProfessionalDocument d) {
            return new DocumentView(d.getId(), d.getDocType().name(), d.getOriginalFilename(), d.getSizeBytes(),
                    d.getUploadedAt());
        }
    }

    /** The most recent note explaining a verification decision (for example why more information is needed). */
    public record VerificationNoteView(String note, String action, Instant at) {}

    public record ProMeResponse(
            AccountView account,
            ProfileView profile,
            VerificationNoteView verificationNote,
            List<DocumentView> documents,
            List<String> requiredDocuments,
            List<String> missingDocuments,
            List<MissingItem> missingForSubmit,
            // What the professional may do in the current status.
            boolean canEditCredentials,
            boolean canManageDocuments,
            boolean canSubmit) {}
}
