package com.neuronest.backend.payment;

import com.neuronest.backend.professional.AvailabilityService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Read-only SQL for earnings and commission, shared by the professional's earnings and the admin's
 * payment reports. Everything is read from payments (never from bookings alone, so older CONFIRMED
 * bookings without a payment never count). Totals and the professional's list count only PAID payments
 * whose booking is CONFIRMED; a payment whose booking is not CONFIRMED belongs in the refunds-needed list.
 * No user name, email, phone or id is ever selected.
 */
@Repository
public class PaymentReportQueries {

    /** Amounts in paise. paidSessions: the number of counted payments. */
    public record Totals(long grossPaise, long commissionPaise, long professionalSharePaise, long paidSessions) {}

    /** One counted payment of a professional. sessionTime is in Indian Standard Time. */
    public record ProfessionalPaymentRow(UUID paymentId, UUID bookingId, OffsetDateTime sessionTime, Instant paidAt,
                                         long amountPaise, long commissionPaise, long professionalSharePaise,
                                         String currency) {}

    /** One PAID payment for the admin; counted is false when its booking is not CONFIRMED. */
    public record AdminPaymentRow(UUID paymentId, UUID bookingId, UUID professionalId, String professionalName,
                                  OffsetDateTime sessionTime, Instant paidAt, long amountPaise, long commissionPaise,
                                  long professionalSharePaise, String currency, String bookingStatus, boolean counted) {}

    public record Page<T>(List<T> items, long total) {}

    static final String COUNTED = "pm.status = 'PAID' AND b.status = 'CONFIRMED'";

    static final String JOIN = " FROM payments pm JOIN booking_requests b ON b.id = pm.booking_id ";

    // Optional filters: a professional, and a [from, to) window on the payment date.
    static final String FILTERS = """
               AND (CAST(:professionalId AS uuid) IS NULL OR b.professional_id = CAST(:professionalId AS uuid))
               AND (CAST(:fromTime AS timestamptz) IS NULL OR pm.paid_at >= CAST(:fromTime AS timestamptz))
               AND (CAST(:toTime AS timestamptz) IS NULL OR pm.paid_at < CAST(:toTime AS timestamptz))
            """;

    static final String TOTALS = """
            SELECT coalesce(sum(pm.amount_paise), 0) AS gross,
                   coalesce(sum(pm.commission_paise), 0) AS commission,
                   coalesce(sum(pm.professional_share_paise), 0) AS share,
                   count(*) AS sessions
            """ + JOIN + " WHERE " + COUNTED + FILTERS;

    static final String PROFESSIONAL_LIST = """
            SELECT pm.id AS payment_id, b.id AS booking_id, b.preferred_datetime, pm.paid_at, pm.amount_paise,
                   pm.commission_paise, pm.professional_share_paise, pm.currency
            """ + JOIN + " WHERE " + COUNTED + " AND b.professional_id = :professionalId"
            + " ORDER BY pm.paid_at DESC, pm.id LIMIT :size OFFSET :offset";

    static final String PROFESSIONAL_COUNT = "SELECT count(*)" + JOIN + " WHERE " + COUNTED
            + " AND b.professional_id = :professionalId";

    static final String ADMIN_LIST = """
            SELECT pm.id AS payment_id, b.id AS booking_id, b.professional_id, p.full_name AS professional_name,
                   b.preferred_datetime, pm.paid_at, pm.amount_paise, pm.commission_paise,
                   pm.professional_share_paise, pm.currency, b.status AS booking_status
            """ + JOIN + " JOIN professionals p ON p.id = b.professional_id WHERE pm.status = 'PAID'"
            + " ORDER BY pm.paid_at DESC, pm.id LIMIT :size OFFSET :offset";

    static final String ADMIN_COUNT = "SELECT count(*)" + JOIN + " WHERE pm.status = 'PAID'";

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Counted payments, of one professional or (null) all, paid in [from, to) (either may be null). */
    public Totals totals(UUID professionalId, Instant from, Instant to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("professionalId", professionalId == null ? null : professionalId.toString(), Types.VARCHAR)
                .addValue("fromTime", from == null ? null : at(from), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toTime", to == null ? null : at(to), Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbc.queryForObject(TOTALS, params, (rs, i) -> new Totals(rs.getLong("gross"), rs.getLong("commission"),
                rs.getLong("share"), rs.getLong("sessions")));
    }

    /** One professional's counted payments, newest payment first. */
    public Page<ProfessionalPaymentRow> professionalPayments(UUID professionalId, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("professionalId", professionalId)
                .addValue("size", size).addValue("offset", (long) page * size);
        List<ProfessionalPaymentRow> items = jdbc.query(PROFESSIONAL_LIST, params, (rs, i) -> new ProfessionalPaymentRow(
                rs.getObject("payment_id", UUID.class), rs.getObject("booking_id", UUID.class),
                inIndia(instant(rs, "preferred_datetime")), instant(rs, "paid_at"), rs.getLong("amount_paise"),
                rs.getLong("commission_paise"), rs.getLong("professional_share_paise"), rs.getString("currency")));
        Long total = jdbc.queryForObject(PROFESSIONAL_COUNT, params, Long.class);
        return new Page<>(items, total == null ? 0 : total);
    }

    /** Every PAID payment, newest first, with its booking's status. */
    public Page<AdminPaymentRow> adminPayments(int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("size", size).addValue("offset", (long) page * size);
        List<AdminPaymentRow> items = jdbc.query(ADMIN_LIST, params, (rs, i) -> {
            String bookingStatus = rs.getString("booking_status");
            return new AdminPaymentRow(rs.getObject("payment_id", UUID.class), rs.getObject("booking_id", UUID.class),
                    rs.getObject("professional_id", UUID.class), rs.getString("professional_name"),
                    inIndia(instant(rs, "preferred_datetime")), instant(rs, "paid_at"), rs.getLong("amount_paise"),
                    rs.getLong("commission_paise"), rs.getLong("professional_share_paise"), rs.getString("currency"),
                    bookingStatus, "CONFIRMED".equals(bookingStatus));
        });
        Long total = jdbc.queryForObject(ADMIN_COUNT, params, Long.class);
        return new Page<>(items, total == null ? 0 : total);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime inIndia(Instant instant) {
        return instant == null ? null : instant.atZone(AvailabilityService.ZONE).toOffsetDateTime();
    }
}
