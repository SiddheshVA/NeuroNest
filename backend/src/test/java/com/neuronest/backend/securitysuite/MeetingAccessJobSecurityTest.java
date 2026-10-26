package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.jobs.MeetingAccessJob;
import com.neuronest.backend.meeting.GoogleMeetService;
import com.neuronest.backend.meeting.MeetingWindow;
import com.neuronest.backend.professional.Professional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * MeetingAccessJob against the real booking queries (embedded PostgreSQL) with Google mocked: it opens a
 * space when its window starts, closes it once the session has ended, and a second run changes nothing.
 */
class MeetingAccessJobSecurityTest extends SecuritySuiteBase {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private Professional asha;
    private User person;
    private MeetingAccessJob job;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        person = user("user_person", "person@example.com");
        when(googleMeet.isConfigured()).thenReturn(true);
        job = new MeetingAccessJob(bookings, googleMeet, new MeetingWindow(5), meetingLinkRecovery, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Booking video(String space, BookingStatus status, Duration startsIn, boolean open) {
        Booking b = booking(person.getId(), asha.getId(), status, NOW.plus(startsIn), null);
        b.setPreferredMethod("VIDEO");
        b.setMeetingMethod("VIDEO");
        b.setEndsAt(NOW.plus(startsIn).plus(Duration.ofMinutes(60)));
        b.setMeetingSpaceName(space);
        b.setMeetingLink(space == null ? "https://zoom.us/j/1" : "https://meet.google.com/" + space.substring(7));
        b.setMeetingAccessOpen(open);
        return bookings.saveAndFlush(b);
    }

    private Booking reload(Booking b) {
        return bookings.findById(b.getId()).orElseThrow();
    }

    @Test
    void opensWhenTheWindowStartsClosesAfterTheEndAndASecondRunChangesNothing() {
        Booking due = video("spaces/due", BookingStatus.CONFIRMED, Duration.ofMinutes(4), false);
        Booking exactlyAtOpen = video("spaces/edge", BookingStatus.CONFIRMED, Duration.ofMinutes(5), false);
        Booking notYet = video("spaces/later", BookingStatus.CONFIRMED, Duration.ofMinutes(6), false);
        Booking ended = video("spaces/ended", BookingStatus.CONFIRMED, Duration.ofHours(-2), true);
        Booking endedNeverOpened = video("spaces/missed", BookingStatus.CONFIRMED, Duration.ofHours(-2), false);
        Booking pasted = video(null, BookingStatus.CONFIRMED, Duration.ofMinutes(4), false);
        Booking cancelled = video("spaces/cancelled", BookingStatus.CANCELLED, Duration.ofMinutes(4), false);

        job.run();

        verify(googleMeet).setAccessType("spaces/due", GoogleMeetService.AccessType.OPEN);
        verify(googleMeet).setAccessType("spaces/edge", GoogleMeetService.AccessType.OPEN);
        var closing = inOrder(googleMeet);
        closing.verify(googleMeet).endActiveConference("spaces/ended");
        closing.verify(googleMeet).setAccessType("spaces/ended", GoogleMeetService.AccessType.RESTRICTED);
        verify(googleMeet, times(1)).isConfigured();
        verifyNoMoreInteractions(googleMeet);

        assertThat(reload(due).isMeetingAccessOpen()).isTrue();
        assertThat(reload(exactlyAtOpen).isMeetingAccessOpen()).isTrue();
        assertThat(reload(notYet).isMeetingAccessOpen()).isFalse();
        assertThat(reload(ended).isMeetingAccessClosed()).isTrue();
        assertThat(reload(endedNeverOpened).isMeetingAccessOpen()).isFalse();
        assertThat(reload(endedNeverOpened).isMeetingAccessClosed()).isFalse();
        assertThat(reload(pasted).isMeetingAccessOpen()).isFalse();
        assertThat(reload(cancelled).isMeetingAccessOpen()).isFalse();

        // Idempotent: nothing is opened or closed twice.
        reset(googleMeet);
        when(googleMeet.isConfigured()).thenReturn(true);
        job.run();
        verify(googleMeet, times(0)).setAccessType(anyString(), any());
        verify(googleMeet, times(0)).endActiveConference(anyString());
    }

    @Test
    void aFailureIsRetriedOnTheNextRunAndRecordedOnlyOnceItWorks() {
        Booking due = video("spaces/due", BookingStatus.CONFIRMED, Duration.ofMinutes(2), false);
        Booking ended = video("spaces/ended", BookingStatus.CONFIRMED, Duration.ofHours(-2), true);
        doThrow(new GoogleMeetService.GoogleMeetException("Google Meet refused to change the meeting space (HTTP 503)."))
                .when(googleMeet).setAccessType(anyString(), any());

        job.run();
        job.run();
        job.run();

        assertThat(reload(due).isMeetingAccessOpen()).isFalse();
        assertThat(reload(ended).isMeetingAccessClosed()).isFalse();
        verify(googleMeet, times(3)).setAccessType("spaces/due", GoogleMeetService.AccessType.OPEN);
        verify(googleMeet, times(3)).setAccessType("spaces/ended", GoogleMeetService.AccessType.RESTRICTED);

        // Google is back: the next run finishes both.
        reset(googleMeet);
        when(googleMeet.isConfigured()).thenReturn(true);
        job.run();
        assertThat(reload(due).isMeetingAccessOpen()).isTrue();
        assertThat(reload(ended).isMeetingAccessClosed()).isTrue();
    }

    // A video booking whose space could not be made at confirmation.
    private Booking flagged(Duration startsIn) {
        Booking b = video(null, BookingStatus.CONFIRMED, startsIn, false);
        b.setMeetingLink(null);
        b.setMeetingMethod(null);
        b.setPreferredMode("ONLINE");
        b.setManualMeetingLinkNeeded(true);
        return bookings.saveAndFlush(b);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFlaggedBookingIsRetriedRecoversAndBothPeopleGetTheLink() {
        Booking waiting = flagged(Duration.ofMinutes(30));
        Booking ended = flagged(Duration.ofHours(-3));
        Booking notFlagged = video(null, BookingStatus.CONFIRMED, Duration.ofMinutes(40), false);
        notFlagged.setMeetingLink(null);
        bookings.saveAndFlush(notFlagged);
        when(googleMeet.createSpace()).thenReturn(
                new GoogleMeetService.Space("spaces/healed", "https://meet.google.com/hea-ledl-ink"));

        job.run();

        // Only the flagged booking whose session has not ended was retried.
        verify(googleMeet, times(1)).createSpace();
        Booking healed = reload(waiting);
        assertThat(healed.isManualMeetingLinkNeeded()).isFalse();
        assertThat(healed.getMeetingSpaceName()).isEqualTo("spaces/healed");
        assertThat(healed.getMeetingLink()).isEqualTo("https://meet.google.com/hea-ledl-ink");
        assertThat(reload(ended).isManualMeetingLinkNeeded()).isTrue();
        assertThat(reload(notFlagged).getMeetingLink()).isNull();

        // Both people are emailed the link, each in their own words.
        var sent = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(notifications).send(sent.capture());
        java.util.List<com.neuronest.backend.notification.Notification> linkEmails = sent.getValue();
        assertThat(linkEmails).hasSize(2).allSatisfy(n -> {
            assertThat(n.kind()).isEqualTo("LINK_READY");
            assertThat(n.text()).contains("Join your session: https://meet.google.com/hea-ledl-ink");
            assertThat(n.text()).doesNotContain("REQUEST-NOTE-SECRET", "PHQ", "moderately severe", "9876543210");
        });
        assertThat(linkEmails).extracting(com.neuronest.backend.notification.Notification::email)
                .containsExactly("person.private@example.com", asha.getContactEmail());

        // The next run has nothing left to heal.
        job.run();
        verify(googleMeet, times(1)).createSpace();
    }

    @Test
    void withoutGoogleConfiguredTheJobDoesNothing() {
        video("spaces/due", BookingStatus.CONFIRMED, Duration.ofMinutes(2), false);
        when(googleMeet.isConfigured()).thenReturn(false);

        job.run();

        verify(googleMeet).isConfigured();
        verifyNoMoreInteractions(googleMeet);
    }
}
