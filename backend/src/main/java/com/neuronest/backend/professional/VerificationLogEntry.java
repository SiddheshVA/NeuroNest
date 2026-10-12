package com.neuronest.backend.professional;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

// One row of verification_log (V2): the audit trail of verification status changes.
// Only the columns this code writes are mapped; source and evidence_document_id stay null.
@Entity
@Table(name = "verification_log")
public class VerificationLogEntry {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "from_status", updatable = false)
    private String fromStatus;

    @Column(name = "to_status", updatable = false)
    private String toStatus;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected VerificationLogEntry() {}

    public VerificationLogEntry(UUID professionalId, String action, VerificationStatus fromStatus,
                                VerificationStatus toStatus, String actor, String note) {
        this.professionalId = professionalId;
        this.action = action;
        this.fromStatus = fromStatus == null ? null : fromStatus.name();
        this.toStatus = toStatus == null ? null : toStatus.name();
        this.actor = actor;
        this.note = note;
    }

    public UUID getId() { return id; }
    public UUID getProfessionalId() { return professionalId; }
    public String getAction() { return action; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
