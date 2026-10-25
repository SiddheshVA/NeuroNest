package com.neuronest.backend.jobs;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.notification.EmailChannel;
import com.neuronest.backend.notification.MailRetryScheduler;
import com.neuronest.backend.notification.MailSettings;
import com.neuronest.backend.notification.NotificationService;
import com.neuronest.backend.notification.OutgoingEmail;
import com.neuronest.backend.notification.SessionMessages;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionReminderJobTest {

    private static final String BASE_URL = "https://app.neuronest.example";
    // Two sessions from 7:45 pm: the meeting runs to 9:45 pm, the time is blocked until 10:15 pm.
    private static final Instant START = OffsetDateTime.parse("2026-10-29T19:45:00+05:30").toInstant();
    private static final Instant NOW = START.minus(Duration.ofMinutes(10));

    private final BookingJobStore store = mock(BookingJobStore.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final List<OutgoingEmail> sent = new ArrayList<>();
    private final MailSettings settings = new MailSettings("noreply@neuronest.example", "app-password", "NeuroNest", BASE_URL);
    private final SessionReminderJob job = new SessionReminderJob(store, bookings, professionals,
            new SessionMessages(settings),
            new NotificationService(List.of(new EmailChannel(sent::add, settings)), mock(MailRetryScheduler.class)),
            10, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void tenMinutesBeforeTheMeetingBothSidesGetTheMeetingTimeAndSessionCountNeverTheBreak() {
        UUID professionalId = UUID.randomUUID();
        Professional p = new Professional();
        ReflectionTestUtils.setField(p, "id", professionalId);
        p.setFullName("Dr. Asha Rao");
        p.setContactEmail("asha.pro@example.com");
        Booking b = new Booking();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(b, "id", id);
        b.setProfessionalId(professionalId);
        b.setStatus(BookingStatus.CONFIRMED);
        b.setContactName("Ravi Kumar");
        b.setContactEmail("ravi.user@example.com");
        b.setNote("PRIVATE-REQUEST-NOTE about my sleep");
        b.setPreferredDatetime(START);
        b.setSessionCount(2);
        b.setEndsAt(START.plus(Duration.ofMinutes(120)));
        b.setBlockedUntil(START.plus(Duration.ofMinutes(150)));
        when(store.claimSessionReminders(NOW, START.minus(SessionReminderJob.LATEST), START, 10, SessionReminderJob.BATCH))
                .thenReturn(List.of(id));
        when(bookings.findById(id)).thenReturn(Optional.of(b));
        when(professionals.findById(professionalId)).thenReturn(Optional.of(p));

        job.run();

        assertThat(sent).hasSize(2);
        OutgoingEmail toUser = sent.get(0);
        assertThat(toUser.to()).isEqualTo("ravi.user@example.com");
        assertThat(toUser.subject()).isEqualTo("NeuroNest: your session starts in 10 minutes");
        assertThat(toUser.text()).startsWith("Hi Ravi Kumar, a gentle reminder: your session with Dr. Asha Rao starts in "
                + "10 minutes, 7:45 PM to 9:45 PM IST (2 sessions, 2 hours). Open it here: " + BASE_URL
                + "/appointments. Team NeuroNest.");
        OutgoingEmail toProfessional = sent.get(1);
        assertThat(toProfessional.to()).isEqualTo("asha.pro@example.com");
        assertThat(toProfessional.text()).startsWith("Hi Dr. Asha Rao, a gentle reminder: your session with Ravi Kumar "
                + "starts in 10 minutes, 7:45 PM to 9:45 PM IST (2 sessions, 2 hours). Open it here: " + BASE_URL
                + "/pro/requests/" + id + ". Team NeuroNest.");
        assertThat(sent).allSatisfy(e -> assertThat(e.text() + e.html()).doesNotContain("10:15", "PRIVATE-REQUEST-NOTE"));
    }

    @Test
    void aBookingThatIsNoLongerConfirmedGetsNothing() {
        UUID id = UUID.randomUUID();
        Booking cancelled = new Booking();
        cancelled.setStatus(BookingStatus.CANCELLED);
        when(store.claimSessionReminders(NOW, START.minus(SessionReminderJob.LATEST), START, 10, SessionReminderJob.BATCH))
                .thenReturn(List.of(id));
        when(bookings.findById(id)).thenReturn(Optional.of(cancelled));

        job.run();

        assertThat(sent).isEmpty();
        verify(store).claimSessionReminders(NOW, START.minus(SessionReminderJob.LATEST), START, 10, SessionReminderJob.BATCH);
    }

    @Test
    void aFailedClaimIsLoggedAndTheJobCarriesOn() {
        when(store.claimSessionReminders(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("database down"));

        job.run();

        assertThat(sent).isEmpty();
    }
}
