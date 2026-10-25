package com.neuronest.backend.professional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

// The one session grid every professional uses, set by bookings.session-minutes and bookings.break-minutes
// (not per professional). Each session lasts sessionMinutes and is followed by a break, so slots start every
// sessionMinutes + breakMinutes from the start of each weekly-hours range (7:45, 9:00, 10:15 with 60 + 15).
// N consecutive sessions are one meeting of N x sessionMinutes with no break inside it; the breaks it skipped
// are added after it, so the professional's time is blocked for N x (sessionMinutes + breakMinutes) and the
// next free slot is the grid slot right after that. Read through current(), so static helpers and DTOs see
// the configured values; the defaults apply where Spring did not create it (plain unit tests).
public final class SessionGrid {

    public static final int DEFAULT_SESSION_MINUTES = 60;
    public static final int DEFAULT_BREAK_MINUTES = 15;

    private static volatile SessionGrid current = new SessionGrid(DEFAULT_SESSION_MINUTES, DEFAULT_BREAK_MINUTES);

    private final int sessionMinutes;
    private final int breakMinutes;

    public SessionGrid(int sessionMinutes, int breakMinutes) {
        if (sessionMinutes <= 0 || breakMinutes < 0) {
            throw new IllegalArgumentException("bookings.session-minutes must be above 0 and bookings.break-minutes "
                    + "0 or more (got " + sessionMinutes + " and " + breakMinutes + ").");
        }
        this.sessionMinutes = sessionMinutes;
        this.breakMinutes = breakMinutes;
    }

    public static SessionGrid current() {
        return current;
    }

    public int sessionMinutes() { return sessionMinutes; }

    public int breakMinutes() { return breakMinutes; }

    /** From one slot start to the next: a session and its break. */
    public Duration step() {
        return Duration.ofMinutes(sessionMinutes + breakMinutes);
    }

    /** When a meeting of {@code count} sessions from {@code start} ends (no breaks inside it). */
    public Instant meetingEnd(Instant start, int count) {
        return start.plus(Duration.ofMinutes((long) sessionMinutes * count));
    }

    /** Until when {@code count} sessions from {@code start} block the professional's time (the breaks added at the end). */
    public Instant blockedUntil(Instant start, int count) {
        return start.plus(step().multipliedBy(count));
    }

    @Configuration
    static class FromProperties {
        @Bean
        SessionGrid sessionGrid(@Value("${bookings.session-minutes:" + DEFAULT_SESSION_MINUTES + "}") int sessionMinutes,
                                @Value("${bookings.break-minutes:" + DEFAULT_BREAK_MINUTES + "}") int breakMinutes) {
            current = new SessionGrid(sessionMinutes, breakMinutes);
            return current;
        }
    }
}
