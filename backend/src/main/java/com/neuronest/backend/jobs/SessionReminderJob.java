package com.neuronest.backend.jobs;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.notification.NotificationService;
import com.neuronest.backend.notification.SessionMessages;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every minute: the reminder to both the client and the professional, notifications.reminder.minutes-before
// (10) minutes before a CONFIRMED meeting starts, through every enabled channel. Each booking is claimed
// (session_reminder_sent = true) before sending, so it is reminded once even with several app copies. A
// session booked less than 10 minutes before it starts gets no reminder, and one whose moment passed more
// than LATEST ago (the app was down) gets none either, rather than a wrong "starts in 10 minutes". Runs even
// when the other background jobs (JOBS_ENABLED) are off; off only with notifications.reminder.enabled=false.
@Component
@ConditionalOnProperty(name = "notifications.reminder.enabled", havingValue = "true", matchIfMissing = true)
public class SessionReminderJob {

    /** How long after its moment a reminder may still go out. */
    static final Duration LATEST = Duration.ofMinutes(3);
    static final int BATCH = 50;

    private static final Logger log = LoggerFactory.getLogger(SessionReminderJob.class);

    private final BookingJobStore store;
    private final BookingRepository bookings;
    private final ProfessionalRepository professionals;
    private final SessionMessages messages;
    private final NotificationService notifications;
    private final long minutesBefore;
    private final Clock clock;

    @Autowired
    public SessionReminderJob(BookingJobStore store, BookingRepository bookings, ProfessionalRepository professionals,
                              SessionMessages messages, NotificationService notifications,
                              @Value("${notifications.reminder.minutes-before:10}") long minutesBefore) {
        this(store, bookings, professionals, messages, notifications, minutesBefore, Clock.systemUTC());
    }

    SessionReminderJob(BookingJobStore store, BookingRepository bookings, ProfessionalRepository professionals,
                       SessionMessages messages, NotificationService notifications, long minutesBefore, Clock clock) {
        this.store = store;
        this.bookings = bookings;
        this.professionals = professionals;
        this.messages = messages;
        this.notifications = notifications;
        this.minutesBefore = minutesBefore;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT45S")
    public void run() {
        Instant now = clock.instant();
        // Meetings starting at most minutesBefore from now, and not more than LATEST sooner than that.
        Instant to = now.plus(Duration.ofMinutes(minutesBefore));
        List<UUID> due;
        try {
            due = store.claimSessionReminders(now, to.minus(LATEST), to, minutesBefore, BATCH);
        } catch (RuntimeException e) {
            log.warn("Session reminders not claimed: {}", e.getClass().getName());
            return;
        }
        for (UUID id : due) {
            try {
                Optional<Booking> booking = bookings.findById(id).filter(b -> b.getStatus() == BookingStatus.CONFIRMED);
                Optional<Professional> professional = booking.flatMap(b -> professionals.findById(b.getProfessionalId()));
                if (booking.isEmpty() || professional.isEmpty()) continue;
                notifications.send(messages.reminder(booking.get(), professional.get(), minutesBefore));
            } catch (RuntimeException e) {
                log.warn("Session reminder failed: {}", e.getClass().getName());
            }
        }
    }
}
