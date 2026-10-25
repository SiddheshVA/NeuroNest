package com.neuronest.backend.professional;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

// One grid slot a professional blocked (V18): it is not offered to clients and cannot be booked. It blocks
// the same time a one-session booking there would: from the slot start to blocked_until (the session and
// the break after it, SessionGrid). The database refuses a block over an AWAITING_PAYMENT or CONFIRMED
// booking and a booking over a block (V18 triggers), as well as two overlapping blocks.
@Entity
@Table(name = "professional_blocked_slots")
public class ProfessionalBlockedSlot {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "blocked_until", nullable = false, updatable = false)
    private Instant blockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProfessionalBlockedSlot() {}

    public ProfessionalBlockedSlot(UUID professionalId, Instant startsAt, Instant blockedUntil) {
        this.professionalId = professionalId;
        this.startsAt = startsAt;
        this.blockedUntil = blockedUntil;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean overlaps(Instant start, Instant end) {
        return startsAt.isBefore(end) && start.isBefore(blockedUntil);
    }

    public UUID getId() { return id; }
    public UUID getProfessionalId() { return professionalId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getBlockedUntil() { return blockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
}
