package com.neuronest.backend.payment;

import com.neuronest.backend.professional.AvailabilityService;

import java.time.Instant;
import java.time.YearMonth;

/** The current calendar month in India (Asia/Kolkata), as the [start, end) window the reports use. */
public record PaymentPeriods(YearMonth month, Instant monthStart, Instant monthEnd) {

    public static PaymentPeriods at(Instant now) {
        YearMonth month = YearMonth.from(now.atZone(AvailabilityService.ZONE));
        return new PaymentPeriods(month, month.atDay(1).atStartOfDay(AvailabilityService.ZONE).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(AvailabilityService.ZONE).toInstant());
    }
}
