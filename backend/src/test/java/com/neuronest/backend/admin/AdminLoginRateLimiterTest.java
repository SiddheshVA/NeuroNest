package com.neuronest.backend.admin;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AdminLoginRateLimiterTest {

    private static final String IP = "203.0.113.7";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T10:00:00Z"));
    private final AdminLoginRateLimiter limiter = new AdminLoginRateLimiter(clock);

    @Test
    void blocksForFifteenMinutesAfterFiveFailures() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP);
        }
        assertThat(limiter.blockedFor(IP)).isEmpty();

        limiter.recordFailure(IP);
        assertThat(limiter.blockedFor(IP)).contains(Duration.ofMinutes(15));
        assertThat(limiter.blockedFor("198.51.100.1")).isEmpty();

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(limiter.blockedFor(IP)).contains(Duration.ofSeconds(1));

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.blockedFor(IP)).isEmpty();
    }

    @Test
    void failuresOlderThanTheWindowDoNotCount() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP);
        }
        clock.advance(Duration.ofMinutes(15));
        limiter.recordFailure(IP);

        assertThat(limiter.blockedFor(IP)).isEmpty();
    }

    @Test
    void successResetsTheCount() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP);
        }
        limiter.recordSuccess(IP);
        limiter.recordFailure(IP);

        assertThat(limiter.blockedFor(IP)).isEmpty();
    }

    @Test
    void credentialComparisonIsExact() {
        assertThat(AdminController.constantTimeEquals("admin", "admin")).isTrue();
        assertThat(AdminController.constantTimeEquals("admin ", "admin")).isFalse();
        assertThat(AdminController.constantTimeEquals(null, "admin")).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
