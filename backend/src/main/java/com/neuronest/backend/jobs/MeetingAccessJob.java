package com.neuronest.backend.jobs;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.meeting.GoogleMeetService;
import com.neuronest.backend.meeting.MeetingLinkRecovery;
import com.neuronest.backend.meeting.MeetingWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Every minute: retries the Meet space of video bookings whose space could not be made (flagged
// manual_meeting_link_needed, until the session ends; MeetingLinkRecovery emails the link once it works), and
// keeps each space's access in step with its session's time window (MeetingWindow), since Google cannot
// limit a link to a time window by itself.
//  - Open: a CONFIRMED video session's space, not yet opened, whose window has started and not ended, is set
//    OPEN (anyone with the link joins without knocking) and marked meeting_access_open.
//  - Close: an opened space whose session has ended has its call ended and is set RESTRICTED again, then is
//    marked meeting_access_closed.
// Every Google call here is idempotent and each mark is a conditional update, so two overlapping runs (or a
// join at the same moment) do no harm. A failure is simply tried again on the next run; after
// WARN_AFTER_FAILURES runs in a row it is logged as a warning. Links the professional pasted have no space
// and are left alone. Runs even when the other background jobs (JOBS_ENABLED) are off; off only with
// google.meet.access-job.enabled=false.
@Component
@ConditionalOnProperty(name = "google.meet.access-job.enabled", havingValue = "true", matchIfMissing = true)
public class MeetingAccessJob {

    static final int WARN_AFTER_FAILURES = 3;

    private static final Logger log = LoggerFactory.getLogger(MeetingAccessJob.class);

    private final BookingRepository bookings;
    private final GoogleMeetService google;
    private final MeetingWindow window;
    private final MeetingLinkRecovery recovery;
    private final Clock clock;
    // Failed runs in a row per booking and step, for the warning only.
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    @Autowired
    public MeetingAccessJob(BookingRepository bookings, GoogleMeetService google, MeetingWindow window,
                            MeetingLinkRecovery recovery) {
        this(bookings, google, window, recovery, Clock.systemUTC());
    }

    // With a clock, for tests.
    public MeetingAccessJob(BookingRepository bookings, GoogleMeetService google, MeetingWindow window,
                            MeetingLinkRecovery recovery, Clock clock) {
        this.bookings = bookings;
        this.google = google;
        this.window = window;
        this.recovery = recovery;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT50S")
    public void run() {
        if (!google.isConfigured()) return;
        Instant now = clock.instant();
        retryMissingLinks(now);
        openDue(now);
        closeEnded(now);
    }

    // Self-healing: a video booking whose space could not be made at confirmation is tried again every run
    // (up to 3 attempts each time) until its session ends; when it works, both get the link by email.
    void retryMissingLinks(Instant now) {
        for (Booking b : find(() -> bookings.findMeetingLinksToRetry(now))) {
            try {
                recovery.retry(b.getId());
            } catch (RuntimeException e) {
                log.warn("Meet link retry failed for booking {}: {}", b.getId(), e.getClass().getName());
            }
        }
    }

    void openDue(Instant now) {
        for (Booking b : find(() -> bookings.findMeetingSpacesToOpen(now.plus(window.openEarly()), now))) {
            attempt("open", b.getId(), () -> {
                google.setAccessType(b.getMeetingSpaceName(), GoogleMeetService.AccessType.OPEN);
                bookings.markMeetingAccessOpen(b.getId());
            });
        }
    }

    void closeEnded(Instant now) {
        for (Booking b : find(() -> bookings.findMeetingSpacesToClose(now))) {
            attempt("close", b.getId(), () -> {
                google.endActiveConference(b.getMeetingSpaceName());
                google.setAccessType(b.getMeetingSpaceName(), GoogleMeetService.AccessType.RESTRICTED);
                bookings.markMeetingAccessClosed(b.getId());
            });
        }
    }

    private java.util.List<Booking> find(java.util.function.Supplier<java.util.List<Booking>> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            log.warn("Meet access job could not read bookings: {}", e.getClass().getName());
            return java.util.List.of();
        }
    }

    private void attempt(String step, UUID bookingId, Runnable action) {
        String key = step + ":" + bookingId;
        try {
            action.run();
            failures.remove(key);
        } catch (RuntimeException e) {
            int count = failures.merge(key, 1, Integer::sum);
            String reason = e instanceof GoogleMeetService.GoogleMeetException ? e.getMessage() : e.getClass().getSimpleName();
            if (count >= WARN_AFTER_FAILURES) {
                log.warn("Meet space could not {} for booking {} ({} runs in a row): {}", step, bookingId, count, reason);
            } else {
                log.info("Meet space could not {} for booking {}; trying again next run: {}", step, bookingId, reason);
            }
        }
    }

    int failuresFor(String step, UUID bookingId) {
        return failures.getOrDefault(step + ":" + bookingId, 0);
    }
}
