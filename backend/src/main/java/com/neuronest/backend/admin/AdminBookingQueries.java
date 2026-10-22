package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.AdminBookingItem;
import com.neuronest.backend.admin.AdminBookingDtos.DashboardSummary;
import com.neuronest.backend.admin.AdminBookingDtos.Last30Days;
import com.neuronest.backend.admin.AdminBookingDtos.ResponsivenessItem;
import com.neuronest.backend.professional.AvailabilityService;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Read-only SQL for the admin's booking overview and the two atomic admin claims (nudge, close). Sample
// listings are left out everywhere. The item query selects only the columns the admin item shows, so the
// user's contact details, notes, assessment and meeting details are never read here.
@Repository
public class AdminBookingQueries {

    /** A request the professional answered: confirmed, declined or another time suggested by them, or confirmed by the user accepting a suggestion. */
    static final String ANSWERED = """
            ((b.responded_by = 'PROFESSIONAL' AND b.status IN ('CONFIRMED', 'DECLINED', 'ALTERNATE_PROPOSED'))
             OR (b.responded_by = 'USER' AND b.status = 'CONFIRMED'))""";

    static final String HOURS_TO_RESPONSE = "extract(epoch FROM (b.responded_at - b.created_at)) / 3600.0";

    static final String REAL_BOOKINGS = "booking_requests b JOIN professionals p ON p.id = b.professional_id AND p.is_sample_data = false";

    static final String SUMMARY = """
            SELECT
              (SELECT count(*) FROM professionals p WHERE p.is_sample_data = false AND p.verification_status = 'SUBMITTED') AS review_queue,
              (SELECT count(*) FROM professionals p WHERE p.is_sample_data = false AND p.verification_status = 'SUBMITTED'
                  AND p.submitted_at < :before48h) AS review_queue_over_48h,
              (SELECT count(*) FROM %1$s WHERE b.status = 'PENDING' AND b.created_at < :before48h AND b.expires_at > :now) AS unanswered_over_48h,
              (SELECT count(*) FROM %1$s WHERE b.status = 'PENDING' AND b.expires_at > :now) AS pending_total,
              (SELECT count(*) FROM %1$s WHERE b.status = 'ALTERNATE_PROPOSED' AND b.expires_at > :now) AS awaiting_user_total,
              (SELECT count(*) FROM %1$s WHERE b.status = 'CONFIRMED' AND b.preferred_datetime > :now) AS upcoming_confirmed,
              (SELECT count(*) FROM professionals p WHERE p.is_sample_data = false AND p.verification_status = 'VERIFIED'
                  AND p.active = true) AS professionals_verified_active,
              (SELECT count(*) FROM professionals p WHERE p.is_sample_data = false AND p.verification_status = 'NEEDS_INFO') AS professionals_needs_info,
              (SELECT count(*) FROM professionals p WHERE p.is_sample_data = false AND p.verification_status = 'VERIFIED'
                  AND p.registration_expiry BETWEEN :today AND :in30Days) AS registrations_expiring
            """.formatted(REAL_BOOKINGS);

    static final String LAST_30_DAYS = """
            SELECT count(*) AS created,
                   count(*) FILTER (WHERE %2$s) AS answered,
                   count(*) FILTER (WHERE b.status = 'EXPIRED') AS expired,
                   count(*) FILTER (WHERE b.status = 'CANCELLED' AND b.responded_by = 'USER') AS cancelled_by_user,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY %3$s)
                       FILTER (WHERE %2$s AND b.responded_at IS NOT NULL) AS median_hours
              FROM %1$s
             WHERE b.created_at >= :since
            """.formatted(REAL_BOOKINGS, ANSWERED, HOURS_TO_RESPONSE);

    static final String ITEM_COLUMNS = """
            b.id, b.status, b.created_at, b.expires_at, b.professional_id, p.full_name AS professional_name,
            b.contact_name, b.preferred_mode, b.preferred_method, b.preferred_datetime, b.proposed_datetime,
            b.responded_by, b.responded_at, b.admin_nudged_at, b.admin_note""";

