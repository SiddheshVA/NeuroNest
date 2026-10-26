package com.neuronest.backend.meeting;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The Meet space of a confirmed video booking, with GoogleMeetService mocked: Google is never called. */
class MeetingLinkServiceTest {

    private static final Instant START = OffsetDateTime.parse("2026-10-29T19:45:00+05:30").toInstant();
    private static final GoogleMeetService.Space SPACE =
            new GoogleMeetService.Space("spaces/jQCFfuBOdN5z", "https://meet.google.com/abc-mnop-xyz");
    private static final GoogleMeetService.GoogleMeetException DOWN =
            new GoogleMeetService.GoogleMeetException("Google Meet refused to create the meeting space (HTTP 500).");

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final GoogleMeetService google = mock(GoogleMeetService.class);
    private final List<Duration> sleeps = new ArrayList<>();
    // The day before the session: its join window is not open yet.
    private final MeetingLinkService service = serviceAt(START.minus(Duration.ofDays(1)));

    private MeetingLinkService serviceAt(Instant now) {
        return new MeetingLinkService(bookings, google, new MeetingWindow(5, Clock.fixed(now, ZoneOffset.UTC)),
                TransactionOperations.withoutTransaction(), sleeps::add);
    }
    private Booking booking;

    @BeforeEach
    void confirmedVideoBooking() {
        booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", UUID.randomUUID());
        booking.setProfessionalId(UUID.randomUUID());
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPreferredMode("ONLINE");
        booking.setPreferredMethod("VIDEO");
        booking.setPreferredDatetime(START);
        booking.setEndsAt(START.plus(Duration.ofHours(1)));
        when(bookings.findByIdForUpdate(booking.getId())).thenReturn(Optional.of(booking));
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
    }

    // ---- creating ----

    @Test
    void aConfirmedVideoBookingGetsOneRestrictedSpaceEvenWhenConfirmedTwice() {
        when(google.createSpace()).thenReturn(SPACE);

        service.ensureLink(booking.getId());
        service.ensureLink(booking.getId());

        verify(google, times(1)).createSpace();
        assertThat(booking.getMeetingSpaceName()).isEqualTo("spaces/jQCFfuBOdN5z");
        assertThat(booking.getMeetingLink()).isEqualTo("https://meet.google.com/abc-mnop-xyz");
        assertThat(booking.getMeetingMethod()).isEqualTo("VIDEO");
        assertThat(booking.isMeetingAccessOpen()).isFalse();
        assertThat(booking.isManualMeetingLinkNeeded()).isFalse();
        // Created, not opened: it stays RESTRICTED until the session's window.
        verify(google, never()).setAccessType(anyString(), any());
        verify(bookings, times(1)).saveAndFlush(booking);
    }

    @Test
    void aBookingConfirmedInsideItsJoinWindowHasItsSpaceOpenedAtOnce() {
        when(google.createSpace()).thenReturn(SPACE);
        // A last-minute booking paid 2 minutes before the start: the window opened 3 minutes ago.
        serviceAt(START.minus(Duration.ofMinutes(2))).ensureLink(booking.getId());

        var order = inOrder(google, bookings);
        order.verify(google).createSpace();
        order.verify(google).setAccessType(SPACE.name(), GoogleMeetService.AccessType.OPEN);
        order.verify(bookings).saveAndFlush(booking);
        assertThat(booking.isMeetingAccessOpen()).isTrue();
        // The end stays the stored end of the session.
        assertThat(booking.getEndsAt()).isEqualTo(START.plus(Duration.ofHours(1)));
    }

