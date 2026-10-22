package com.neuronest.backend.jobs;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Each claim is a single UPDATE ... RETURNING whose rows are picked with FOR UPDATE SKIP LOCKED, and the
// UPDATE repeats the state check, so a row is changed by exactly one caller even when several copies of
// the app run the jobs at the same moment.
@Repository
public class JdbcBookingJobStore implements BookingJobStore {

    static final String EXPIRE_REQUESTS = """
            UPDATE booking_requests
               SET status = 'EXPIRED', responded_at = :now, responded_by = NULL, updated_at = :now
             WHERE id IN (SELECT id FROM booking_requests
                           WHERE status IN ('PENDING', 'ALTERNATE_PROPOSED') AND expires_at < :now
                           ORDER BY expires_at, id
                           LIMIT :limit
                           FOR UPDATE SKIP LOCKED)
               AND status IN ('PENDING', 'ALTERNATE_PROPOSED') AND expires_at < :now
            RETURNING id""";

    // responded_by NULL with responded_at set is what the jobs' own expiry looks like (the table allows no
    // other value for a system action).
    static final String CLAIM_EXPIRY_RETRIES = """
            UPDATE booking_requests
               SET expiry_notified_at = :now
             WHERE id IN (SELECT id FROM booking_requests
                           WHERE status = 'EXPIRED' AND expiry_notified_at IS NULL AND responded_by IS NULL
                             AND responded_at >= :since AND responded_at < :before
                           ORDER BY responded_at, id
                           LIMIT :limit
                           FOR UPDATE SKIP LOCKED)
               AND status = 'EXPIRED' AND expiry_notified_at IS NULL
            RETURNING id""";

    static final String CLAIM_REMINDERS = """
            UPDATE booking_requests
               SET reminder_sent_at = :now
             WHERE id IN (SELECT id FROM booking_requests
                           WHERE status = 'PENDING' AND reminder_sent_at IS NULL
                             AND expires_at > :now AND expires_at <= :until
                           ORDER BY expires_at, id
                           LIMIT :limit
                           FOR UPDATE SKIP LOCKED)
               AND status = 'PENDING' AND reminder_sent_at IS NULL
            RETURNING id""";

    // One statement: the professionals are changed and their log rows written together. The note is also
    // stored as verification_notes, so the professional's status banner explains the change instead of
    // showing an older note (for example the one written when they were verified).
    static final String EXPIRE_REGISTRATIONS = """
            WITH expired AS (
                UPDATE professionals
                   SET verification_status = 'EXPIRED', verified = false, active = false, updated_at = :now,
                       verification_notes = :note
                 WHERE id IN (SELECT p.id FROM professionals p
                               WHERE p.verification_status = 'VERIFIED' AND p.registration_expiry < :today
                                 AND NOT (p.is_sample_data = true
                                          AND NOT EXISTS (SELECT 1 FROM professional_accounts pa
                                                           WHERE pa.professional_id = p.id))
                               ORDER BY p.id
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                   AND verification_status = 'VERIFIED'
                RETURNING id)
            INSERT INTO verification_log (professional_id, action, from_status, to_status, actor, note, created_at)
            SELECT id, 'EXPIRED', 'VERIFIED', 'EXPIRED', 'SYSTEM', :note, :now FROM expired
            RETURNING professional_id""";

    static final String REGISTRATION_EXPIRED_NOTE = "Registration expiry date passed.";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBookingJobStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> expireOverdueRequests(Instant now, int limit) {
        return jdbc.queryForList(EXPIRE_REQUESTS, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now)).addValue("limit", limit), UUID.class);
    }

    @Override
    public List<UUID> claimExpiryEmailRetries(Instant now, Instant since, Instant before, int limit) {
        return jdbc.queryForList(CLAIM_EXPIRY_RETRIES, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now)).addValue("since", Timestamp.from(since))
                .addValue("before", Timestamp.from(before)).addValue("limit", limit), UUID.class);
    }

    @Override
    public List<UUID> claimReminders(Instant now, Instant until, int limit) {
        return jdbc.queryForList(CLAIM_REMINDERS, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now)).addValue("until", Timestamp.from(until))
                .addValue("limit", limit), UUID.class);
    }

    @Override
    public List<UUID> expireRegistrations(LocalDate today, Instant now, int limit) {
        return jdbc.queryForList(EXPIRE_REGISTRATIONS, new MapSqlParameterSource()
                .addValue("today", today).addValue("now", Timestamp.from(now))
                .addValue("note", REGISTRATION_EXPIRED_NOTE).addValue("limit", limit), UUID.class);
    }
}
