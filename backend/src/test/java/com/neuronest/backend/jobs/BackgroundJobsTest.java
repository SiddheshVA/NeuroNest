package com.neuronest.backend.jobs;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.SharedAssessment;
import com.neuronest.backend.notification.BookingEmails;
import com.neuronest.backend.notification.BookingEvent;
import com.neuronest.backend.notification.BookingNotificationListener;
import com.neuronest.backend.notification.EmailChannel;
import com.neuronest.backend.notification.EmailSender;
import com.neuronest.backend.notification.MailRetryScheduler;
import com.neuronest.backend.notification.MailSettings;
import com.neuronest.backend.notification.NotificationService;
import com.neuronest.backend.notification.OutgoingEmail;
import com.neuronest.backend.notification.SessionMessages;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.PublicProfessionalService;
import com.neuronest.backend.safety.CrisisResource;
import com.neuronest.backend.safety.CrisisResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// The job flow with a controllable clock, a fake sender and an in-memory store whose claims behave like
// the SQL ones (each row is claimed by exactly one caller). The SQL itself is tested against PostgreSQL
// in JdbcBookingJobStoreTest.
class BackgroundJobsTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Instant START = Instant.parse("2026-10-12T04:30:00Z");
    private static final String BASE_URL = "https://app.neuronest.example";
    private static final String USER_EMAIL = "ravi.user@example.com";
    private static final String PRO_EMAIL = "asha.pro@example.com";
    private static final String NOTE = "PRIVATE-REQUEST-NOTE";

    /** A clock the test moves forward. */
    private static final class TestClock extends Clock {
        private volatile Instant now = START;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return AvailabilityService.ZONE;
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

    /** Records what would have been sent; fails for the recipients `failFor` matches. */
    private static final class FakeSender implements EmailSender {
        final List<OutgoingEmail> sent = new CopyOnWriteArrayList<>();
        volatile Predicate<String> failFor = to -> false;

        @Override
        public void send(OutgoingEmail email) {
            if (failFor.test(email.to())) throw new IllegalStateException("SMTP down");
            sent.add(email);
        }

        List<OutgoingEmail> to(String address) {
            return sent.stream().filter(e -> e.to().equals(address)).toList();
        }
    }

    /** The store's claims, done on in-memory rows; synchronized so each row is claimed once. */
    private static final class InMemoryStore implements BookingJobStore {
        final Map<UUID, Booking> rows = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<LocalDate> registrationDays = new CopyOnWriteArrayList<>();
        volatile RuntimeException failExpire;

        @Override
        public synchronized List<UUID> expireOverdueRequests(Instant now, int limit) {
            if (failExpire != null) throw failExpire;
            List<UUID> claimed = new ArrayList<>();
            for (Booking b : rows.values()) {
                boolean open = b.getStatus() == BookingStatus.PENDING || b.getStatus() == BookingStatus.ALTERNATE_PROPOSED;
                if (open && b.getExpiresAt().isBefore(now) && claimed.size() < limit) {
                    b.setStatus(BookingStatus.EXPIRED);
                    b.setRespondedAt(now);
                    b.setRespondedBy(null);
                    claimed.add(b.getId());
                }
            }
            return claimed;
        }

        @Override
        public synchronized List<UUID> expireUnpaidBookings(Instant now, Instant cutoff, int limit) {
            List<UUID> claimed = new ArrayList<>();
            for (Booking b : rows.values()) {
                if (b.getStatus() == BookingStatus.AWAITING_PAYMENT && b.getPaymentDueAt().isBefore(cutoff)
                        && claimed.size() < limit) {
                    b.setStatus(BookingStatus.EXPIRED);
                    b.setPaymentExpiredAt(now);
                    claimed.add(b.getId());
                }
            }
            return claimed;
        }

        @Override
        public synchronized List<UUID> claimExpiryEmailRetries(Instant now, Instant since, Instant before, int limit) {
            List<UUID> claimed = new ArrayList<>();
            for (Booking b : rows.values()) {
                Instant expiredAt = b.getPaymentExpiredAt() != null ? b.getPaymentExpiredAt()
                        : b.getRespondedBy() == null ? b.getRespondedAt() : null;
                if (b.getStatus() == BookingStatus.EXPIRED && b.getExpiryNotifiedAt() == null
                        && expiredAt != null && !expiredAt.isBefore(since)
                        && expiredAt.isBefore(before) && claimed.size() < limit) {
                    b.setExpiryNotifiedAt(now);
                    claimed.add(b.getId());
                }
            }
            return claimed;
        }

        @Override
        public synchronized List<UUID> claimSessionReminders(Instant now, Instant from, Instant to, long leadMinutes,
                                                             int limit) {
            List<UUID> claimed = new ArrayList<>();
            for (Booking b : rows.values()) {
                Instant start = b.getPreferredDatetime();
                if (b.getStatus() == BookingStatus.CONFIRMED && !b.isSessionReminderSent() && start.isAfter(from)
                        && !start.isAfter(to) && !b.getCreatedAt().isAfter(start.minus(Duration.ofMinutes(leadMinutes)))
                        && claimed.size() < limit) {
                    b.setSessionReminderSent(true);
                    claimed.add(b.getId());
                }
            }
            return claimed;
        }

        @Override
        public synchronized List<UUID> claimReminders(Instant now, Instant until, int limit) {
            List<UUID> claimed = new ArrayList<>();
            for (Booking b : rows.values()) {
                if (b.getStatus() == BookingStatus.PENDING && b.getReminderSentAt() == null
                        && b.getExpiresAt().isAfter(now) && !b.getExpiresAt().isAfter(until) && claimed.size() < limit) {
                    b.setReminderSentAt(now);
                    claimed.add(b.getId());
                }
            }
            return claimed;
        }

        @Override
        public List<UUID> expireRegistrations(LocalDate today, Instant now, int limit) {
            registrationDays.add(today);
            return List.of();
        }
    }

    private final TestClock clock = new TestClock();
    private final InMemoryStore store = new InMemoryStore();
    private final FakeSender sender = new FakeSender();
    private final List<BookingEvent> published = new CopyOnWriteArrayList<>();
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final PublicProfessionalService directory = mock(PublicProfessionalService.class);
    private final MailSettings settings = new MailSettings("noreply@neuronest.example", "app-password", "NeuroNest", BASE_URL);
    private final UUID professionalId = UUID.randomUUID();
    private Professional professional;
    private BackgroundJobs jobs;

    @BeforeEach
    void setUp() {
        professional = professional(professionalId, "Dr. Asha Rao");
        professional.setContactEmail(PRO_EMAIL);
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));
        when(bookings.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.rows.get((UUID) inv.getArgument(0))));
        when(bookings.markExpiryNotified(any(), any())).thenAnswer(inv -> {
            store.rows.get((UUID) inv.getArgument(0)).setExpiryNotifiedAt(inv.getArgument(1));
            return 1;
        });
        jobs = newJobs();
    }

    // The listener is called directly where Spring would call it after the commit.
    private BackgroundJobs newJobs() {
        MailRetryScheduler retries = mock(MailRetryScheduler.class);
        BookingNotificationListener listener = new BookingNotificationListener(bookings, professionals, directory,
                new BookingEmails(settings), sender, settings, retries, new SessionMessages(settings),
                new NotificationService(List.of(new EmailChannel(sender, settings)), retries));
        ApplicationEventPublisher publisher = event -> {
            published.add((BookingEvent) event);
            listener.onBookingEvent((BookingEvent) event);
        };
        return new BackgroundJobs(store, publisher, TransactionOperations.withoutTransaction(), clock);
    }

    private static Professional professional(UUID id, String name) {
        Professional p = new Professional();
        ReflectionTestUtils.setField(p, "id", id);
        p.setFullName(name);
        p.setCredentialType(CredentialType.PSYCHOLOGIST);
        return p;
    }

    private Booking request(BookingStatus status, Duration expiresIn) {
        Booking b = new Booking();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setProfessionalId(professionalId);
        b.setUserId(UUID.randomUUID());
        b.setContactName("Ravi Kumar");
        b.setContactEmail(USER_EMAIL);
        b.setContactPhone("+919876543210");
        b.setPreferredMode("ONLINE");
        b.setPreferredMethod("VIDEO");
        b.setPreferredDatetime(START.plus(Duration.ofDays(3)));
        b.setNote(NOTE);
        b.setSharedAssessment(new SharedAssessment("PHQ9", "PHQ-9", 17, "moderately severe", "2026-10-01T10:00:00Z"));
        b.setStatus(status);
        b.setCreatedAt(START.minus(Duration.ofDays(3)));
        b.setExpiresAt(clock.instant().plus(expiresIn));
        store.rows.put(b.getId(), b);
        return b;
    }

    // ---- expiring unpaid bookings ----

    private Booking awaitingPayment(Duration dueIn) {
        Booking b = request(BookingStatus.AWAITING_PAYMENT, Duration.ofHours(30));
        b.setPaymentDueAt(clock.instant().plus(dueIn));
        b.setRespondedBy("PROFESSIONAL");
        b.setRespondedAt(START.minus(Duration.ofHours(20)));
        return b;
    }

    @Test
    void anUnpaidBookingExpiresOnlyAfterTheGracePeriodAndOnlyTheUserIsEmailed() {
        // Due 10 minutes ago: still inside the 15-minute grace period.
        Booking b = awaitingPayment(Duration.ofMinutes(-10));
        Booking notDue = awaitingPayment(Duration.ofHours(2));

        jobs.runOnce();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(sender.sent).isEmpty();

        clock.advance(Duration.ofMinutes(6));
        jobs.runOnce();

        assertThat(b.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(b.getPaymentExpiredAt()).isEqualTo(clock.instant());
        // The acceptance stays, for the admin's response metrics.
        assertThat(b.getRespondedBy()).isEqualTo("PROFESSIONAL");
        assertThat(b.getRespondedAt()).isEqualTo(START.minus(Duration.ofHours(20)));
        assertThat(notDue.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(published).containsExactly(new BookingEvent(BookingEvent.Type.EXPIRED, b.getId()));
        assertThat(sender.sent).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL);
        assertThat(sender.to(USER_EMAIL).get(0).text()).contains("was released because the payment was not made by");
    }

    @Test
    void theGracePeriodComesFromTheSettingAndAPaidBookingIsNeverExpired() {
        Booking paid = awaitingPayment(Duration.ofHours(-5));
        paid.setStatus(BookingStatus.CONFIRMED);
        Booking unpaid = awaitingPayment(Duration.ofMinutes(-2));
        BackgroundJobs noGrace = new BackgroundJobs(store, event -> published.add((BookingEvent) event),
                TransactionOperations.withoutTransaction(), clock, Duration.ZERO);

        noGrace.runOnce();

        assertThat(unpaid.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(paid.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(published).containsExactly(new BookingEvent(BookingEvent.Type.EXPIRED, unpaid.getId()));
    }

    // ---- expiring requests ----

    @Test
    void aPendingRequestPastItsExpiryBecomesExpiredAndBothSidesAreEmailed() {
        Booking b = request(BookingStatus.PENDING, Duration.ofMinutes(-1));

        assertThat(jobs.runOnce()).isTrue();

        assertThat(b.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(b.getRespondedAt()).isEqualTo(START);
        assertThat(b.getRespondedBy()).isNull();
        assertThat(b.getExpiryNotifiedAt()).isNotNull();
        assertThat(sender.sent).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL, PRO_EMAIL);
        OutgoingEmail toUser = sender.to(USER_EMAIL).get(0);
        assertThat(toUser.subject()).isEqualTo("NeuroNest: an update on your request");
        assertThat(toUser.text()).contains("Your request to Dr. Asha Rao expired because it was not answered in time. "
                + "This is not a reflection on you.");
        assertThat(sender.to(PRO_EMAIL).get(0).text()).contains("A request from Ravi Kumar expired because it was not "
                + "answered. Open NeuroNest to see your requests.", BASE_URL + "/pro/requests");
    }

    @Test
    void aRequestNotYetExpiredIsUntouched() {
        Booking pending = request(BookingStatus.PENDING, Duration.ofHours(30));
        Booking proposed = request(BookingStatus.ALTERNATE_PROPOSED, Duration.ofMinutes(5));

        jobs.runOnce();

        assertThat(pending.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(proposed.getStatus()).isEqualTo(BookingStatus.ALTERNATE_PROPOSED);
        assertThat(pending.getRespondedAt()).isNull();
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void anAlternateProposedRequestPastItsExpiryExpiresToo() {
        Booking b = request(BookingStatus.ALTERNATE_PROPOSED, Duration.ofHours(-2));
        Booking confirmed = request(BookingStatus.CONFIRMED, Duration.ofHours(-2));

        jobs.runOnce();

        assertThat(b.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(sender.sent).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL, PRO_EMAIL);
    }

    @Test
    void runningTwiceDoesNotSendTwice() {
        request(BookingStatus.PENDING, Duration.ofMinutes(-1));
        jobs.runOnce();
        clock.advance(Duration.ofMinutes(10));
        jobs.runOnce();
        clock.advance(Duration.ofHours(3));
        jobs.runOnce();

        assertThat(sender.sent).hasSize(2);
        assertThat(published).extracting(BookingEvent::type).containsExactly(BookingEvent.Type.EXPIRED);
    }

    @Test
    void twoConcurrentRunsProcessEachRowOnlyOnce() throws Exception {
        List<Booking> overdue = new ArrayList<>();
        for (int i = 0; i < 60; i++) overdue.add(request(BookingStatus.PENDING, Duration.ofMinutes(-1 - i)));
        BackgroundJobs other = newJobs();
        CyclicBarrier together = new CyclicBarrier(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Runnable runA = () -> { try { together.await(); jobs.runOnce(); } catch (Throwable t) { errors.add(t); } };
        Runnable runB = () -> { try { together.await(); other.runOnce(); } catch (Throwable t) { errors.add(t); } };
        Thread a = new Thread(runA);
        Thread b = new Thread(runB);
        a.start();
        b.start();
        a.join(10_000);
        b.join(10_000);

        assertThat(errors).isEmpty();
        assertThat(overdue).allSatisfy(booking -> assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED));
        assertThat(published).hasSize(60);
        assertThat(published.stream().map(BookingEvent::bookingId).distinct()).hasSize(60);
        assertThat(sender.to(USER_EMAIL)).hasSize(60);
        assertThat(sender.to(PRO_EMAIL)).hasSize(60);
    }

    @Test
    void theExpiryEmailHasTheSupportLinesTheDirectoryAndAtMostThreeOtherProfessionals() {
        Booking b = request(BookingStatus.PENDING, Duration.ofMinutes(-1));
        Professional first = professional(UUID.randomUUID(), "Dr. Meera Iyer");
        Professional second = professional(UUID.randomUUID(), "Dr. Karan Shah");
        Professional third = professional(UUID.randomUUID(), "Dr. Neha Joshi");
        Professional fourth = professional(UUID.randomUUID(), "Dr. Fourth Person");
        // Even if the lookup returned the same professional or too many, the email keeps three others.
        when(directory.similarBookable(eq(professional), anyInt()))
                .thenReturn(List.of(professional, first, second, third, fourth));

        jobs.runOnce();

        OutgoingEmail email = sender.to(USER_EMAIL).get(0);
        for (String part : List.of(email.text(), email.html())) {
            assertThat(part).contains(BASE_URL + "/professionals", "If you need support right now:",
                    "Dr. Meera Iyer", BASE_URL + "/professionals/" + first.getId(),
                    "Dr. Karan Shah", "Dr. Neha Joshi");
            assertThat(part).doesNotContain("Dr. Fourth Person", BASE_URL + "/professionals/" + professionalId, NOTE,
                    "PHQ-9", "9876543210", PRO_EMAIL);
            for (CrisisResource r : CrisisResources.INDIA_RESOURCES) {
                assertThat(part).contains(r.name(), r.phone());
            }
        }
        assertThat(email.text().split(BASE_URL + "/professionals/", -1)).hasSize(4);
        assertThat(b.getExpiryNotifiedAt()).isNotNull();
        verify(directory).similarBookable(professional, 3);
    }

    // ---- reminders ----

    @Test
    void theReminderIsSentOnceAndOnlyWithinTheLast24Hours() {
        Booking soon = request(BookingStatus.PENDING, Duration.ofHours(23));
        Booking later = request(BookingStatus.PENDING, Duration.ofHours(25));

        jobs.runOnce();

        assertThat(sender.to(PRO_EMAIL)).hasSize(1);
        OutgoingEmail reminder = sender.to(PRO_EMAIL).get(0);
        assertThat(reminder.subject()).isEqualTo("NeuroNest: a request is still waiting");
        assertThat(reminder.text()).contains("A request from Ravi Kumar is still waiting. Please reply before "
                + "Tue, 13 Oct 2026, 9:00 AM IST.", BASE_URL + "/pro/requests/" + soon.getId());
        assertThat(reminder.text()).doesNotContain(NOTE, USER_EMAIL, "9876543210");
        assertThat(soon.getReminderSentAt()).isEqualTo(START);
        assertThat(later.getReminderSentAt()).isNull();

        clock.advance(Duration.ofHours(2));
        jobs.runOnce();
        clock.advance(Duration.ofMinutes(10));
        jobs.runOnce();

        assertThat(sender.to(PRO_EMAIL)).hasSize(2);
        assertThat(later.getReminderSentAt()).isEqualTo(START.plus(Duration.ofHours(2)));
        assertThat(sender.to(USER_EMAIL)).isEmpty();
    }

    // ---- failures ----

    @Test
    void aFailingSendDoesNotStopTheStatusChangeOrTheOtherTasksAndTheUserEmailIsRetriedOnceNextRun() {
        sender.failFor = to -> true;
        Booking overdue = request(BookingStatus.PENDING, Duration.ofMinutes(-1));
        Booking waiting = request(BookingStatus.PENDING, Duration.ofHours(5));

        jobs.runOnce();

        assertThat(overdue.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(overdue.getExpiryNotifiedAt()).isNull();
        assertThat(waiting.getReminderSentAt()).isNotNull();
        assertThat(store.registrationDays).hasSize(1);
        assertThat(sender.sent).isEmpty();

        // The next run (once the first attempt has had time to finish) retries only the user's email.
        sender.failFor = to -> false;
        clock.advance(Duration.ofMinutes(10));
        jobs.runOnce();
        assertThat(sender.sent).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL);
        assertThat(overdue.getExpiryNotifiedAt()).isNotNull();

        clock.advance(Duration.ofMinutes(10));
        jobs.runOnce();
        assertThat(sender.sent).hasSize(1);
    }

    @Test
    void theRetryHappensOnlyOnce() {
        sender.failFor = USER_EMAIL::equals;
        request(BookingStatus.PENDING, Duration.ofMinutes(-1));
        jobs.runOnce();
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(10));
            jobs.runOnce();
        }
        assertThat(published).extracting(BookingEvent::type)
                .containsExactly(BookingEvent.Type.EXPIRED, BookingEvent.Type.EXPIRY_RETRY);
    }

    @Test
    void aTaskThatThrowsDoesNotStopTheOthers() {
        store.failExpire = new IllegalStateException("database down");
        Booking waiting = request(BookingStatus.PENDING, Duration.ofHours(5));

        assertThat(jobs.runOnce()).isTrue();

        assertThat(waiting.getReminderSentAt()).isNotNull();
        assertThat(sender.to(PRO_EMAIL)).hasSize(1);
        assertThat(store.registrationDays).hasSize(1);
    }

    @Test
    void registrationsUseTodayInIndia() {
        // 19:00 UTC on 11 Oct is 00:30 on 12 Oct in India.
        clock.advance(Duration.between(START, Instant.parse("2026-10-11T19:00:00Z")));
        jobs.runOnce();
        assertThat(store.registrationDays).containsExactly(LocalDate.of(2026, 10, 12));
    }

    @Test
    void aRunThatStartsWhileAnotherIsGoingIsSkipped() {
        BookingJobStore reentrant = mock(BookingJobStore.class);
        BackgroundJobs[] holder = new BackgroundJobs[1];
        List<Boolean> nested = new ArrayList<>();
        when(reentrant.claimExpiryEmailRetries(any(), any(), any(), anyInt())).thenAnswer(inv -> {
            nested.add(holder[0].runOnce());
            return List.of();
        });
        holder[0] = new BackgroundJobs(reentrant, event -> { }, TransactionOperations.withoutTransaction(), clock);

        assertThat(holder[0].runOnce()).isTrue();
        assertThat(nested).containsExactly(false);
    }
}
