package com.neuronest.backend.professional;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

// A professional's login, linked to Clerk by clerk_user_id (V2). password_hash, invite_token and
// invite_expires_at belong to the older invite flow and are left null for Clerk sign-ups.
@Entity
@Table(name = "professional_accounts")
public class ProfessionalAccount {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProfessionalAccountStatus status = ProfessionalAccountStatus.ACTIVE;

    @Column(name = "invite_token")
    private String inviteToken;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "clerk_user_id")
    private String clerkUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfessionalAccount() {}

    public ProfessionalAccount(UUID professionalId, String email, String clerkUserId) {
        this.professionalId = professionalId;
        this.email = email;
        this.clerkUserId = clerkUserId;
        this.status = ProfessionalAccountStatus.ACTIVE;
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
    public UUID getProfessionalId() { return professionalId; }
    public String getEmail() { return email; }
    public ProfessionalAccountStatus getStatus() { return status; }
    public String getClerkUserId() { return clerkUserId; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
}
