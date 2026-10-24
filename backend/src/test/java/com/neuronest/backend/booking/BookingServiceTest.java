package com.neuronest.backend.booking;

import com.neuronest.backend.assessment.AssessmentResult;
import com.neuronest.backend.assessment.AssessmentResultRepository;
import com.neuronest.backend.auth.User;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingDtos.CreateBookingRequest;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.notification.BookingEvent;
import com.neuronest.backend.payment.Payment;
import com.neuronest.backend.payment.PaymentRepository;
import com.neuronest.backend.payment.PaymentStatus;
import com.neuronest.backend.professional.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final Clock CLOCK = Clock.fixed(NOW, AvailabilityService.ZONE);
    // The professional works Wednesdays 18:00 to 20:00 in 60-minute sessions: slots 18:00 and 19:00.
    private static final String WED_18 = "2026-10-14T18:00:00+05:30";
    private static final String WED_19 = "2026-10-14T19:00:00+05:30";

    private final PublicProfessionalRepository directoryRepository = mock(PublicProfessionalRepository.class);
    // Real default methods (the taken-slot rule), stubbed abstract queries.
    private final BookingRepository bookings = mock(BookingRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final ProfessionalAccountRepository accounts = mock(ProfessionalAccountRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AssessmentResultRepository assessments = mock(AssessmentResultRepository.class);
    private final ProfessionalDayOffRepository daysOff = mock(ProfessionalDayOffRepository.class);
    private final SlotService slots = new SlotService(new AvailabilityService(), bookings, daysOff);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PublicProfessionalService directory = new PublicProfessionalService(directoryRepository, slots, CLOCK);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final BookingService service = new BookingService(bookings, slots, directory, professionals, accounts, users,
            assessments, events, new PaymentAmounts(paymentRepository), CLOCK);

    private final UUID userId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private Professional professional;

    @BeforeEach
    void setUp() {
        professional = new Professional();
        ReflectionTestUtils.setField(professional, "id", professionalId);
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(CredentialType.PSYCHOLOGIST);
        professional.setConsultationMode(ConsultationMode.BOTH);
        professional.setContactEmail("asha@example.com");
        professional.setCity("Pune");
        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        professional.setVerified(true);
        professional.setActive(true);
        professional.setSessionMinutes(60);
        professional.setBufferMinutes(0);
        professional.setWeeklyHours(Map.of("WED", List.of(Map.of("start", "18:00", "end", "20:00"))));
        when(directoryRepository.findVisibleById(eq(professionalId), any())).thenReturn(Optional.of(professional));

        User user = new User();
        user.setEmail("user@example.com");
        user.setClerkUserId("user_clerk_1");
        when(users.findById(userId)).thenReturn(Optional.of(user));

        setUpBookingDefaults();
        when(accounts.findByClerkUserId(any())).thenReturn(Optional.empty());
        when(professionals.findById(any())).thenReturn(Optional.empty());
    }

    // Kept separate so a test can reset the booking repository and start again.
    private void setUpBookingDefaults() {
        doAnswer(inv -> inv.getArgument(0)).when(bookings).saveAndFlush(any());
        doReturn(List.of()).when(bookings).findInWindow(any(), any(), any(), any());
        doReturn(0L).when(bookings).countByUserIdAndCreatedAtAfter(any(), any());
        doReturn(0L).when(bookings).countOpenByUser(any(), any(), any());
        doReturn(0L).when(bookings).countOpenByUserAndProfessional(any(), any(), any(), any());
    }

    private CreateBookingRequest request(String slot, String alternate, Boolean consent) {
        return new CreateBookingRequest(professionalId.toString(), "online", "  Ravi Kumar ", "098765 43210", slot,
                alternate, " First session, please. ", consent, "VIDEO", null);
    }

    private BookingView book(String slot) {
        return service.create(userId, request(slot, null, true));
    }

    private Booking existing(BookingStatus status, String preferred, String proposed, UUID owner) {
        Booking b = new Booking();
        b.setProfessionalId(professionalId);
        b.setUserId(owner);
        b.setStatus(status);
        b.setPreferredDatetime(OffsetDateTime.parse(preferred).toInstant());
        if (proposed != null) b.setProposedDatetime(OffsetDateTime.parse(proposed).toInstant());
        return b;
    }

    // ---- creating ----

    @Test
    void aValidSlotIsAcceptedAndStoredAsPending() {
        BookingView view = service.create(userId, request(WED_18, WED_19, true));

        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.preferredDatetime()).isEqualTo(OffsetDateTime.parse(WED_18));
        assertThat(view.alternateDatetime()).isEqualTo(OffsetDateTime.parse(WED_19));
        assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(72 * 3600));
        assertThat(view.professional().fullName()).isEqualTo("Dr. Asha Rao");

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).saveAndFlush(saved.capture());
        Booking b = saved.getValue();
        assertThat(b.getUserId()).isEqualTo(userId);
        assertThat(b.getContactEmail()).isEqualTo("user@example.com");
        assertThat(b.getContactName()).isEqualTo("Ravi Kumar");
        assertThat(b.getContactPhone()).isEqualTo("+919876543210");
        assertThat(b.getNote()).isEqualTo("First session, please.");
        assertThat(b.getPreferredMode()).isEqualTo("ONLINE");
        assertThat(b.getConsentGivenAt()).isEqualTo(NOW);
        assertThat(b.getRespondedBy()).isNull();
        assertThat(b.getPreferredWindow()).isNull();
        assertThat(b.getPreferredMethod()).isEqualTo("VIDEO");
        assertThat(b.getSharedAssessment()).isNull();
        assertThat(b.getAssessmentSharedAt()).isNull();
        assertThat(view.preferredMethod()).isEqualTo("VIDEO");
    }

    @Test
    void aTimeThatIsNotOnTheWeeklyHoursIsRejected() {
        for (String notASlot : List.of("2026-10-14T17:00:00+05:30", "2026-10-14T18:30:00+05:30",
                "2026-10-15T18:00:00+05:30", "2026-10-14T12:45:00Z")) {
            assertThatThrownBy(() -> book(notASlot)).isInstanceOf(ApiValidationException.class)
                    .hasMessage("slotStart is not one of this professional's available times. Please choose a time from their availability.");
        }
        // The same instant written in UTC (13:30Z is 19:00 in India) is the same slot.
        assertThat(book("2026-10-14T13:30:00Z").status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> book("2026-10-14 18:00")).hasMessageStartingWith("slotStart must be a date and time with an offset");
        verify(bookings, times(1)).saveAndFlush(any());
    }

    @Test
    void aTimeLessThan24HoursAheadOrBeyond60DaysIsRejected() {
        professional.setWeeklyHours(Map.of("TUE", List.of(Map.of("start", "09:00", "end", "12:00"))));
        // Now + 24 hours is Tuesday 13 Oct 10:00: 09:00 is too soon, 10:00 is allowed.
        assertThatThrownBy(() -> book("2026-10-13T09:00:00+05:30")).isInstanceOf(ApiValidationException.class)
                .hasMessage("slotStart must be at least 24 hours from now.");
        assertThat(book("2026-10-13T10:00:00+05:30").status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> book("2026-12-15T09:00:00+05:30")).hasMessage("slotStart must be within the next 60 days.");
    }

    @Test
    void aSlotOverlappingAConfirmedBookingIsRejectedAndLeavesTheAvailability() {
        Booking confirmed = existing(BookingStatus.CONFIRMED, WED_19, null, UUID.randomUUID());
        doReturn(List.of(confirmed)).when(bookings).findInWindow(eq(professionalId), eq(BookingStatus.BLOCKS_SLOT), any(), any());

        assertThatThrownBy(() -> book(WED_19)).isInstanceOf(ConflictException.class)
                .hasMessage("This time is no longer available. Please choose another time.");
        assertThatThrownBy(() -> service.create(userId, request(WED_18, WED_19, true)))
                .hasMessage("This time is no longer available. Please choose another time.");

        var availability = directory.availability(professionalId.toString(), "2026-10-14", "2026-10-14");
        assertThat(availability.days()).singleElement().satisfies(day ->
                assertThat(day.slots()).containsExactly("2026-10-14T18:00:00+05:30"));
        // 18:00 to 19:00 does not overlap a booking that starts at 19:00.
        assertThat(book(WED_18).status()).isEqualTo("PENDING");
    }

    @Test
    void theProposedTimeAndTheBufferDecideWhatAConfirmedBookingBlocks() {
        // Confirmed for 18:00, but the professional proposed 19:00: only 19:00 is taken.
        doReturn(List.of(existing(BookingStatus.CONFIRMED, WED_18, WED_19, UUID.randomUUID())))
                .when(bookings).findInWindow(any(), any(), any(), any());
        assertThat(directory.availability(professionalId.toString(), "2026-10-14", "2026-10-14").days().get(0).slots())
                .containsExactly("2026-10-14T18:00:00+05:30");

        // 45-minute sessions with a 15-minute buffer: slots 18:00 and 19:00. A booking at 18:45 occupies
        // 18:45 to 19:45, which overlaps both.
        professional.setSessionMinutes(45);
        professional.setBufferMinutes(15);
        doReturn(List.of(existing(BookingStatus.CONFIRMED, "2026-10-14T18:45:00+05:30", null, UUID.randomUUID())))
                .when(bookings).findInWindow(any(), any(), any(), any());
        assertThat(directory.availability(professionalId.toString(), "2026-10-14", "2026-10-14").days()).isEmpty();
    }

    @Test
    void aPendingOrProposedRequestDoesNotRemoveTheSlot() {
        // Even if the database returned them, only AWAITING_PAYMENT and CONFIRMED bookings count.
        doReturn(List.of(existing(BookingStatus.PENDING, WED_19, null, UUID.randomUUID()),
                existing(BookingStatus.ALTERNATE_PROPOSED, WED_18, WED_19, UUID.randomUUID())))
                .when(bookings).findInWindow(any(), any(), any(), any());

        assertThat(directory.availability(professionalId.toString(), "2026-10-14", "2026-10-14").days().get(0).slots())
                .containsExactly("2026-10-14T18:00:00+05:30", "2026-10-14T19:00:00+05:30");
        assertThat(book(WED_19).status()).isEqualTo("PENDING");
        // And the query itself asks only for AWAITING_PAYMENT and CONFIRMED bookings.
        verify(bookings, atLeastOnce()).findInWindow(eq(professionalId), eq(BookingStatus.BLOCKS_SLOT), any(), any());
        assertThat(BookingStatus.BLOCKS_SLOT).containsExactlyInAnyOrder(BookingStatus.AWAITING_PAYMENT,
                BookingStatus.CONFIRMED);
    }

    @Test
    void aBookingAwaitingPaymentBlocksItsSlot() {
        doReturn(List.of(existing(BookingStatus.AWAITING_PAYMENT, WED_19, null, UUID.randomUUID())))
                .when(bookings).findInWindow(eq(professionalId), eq(BookingStatus.BLOCKS_SLOT), any(), any());

        assertThat(directory.availability(professionalId.toString(), "2026-10-14", "2026-10-14").days().get(0).slots())
                .containsExactly("2026-10-14T18:00:00+05:30");
        assertThatThrownBy(() -> book(WED_19)).isInstanceOf(ConflictException.class)
                .hasMessage("This time is no longer available. Please choose another time.");
    }

    @Test
    void anAlternateSlotEqualToTheFirstIsRejected() {
        assertThatThrownBy(() -> service.create(userId, request(WED_18, "2026-10-14T12:30:00Z", true)))
                .isInstanceOf(ApiValidationException.class)
                .hasMessage("alternateSlotStart must be different from slotStart.");
        assertThatThrownBy(() -> service.create(userId, request(WED_18, "2026-10-14T18:15:00+05:30", true)))
                .hasMessageStartingWith("alternateSlotStart is not one of this professional's available times.");
    }

    @Test
    void consentAndInputAreChecked() {
        assertThatThrownBy(() -> service.create(userId, request(WED_18, null, false))).isInstanceOf(ApiValidationException.class)
                .hasMessageStartingWith("consentGiven must be true.");
        assertThatThrownBy(() -> service.create(userId, request(WED_18, null, null))).hasMessageStartingWith("consentGiven must be true.");
        assertThatThrownBy(() -> service.create(userId, null)).hasMessage("Request body is required.");
        assertThatThrownBy(() -> service.create(userId, new CreateBookingRequest(professionalId.toString(), "online",
                "R", "9876543210", WED_18, null, null, true, "VIDEO", null))).hasMessage("contactName must be between 2 and 80 characters.");
        assertThatThrownBy(() -> service.create(userId, new CreateBookingRequest(professionalId.toString(), "online",
                "Ravi", "12345", WED_18, null, null, true, "VIDEO", null))).hasMessageStartingWith("contactPhone must be a 10-digit");
        assertThatThrownBy(() -> service.create(userId, new CreateBookingRequest(professionalId.toString(), "online",
                "Ravi", "9876543210", WED_18, null, "x".repeat(501), true, "VIDEO", null))).hasMessage("note must be at most 500 characters.");
        assertThatThrownBy(() -> service.create(userId, new CreateBookingRequest(professionalId.toString(), "phone",
                "Ravi", "9876543210", WED_18, null, null, true, "VIDEO", null))).hasMessage("preferredMode must be ONLINE or IN_PERSON.");
        professional.setConsultationMode(ConsultationMode.ONLINE);
        assertThatThrownBy(() -> service.create(userId, new CreateBookingRequest(professionalId.toString(), "in_person",
                "Ravi", "9876543210", WED_18, null, null, true, "VIDEO", null)))
                .hasMessage("preferredMode must be ONLINE: this professional only offers online sessions.");
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void aPhoneNumberIsRequired() {
        for (String missing : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.create(userId, new CreateBookingRequest(professionalId.toString(), "online",
                    "Ravi", missing, WED_18, null, null, true, "VIDEO", null))).isInstanceOf(ApiValidationException.class)
                    .hasMessage("A phone number is required so the professional can reach you after confirming.");
        }
        for (String good : List.of("9876543210", "+91 98765 43210", "098765-43210")) {
            reset(bookings);
            setUpBookingDefaults();
            service.create(userId, new CreateBookingRequest(professionalId.toString(), "online", "Ravi", good, WED_18,
                    null, null, true, "VIDEO", null));
            ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
            verify(bookings).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getContactPhone()).isEqualTo("+919876543210");
        }
    }

    @Test
    void sampleUnlistedOwnAndUnavailableProfessionalsCannotBeBooked() {
        professional.setSampleData(true);
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(ConflictException.class)
                .hasMessage("This is a sample listing and cannot be booked.");
        professional.setSampleData(false);

        professional.setWeeklyHours(Map.of());
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(ConflictException.class)
                .hasMessage("This professional has not set their availability yet.");
        professional.setWeeklyHours(Map.of("WED", List.of(Map.of("start", "18:00", "end", "20:00"))));
        professional.setSessionMinutes(null);
        assertThatThrownBy(() -> book(WED_18)).hasMessage("This professional has not set their availability yet.");
        professional.setSessionMinutes(60);

        ProfessionalAccount own = new ProfessionalAccount(professionalId, "user@example.com", "user_clerk_1");
        when(accounts.findByClerkUserId("user_clerk_1")).thenReturn(Optional.of(own));
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(ConflictException.class)
                .hasMessage("You cannot book your own professional listing.");
        when(accounts.findByClerkUserId("user_clerk_1")).thenReturn(Optional.empty());

        when(directoryRepository.findVisibleById(eq(professionalId), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(BookingExceptions.NotFoundException.class)
                .hasMessage("This professional was not found.");
        assertThatThrownBy(() -> service.create(userId, new CreateBookingRequest("not-an-id", "online", "Ravi", "9876543210",
                WED_18, null, null, true, "VIDEO", null))).isInstanceOf(BookingExceptions.NotFoundException.class);
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void openAndDailyLimitsApply() {
        doReturn(5L).when(bookings).countByUserIdAndCreatedAtAfter(eq(userId), eq(NOW.minusSeconds(24 * 3600)));
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(BookingExceptions.TooManyRequestsException.class)
                .hasMessage("You have sent 5 booking requests in the last 24 hours. Please try again later.");
        doReturn(4L).when(bookings).countByUserIdAndCreatedAtAfter(any(), any());

        doReturn(3L).when(bookings).countOpenByUser(eq(userId), eq(BookingStatus.OPEN), eq(NOW));
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("You already have 3 open booking requests.");
        doReturn(2L).when(bookings).countOpenByUser(any(), any(), any());

        doReturn(1L).when(bookings).countOpenByUserAndProfessional(eq(userId), eq(professionalId), eq(BookingStatus.OPEN), eq(NOW));
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("You already have an open request with this professional.");
        verify(bookings, never()).saveAndFlush(any());
    }

    // ---- reading and cancelling ----


    // Marks a single date as off for this professional, as the repository's overlap query would answer.
    private void dayOff(LocalDate off, String reason) {
        when(daysOff.findOverlapping(eq(professionalId), any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(1);
            LocalDate to = inv.getArgument(2);
            return off.isBefore(from) || off.isAfter(to) ? List.of()
                    : List.of(new ProfessionalDayOff(professionalId, off, off, reason));
        });
    }

    @Test
    void aSlotOnADayOffIsNotOfferedAndBookingItIsAConflict() {
        dayOff(LocalDate.of(2026, 10, 14), "PRIVATE-REASON family wedding");

        var availability = directory.availability(professionalId.toString(), "2026-10-13", "2026-10-21");

        assertThat(availability.days()).extracting(d -> d.date()).containsExactly(LocalDate.of(2026, 10, 21));
        assertThat(availability.toString()).doesNotContain("PRIVATE-REASON");
        assertThatThrownBy(() -> book(WED_18)).isInstanceOf(ConflictException.class)
                .hasMessage(BookingService.TAKEN_MESSAGE);
        assertThatThrownBy(() -> service.create(userId, request("2026-10-21T18:00:00+05:30", WED_19, true)))
                .isInstanceOf(ConflictException.class).hasMessage(BookingService.TAKEN_MESSAGE);
        verify(bookings, never()).saveAndFlush(any());
        // The next Wednesday is still bookable.
        assertThat(book("2026-10-21T18:00:00+05:30").status()).isEqualTo("PENDING");
    }

    @Test
    void theSummaryUsesOnlyTheUsersOwnBookingsAndTheProfessionalsPublicFields() {
        Booking next = existing(BookingStatus.CONFIRMED, WED_18, null, userId);
        ReflectionTestUtils.setField(next, "id", UUID.randomUUID());
        next.setPreferredMode("IN_PERSON");
        next.setMeetingMethod("IN_PERSON");
        next.setMeetingAddress("Clinic 4, MG Road, Pune");
        doReturn(Optional.of(next)).when(bookings)
                .findFirstByUserIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                        userId, BookingStatus.CONFIRMED, NOW);
        doReturn(3L).when(bookings).countByUserIdAndStatusAndExpiresAtAfter(userId, BookingStatus.PENDING, NOW);
        doReturn(1L).when(bookings).countByUserIdAndStatusAndExpiresAtAfter(userId, BookingStatus.ALTERNATE_PROPOSED, NOW);
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));

        var summary = service.summary(userId);

        assertThat(summary.waitingForProfessional()).isEqualTo(3);
        assertThat(summary.needsYourAnswer()).isEqualTo(1);
        assertThat(summary.nextAppointment().id()).isEqualTo(next.getId());
        assertThat(summary.nextAppointment().scheduledTime()).isEqualTo(OffsetDateTime.parse(WED_18));
        assertThat(summary.nextAppointment().meetingAddress()).isEqualTo("Clinic 4, MG Road, Pune");
        assertThat(summary.nextAppointment().professional().fullName()).isEqualTo("Dr. Asha Rao");
        assertThat(summary.toString()).doesNotContain("asha@example.com", "user@example.com");

        UUID otherUser = UUID.randomUUID();
        var empty = service.summary(otherUser);
        assertThat(empty.nextAppointment()).isNull();
        assertThat(empty.waitingForProfessional()).isZero();
        verify(bookings).countByUserIdAndStatusAndExpiresAtAfter(otherUser, BookingStatus.PENDING, NOW);
    }

    @Test
    void cancelWorksOnceAndASecondCancelIsAConflict() {
        UUID bookingId = UUID.randomUUID();
        Booking mine = existing(BookingStatus.PENDING, WED_18, null, userId);
        doReturn(Optional.of(mine)).when(bookings).findByIdForUpdate(bookingId);

        BookingView cancelled = service.cancel(userId, bookingId.toString());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(mine.getRespondedBy()).isEqualTo("USER");
        assertThat(mine.getRespondedAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> service.cancel(userId, bookingId.toString())).isInstanceOf(ConflictException.class)
                .hasMessage("This booking request can no longer be cancelled. Its status is CANCELLED.");
        verify(bookings, times(1)).saveAndFlush(any());
    }

    @Test
    void aBookingAwaitingPaymentCanBeCancelledAndTheProfessionalIsTold() {
        UUID bookingId = UUID.randomUUID();
        Booking mine = existing(BookingStatus.AWAITING_PAYMENT, WED_18, null, userId);
        mine.setPaymentDueAt(NOW.plusSeconds(3600));
        doReturn(Optional.of(mine)).when(bookings).findByIdForUpdate(bookingId);

        BookingView cancelled = service.cancel(userId, bookingId.toString());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(mine.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(mine.getRespondedBy()).isEqualTo("USER");
        assertThat(cancelled.paymentDueAt()).isNull();
        verify(events).publishEvent(new BookingEvent(BookingEvent.Type.CANCELLED_BY_USER, mine.getId()));
        // A paid booking cannot be cancelled here.
        Booking paid = existing(BookingStatus.CONFIRMED, WED_19, null, userId);
        UUID paidId = UUID.randomUUID();
        doReturn(Optional.of(paid)).when(bookings).findByIdForUpdate(paidId);
        assertThatThrownBy(() -> service.cancel(userId, paidId.toString())).isInstanceOf(ConflictException.class)
                .hasMessage("This booking request can no longer be cancelled. Its status is CONFIRMED.");
    }

    @Test
    void theSummaryListsBookingsAwaitingPaymentAsTheirOwnGroup() {
        Booking unpaid = existing(BookingStatus.AWAITING_PAYMENT, WED_19, null, userId);
        ReflectionTestUtils.setField(unpaid, "id", UUID.randomUUID());
        unpaid.setPreferredMode("ONLINE");
        unpaid.setMeetingMethod("VIDEO");
        unpaid.setMeetingLink("https://meet.example.com/PRIVATE-LINK");
        unpaid.setPaymentDueAt(NOW.plusSeconds(6 * 3600));
        doReturn(List.of(unpaid)).when(bookings).findByUserIdAndStatusAndPaymentDueAtAfterOrderByPaymentDueAtAscIdAsc(
                userId, BookingStatus.AWAITING_PAYMENT, NOW);
        when(professionals.findAllById(List.of(professionalId))).thenReturn(List.of(professional));

        var summary = service.summary(userId);

        assertThat(summary.awaitingPayment()).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo(unpaid.getId());
            assertThat(a.scheduledTime()).isEqualTo(OffsetDateTime.parse(WED_19));
            assertThat(a.paymentDueAt()).isEqualTo(NOW.plusSeconds(6 * 3600));
            assertThat(a.professional().fullName()).isEqualTo("Dr. Asha Rao");
        });
        // The upcoming appointment stays CONFIRMED-only, and no meeting detail is shown before payment.
        assertThat(summary.nextAppointment()).isNull();
        verify(bookings).findFirstByUserIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                userId, BookingStatus.CONFIRMED, NOW);
        assertThat(summary.toString()).doesNotContain("PRIVATE-LINK", "asha@example.com");
    }

    @Test
    void aBookingAwaitingPaymentShowsTheAmountTheOrderWouldCharge() {
        professional.setConsultationFee(1500);
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));
        when(professionals.findAllById(List.of(professionalId))).thenReturn(List.of(professional));
        Booking unpaid = existing(BookingStatus.AWAITING_PAYMENT, WED_19, null, userId);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(unpaid, "id", id);
        unpaid.setPaymentDueAt(NOW.plusSeconds(3600));
        doReturn(Optional.of(unpaid)).when(bookings).findByIdAndUserId(id, userId);
        doReturn(List.of(unpaid)).when(bookings).findByUserIdAndStatusAndPaymentDueAtAfterOrderByPaymentDueAtAscIdAsc(
                userId, BookingStatus.AWAITING_PAYMENT, NOW);

        BookingView view = service.get(userId, id.toString());
        assertThat(view.amount()).isEqualTo(150_000);
        assertThat(view.currency()).isEqualTo("INR");
        assertThat(service.summary(userId).awaitingPayment()).singleElement()
                .satisfies(a -> assertThat(a.amount()).isEqualTo(150_000));

        // Once an order exists, its amount is shown even if the fee changed since.
        Payment order = new Payment(id, "order_1", 120_000, "INR", NOW);
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(id, PaymentStatus.CREATED))
                .thenReturn(Optional.of(order));
        assertThat(service.get(userId, id.toString()).amount()).isEqualTo(120_000);
        assertThat(service.summary(userId).awaitingPayment().get(0).amount()).isEqualTo(120_000);

        // No amount once it is no longer waiting for payment.
        unpaid.setStatus(BookingStatus.CONFIRMED);
        assertThat(service.get(userId, id.toString()).amount()).isNull();
    }

    @Test
    void aPaidConfirmedBookingShowsWhatWasPaidAndAnOlderOneShowsNothing() {
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));
        Booking paid = existing(BookingStatus.CONFIRMED, WED_19, null, userId);
        UUID paidId = UUID.randomUUID();
        ReflectionTestUtils.setField(paid, "id", paidId);
        doReturn(Optional.of(paid)).when(bookings).findByIdAndUserId(paidId, userId);
        Payment payment = new Payment(paidId, "order_1", 80_000, "INR", NOW);
        payment.markPaid("pay_1", NOW, 10, 8_000, 72_000);
        when(paymentRepository.findFirstByBookingIdAndStatusOrderByCreatedAtDesc(paidId, PaymentStatus.PAID))
                .thenReturn(Optional.of(payment));

        BookingView view = service.get(userId, paidId.toString());
        assertThat(view.paidAmount()).isEqualTo(80_000);
        assertThat(view.paidCurrency()).isEqualTo("INR");
        // The amount still to pay is a different field and stays empty once paid.
        assertThat(view.amount()).isNull();

        // An older confirmed booking (no payment row) shows nothing.
        Booking legacy = existing(BookingStatus.CONFIRMED, WED_18, null, userId);
        UUID legacyId = UUID.randomUUID();
        ReflectionTestUtils.setField(legacy, "id", legacyId);
        doReturn(Optional.of(legacy)).when(bookings).findByIdAndUserId(legacyId, userId);
        assertThat(service.get(userId, legacyId.toString()).paidAmount()).isNull();

        // Paid, but the booking is not CONFIRMED (a refund case): no receipt.
        paid.setStatus(BookingStatus.EXPIRED);
        assertThat(service.get(userId, paidId.toString()).paidAmount()).isNull();
    }

    @Test
    void anExpiredUnpaidBookingKeepsItsPaymentDeadlineAndAnUnansweredOneHasNone() {
        Booking unpaid = existing(BookingStatus.EXPIRED, WED_19, null, userId);
        unpaid.setPaymentDueAt(NOW.minusSeconds(3600));
        Booking unanswered = existing(BookingStatus.EXPIRED, WED_18, null, userId);

        assertThat(BookingView.from(unpaid, professional).paymentDueAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(BookingView.from(unanswered, professional).paymentDueAt()).isNull();
        // Paid or cancelled bookings do not carry it.
        unpaid.setStatus(BookingStatus.CANCELLED);
        assertThat(BookingView.from(unpaid, professional).paymentDueAt()).isNull();
    }

    @Test
    void anotherUsersBookingIsNotFound() {
        UUID bookingId = UUID.randomUUID();
        Booking theirs = existing(BookingStatus.PENDING, WED_18, null, UUID.randomUUID());
        doReturn(Optional.of(theirs)).when(bookings).findByIdForUpdate(bookingId);
        doReturn(Optional.empty()).when(bookings).findByIdAndUserId(bookingId, userId);

        assertThatThrownBy(() -> service.get(userId, bookingId.toString()))
                .isInstanceOf(BookingExceptions.NotFoundException.class).hasMessage("This booking request was not found.");
        assertThatThrownBy(() -> service.cancel(userId, bookingId.toString()))
                .isInstanceOf(BookingExceptions.NotFoundException.class).hasMessage("This booking request was not found.");
        assertThatThrownBy(() -> service.get(userId, "not-an-id")).isInstanceOf(BookingExceptions.NotFoundException.class);
        assertThat(theirs.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void theViewNeverExposesContactOrAdminFields() {
        List<String> fields = java.util.Arrays.stream(BookingView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(fields).doesNotContain("adminNote", "contactEmail", "contactPhone", "userId", "note");
        List<String> summary = java.util.Arrays.stream(BookingDtos.ProfessionalSummary.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(summary).containsExactly("id", "fullName", "credentialType", "city");
    }

    // ---- meeting method ----

    private CreateBookingRequest withMethod(String mode, String method, Boolean share) {
        return new CreateBookingRequest(professionalId.toString(), mode, "Ravi Kumar", "9876543210", WED_18, null,
                null, true, method, share);
    }

    private Booking savedBooking() {
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void anOnlineRequestNeedsPhoneOrVideo() {
        for (String missing : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> service.create(userId, withMethod("online", missing, null)))
                    .isInstanceOf(ApiValidationException.class)
                    .hasMessage("preferredMethod is required for an online session: PHONE or VIDEO.");
        }
        assertThatThrownBy(() -> service.create(userId, withMethod("online", "IN_PERSON", null)))
                .isInstanceOf(ApiValidationException.class).hasMessage("preferredMethod must be PHONE or VIDEO.");
        verify(bookings, never()).saveAndFlush(any());

        service.create(userId, withMethod("online", " phone ", null));
        assertThat(savedBooking().getPreferredMethod()).isEqualTo("PHONE");
    }

    @Test
    void anInPersonRequestIgnoresTheMethod() {
        BookingView view = service.create(userId, withMethod("in_person", "VIDEO", null));
        Booking b = savedBooking();
        assertThat(b.getPreferredMode()).isEqualTo("IN_PERSON");
        assertThat(b.getPreferredMethod()).isNull();
        assertThat(view.preferredMethod()).isNull();

        // Even a value that would be wrong for ONLINE is ignored.
        reset(bookings);
        setUpBookingDefaults();
        service.create(userId, withMethod("in_person", "carrier pigeon", null));
        assertThat(savedBooking().getPreferredMethod()).isNull();
    }

    @Test
    void meetingDetailsAreOnlyShownOnceConfirmed() {
        Booking b = existing(BookingStatus.PENDING, WED_18, null, userId);
        b.setPreferredMethod("VIDEO");
        b.setMeetingMethod("VIDEO");
        b.setMeetingLink("https://meet.example.com/abc");
        b.setMeetingAddress("Clinic, Pune");
        for (BookingStatus status : BookingStatus.values()) {
            b.setStatus(status);
            BookingView view = BookingView.from(b, professional);
            assertThat(view.preferredMethod()).isEqualTo("VIDEO");
            if (status == BookingStatus.CONFIRMED) {
                assertThat(view.meetingMethod()).isEqualTo("VIDEO");
                assertThat(view.meetingLink()).isEqualTo("https://meet.example.com/abc");
                assertThat(view.meetingAddress()).isEqualTo("Clinic, Pune");
            } else {
                assertThat(view.meetingMethod()).isNull();
                assertThat(view.meetingLink()).isNull();
                assertThat(view.meetingAddress()).isNull();
            }
        }
    }

    // ---- sharing the latest assessment ----

    private AssessmentResult result(String instrument, int score, String band, Instant createdAt) {
        AssessmentResult r = new AssessmentResult();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setSessionId(UUID.randomUUID());
        r.setInstrument(instrument);
        r.setItemResponses(List.of(3, 3, 3, 3, 3, 3, 3, 3, 3));
        r.setScore(score);
        r.setSeverityBand(band);
        r.setRiskFlag(true);
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        return r;
    }

    @Test
    void notTickedStoresNothingAndNeverReadsAssessments() {
        service.create(userId, withMethod("online", "VIDEO", null));
        Booking b = savedBooking();
        assertThat(b.getSharedAssessment()).isNull();
        assertThat(b.getAssessmentSharedAt()).isNull();

        reset(bookings);
        setUpBookingDefaults();
        service.create(userId, withMethod("online", "VIDEO", false));
        assertThat(savedBooking().getSharedAssessment()).isNull();
        verifyNoInteractions(assessments);
    }

    @Test
    void tickedStoresExactlyTheFiveSnapshotFields() {
        AssessmentResult latest = result("PHQ9", 14, "moderate", Instant.parse("2026-10-10T08:00:00Z"));
        when(assessments.findLatestForUser(eq(userId), any())).thenReturn(List.of(latest));

        BookingView view = service.create(userId, withMethod("online", "VIDEO", true));
        Booking b = savedBooking();
        SharedAssessment shared = b.getSharedAssessment();
        assertThat(shared).isEqualTo(new SharedAssessment("PHQ9", "PHQ-9", 14, "moderate", "2026-10-10T08:00:00Z"));
        assertThat(b.getAssessmentSharedAt()).isEqualTo(NOW);
        assertThat(view.sharedAssessment()).isEqualTo(shared);
        assertThat(view.assessmentSharedAt()).isEqualTo(NOW);
        // Only the latest one is asked for, and only for the signed-in user.
        verify(assessments).findLatestForUser(userId, PageRequest.of(0, 1));

        // Exactly these fields in the stored JSON: no item responses, risk flag or session id.
        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(shared);
        assertThat(mapper.readTree(json).propertyNames()).containsExactlyInAnyOrder(
                "instrument", "instrumentLabel", "score", "severityBand", "completedAt");
        assertThat(json).doesNotContain(latest.getSessionId().toString(), "riskFlag", "itemResponses", "true");

        // A later change to the user's assessment does not change the stored copy.
        latest.setScore(2);
        latest.setSeverityBand("minimal");
        assertThat(b.getSharedAssessment()).isEqualTo(shared);
        assertThat(b.getSharedAssessment().score()).isEqualTo(14);
    }

    @Test
    void tickedWithNoSavedResultIsRejected() {
        when(assessments.findLatestForUser(any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(userId, withMethod("online", "VIDEO", true)))
                .isInstanceOf(ApiValidationException.class)
                .hasMessage("You have no saved assessment result to share.");
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void anotherUsersResultsAreNeverUsed() {
        UUID otherUser = UUID.randomUUID();
        when(assessments.findLatestForUser(eq(otherUser), any()))
                .thenReturn(List.of(result("GAD7", 18, "severe", NOW.minusSeconds(60))));
        when(assessments.findLatestForUser(eq(userId), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(userId, withMethod("online", "VIDEO", true)))
                .hasMessage("You have no saved assessment result to share.");
        verify(assessments).findLatestForUser(eq(userId), any());
        verify(assessments, never()).findLatestForUser(eq(otherUser), any());
        verify(bookings, never()).saveAndFlush(any());
    }

    // ---- no professional contact details in any booking response ----

    @Test
    void noBookingResponseContainsTheProfessionalsContactDetails() {
        professional.setContactEmail("asha.private@example.com");
        professional.setContactPhone("+919812345678");
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));
        when(professionals.findAllById(any())).thenReturn(List.of(professional));

        BookingView created = service.create(userId, withMethod("online", "VIDEO", null));
        Booking stored = savedBooking();
        stored.setStatus(BookingStatus.CONFIRMED);
        stored.setAdminNote("internal admin note");
        UUID bookingId = UUID.randomUUID();
        doReturn(Optional.of(stored)).when(bookings).findByIdAndUserId(bookingId, userId);
        doReturn(new PageImpl<>(List.of(stored))).when(bookings).findByUserId(eq(userId), any());

        JsonMapper mapper = JsonMapper.builder().build();
        for (Object response : List.of(created, service.get(userId, bookingId.toString()), service.mine(userId, 0, 20))) {
            String json = mapper.writeValueAsString(response);
            assertThat(json).contains("Dr. Asha Rao");
            assertThat(json).doesNotContain("asha.private@example.com", "9812345678", "contactEmail", "contactPhone",
                    "adminNote", "internal admin note");
        }
    }
}
