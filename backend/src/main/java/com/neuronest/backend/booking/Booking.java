package com.neuronest.backend.booking;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

// Maps the existing booking_requests table exactly. Flyway owns the schema; created_at and updated_at
// are set here because no database trigger maintains them.
@Entity
@Table(name = "booking_requests")
public class Booking {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    // ONLINE or IN_PERSON (the table's CHECK constraint).
    @Column(name = "preferred_mode")
    private String preferredMode;

    @Column(name = "preferred_datetime")
    private Instant preferredDatetime;

    @Column(name = "alternate_datetime")
    private Instant alternateDatetime;

    @Column(name = "note")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "professional_notified_at")
    private Instant professionalNotifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "professional_note")
    private String professionalNote;

    @Column(name = "responded_at")
    private Instant respondedAt;

    // PROFESSIONAL, ADMIN or USER (the table's CHECK constraint).
    @Column(name = "responded_by")
    private String respondedBy;

    // Kept in the table for older rows; new requests never set it (they use exact slots).
    @Column(name = "preferred_window")
    private String preferredWindow;

    @Column(name = "proposed_datetime")
    private Instant proposedDatetime;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "consent_given_at")
    private Instant consentGivenAt;

    // PHONE or VIDEO for an ONLINE request; null for IN_PERSON (the table's CHECK constraint).
    @Column(name = "preferred_method")
    private String preferredMethod;

    // Set by the professional when confirming: PHONE, VIDEO or IN_PERSON (the table's CHECK constraint).
    @Column(name = "meeting_method")
    private String meetingMethod;

    @Column(name = "meeting_link")
    private String meetingLink;

    @Column(name = "meeting_address")
    private String meetingAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shared_assessment")
    private SharedAssessment sharedAssessment;

    @Column(name = "assessment_shared_at")
    private Instant assessmentSharedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * When a CONFIRMED booking takes place: the time the professional proposed, if any, otherwise the
     * time the user asked for. Null for every other status, which never blocks a slot.
     */
    public Instant scheduledTime() {
        if (status != BookingStatus.CONFIRMED) return null;
        return proposedDatetime != null ? proposedDatetime : preferredDatetime;
    }

    public UUID getId() { return id; }

    public UUID getProfessionalId() { return professionalId; }
    public void setProfessionalId(UUID professionalId) { this.professionalId = professionalId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getPreferredMode() { return preferredMode; }
    public void setPreferredMode(String preferredMode) { this.preferredMode = preferredMode; }

    public Instant getPreferredDatetime() { return preferredDatetime; }
    public void setPreferredDatetime(Instant preferredDatetime) { this.preferredDatetime = preferredDatetime; }

    public Instant getAlternateDatetime() { return alternateDatetime; }
    public void setAlternateDatetime(Instant alternateDatetime) { this.alternateDatetime = alternateDatetime; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }

    public String getAdminNote() { return adminNote; }
    public void setAdminNote(String adminNote) { this.adminNote = adminNote; }

    public Instant getProfessionalNotifiedAt() { return professionalNotifiedAt; }
    public void setProfessionalNotifiedAt(Instant professionalNotifiedAt) { this.professionalNotifiedAt = professionalNotifiedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public String getProfessionalNote() { return professionalNote; }
    public void setProfessionalNote(String professionalNote) { this.professionalNote = professionalNote; }

    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }

    public String getRespondedBy() { return respondedBy; }
    public void setRespondedBy(String respondedBy) { this.respondedBy = respondedBy; }

    public String getPreferredWindow() { return preferredWindow; }
    public void setPreferredWindow(String preferredWindow) { this.preferredWindow = preferredWindow; }

    public Instant getProposedDatetime() { return proposedDatetime; }
    public void setProposedDatetime(Instant proposedDatetime) { this.proposedDatetime = proposedDatetime; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getConsentGivenAt() { return consentGivenAt; }
    public void setConsentGivenAt(Instant consentGivenAt) { this.consentGivenAt = consentGivenAt; }

    public String getPreferredMethod() { return preferredMethod; }
    public void setPreferredMethod(String preferredMethod) { this.preferredMethod = preferredMethod; }

    public String getMeetingMethod() { return meetingMethod; }
    public void setMeetingMethod(String meetingMethod) { this.meetingMethod = meetingMethod; }

    public String getMeetingLink() { return meetingLink; }
    public void setMeetingLink(String meetingLink) { this.meetingLink = meetingLink; }

    public String getMeetingAddress() { return meetingAddress; }
    public void setMeetingAddress(String meetingAddress) { this.meetingAddress = meetingAddress; }

    public SharedAssessment getSharedAssessment() { return sharedAssessment; }
    public void setSharedAssessment(SharedAssessment sharedAssessment) { this.sharedAssessment = sharedAssessment; }

    public Instant getAssessmentSharedAt() { return assessmentSharedAt; }
    public void setAssessmentSharedAt(Instant assessmentSharedAt) { this.assessmentSharedAt = assessmentSharedAt; }
}
