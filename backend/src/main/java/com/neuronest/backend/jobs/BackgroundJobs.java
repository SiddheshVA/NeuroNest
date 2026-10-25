package com.neuronest.backend.jobs;

import com.neuronest.backend.notification.BookingEvent;
import com.neuronest.backend.professional.AvailabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One run of the background jobs. Each task runs in its own transaction and its own try/catch, so one
 * failing task never stops the others, and logs one line with a count only. Emails are not sent here:
 * each task publishes BookingEvents, which the notification layer sends after the transaction commits.
 * A run that starts while another is still going in this copy of the app is skipped; other copies are
 * kept apart by the store's atomic claims.
 */
public class BackgroundJobs {

    static final int BATCH = 100;
    static final Duration REMINDER_WINDOW = Duration.ofHours(24);
    static final Duration RETRY_WINDOW = Duration.ofHours(24);
    // An expiry email is retried only once its first attempt has had time to finish (it runs in the
    // background after the commit).
    static final Duration RETRY_AFTER = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    private final BookingJobStore store;
    private final ApplicationEventPublisher events;
    private final TransactionOperations transactions;
    /** Extra time after payment_due_at before an unpaid booking expires, for a checkout still finishing. */
    static final Duration DEFAULT_PAYMENT_GRACE = Duration.ofMinutes(15);

    private final Clock clock;
    private final Duration paymentGrace;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BackgroundJobs(BookingJobStore store, ApplicationEventPublisher events, TransactionOperations transactions,
                          Clock clock) {
        this(store, events, transactions, clock, DEFAULT_PAYMENT_GRACE);
    }

    public BackgroundJobs(BookingJobStore store, ApplicationEventPublisher events, TransactionOperations transactions,
                          Clock clock, Duration paymentGrace) {
        this.store = store;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.paymentGrace = paymentGrace;
    }

    /** Runs every task once. Returns false when another run was still going and this one was skipped. */
    public boolean runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.info("Background jobs: the previous run is still going, so this run is skipped.");
            return false;
        }
        try {
            // The retry goes first, so requests expired in this run are never retried in the same run.
            task("expiry email retries", this::retryExpiryEmails);
            task("expired requests", this::expireRequests);
            task("unpaid bookings", this::expireUnpaidBookings);
            task("reminders", this::sendReminders);
            task("expired registrations", this::expireRegistrations);
            log.info("Background jobs: run complete.");
            return true;
        } finally {
            running.set(false);
        }
    }

    /**
     * Releases the slots of bookings whose 10-minute payment hold has ended unpaid. Runs every minute on
     * its own (BackgroundJobsConfig), whether or not the other jobs are enabled; the claim is atomic, so it
     * never clashes with runOnce doing the same.
     */
    public void releaseExpiredHolds() {
        task("expired payment holds", this::expireUnpaidBookings);
    }

    private void task(String name, Supplier<Integer> work) {
        try {
            log.info("Background job {}: {} processed.", name, work.get());
        } catch (RuntimeException e) {
            log.warn("Background job {} failed: {}", name, e.getClass().getName());
        }
    }

    int retryExpiryEmails() {
        Instant now = clock.instant();
        return claimAndPublish(() -> store.claimExpiryEmailRetries(now, now.minus(RETRY_WINDOW), now.minus(RETRY_AFTER),
                BATCH), BookingEvent.Type.EXPIRY_RETRY);
    }

    int expireRequests() {
        Instant now = clock.instant();
        return claimAndPublish(() -> store.expireOverdueRequests(now, BATCH), BookingEvent.Type.EXPIRED);
    }

    // Same EXPIRED status and event as an unanswered request, so the slot is released, both sides are
    // emailed, and the user's email gets the usual one retry. The email wording follows payment_due_at.
    int expireUnpaidBookings() {
        Instant now = clock.instant();
        return claimAndPublish(() -> store.expireUnpaidBookings(now, now.minus(paymentGrace), BATCH),
                BookingEvent.Type.EXPIRED);
    }

    int sendReminders() {
        Instant now = clock.instant();
        return claimAndPublish(() -> store.claimReminders(now, now.plus(REMINDER_WINDOW), BATCH),
                BookingEvent.Type.REMINDER);
    }

    int expireRegistrations() {
        Instant now = clock.instant();
        LocalDate today = now.atZone(AvailabilityService.ZONE).toLocalDate();
        Integer count = transactions.execute(status -> store.expireRegistrations(today, now, BATCH).size());
        return count == null ? 0 : count;
    }

    // The claim and its events share one transaction, so the emails go out only once the claim is committed.
    private int claimAndPublish(Supplier<List<UUID>> claim, BookingEvent.Type type) {
        Integer count = transactions.execute(status -> {
            List<UUID> ids = claim.get();
            ids.forEach(id -> events.publishEvent(new BookingEvent(type, id)));
            return ids.size();
        });
        return count == null ? 0 : count;
    }
}
