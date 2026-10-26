package com.neuronest.backend.meeting;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.MeetingTimes;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * "Join session": the only place a session's raw video link is given out, to the booking's own user or
 * professional (the controllers check that), and only inside the session's window (MeetingWindow): from a
 * few minutes before the start until the end. Inside it, an automatic Meet space is opened first (anyone
 * with the link enters without knocking); a link the professional pasted is simply returned.
 */
@Service
public class MeetingJoinService {

    public record JoinLink(String meetingUri) {}

    public static final String LINK_NOT_READY_MESSAGE =
            "We're setting up your video link. This usually takes a minute. You'll also get it by email.";

    /** 409: before or after the window. Carries the window, never the link. */
    public static class NotOpenException extends RuntimeException {
        private final String code;
        private final OffsetDateTime opensAt;
        private final OffsetDateTime endsAt;

        NotOpenException(MeetingWindow.Position position, Instant opensAt, Instant endsAt) {
            super(position == MeetingWindow.Position.TOO_EARLY
                    ? "Your join button becomes active at " + MeetingTimes.dayAndTime(opensAt) + " (IST)."
                    : "This session has ended.");
            this.code = position.name();
            this.opensAt = inIndia(opensAt);
            this.endsAt = inIndia(endsAt);
        }

        public String code() { return code; }
        public OffsetDateTime opensAt() { return opensAt; }
        public OffsetDateTime endsAt() { return endsAt; }
    }

    /** 409: not a confirmed video session, or it has no link yet. */
    public static class UnavailableException extends RuntimeException {
        UnavailableException(String message) {
            super(message);
        }
    }

    /** 503: inside the window, but Google could not open the space. */
    public static class OpenFailedException extends RuntimeException {
        OpenFailedException() {
            super("The video session could not be opened right now. Please try again in a moment.");
        }
    }

    private final ProfessionalRepository professionals;
    private final MeetingLinkService links;
    private final MeetingWindow window;
    private final Clock clock;

    @Autowired
    public MeetingJoinService(ProfessionalRepository professionals, MeetingLinkService links, MeetingWindow window) {
        this(professionals, links, window, Clock.systemUTC());
    }

    MeetingJoinService(ProfessionalRepository professionals, MeetingLinkService links, MeetingWindow window, Clock clock) {
        this.professionals = professionals;
        this.links = links;
        this.window = window;
        this.clock = clock;
    }

    /** The link of a booking the caller owns (checked by the caller), if the session is open now. */
    public JoinLink join(Booking b) {
        if (b.getStatus() != BookingStatus.CONFIRMED || !b.isVideo()) {
            throw new UnavailableException("Only a confirmed video session can be joined.");
        }
        if (b.getMeetingLink() == null) {
            throw new UnavailableException(LINK_NOT_READY_MESSAGE);
        }
        Professional p = professionals.findById(b.getProfessionalId()).orElse(null);
        Instant now = clock.instant();
        MeetingWindow.Position position = window.position(b, p, now);
        if (position != MeetingWindow.Position.OPEN) {
            throw new NotOpenException(position, window.opensAt(b), window.endsAt(b, p));
        }
        try {
            links.open(b);
        } catch (RuntimeException e) {
            throw new OpenFailedException();
        }
        return new JoinLink(b.getMeetingLink());
    }

    private static OffsetDateTime inIndia(Instant instant) {
        return instant == null ? null : instant.atZone(AvailabilityService.ZONE).toOffsetDateTime();
    }
}
