package com.neuronest.backend.booking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommissionSplitTest {

    @Test
    void tenPercentOfARoundAmount() {
        assertThat(CommissionSplit.split(80_000, 10)).isEqualTo(new CommissionSplit.Split(10, 8_000, 72_000));
        assertThat(new CommissionSplit(10).split(150_000)).isEqualTo(new CommissionSplit.Split(10, 15_000, 135_000));
    }

    @Test
    void oddAmountsRoundHalfUpAndStillAddUp() {
        // 1234.5 paise rounds up; 0.5 rounds up; 0.4 rounds down.
        assertThat(CommissionSplit.split(12_345, 10)).isEqualTo(new CommissionSplit.Split(10, 1_235, 11_110));
        assertThat(CommissionSplit.split(5, 10)).isEqualTo(new CommissionSplit.Split(10, 1, 4));
        assertThat(CommissionSplit.split(4, 10)).isEqualTo(new CommissionSplit.Split(10, 0, 4));
        assertThat(CommissionSplit.split(99_999, 15)).isEqualTo(new CommissionSplit.Split(15, 15_000, 84_999));
        assertThat(CommissionSplit.split(0, 10)).isEqualTo(new CommissionSplit.Split(10, 0, 0));

        for (int percent = 0; percent <= 100; percent++) {
            for (long amount = 0; amount <= 2_000; amount++) {
                CommissionSplit.Split s = CommissionSplit.split(amount, percent);
                assertThat(s.commissionPaise() + s.professionalSharePaise()).isEqualTo(amount);
                assertThat(s.commissionPaise()).isBetween(0L, amount);
                // Half up: within half a paisa of the exact commission, and .5 always goes up.
                long twiceExact = 2 * amount * percent;
                assertThat(Math.abs(200 * s.commissionPaise() - twiceExact)).isLessThanOrEqualTo(100);
                if ((amount * percent) % 100 == 50) assertThat(200 * s.commissionPaise()).isGreaterThan(twiceExact);
            }
        }
    }

    @Test
    void theEdgesOfTheSetting() {
        assertThat(CommissionSplit.split(1_000, 0)).isEqualTo(new CommissionSplit.Split(0, 0, 1_000));
        assertThat(CommissionSplit.split(1_000, 100)).isEqualTo(new CommissionSplit.Split(100, 1_000, 0));
        assertThatThrownBy(() -> new CommissionSplit(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CommissionSplit(101)).isInstanceOf(IllegalStateException.class);
    }
}
