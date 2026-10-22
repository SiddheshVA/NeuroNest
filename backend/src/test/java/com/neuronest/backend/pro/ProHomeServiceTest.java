package com.neuronest.backend.pro;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProBookingDtos.ProAppointmentView;
import com.neuronest.backend.pro.ProHomeDtos.DayOffCreated;
import com.neuronest.backend.pro.ProHomeDtos.DayOffRequest;
import com.neuronest.backend.pro.ProHomeDtos.ProDashboard;
import com.neuronest.backend.pro.ProHomeDtos.Warning;
import com.neuronest.backend.professional.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProHomeServiceTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final Clock CLOCK = Clock.fixed(NOW, AvailabilityService.ZONE);

    private final ProfessionalAccountRepository accounts = mock(ProfessionalAccountRepository.class);
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final ProfessionalDayOffRepository daysOff = mock(ProfessionalDayOffRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final ProHomeService service = new ProHomeService(accounts, professionals, daysOff, bookings, CLOCK);

    private final UUID professionalId = UUID.randomUUID();
    private final UUID otherProfessionalId = UUID.randomUUID();
    private final ProPrincipal me = new ProPrincipal("pro_me");
    private final ProPrincipal other = new ProPrincipal("pro_other");
    private Professional professional;
    private ProfessionalAccount account;

    @BeforeEach
    void setUp() {
        professional = new Professional();
        ReflectionTestUtils.setField(professional, "id", professionalId);
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(CredentialType.PSYCHOLOGIST);
        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        professional.setVerified(true);
        professional.setActive(true);
        professional.setSessionMinutes(60);
        professional.setWeeklyHours(Map.of("WED", List.of(Map.of("start", "18:00", "end", "20:00"))));
        account = new ProfessionalAccount(professionalId, "asha@example.com", "pro_me");
        when(accounts.findByClerkUserId("pro_me")).thenReturn(Optional.of(account));
        when(accounts.findByClerkUserId("pro_other"))
                .thenReturn(Optional.of(new ProfessionalAccount(otherProfessionalId, "ravi@example.com", "pro_other")));
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));
        when(professionals.findByIdForUpdate(any())).thenReturn(Optional.of(professional));
        when(daysOff.saveAndFlush(any())).thenAnswer(inv -> {
            ProfessionalDayOff d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(d, "createdAt", NOW);
            return d;
        });
    }

    private static DayOffRequest days(String from, String to, String reason) {
        return new DayOffRequest(from, to, reason);
    }

    private static List<String> codes(ProDashboard d) {
        return d.warnings().stream().map(Warning::code).toList();
    }

    // ---- days off ----

    @Test
    void aValidRangeIsSavedWithTheReasonTrimmedAndTheAffectedBookingsCountedButNotChanged() {
        when(bookings.countScheduledBetween(eq(professionalId), eq(java.util.Set.of(BookingStatus.CONFIRMED)), any(), any()))
                .thenReturn(2L);
        when(bookings.countOpenRequestedBetween(eq(professionalId), any(), eq(NOW), any(), any())).thenReturn(3L);

        DayOffCreated created = service.addDayOff(me, days("2026-10-14", "2026-10-16", "  Family wedding  "));

        assertThat(created.dateFrom()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(created.dateTo()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(created.reason()).isEqualTo("Family wedding");
        assertThat(created.affectedConfirmed()).isEqualTo(2);
        assertThat(created.affectedPending()).isEqualTo(3);
        // The window is the whole of those dates in India: 14 Oct 00:00 to 17 Oct 00:00 IST.
        verify(bookings).countScheduledBetween(professionalId, java.util.Set.of(BookingStatus.CONFIRMED),
                Instant.parse("2026-10-13T18:30:00Z"), Instant.parse("2026-10-16T18:30:00Z"));
        verify(bookings, never()).save(any());
        verify(bookings, never()).saveAndFlush(any());
        verify(professionals).findByIdForUpdate(professionalId);
        ArgumentCaptor<ProfessionalDayOff> saved = ArgumentCaptor.forClass(ProfessionalDayOff.class);
        verify(daysOff).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getProfessionalId()).isEqualTo(professionalId);
    }

    @Test
    void theDateRulesAreChecked() {
        assertThatThrownBy(() -> service.addDayOff(me, days("2026-10-11", "2026-10-12", null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("today or later");
        assertThatThrownBy(() -> service.addDayOff(me, days("2026-10-15", "2026-10-14", null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("on or after dateFrom");
        // 60 days is the longest entry: 14 Oct to 12 Dec is 60 days, to 13 Dec is 61.
        assertThat(service.addDayOff(me, days("2026-10-14", "2026-12-12", null))).isNotNull();
        assertThatThrownBy(() -> service.addDayOff(me, days("2026-10-14", "2026-12-13", null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("60 days");
        // At most 365 days ahead: 12 Oct 2027 is the last allowed date.
        assertThat(service.addDayOff(me, days("2027-10-12", "2027-10-12", null))).isNotNull();
        assertThatThrownBy(() -> service.addDayOff(me, days("2027-10-13", "2027-10-13", null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("365 days");
        assertThatThrownBy(() -> service.addDayOff(me, days("14-10-2026", "2026-10-14", null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("dateFrom");
        assertThatThrownBy(() -> service.addDayOff(me, days("2026-10-14", null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("dateTo");
        assertThatThrownBy(() -> service.addDayOff(me, days("2026-10-14", "2026-10-14", "a".repeat(101))))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> service.addDayOff(me, null)).isInstanceOf(ApiValidationException.class);
    }

    @Test
    void anOverlapOrTheFiftyEntryLimitIsAConflict() {
        when(daysOff.findOverlapping(professionalId, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16)))
                .thenReturn(List.of(new ProfessionalDayOff(professionalId, LocalDate.of(2026, 10, 16),
                        LocalDate.of(2026, 10, 20), null)));
        assertThatThrownBy(() -> service.addDayOff(me, days("2026-10-14", "2026-10-16", null)))
                .isInstanceOf(ConflictException.class).hasMessage("These dates overlap days you already marked as off.");

        when(daysOff.countByProfessionalIdAndDateToGreaterThanEqual(professionalId, TODAY)).thenReturn(50L);
        assertThatThrownBy(() -> service.addDayOff(me, days("2026-11-01", "2026-11-01", null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("at most 50");
        verify(daysOff, never()).saveAndFlush(any());
    }

    @Test
    void theListShowsOnlyCurrentEntriesOfTheTokensProfessional() {
        ProfessionalDayOff entry = new ProfessionalDayOff(professionalId, TODAY, TODAY.plusDays(2), "Private");
        when(daysOff.findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(professionalId, TODAY))
                .thenReturn(List.of(entry));

        assertThat(service.daysOff(me)).singleElement().satisfies(d -> assertThat(d.reason()).isEqualTo("Private"));
        assertThat(service.daysOff(other)).isEmpty();
        verify(daysOff).findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(otherProfessionalId, TODAY);
    }

    @Test
    void deletingAnotherProfessionalsEntryGetsTheSame404AsAMissingOne() {
        UUID entryId = UUID.randomUUID();
        ProfessionalDayOff entry = new ProfessionalDayOff(professionalId, TODAY, TODAY, null);
        when(daysOff.findByIdAndProfessionalId(entryId, professionalId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.deleteDayOff(other, entryId.toString()))
                .isInstanceOf(ProExceptions.DayOffNotFoundException.class);
        assertThatThrownBy(() -> service.deleteDayOff(me, UUID.randomUUID().toString()))
                .isInstanceOf(ProExceptions.DayOffNotFoundException.class);
        assertThatThrownBy(() -> service.deleteDayOff(me, "not-a-uuid"))
                .isInstanceOf(ProExceptions.DayOffNotFoundException.class);
        verify(daysOff, never()).delete(any());

        service.deleteDayOff(me, entryId.toString());
        verify(daysOff).delete(entry);
    }

    @Test
    void aSignInWithoutAProfessionalAccountIsRefused() {
        ProPrincipal stranger = new ProPrincipal("user_clerk");
        assertThatThrownBy(() -> service.dashboard(stranger)).isInstanceOf(ProExceptions.NotAProfessionalException.class);
        assertThatThrownBy(() -> service.daysOff(stranger)).isInstanceOf(ProExceptions.NotAProfessionalException.class);
        assertThatThrownBy(() -> service.appointments(stranger, "upcoming", 0, 20))
                .isInstanceOf(ProExceptions.NotAProfessionalException.class);
    }

    // ---- appointments ----

    private Booking confirmed(Instant at) {
        Booking b = new Booking();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setProfessionalId(professionalId);
        b.setUserId(UUID.randomUUID());
        b.setSessionId(UUID.randomUUID());
        b.setContactName("Ravi Kumar");
        b.setContactEmail("user@example.com");
        b.setContactPhone("+919876543210");
        b.setNote("PRIVATE-REQUEST-NOTE");
        b.setAdminNote("PRIVATE-ADMIN-NOTE");
        b.setPreferredMode("ONLINE");
        b.setPreferredDatetime(at);
        b.setStatus(BookingStatus.CONFIRMED);
        b.setMeetingMethod("VIDEO");
        b.setMeetingLink("https://meet.example.com/room");
        b.setProfessionalNote("See you then.");
        return b;
    }

    @Test
    void upcomingAndPastUseTheRightQueryAndOrderAndKeepTheContactRule() {
        Booking soon = confirmed(NOW.plus(Duration.ofDays(1)));
        Booking recent = confirmed(NOW.minus(Duration.ofDays(6)));
        Booking old = confirmed(NOW.minus(Duration.ofDays(8)));
        when(bookings.findByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(eq(professionalId),
                eq(BookingStatus.CONFIRMED), eq(NOW), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(soon)));
        when(bookings.findByProfessionalIdAndStatusAndPreferredDatetimeLessThan(eq(professionalId),
                eq(BookingStatus.CONFIRMED), eq(NOW), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(recent, old)));

        List<ProAppointmentView> upcoming = service.appointments(me, "upcoming", 0, 20).items();
        List<ProAppointmentView> past = service.appointments(me, "PAST", 0, 20).items();

        ArgumentCaptor<Pageable> upcomingPage = ArgumentCaptor.forClass(Pageable.class);
        verify(bookings).findByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(eq(professionalId),
                eq(BookingStatus.CONFIRMED), eq(NOW), upcomingPage.capture());
        assertThat(upcomingPage.getValue().getSort().getOrderFor("preferredDatetime").getDirection()).isEqualTo(Sort.Direction.ASC);
        ArgumentCaptor<Pageable> pastPage = ArgumentCaptor.forClass(Pageable.class);
        verify(bookings).findByProfessionalIdAndStatusAndPreferredDatetimeLessThan(eq(professionalId),
                eq(BookingStatus.CONFIRMED), eq(NOW), pastPage.capture());
        assertThat(pastPage.getValue().getSort().getOrderFor("preferredDatetime").getDirection()).isEqualTo(Sort.Direction.DESC);

        assertThat(upcoming.get(0).contactPhone()).isEqualTo("+919876543210");
        assertThat(upcoming.get(0).meetingLink()).isEqualTo("https://meet.example.com/room");
        assertThat(upcoming.get(0).professionalNote()).isEqualTo("See you then.");
        assertThat(past.get(0).contactEmail()).isEqualTo("user@example.com");
        // More than 7 days after the appointment the contact details are gone.
        assertThat(past.get(1).contactEmail()).isNull();
        assertThat(past.get(1).contactPhone()).isNull();
        assertThat(upcoming.toString() + past).doesNotContain("PRIVATE", soon.getUserId().toString());
        assertThat(java.util.Arrays.stream(ProAppointmentView.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("note", "userId", "sessionId", "adminNote");

        assertThatThrownBy(() -> service.appointments(me, "tomorrow", 0, 20)).isInstanceOf(ApiValidationException.class)
                .hasMessage("when must be upcoming or past.");
    }

    // ---- dashboard ----

    @Test
    void theDashboardCountsAndNextAppointment() {
        when(bookings.countByProfessionalIdAndStatusAndExpiresAtAfter(professionalId, BookingStatus.PENDING, NOW)).thenReturn(4L);
        when(bookings.countByProfessionalIdAndStatusAndExpiresAtAfter(professionalId, BookingStatus.ALTERNATE_PROPOSED, NOW)).thenReturn(1L);
        when(bookings.countByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(professionalId, BookingStatus.CONFIRMED, NOW))
                .thenReturn(2L);
        Booking next = confirmed(Instant.parse("2026-10-14T12:30:00Z"));
        when(bookings.findFirstByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                professionalId, BookingStatus.CONFIRMED, NOW)).thenReturn(Optional.of(next));
        when(daysOff.findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(professionalId, TODAY))
                .thenReturn(List.of(new ProfessionalDayOff(professionalId, TODAY.plusDays(20), TODAY.plusDays(22), null)));
        professional.setRegistrationExpiry(TODAY.plusDays(200));

        ProDashboard d = service.dashboard(me);

        assertThat(d.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(d.active()).isTrue();
        assertThat(d.accountStatus()).isEqualTo("ACTIVE");
        assertThat(d.pendingRequests()).isEqualTo(4);
        assertThat(d.awaitingUser()).isEqualTo(1);
        assertThat(d.upcomingConfirmed()).isEqualTo(2);
        assertThat(d.nextAppointment().id()).isEqualTo(next.getId());
        assertThat(d.nextAppointment().contactName()).isEqualTo("Ravi Kumar");
        assertThat(d.nextAppointment().meetingMethod()).isEqualTo("VIDEO");
        assertThat(d.nextAppointment().scheduledTime().toString()).isEqualTo("2026-10-14T18:00+05:30");
        assertThat(d.registrationDaysLeft()).isEqualTo(200);
        assertThat(d.hasWeeklyHours()).isTrue();
        assertThat(d.daysOffCount()).isEqualTo(1);
        assertThat(d.warnings()).isEmpty();
    }

    @Test
    void eachWarningCodeAppearsForItsCondition() {
        professional.setRegistrationExpiry(LocalDate.of(2026, 10, 31));
        ProDashboard expiring = service.dashboard(me);
        assertThat(codes(expiring)).containsExactly("REGISTRATION_EXPIRING");
        assertThat(expiring.warnings().get(0).message()).isEqualTo("Your registration expires on 31 Oct 2026. Update it "
                + "with the NeuroNest team before then, or your listing will be hidden.");
        assertThat(expiring.registrationDaysLeft()).isEqualTo(19);

        professional.setRegistrationExpiry(LocalDate.of(2026, 10, 1));
        assertThat(codes(service.dashboard(me))).containsExactly("REGISTRATION_EXPIRED");
        professional.setRegistrationExpiry(null);
        professional.setVerificationStatus(VerificationStatus.EXPIRED);
        assertThat(codes(service.dashboard(me))).containsExactly("REGISTRATION_EXPIRED", "NOT_LISTED");

        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        professional.setWeeklyHours(Map.of());
        ProDashboard noHours = service.dashboard(me);
        assertThat(codes(noHours)).containsExactly("NO_WEEKLY_HOURS");
        assertThat(noHours.warnings().get(0).message()).isEqualTo("You have not set your weekly hours, so people cannot book you.");
        assertThat(noHours.hasWeeklyHours()).isFalse();

        professional.setWeeklyHours(Map.of("WED", List.of(Map.of("start", "18:00", "end", "20:00"))));
        professional.setActive(false);
        ProDashboard hidden = service.dashboard(me);
        assertThat(codes(hidden)).containsExactly("NOT_LISTED");
        assertThat(hidden.warnings().get(0).message()).isEqualTo("Your listing is not visible to the public right now.");

        professional.setVerificationStatus(VerificationStatus.SUSPENDED);
        assertThat(codes(service.dashboard(me))).containsExactly("NOT_LISTED", "SUSPENDED");

        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        professional.setActive(true);
        when(daysOff.findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(professionalId, TODAY))
                .thenReturn(List.of(new ProfessionalDayOff(professionalId, TODAY.plusDays(2), TODAY.plusDays(2), null)));
        when(bookings.countScheduledBetween(eq(professionalId), any(), any(), any())).thenReturn(1L);
        ProDashboard conflict = service.dashboard(me);
        assertThat(codes(conflict)).containsExactly("DAY_OFF_CONFLICTS");
        assertThat(conflict.warnings().get(0).message())
                .isEqualTo("You have a confirmed appointment on a day you marked as off.");
        verify(bookings, atLeastOnce()).countScheduledBetween(eq(professionalId),
                eq(java.util.Set.of(BookingStatus.CONFIRMED)), eq(Instant.parse("2026-10-13T18:30:00Z")),
                eq(Instant.parse("2026-10-14T18:30:00Z")));
        verify(bookings, never()).countOpenRequestedBetween(any(), any(), any(), any(), any());
        verifyNoMoreInteractionsOnWrites();
    }

    private void verifyNoMoreInteractionsOnWrites() {
        verify(bookings, never()).saveAndFlush(any());
        verify(daysOff, never()).saveAndFlush(any());
        verify(bookings, never()).countByUserIdAndCreatedAtAfter(any(), any());
        verify(bookings, never()).markProfessionalNotified(any(), any());
    }
}
