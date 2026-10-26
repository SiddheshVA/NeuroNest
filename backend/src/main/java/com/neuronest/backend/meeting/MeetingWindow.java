package com.neuronest.backend.meeting;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.MeetingTimes;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.SessionGrid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * When a session's video link may be used: from google.meet.open-early-minutes (5) before it starts until
 * the end of its last session (the stored meeting end; one session for an older booking without one).
 * Shared by the join endpoints, the access job and the emails, so they all agree.
 */
@Component
public class MeetingWindow {

    public static final long DEFAULT_OPEN_EARLY_MINUTES = 5;

    private final Duration openEarly;
    private final Clock clock;

    @Autowired
    public MeetingWindow(@Value("${google.meet.open-early-minutes:" + DEFAULT_OPEN_EARLY_MINUTES + "}") long openEarlyMinutes) {
        this(openEarlyMinutes, Clock.systemUTC());
    }

    // With a clock, for tests.
    public MeetingWindow(long openEarlyMinutes, Clock clock) {
        this.openEarly = Duration.ofMinutes(Math.max(0, openEarlyMinutes));
        this.clock = clock;
    }

    public Duration openEarly() {
        return openEarly;
    }

    /** When the join button becomes active. */
    public Instant opensAt(Booking b) {
        return b.scheduledTime() == null ? null : b.scheduledTime().minus(openEarly);
    }

    /** When the session ends and the link stops working. */
    public Instant endsAt(Booking b, Professional p) {
        Instant start = b.scheduledTime();
        if (start == null) return null;
        Instant end = MeetingTimes.end(b, p);
        return end != null && end.isAfter(start) ? end
                : start.plus(Duration.ofMinutes(SessionGrid.current().sessionMinutes()));
    }

    /** Whether the window is open right now (a last-minute booking can be confirmed inside it). */
    public boolean isOpenNow(Booking b) {
        return b.scheduledTime() != null && position(b, null, clock.instant()) == Position.OPEN;
    }

    /** Whether the session has already ended (its link no longer worth making). */
    public boolean hasEnded(Booking b) {
        return b.scheduledTime() != null && position(b, null, clock.instant()) == Position.ENDED;
    }

    /** Before, inside or after the window at {@code now}. */
    public Position position(Booking b, Professional p, Instant now) {
        if (now.isBefore(opensAt(b))) return Position.TOO_EARLY;
        if (!now.isBefore(endsAt(b, p))) return Position.ENDED;
        return Position.OPEN;
    }

    public enum Position { TOO_EARLY, OPEN, ENDED }
}