    static final String LIST_FILTERS = """
             WHERE (CAST(:status AS text) IS NULL OR b.status = CAST(:status AS text))
               AND (CAST(:professionalId AS uuid) IS NULL OR b.professional_id = CAST(:professionalId AS uuid))
               AND (CAST(:waitingSince AS timestamptz) IS NULL
                    OR (b.status = 'PENDING' AND b.expires_at > :now AND b.created_at < CAST(:waitingSince AS timestamptz)))
            """;

    static final String LIST = "SELECT " + ITEM_COLUMNS + " FROM " + REAL_BOOKINGS + LIST_FILTERS
            + " ORDER BY b.created_at DESC, b.id LIMIT :size OFFSET :offset";

    static final String LIST_COUNT = "SELECT count(*) FROM " + REAL_BOOKINGS + LIST_FILTERS;

    static final String ITEM = "SELECT " + ITEM_COLUMNS + " FROM booking_requests b JOIN professionals p ON p.id = b.professional_id"
            + " WHERE b.id = :id";

    static final String RESPONSIVENESS = """
            SELECT p.id, p.full_name,
                   count(*) AS received,
                   count(*) FILTER (WHERE %2$s) AS answered,
                   count(*) FILTER (WHERE b.status = 'EXPIRED') AS expired,
                   count(*) FILTER (WHERE b.status = 'PENDING' AND b.expires_at > :now) AS pending_now,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY %3$s)
                       FILTER (WHERE %2$s AND b.responded_at IS NOT NULL) AS median_hours,
                   max(b.responded_at) FILTER (WHERE %2$s) AS last_answered_at
              FROM %1$s
             WHERE b.created_at >= :since
             GROUP BY p.id, p.full_name
             ORDER BY count(*) FILTER (WHERE %2$s)::numeric / count(*) ASC,
                      count(*) FILTER (WHERE b.status = 'EXPIRED') DESC,
                      p.id
             LIMIT :size OFFSET :offset
            """.formatted(REAL_BOOKINGS, ANSWERED, HOURS_TO_RESPONSE);

    static final String RESPONSIVENESS_COUNT = "SELECT count(DISTINCT p.id) FROM " + REAL_BOOKINGS + " WHERE b.created_at >= :since";

    // Atomic: only a PENDING, not expired request that was not nudged in the last 12 hours is changed.
    static final String CLAIM_NUDGE = """
            UPDATE booking_requests SET admin_nudged_at = :now, updated_at = :now
             WHERE id = :id AND status = 'PENDING' AND expires_at > :now
               AND (admin_nudged_at IS NULL OR admin_nudged_at <= :nudgedBefore)
            RETURNING id""";

    // Atomic: only an open request is closed, so two simultaneous closes act once.
    static final String CLAIM_CLOSE = """
            UPDATE booking_requests
               SET status = 'EXPIRED', responded_by = 'ADMIN', responded_at = :now, admin_note = :note, updated_at = :now
             WHERE id = :id AND status IN ('PENDING', 'ALTERNATE_PROPOSED')
            RETURNING id""";

    static final String STATE = "SELECT status, expires_at, admin_nudged_at FROM booking_requests WHERE id = :id";

    /** The parts of a request the admin actions check, to explain a refused claim. */
    public record RequestState(String status, Instant expiresAt, Instant adminNudgedAt) {}

