package com.neuronest.backend.review;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

// A client's review of a professional (V14): one per CONFIRMED booking whose session has ended. The user id
// is only used to check who may edit or delete it; it is never sent to anyone else.
@Entity
@Table(name = "professional_reviews")
public class ProfessionalReview {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false, unique = true)
    private UUID bookingId;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "comment", length = ReviewService.MAX_COMMENT_LENGTH)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReviewStatus status = ReviewStatus.VISIBLE;

    @Column(name = "moderation_reason")
    private String moderationReason;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "moderated_by")
    private String moderatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfessionalReview() {}

    public ProfessionalReview(UUID bookingId, UUID professionalId, UUID userId, int rating, String comment) {
        this.bookingId = bookingId;
        this.professionalId = professionalId;
        this.userId = userId;
        this.rating = (short) rating;
        this.comment = comment;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getProfessionalId() { return professionalId; }
    public UUID getUserId() { return userId; }

    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = (short) rating; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public ReviewStatus getStatus() { return status; }

    public String getModerationReason() { return moderationReason; }
    public Instant getModeratedAt() { return moderatedAt; }
    public String getModeratedBy() { return moderatedBy; }

    /** Hides or shows the review again, recording why and by whom. */
    public void moderate(ReviewStatus status, String reason, String admin, Instant at) {
        this.status = status;
        this.moderationReason = reason;
        this.moderatedBy = admin;
        this.moderatedAt = at;
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
