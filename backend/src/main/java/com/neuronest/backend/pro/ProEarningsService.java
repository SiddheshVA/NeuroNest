package com.neuronest.backend.pro;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.payment.PaymentPeriods;
import com.neuronest.backend.payment.PaymentReportQueries;
import com.neuronest.backend.payment.PaymentReportQueries.ProfessionalPaymentRow;
import com.neuronest.backend.payment.PaymentReportQueries.Totals;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// The professional's own earnings: what users paid for their CONFIRMED bookings, NeuroNest's commission and
// their share. The professional is always the one linked to the verified token; a sign-in without a
// professional account gets 401. No user name, email, phone or id is ever included.
@Service
public class ProEarningsService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    /**
     * GET /api/pro/earnings. Amounts in paise. month is the current month in India (YYYY-MM); thisMonth
     * counts payments made in it.
     */
    public record ProEarnings(String month, Totals allTime, Totals thisMonth, ProEarningsPage payments) {}

    public record ProEarningsPage(List<ProfessionalPaymentRow> items, int page, int size, long totalElements,
                                  int totalPages) {}

    private final ProfessionalAccountRepository accounts;
    private final PaymentReportQueries queries;
    private final Clock clock;

    @Autowired
    public ProEarningsService(ProfessionalAccountRepository accounts, PaymentReportQueries queries) {
        this(accounts, queries, Clock.system(AvailabilityService.ZONE));
    }

    ProEarningsService(ProfessionalAccountRepository accounts, PaymentReportQueries queries, Clock clock) {
        this.accounts = accounts;
        this.queries = queries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProEarnings earnings(ProPrincipal principal, int page, int size) {
        // Who is asking comes first, so a sign-in without a professional account always gets 401.
        UUID professionalId = accounts.findByClerkUserId(principal.clerkUserId())
                .orElseThrow(ProExceptions.NotAProfessionalException::new)
                .getProfessionalId();
        if (page < 0) throw new ApiValidationException("page must be 0 or greater.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        Instant now = clock.instant();
        PaymentPeriods periods = PaymentPeriods.at(now);
        var list = queries.professionalPayments(professionalId, page, size);
        return new ProEarnings(periods.month().toString(),
                queries.totals(professionalId, null, null),
                queries.totals(professionalId, periods.monthStart(), periods.monthEnd()),
                new ProEarningsPage(list.items(), page, size, list.total(), (int) ((list.total() + size - 1) / size)));
    }
}
