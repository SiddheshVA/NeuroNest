package com.neuronest.backend.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * NeuroNest's commission on a paid booking, in one place. Integer math in paise: commission = amount *
 * percent / 100 rounded half up, and the professional's share = amount - commission, so the two always
 * add up exactly to the amount. The percent (payments.commission-percent, default 10) is stored on each
 * payment when it is marked PAID, so changing the setting later never alters an old payment.
 */
@Component
public class CommissionSplit {

    public static final int DEFAULT_PERCENT = 10;

    /** How one paid amount is divided. */
    public record Split(int percent, long commissionPaise, long professionalSharePaise) {}

    private final int percent;

    public CommissionSplit(@Value("${payments.commission-percent:" + DEFAULT_PERCENT + "}") int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalStateException("payments.commission-percent must be between 0 and 100.");
        }
        this.percent = percent;
    }

    /** The split at the configured percent. */
    public Split split(long amountPaise) {
        return split(amountPaise, percent);
    }

    public static Split split(long amountPaise, int percent) {
        if (amountPaise < 0) throw new IllegalArgumentException("amountPaise must not be negative.");
        // Half up: add half the divisor before the integer division (all values are non-negative).
        long commission = Math.addExact(Math.multiplyExact(amountPaise, (long) percent), 50) / 100;
        return new Split(percent, commission, amountPaise - commission);
    }
}
