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

    // Set by the background jobs (V7): when the 24-hour reminder went to the professional, and when the
    // expiry email went to the user.
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    // When an admin last reminded the professional about this request (V8).
    @Column(name = "admin_nudged_at")
    private Instant adminNudgedAt;

    // The user's deadline to pay while the booking is AWAITING_PAYMENT (V10).
    @Column(name = "payment_due_at")
    private Instant paymentDueAt;

    // When the background jobs expired it unpaid (V12). responded_at / responded_by keep the professional's
    // (or the user's) acceptance.
    @Column(name = "payment_expired_at")
    private Instant paymentExpiredAt;

    // V15/V16: how many consecutive sessions this booking covers, when the meeting ends (ends_at: start +
    // sessions x session length, no breaks inside) and until when it blocks the professional's time
    // (blocked_until: the skipped breaks added at the end). The database refuses two AWAITING_PAYMENT or
    // CONFIRMED bookings whose [preferred_datetime, blocked_until) overlap. Bookings made before V16 have
    // blocked_until = ends_at (their ends_at already included the buffer); older rows may have neither.
    @Column(name = "session_count", nullable = false)
    private int sessionCount = 1;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    // V17: the client asked for updates on WhatsApp when booking (used only once that channel is on), and
    // whether the 10-minute session reminder has gone out (claimed by SessionReminderJob before sending).
    // reminder_sent_at is a different, older reminder: the professional's nudge for an unanswered request.
    @Column(name = "whatsapp_opt_in", nullable = false)
    private boolean whatsappOptIn;

    @Column(name = "session_reminder_sent", nullable = false)
    private boolean sessionReminderSent;

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
     * When an AWAITING_PAYMENT or CONFIRMED booking takes place: the time the professional proposed, if
     * any, otherwise the time the user asked for. Null for every other status, which never blocks a slot.
     */
    public Instant scheduledTime() {
        if (!BookingStatus.BLOCKS_SLOT.contains(status)) return null;
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

    public Instant getReminderSentAt() { return reminderSentAt; }
    public void setReminderSentAt(Instant reminderSentAt) { this.reminderSentAt = reminderSentAt; }

    public Instant getExpiryNotifiedAt() { return expiryNotifiedAt; }
    public void setExpiryNotifiedAt(Instant expiryNotifiedAt) { this.expiryNotifiedAt = expiryNotifiedAt; }

    public Instant getAdminNudgedAt() { return adminNudgedAt; }
    public void setAdminNudgedAt(Instant adminNudgedAt) { this.adminNudgedAt = adminNudgedAt; }

    public Instant getPaymentDueAt() { return paymentDueAt; }
    public void setPaymentDueAt(Instant paymentDueAt) { this.paymentDueAt = paymentDueAt; }

    public Instant getPaymentExpiredAt() { return paymentExpiredAt; }

    public int getSessionCount() { return sessionCount; }
    public void setSessionCount(int sessionCount) { this.sessionCount = sessionCount; }

    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }

    public Instant getBlockedUntil() { return blockedUntil; }
    public void setBlockedUntil(Instant blockedUntil) { this.blockedUntil = blockedUntil; }

    public boolean isWhatsappOptIn() { return whatsappOptIn; }
    public void setWhatsappOptIn(boolean whatsappOptIn) { this.whatsappOptIn = whatsappOptIn; }

    public boolean isSessionReminderSent() { return sessionReminderSent; }
    public void setSessionReminderSent(boolean sessionReminderSent) { this.sessionReminderSent = sessionReminderSent; }
    public void setPaymentExpiredAt(Instant paymentExpiredAt) { this.paymentExpiredAt = paymentExpiredAt; }
}
