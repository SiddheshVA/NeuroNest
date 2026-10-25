package com.neuronest.backend.notification;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.notification.BookingEmails.BookingEmail;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.PublicProfessionalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Sends the booking emails after the booking change has committed, on the small mail executor, so mail
 * can never fail, roll back or slow a booking request. A confirmation goes to both sides through every
 * enabled channel (SessionMessages, NotificationService); every other event is an email (BookingEmails). Every failure is caught and logged as one line
 * with the event type and the exception class only: never an address, a subject, a body or an
 * exception message. A failed email is retried once, 30 seconds later.
 */
@Component
public class BookingNotificationListener {

    static final Duration RETRY_AFTER = Duration.ofSeconds(30);
    static final int SIMILAR_IN_EXPIRY_EMAIL = 3;

    private static final Logger log = LoggerFactory.getLogger(BookingNotificationListener.class);

    private final BookingRepository bookings;
    private final ProfessionalRepository professionals;
    private final PublicProfessionalService directory;
    private final BookingEmails emails;
    private final EmailSender sender;
    private final MailSettings settings;
    private final MailRetryScheduler retries;
    private final SessionMessages sessionMessages;
    private final NotificationService notifications;
    private final Clock clock;

    @Autowired
    public BookingNotificationListener(BookingRepository bookings, ProfessionalRepository professionals,
                                       PublicProfessionalService directory, BookingEmails emails, EmailSender sender,
                                       MailSettings settings, MailRetryScheduler retries,
                                       SessionMessages sessionMessages, NotificationService notifications) {
        this(bookings, professionals, directory, emails, sender, settings, retries, sessionMessages, notifications,
                Clock.systemUTC());
    }

    BookingNotificationListener(BookingRepository bookings, ProfessionalRepository professionals,
                                PublicProfessionalService directory, BookingEmails emails, EmailSender sender,
                                MailSettings settings, MailRetryScheduler retries,
                                SessionMessages sessionMessages, NotificationService notifications, Clock clock) {
        this.bookings = bookings;
        this.professionals = professionals;
        this.directory = directory;
        this.emails = emails;
        this.sender = sender;
        this.settings = settings;
        this.retries = retries;
        this.sessionMessages = sessionMessages;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Async(MailConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingEvent(BookingEvent event) {
        handle(event);
    }

    void handle(BookingEvent event) {
        BookingEvent.Type type = event.type();
        try {
            Optional<Booking> booking = bookings.findById(event.bookingId());
            Optional<Professional> professional = booking.flatMap(b -> professionals.findById(b.getProfessionalId()));
            if (booking.isEmpty() || professional.isEmpty()) {
                log.warn("Email skipped: booking not found ({})", type);
                return;
            }
            if (type == BookingEvent.Type.CONFIRMED) {
                notifications.send(sessionMessages.confirmed(booking.get(), professional.get()));
                return;
            }
            List<Professional> similar = sendsExpiryEmail(type) ? similarTo(professional.get(), type) : List.of();
            for (BookingEmail email : emails.build(type, booking.get(), professional.get(), similar)) {
                if (!settings.enabled()) {
                    log.info("Email skipped: mail not configured ({})", type);
                } else if (email.email().to() == null || email.email().to().isBlank()) {
                    log.warn("Email skipped: no recipient address ({})", type);
                } else {
                    attempt(event, email, true);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Email failed ({}): {}", type, e.getClass().getName());
        }
    }

    // The events whose retry is the background jobs' next run rather than the 30-second retry.
    private static boolean isExpiry(BookingEvent.Type type) {
        return type == BookingEvent.Type.EXPIRED || type == BookingEvent.Type.EXPIRY_RETRY;
    }

    // The events that send the user the expiry email (with similar professionals and support lines).
    private static boolean sendsExpiryEmail(BookingEvent.Type type) {
        return isExpiry(type) || type == BookingEvent.Type.CLOSED_BY_ADMIN;
    }

    // Suggestions are optional: if they cannot be loaded the email goes out without them.
    private List<Professional> similarTo(Professional p, BookingEvent.Type type) {
        try {
            return directory.similarBookable(p, SIMILAR_IN_EXPIRY_EMAIL);
        } catch (RuntimeException e) {
            log.warn("Similar professionals not loaded ({}): {}", type, e.getClass().getName());
            return List.of();
        }
    }

    private void attempt(BookingEvent event, BookingEmail email, boolean retryOnFailure) {
        // The user's expiry email is retried by the next background-job run instead (expiry_notified_at).
        boolean retry = retryOnFailure && !isExpiry(event.type());
        try {
            sender.send(email.email());
        } catch (Exception e) {
            log.warn("Email failed ({}): {}{}", event.type(), e.getClass().getName(),
                    retry ? "; retrying once in 30 seconds" : "; not retried here");
            if (retry) {
                try {
                    retries.schedule(() -> attempt(event, email, false), RETRY_AFTER);
                } catch (RuntimeException scheduleFailure) {
                    log.warn("Email retry not scheduled ({}): {}", event.type(), scheduleFailure.getClass().getName());
                }
            }
            return;
        }
        try {
            if (event.type() == BookingEvent.Type.REQUEST_CREATED && email.recipient() == BookingEmails.Recipient.PROFESSIONAL) {
                bookings.markProfessionalNotified(event.bookingId(), clock.instant());
            } else if (sendsExpiryEmail(event.type()) && email.recipient() == BookingEmails.Recipient.USER) {
                bookings.markExpiryNotified(event.bookingId(), clock.instant());
            }
        } catch (RuntimeException e) {
            log.warn("Could not record the sent email ({}): {}", event.type(), e.getClass().getName());
        }
    }
}
