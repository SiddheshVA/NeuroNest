package com.neuronest.backend.meeting;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Google Meet space of a CONFIRMED video booking. Created after the confirmation has committed, on the
 * mail executor (BookingNotificationListener), so the payment and the webhook never wait for Google and are
 * never affected by it: a failure only leaves the booking without a link.
 *
 * <p>The space is created RESTRICTED (nobody can enter) and its meetingUri saved as the booking's
 * meeting_link; it is opened for the session's time window only (MeetingJoinService, MeetingAccessJob).
 * Idempotent: the booking row is locked while the space is made, and a booking that already has a link is
 * left alone, so a repeated confirmation (or a retry racing it) never creates a second space. Google is
 * tried up to {@link #ATTEMPTS} times with a short backoff; after that the booking is flagged
 * manual_meeting_link_needed. MeetingAccessJob then retries it every minute until the session ends
 * ({@link #retryLink}), and the professional can still add a link by hand (which has no space and no window
 * automation).
 */
@Service
public class MeetingLinkService {

    static final int ATTEMPTS = 3;
    /** Waits before the 2nd and 3rd attempts. */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

    /** Pauses between attempts; tests pass one that does not sleep. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(MeetingLinkService.class);

    private final BookingRepository bookings;
    private final GoogleMeetService google;
    private final MeetingWindow window;
    private final TransactionOperations transactions;
    private final Sleeper sleeper;

    @Autowired
    public MeetingLinkService(BookingRepository bookings, GoogleMeetService google, MeetingWindow window,
                              PlatformTransactionManager transactionManager) {
        this(bookings, google, window, new TransactionTemplate(transactionManager),
                duration -> Thread.sleep(duration.toMillis()));
    }

    MeetingLinkService(BookingRepository bookings, GoogleMeetService google, MeetingWindow window,
                       TransactionOperations transactions, Sleeper sleeper) {
        this.bookings = bookings;
        this.google = google;
        this.window = window;
        this.transactions = transactions;
        this.sleeper = sleeper;
    }

    /**
     * Makes sure a CONFIRMED video booking has its Meet space: creates a RESTRICTED one if it has no link,
     * saving the space name and its meetingUri; flags the booking for a manual link if Google fails every
     * attempt. Does nothing for a phone or in-person booking, one that is not CONFIRMED, or one that already
     * has a link.
     */
    public LinkResult ensureLink(UUID bookingId) {
        return create(bookingId, false);
    }

    /**
     * For a booking whose space could not be made (flagged manual_meeting_link_needed, or simply without a
     * link): tries again, up to {@link #ATTEMPTS} times, as long as its session has not ended. On success the
     * flag is cleared, the space saved and, inside the join window, opened. The caller sends the link emails
     * (MeetingLinkRecoveryService).
     */
    public LinkResult retryLink(UUID bookingId) {
        return create(bookingId, true);
    }

    /** What a create or retry did. */
    public enum LinkResult {
        /** A space was made now. */
        CREATED,
        /** It already had a link (made earlier, or added by the professional). */
        ALREADY_HAS_LINK,
        /** Google failed every attempt (or is not configured): flagged for a manual link. */
        FAILED,
        /** Not a confirmed video booking, flagged and not retried here, or its session has ended. */
        NOT_APPLICABLE
    }

    private LinkResult create(UUID bookingId, boolean retry) {
        return transactions.execute(status -> {
            Booking b = bookings.findByIdForUpdate(bookingId).orElse(null);
            if (b == null || b.getStatus() != BookingStatus.CONFIRMED || !b.isVideo()) return LinkResult.NOT_APPLICABLE;
            if (b.getMeetingLink() != null) return LinkResult.ALREADY_HAS_LINK;
            if (b.isManualMeetingLinkNeeded() && !retry) return LinkResult.NOT_APPLICABLE;
            if (retry && window.hasEnded(b)) return LinkResult.NOT_APPLICABLE;
            GoogleMeetService.Space space;
            try {
                space = withRetries("create the meeting space", b.getId(), google::createSpace);
            } catch (RuntimeException e) {
                space = null;
            }
            if (space == null) {
                b.setManualMeetingLinkNeeded(true);
                bookings.saveAndFlush(b);
                log.warn("MANUAL MEETING LINK NEEDED: no Google Meet space could be created for booking {}{}", b.getId(),
                        retry ? " (retry)" : "; MeetingAccessJob tries again every minute until the session ends");
                return LinkResult.FAILED;
            }
            b.setMeetingMethod("VIDEO");
            b.setMeetingSpaceName(space.name());
            b.setMeetingLink(space.meetingUri());
            b.setManualMeetingLinkNeeded(false);
            openIfWindowStarted(b);
            bookings.saveAndFlush(b);
            return LinkResult.CREATED;
        });
    }

    // A last-minute booking confirmed after its window opened: the space is opened at once, so the join
    // button works straight away (MeetingAccessJob would only get to it on its next run). If Google fails,
    // the job or the first join opens it instead.
    private void openIfWindowStarted(Booking b) {
        if (!window.isOpenNow(b)) return;
        try {
            withRetries("open the meeting space", b.getId(), () -> {
                google.setAccessType(b.getMeetingSpaceName(), GoogleMeetService.AccessType.OPEN);
                return null;
            });
            b.setMeetingAccessOpen(true);
        } catch (RuntimeException e) {
            // Left closed; logged in withRetries.
        }
    }

    /**
     * Opens the booking's space for its session (anyone with the link joins without knocking) and records
     * it, unless it is already open. Throws when Google could not open it after the retries.
     */
    public void open(Booking b) {
        if (b.getMeetingSpaceName() == null || b.isMeetingAccessOpen()) return;
        withRetries("open the meeting space", b.getId(), () -> {
            google.setAccessType(b.getMeetingSpaceName(), GoogleMeetService.AccessType.OPEN);
            return null;
        });
        bookings.markMeetingAccessOpen(b.getId());
        b.setMeetingAccessOpen(true);
    }

    /**
     * Best effort, for a cancelled booking: ends any call running in its space and makes the space
     * RESTRICTED again, so the link no longer lets anyone in. Every failure is logged and ignored.
     */
    public void closeSpace(UUID bookingId) {
        try {
            Booking b = bookings.findById(bookingId).orElse(null);
            if (b == null || b.getMeetingSpaceName() == null || b.isMeetingAccessClosed()) return;
            String space = b.getMeetingSpaceName();
            withRetries("end the call", bookingId, () -> {
                google.endActiveConference(space);
                return null;
            });
            withRetries("restrict the meeting space", bookingId, () -> {
                google.setAccessType(space, GoogleMeetService.AccessType.RESTRICTED);
                return null;
            });
            bookings.markMeetingAccessClosed(bookingId);
        } catch (RuntimeException e) {
            log.warn("Meet space not closed for cancelled booking {}: {}", bookingId, e.getClass().getSimpleName());
        }
    }

    // Up to ATTEMPTS tries, waiting BACKOFF between them; the last failure is thrown. "Not configured" is not
    // retried. Only our own messages are logged (they never carry a token).
    private <T> T withRetries(String what, UUID bookingId, Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (GoogleMeetService.NotConfiguredException e) {
                log.warn("Could not {} for booking {}: {}", what, bookingId, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                log.warn("Could not {} for booking {} (attempt {} of {}): {}", what, bookingId, attempt, ATTEMPTS,
                        e instanceof GoogleMeetService.GoogleMeetException ? e.getMessage() : e.getClass().getSimpleName());
                if (attempt == ATTEMPTS) throw e;
                try {
                    sleeper.sleep(BACKOFF.get(attempt - 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
