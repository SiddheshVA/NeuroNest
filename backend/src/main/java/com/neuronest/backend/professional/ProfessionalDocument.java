package com.neuronest.backend.professional;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

// An uploaded proof document (V2). storage_path is relative to APP_STORAGE_DIR and is never sent
// to any client.
@Entity
@Table(name = "professional_documents")
public class ProfessionalDocument {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, updatable = false)
    private DocumentType docType;

    @Column(name = "storage_path", nullable = false, updatable = false)
    private String storagePath;

    @Column(name = "original_filename", updatable = false)
    private String originalFilename;

    @Column(name = "content_type", updatable = false)
    private String contentType;

    @Column(name = "size_bytes", updatable = false)
    private Long sizeBytes;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    protected ProfessionalDocument() {}

    public ProfessionalDocument(UUID professionalId, DocumentType docType, String storagePath,
                                String originalFilename, String contentType, long sizeBytes) {
        this.professionalId = professionalId;
        this.docType = docType;
        this.storagePath = storagePath;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
    }

    public UUID getId() { return id; }
    public UUID getProfessionalId() { return professionalId; }
    public DocumentType getDocType() { return docType; }
    public String getStoragePath() { return storagePath; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public Instant getUploadedAt() { return uploadedAt; }
}
