package com.neuronest.backend.jobs;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The background jobs' database work. Every method claims its rows atomically (one UPDATE that only
 * touches rows still in the right state, picked with FOR UPDATE SKIP LOCKED), so two running copies of
 * the app never process the same row, and returns only the ids it claimed.
 */
public interface BookingJobStore {

    /** PENDING or ALTERNATE_PROPOSED requests past expires_at: set EXPIRED, responded_at = now, responded_by NULL. */
    List<UUID> expireOverdueRequests(Instant now, int limit);

    /**
     * AWAITING_PAYMENT bookings whose payment_due_at is before {@code cutoff} (now minus the grace period):
     * set EXPIRED and payment_expired_at = now, which releases their slot. responded_at and responded_by
     * keep the acceptance.
     */
    List<UUID> expireUnpaidBookings(Instant now, Instant cutoff, int limit);

    /**
     * Requests the jobs expired between {@code since} and {@code before} whose user email was not sent
     * (expiry_notified_at NULL). Sets expiry_notified_at = now as the claim, so this retry happens once.
     */
    List<UUID> claimExpiryEmailRetries(Instant now, Instant since, Instant before, int limit);

    /** PENDING requests expiring within (now, until] that have had no reminder: sets reminder_sent_at = now. */
    List<UUID> claimReminders(Instant now, Instant until, int limit);

    /**
     * CONFIRMED sessions starting within (from, to] whose session reminder has not gone out, leaving out any
     * booked less than {@code leadMinutes} before its start: sets session_reminder_sent = true as the claim,
     * so each reminder is sent once.
     */
    List<UUID> claimSessionReminders(Instant now, Instant from, Instant to, long leadMinutes, int limit);

    /**
     * VERIFIED professionals whose registration_expiry is before {@code today}: set EXPIRED, not verified,
     * not active, and write one verification_log row each. Sample listings without an account are skipped.
     */
    List<UUID> expireRegistrations(LocalDate today, Instant now, int limit);
}
