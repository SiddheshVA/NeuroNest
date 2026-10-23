package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.AdminBookingItem;
import com.neuronest.backend.admin.AdminBookingDtos.CloseRequest;
import com.neuronest.backend.admin.AdminBookingDtos.DashboardSummary;
import com.neuronest.backend.admin.AdminBookingDtos.PaymentSummary;
import com.neuronest.backend.admin.AdminBookingDtos.RefundNeededItem;
import com.neuronest.backend.admin.AdminBookingDtos.ResponsivenessItem;
import com.neuronest.backend.admin.AdminProfessionalDtos.PageResponse;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.notification.BookingEvent;
import com.neuronest.backend.payment.PaymentPeriods;
import com.neuronest.backend.payment.PaymentReportQueries;
import com.neuronest.backend.payment.PaymentReportQueries.AdminPaymentRow;
import com.neuronest.backend.professional.AvailabilityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

// The admin's view of booking requests (dashboard counts, the list, each professional's responsiveness)
// and two actions: remind the professional (nudge) and close a request. Both actions claim the row with
// one atomic UPDATE, so two quick clicks act once, and their emails go out through the booking
// notification layer after the commit. Nothing here logs names, notes or contact details.
@Service
public class AdminBookingService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 90;
    static final int MIN_CLOSE_NOTE = 10;
    static final int MAX_CLOSE_NOTE = 500;
    static final Duration NUDGE_GAP = Duration.ofHours(12);
    static final int MAX_UNANSWERED_HOURS = 24 * 365;

    private final AdminBookingQueries queries;
    private final ApplicationEventPublisher events;
    private final PaymentReportQueries payments;
    private final Clock clock;

    @Autowired
    public AdminBookingService(AdminBookingQueries queries, ApplicationEventPublisher events,
                               PaymentReportQueries payments) {
        this(queries, events, payments, Clock.system(AvailabilityService.ZONE));
    }

    AdminBookingService(AdminBookingQueries queries, ApplicationEventPublisher events, PaymentReportQueries payments,
                        Clock clock) {
        this.queries = queries;
        this.events = events;
        this.payments = payments;
        this.clock = clock;
    }

    // ---- Reading ----

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        Instant now = clock.instant();
        return queries.summary(now, now.atZone(AvailabilityService.ZONE).toLocalDate());
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminBookingItem> list(String status, Integer unansweredOver, UUID professionalId, int page, int size) {
        checkPage(page, size);
        String statusFilter = status(status);
        if (unansweredOver != null && (unansweredOver < 0 || unansweredOver > MAX_UNANSWERED_HOURS)) {
            throw new ApiValidationException("unansweredOver must be a number of hours between 0 and " + MAX_UNANSWERED_HOURS + ".");
        }
        Instant now = clock.instant();
        Instant waitingSince = unansweredOver == null ? null : now.minus(Duration.ofHours(unansweredOver));
        var result = queries.list(statusFilter, waitingSince, professionalId, now, page, size);
        return new PageResponse<>(result.items(), page, size, result.total(), totalPages(result.total(), size));
    }

    // ---- Payments and commission (read-only) ----

    @Transactional(readOnly = true)
    public PaymentSummary paymentSummary() {
        PaymentPeriods periods = PaymentPeriods.at(clock.instant());
        return new PaymentSummary(periods.month().toString(), payments.totals(null, null, null),
                payments.totals(null, periods.monthStart(), periods.monthEnd()));
    }

    /** Every PAID payment, newest first; counted is false for one whose booking is not CONFIRMED. */
    @Transactional(readOnly = true)
    public PageResponse<AdminPaymentRow> payments(int page, int size) {
        checkPage(page, size);
        var result = payments.adminPayments(page, size);
        return new PageResponse<>(result.items(), page, size, result.total(), totalPages(result.total(), size));
    }

    // Read-only: refunds are made by hand in the Razorpay dashboard.
    @Transactional(readOnly = true)
    public PageResponse<RefundNeededItem> refundsNeeded(int page, int size) {
        checkPage(page, size);
        var result = queries.refundsNeeded(page, size);
        return new PageResponse<>(result.items(), page, size, result.total(), totalPages(result.total(), size));
    }

    @Transactional(readOnly = true)
    public PageResponse<ResponsivenessItem> responsiveness(int days, int page, int size) {
        checkPage(page, size);
        if (days < 1 || days > MAX_DAYS) {
            throw new ApiValidationException("days must be between 1 and " + MAX_DAYS + ".");
        }
        Instant now = clock.instant();
        var result = queries.responsiveness(now.minus(Duration.ofDays(days)), now, page, size);
        return new PageResponse<>(result.items(), page, size, result.total(), totalPages(result.total(), size));
    }

    // ---- Actions ----

    /** Sends the professional the reminder email again. At most once every 12 hours, only while PENDING. */
    @Transactional
    public AdminBookingItem nudge(UUID id) {
        Instant now = clock.instant();
        if (!queries.claimNudge(id, now, now.minus(NUDGE_GAP))) {
            var state = queries.state(id).orElseThrow(AdminBookingNotFoundException::new);
            boolean waiting = BookingStatus.PENDING.name().equals(state.status())
                    && state.expiresAt() != null && state.expiresAt().isAfter(now);
            throw new ConflictException(waiting ? "This professional was reminded recently."
                    : "Only a request that is still waiting for the professional's reply can be nudged.");
        }
        events.publishEvent(new BookingEvent(BookingEvent.Type.REMINDER, id));
        return queries.item(id, now).orElseThrow(AdminBookingNotFoundException::new);
    }

    /**
     * Closes an open request as EXPIRED on NeuroNest's behalf. The user gets the same email as a normal
     * expiry and the professional is told it was closed; the admin note is stored but never emailed.
     */
    @Transactional
    public AdminBookingItem close(UUID id, CloseRequest request) {
        String note = note(request == null ? null : request.note());
        Instant now = clock.instant();
        if (!queries.claimClose(id, note, now)) {
            queries.state(id).orElseThrow(AdminBookingNotFoundException::new);
            throw new ConflictException("Only a request that is waiting for the professional or for the person can be "
                    + "closed. This one is already finished.");
        }
        events.publishEvent(new BookingEvent(BookingEvent.Type.CLOSED_BY_ADMIN, id));
        return queries.item(id, now).orElseThrow(AdminBookingNotFoundException::new);
    }

    // ---- Checks ----

    private static void checkPage(int page, int size) {
        if (page < 0) throw new ApiValidationException("page must be 0 or greater.");
        if (size < 1 || size > AdminProfessionalService.MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + AdminProfessionalService.MAX_PAGE_SIZE + ".");
        }
    }

    private static int totalPages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return BookingStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new ApiValidationException("status must be one of " + Arrays.stream(BookingStatus.values())
                    .map(Enum::name).collect(Collectors.joining(", ")) + ".");
        }
    }

    private static String note(String value) {
        String note = value == null ? "" : value.trim();
        if (note.length() < MIN_CLOSE_NOTE || note.length() > MAX_CLOSE_NOTE) {
            throw new ApiValidationException("note is required and must be between " + MIN_CLOSE_NOTE + " and "
                    + MAX_CLOSE_NOTE + " characters.");
        }
        return note;
    }
}