    public record Page<T>(List<T> items, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    public AdminBookingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardSummary summary(Instant now, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", at(now))
                .addValue("before48h", at(now.minus(Duration.ofHours(48))))
                .addValue("today", today)
                .addValue("in30Days", today.plusDays(30))
                .addValue("since", at(now.minus(Duration.ofDays(30))));
        Last30Days last30 = jdbc.queryForObject(LAST_30_DAYS, params, (rs, i) -> {
            long created = rs.getLong("created");
            long answered = rs.getLong("answered");
            double rate = created == 0 ? 0 : Math.round(answered * 1000.0 / created) / 10.0;
            return new Last30Days(created, answered, rs.getLong("expired"), rs.getLong("cancelled_by_user"), rate,
                    hours(rs, "median_hours"));
        });
        return jdbc.queryForObject(SUMMARY, params, (rs, i) -> new DashboardSummary(
                rs.getLong("review_queue"), rs.getLong("review_queue_over_48h"), rs.getLong("unanswered_over_48h"),
                rs.getLong("pending_total"), rs.getLong("awaiting_user_total"), rs.getLong("upcoming_confirmed"),
                rs.getLong("professionals_verified_active"), rs.getLong("professionals_needs_info"),
                rs.getLong("registrations_expiring"), last30));
    }

    /** waitingSince: only PENDING, not expired requests created before it (null for no such filter). */
    public Page<AdminBookingItem> list(String status, Instant waitingSince, UUID professionalId, Instant now,
                                       int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR)
                .addValue("professionalId", professionalId == null ? null : professionalId.toString(), Types.VARCHAR)
                .addValue("waitingSince", waitingSince == null ? null : at(waitingSince), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("now", at(now))
                .addValue("size", size)
                .addValue("offset", (long) page * size);
        List<AdminBookingItem> items = jdbc.query(LIST, params, itemMapper(now));
        Long total = jdbc.queryForObject(LIST_COUNT, params, Long.class);
        return new Page<>(items, total == null ? 0 : total);
    }

    public Optional<AdminBookingItem> item(UUID id, Instant now) {
        return jdbc.query(ITEM, new MapSqlParameterSource("id", id), itemMapper(now)).stream().findFirst();
    }

    public Page<ResponsivenessItem> responsiveness(Instant since, Instant now, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("since", at(since)).addValue("now", at(now))
                .addValue("size", size).addValue("offset", (long) page * size);
        List<ResponsivenessItem> items = jdbc.query(RESPONSIVENESS, params, (rs, i) -> new ResponsivenessItem(
                rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getLong("received"),
                rs.getLong("answered"), rs.getLong("expired"), rs.getLong("pending_now"), hours(rs, "median_hours"),
                instant(rs, "last_answered_at")));
        Long total = jdbc.queryForObject(RESPONSIVENESS_COUNT, params, Long.class);
        return new Page<>(items, total == null ? 0 : total);
    }

    public boolean claimNudge(UUID id, Instant now, Instant nudgedBefore) {
        return !jdbc.queryForList(CLAIM_NUDGE, new MapSqlParameterSource()
                .addValue("id", id).addValue("now", at(now)).addValue("nudgedBefore", at(nudgedBefore)), UUID.class).isEmpty();
    }

    public boolean claimClose(UUID id, String note, Instant now) {
        return !jdbc.queryForList(CLAIM_CLOSE, new MapSqlParameterSource()
                .addValue("id", id).addValue("note", note).addValue("now", at(now)), UUID.class).isEmpty();
    }

    public Optional<RequestState> state(UUID id) {
        return jdbc.query(STATE, new MapSqlParameterSource("id", id), (rs, i) -> new RequestState(
                rs.getString("status"), instant(rs, "expires_at"), instant(rs, "admin_nudged_at"))).stream().findFirst();
    }

    // ---- Mapping ----

    private static RowMapper<AdminBookingItem> itemMapper(Instant now) {
        return (rs, i) -> {
            Instant created = instant(rs, "created_at");
            Instant responded = instant(rs, "responded_at");
            Instant until = responded != null ? responded : now;
            long hoursWaiting = created == null ? 0 : Math.max(0, Duration.between(created, until).toHours());
            return new AdminBookingItem(rs.getObject("id", UUID.class), rs.getString("status"), created,
                    instant(rs, "expires_at"), hoursWaiting, rs.getObject("professional_id", UUID.class),
                    rs.getString("professional_name"), rs.getString("contact_name"), rs.getString("preferred_mode"),
                    rs.getString("preferred_method"), inIndia(instant(rs, "preferred_datetime")),
                    inIndia(instant(rs, "proposed_datetime")), rs.getString("responded_by"), responded,
                    instant(rs, "admin_nudged_at"), rs.getString("admin_note"));
        };
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Double hours(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : Math.round(value * 10.0) / 10.0;
    }

    private static OffsetDateTime inIndia(Instant instant) {
        return instant == null ? null : instant.atZone(AvailabilityService.ZONE).toOffsetDateTime();
    }
}