    @Test
    void ifOpeningAtOnceFailsTheSpaceIsStillSavedForTheJobOrTheJoinToOpen() {
        when(google.createSpace()).thenReturn(SPACE);
        doThrow(DOWN).when(google).setAccessType(SPACE.name(), GoogleMeetService.AccessType.OPEN);

        serviceAt(START.minus(Duration.ofMinutes(2))).ensureLink(booking.getId());

        assertThat(booking.getMeetingSpaceName()).isEqualTo(SPACE.name());
        assertThat(booking.isMeetingAccessOpen()).isFalse();
        assertThat(booking.isManualMeetingLinkNeeded()).isFalse();
        verify(bookings).saveAndFlush(booking);
    }

    @Test
    void phoneInPersonAndUnconfirmedBookingsAreLeftAlone() {
        booking.setPreferredMethod("PHONE");
        service.ensureLink(booking.getId());
        booking.setPreferredMode("IN_PERSON");
        booking.setPreferredMethod(null);
        service.ensureLink(booking.getId());
        booking.setPreferredMode("ONLINE");
        booking.setPreferredMethod("VIDEO");
        for (BookingStatus status : new BookingStatus[]{BookingStatus.AWAITING_PAYMENT, BookingStatus.CANCELLED}) {
            booking.setStatus(status);
            service.ensureLink(booking.getId());
        }

        verifyNoInteractions(google);
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void googleFailingThreeTimesFlagsTheBookingForAManualLinkAndKeepsItConfirmed() {
        when(google.createSpace()).thenThrow(DOWN);

        service.ensureLink(booking.getId());

        verify(google, times(3)).createSpace();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(3));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getMeetingLink()).isNull();
        assertThat(booking.isManualMeetingLinkNeeded()).isTrue();

