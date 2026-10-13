package com.neuronest.backend.admin;

import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.VerificationLogEntry;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

// Request/response shapes for the admin verification routes. Entities are never serialized directly,
// and a document's storage path is never part of any response.
public final class AdminVerificationDtos {

    private AdminVerificationDtos() {}

    public record ReviewQueueItem(
            UUID id,
            String fullName,
            String credentialType,
            String city,
            Instant submittedAt,
            long documentCount,
            String registrationBody,
            String registrationNumber,
            LocalDate registrationExpiry) {

        static ReviewQueueItem from(Professional p, long documentCount) {
            return new ReviewQueueItem(p.getId(), p.getFullName(), p.getCredentialType().name(), p.getCity(),
                    p.getSubmittedAt(), documentCount,
                    p.getRegistrationBody() == null ? null : p.getRegistrationBody().name(),
                    p.getRegistrationNumber(), p.getRegistrationExpiry());
        }
    }

    public record DocumentView(UUID id, String docType, String originalFilename, String contentType, Long sizeBytes,
                               Instant uploadedAt) {
        static DocumentView from(ProfessionalDocument d) {
            return new DocumentView(d.getId(), d.getDocType().name(), d.getOriginalFilename(), d.getContentType(),
                    d.getSizeBytes(), d.getUploadedAt());
        }
    }

    public record LogEntryView(String action, String fromStatus, String toStatus, String actor, String source,
                               String note, UUID evidenceDocumentId, Instant createdAt) {
        static LogEntryView from(VerificationLogEntry e) {
            return new LogEntryView(e.getAction(), e.getFromStatus(), e.getToStatus(), e.getActor(), e.getSource(),
                    e.getNote(), e.getEvidenceDocumentId(), e.getCreatedAt());
        }
    }

    // Body of POST /{id}/verify. source arrives as a string so an unknown value gets a readable 400.
    public record VerifyRequest(
            String source,
            LocalDate registrationExpiry,
            LocalDate nextRecheckDate,
            String notes,
            UUID evidenceDocumentId,
            Boolean activate,
            Map<String, Boolean> checklist) {}

    public record NeedsInfoRequest(String note) {}

    public record RejectRequest(String reason) {}

    /** A stored file ready to stream; only used inside the backend. */
    record DocumentFile(Path path, String contentType, String filename, long size) {}
}