        // A repeated confirmation does not try again: the professional adds the link.
        service.ensureLink(booking.getId());
        verify(google, times(3)).createSpace();
    }

    @Test
    void aFailureThatClearsOnARetryStillGetsTheSpace() {
        when(google.createSpace()).thenThrow(DOWN).thenReturn(SPACE);

        service.ensureLink(booking.getId());

        assertThat(booking.getMeetingSpaceName()).isEqualTo(SPACE.name());
        assertThat(booking.isManualMeetingLinkNeeded()).isFalse();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void googleNotConfiguredIsNotRetriedAndFlagsTheBooking() {
        when(google.createSpace()).thenThrow(new GoogleMeetService.NotConfiguredException());

        service.ensureLink(booking.getId());

        verify(google, times(1)).createSpace();
        assertThat(sleeps).isEmpty();
        assertThat(booking.isManualMeetingLinkNeeded()).isTrue();
    }

    // ---- self-healing ----

    @Test
    void aFlaggedBookingIsRetriedAndRecoversWithTheFlagCleared() {
        booking.setManualMeetingLinkNeeded(true);
        // The confirmation step leaves a flagged booking alone; only the retry tries again.
        assertThat(service.ensureLink(booking.getId())).isEqualTo(MeetingLinkService.LinkResult.NOT_APPLICABLE);
        verifyNoInteractions(google);

        when(google.createSpace()).thenReturn(SPACE);
        assertThat(service.retryLink(booking.getId())).isEqualTo(MeetingLinkService.LinkResult.CREATED);

        assertThat(booking.isManualMeetingLinkNeeded()).isFalse();
        assertThat(booking.getMeetingSpaceName()).isEqualTo(SPACE.name());
        assertThat(booking.getMeetingLink()).isEqualTo(SPACE.meetingUri());
        // Again: it has its link now.
        assertThat(service.retryLink(booking.getId())).isEqualTo(MeetingLinkService.LinkResult.ALREADY_HAS_LINK);
        verify(google, times(1)).createSpace();
    }

    @Test
    void aRetryThatStillFailsKeepsTheFlagAndASessionThatEndedIsNotRetried() {
        booking.setManualMeetingLinkNeeded(true);
        when(google.createSpace()).thenThrow(DOWN);
        assertThat(service.retryLink(booking.getId())).isEqualTo(MeetingLinkService.LinkResult.FAILED);
        assertThat(booking.isManualMeetingLinkNeeded()).isTrue();
        verify(google, times(3)).createSpace();

        // The day after the session: nothing to do.
        assertThat(serviceAt(START.plus(Duration.ofDays(1))).retryLink(booking.getId()))
                .isEqualTo(MeetingLinkService.LinkResult.NOT_APPLICABLE);
        verify(google, times(3)).createSpace();
    }

    @Test
    void eachFailedAttemptIsLoggedWithGooglesReasonAndNoSecret() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MeetingLinkService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        when(google.createSpace()).thenThrow(new GoogleMeetService.GoogleMeetException(
                "Google Meet step 'create space' failed: HTTP 403 PERMISSION_DENIED: Google Meet REST API has not been "
                        + "used in project 1234 before or it is disabled."));
        try {
            service.ensureLink(booking.getId());
        } finally {
            logger.detachAppender(logs);
        }

        List<String> warnings = logs.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).hasSize(4);
        assertThat(warnings.subList(0, 3)).allSatisfy(line -> assertThat(line)
                .contains("create the meeting space", "step 'create space' failed: HTTP 403 PERMISSION_DENIED",
                        "API has not been used"));
        assertThat(warnings.get(3)).startsWith("MANUAL MEETING LINK NEEDED");
        assertThat(String.join("\n", warnings)).doesNotContain("ya29", "Bearer", "client_secret", "refresh_token");
    }

    // ---- opening for the session ----

    @Test
    void openingSetsTheSpaceOpenOnceAndRecordsIt() {
        booking.setMeetingSpaceName(SPACE.name());

        service.open(booking);
        service.open(booking);

        verify(google, times(1)).setAccessType(SPACE.name(), GoogleMeetService.AccessType.OPEN);
        verify(bookings, times(1)).markMeetingAccessOpen(booking.getId());
        assertThat(booking.isMeetingAccessOpen()).isTrue();
    }

    @Test
    void aPastedLinkHasNoSpaceToOpen() {
        booking.setMeetingLink("https://zoom.us/j/123");
        service.open(booking);
        verifyNoInteractions(google);
    }

    @Test
    void openingThatKeepsFailingIsReportedAndNotRecorded() {
        booking.setMeetingSpaceName(SPACE.name());
        doThrow(DOWN).when(google).setAccessType(SPACE.name(), GoogleMeetService.AccessType.OPEN);

        assertThatThrownBy(() -> service.open(booking)).isSameAs(DOWN);

        verify(google, times(3)).setAccessType(SPACE.name(), GoogleMeetService.AccessType.OPEN);
        verify(bookings, never()).markMeetingAccessOpen(any());
        assertThat(booking.isMeetingAccessOpen()).isFalse();
    }

    // ---- cancelling ----

    @Test
    void aCancelledBookingsSpaceHasItsCallEndedAndIsRestricted() {
        booking.setMeetingSpaceName(SPACE.name());
        booking.setMeetingAccessOpen(true);

        service.closeSpace(booking.getId());

        var order = inOrder(google, bookings);
        order.verify(google).endActiveConference(SPACE.name());
        order.verify(google).setAccessType(SPACE.name(), GoogleMeetService.AccessType.RESTRICTED);
        order.verify(bookings).markMeetingAccessClosed(booking.getId());
    }

    @Test
    void closingOnCancelIsBestEffort() {
        booking.setMeetingSpaceName(SPACE.name());
        doThrow(DOWN).when(google).endActiveConference(SPACE.name());

        service.closeSpace(booking.getId());

        verify(google, times(3)).endActiveConference(SPACE.name());
        verify(bookings, never()).markMeetingAccessClosed(any());

        // Without a space (no link, or a pasted one) there is nothing to close.
        booking.setMeetingSpaceName(null);
        doNothing().when(google).endActiveConference(anyString());
        service.closeSpace(booking.getId());
        verify(google, times(3)).endActiveConference(anyString());
    }
}
