package com.neuronest.backend.pro;

import com.neuronest.backend.booking.PaymentAmounts;
import com.neuronest.backend.payment.PaymentRepository;
import com.neuronest.backend.assessment.AssessmentResultRepository;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingExceptions;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingService;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProBookingDtos.ConfirmRequest;
import com.neuronest.backend.pro.ProBookingDtos.DeclineRequest;
import com.neuronest.backend.pro.ProBookingDtos.ProBookingView;
import com.neuronest.backend.pro.ProBookingDtos.ProposeRequest;
import com.neuronest.backend.notification.BookingEvent;
import com.neuronest.backend.professional.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProBookingServiceTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, AvailabilityService.ZONE);
    // The professional works Wednesdays and Thursdays 18:00 to 20:30: slots 18:00 and 19:15 on the global grid.
    private static final String WED_18 = "2026-10-14T18:00:00+05:30";
    private static final String WED_1915 = "2026-10-14T19:15:00+05:30";
    private static final String THU_18 = "2026-10-15T18:00:00+05:30";
    private static final String LINK = "https://meet.google.com/abc-defg-hij";

    // Real default methods (the shared taken-slot rule), with the abstract queries answered from `store`.
    private final BookingRepository bookings = mock(BookingRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final ProfessionalAccountRepository accounts = mock(ProfessionalAccountRepository.class);
    private final ProfessionalDayOffRepository daysOff = mock(ProfessionalDayOffRepository.class);
    private final SlotService slots = new SlotService(new AvailabilityService(), bookings, daysOff,
            mock(com.neuronest.backend.professional.ProfessionalBlockedSlotRepository.class));
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ProBookingService service = new ProBookingService(bookings, slots, professionals, accounts, events, CLOCK);
    private final BookingService userService = new BookingService(bookings, slots, mock(PublicProfessionalService.class),
            professionals, accounts, mock(UserRepository.class), mock(AssessmentResultRepository.class), events,
            new PaymentAmounts(mock(PaymentRepository.class)));

    private final List<Booking> store = new ArrayList<>();
    private final UUID professionalId = UUID.randomUUID();
    private final UUID otherProfessionalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final ProPrincipal me = new ProPrincipal("pro_clerk_me");
    private final ProPrincipal other = new ProPrincipal("pro_clerk_other");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userService, "clock", CLOCK);
        Professional professional = professional(professionalId);
        Professional otherProfessional = professional(otherProfessionalId);
        when(professionals.findByIdForUpdate(professionalId)).thenReturn(Optional.of(professional));
        when(professionals.findByIdForUpdate(otherProfessionalId)).thenReturn(Optional.of(otherProfessional));
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));
        when(accounts.findByClerkUserId("pro_clerk_me"))
                .thenReturn(Optional.of(new ProfessionalAccount(professionalId, "asha@example.com", "pro_clerk_me")));
        when(accounts.findByClerkUserId("pro_clerk_other"))
                .thenReturn(Optional.of(new ProfessionalAccount(otherProfessionalId, "ravi@example.com", "pro_clerk_other")));

        doAnswer(inv -> inv.getArgument(0)).when(bookings).saveAndFlush(any());
        doAnswer(inv -> store.stream().filter(b -> b.getId().equals(inv.getArgument(0))).findFirst())
                .when(bookings).findByIdForUpdate(any());
        doAnswer(inv -> store.stream().filter(b -> b.getId().equals(inv.getArgument(0))
                        && b.getProfessionalId().equals(inv.getArgument(1))).findFirst())
                .when(bookings).findByIdAndProfessionalId(any(), any());
        doAnswer(inv -> store.stream().filter(b -> b.getId().equals(inv.getArgument(0))
                        && b.getUserId().equals(inv.getArgument(1))).findFirst().map(Booking::getProfessionalId))
                .when(bookings).findProfessionalIdByIdAndUserId(any(), any());
        doAnswer(inv -> store.stream().filter(b -> b.getId().equals(inv.getArgument(0))
                        && b.getUserId().equals(inv.getArgument(1))).findFirst())
                .when(bookings).findByIdAndUserId(any(), any());
        doAnswer(inv -> store.stream().filter(b -> b.getProfessionalId().equals(inv.getArgument(0))
                        && inv.<java.util.Collection<BookingStatus>>getArgument(1).contains(b.getStatus())).toList())
                .when(bookings).findInWindow(any(), any(), any(), any());
    }

    private static Professional professional(UUID id) {
        Professional p = new Professional();
        ReflectionTestUtils.setField(p, "id", id);
        p.setFullName("Dr. Asha Rao");
        p.setCredentialType(CredentialType.PSYCHOLOGIST);
        p.setConsultationMode(ConsultationMode.BOTH);
        p.setSessionMinutes(60);
        p.setBufferMinutes(0);
        // A paid professional; the free-session tests clear it.
        p.setConsultationFee(1500);
        p.setWeeklyHours(Map.of(
                "WED", List.of(Map.of("start", "18:00", "end", "20:30")),
                "THU", List.of(Map.of("start", "18:00", "end", "20:30"))));
        return p;
    }

    // A request from `userId` to this professional, waiting for a reply.
    private Booking pending(String mode, String method) {
        Booking b = new Booking();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setProfessionalId(professionalId);
        b.setUserId(userId);
        b.setSessionId(UUID.randomUUID());
        b.setContactName("Ravi Kumar");
        b.setContactEmail("user@example.com");
        b.setContactPhone("+919876543210");
        b.setPreferredMode(mode);
        b.setPreferredMethod(method);
        b.setPreferredDatetime(at(WED_18));
        b.setAlternateDatetime(at(WED_1915));
        b.setNote("First session, please.");
        b.setAdminNote("internal");
        b.setStatus(BookingStatus.PENDING);
        b.setCreatedAt(NOW.minusSeconds(3600));
        b.setExpiresAt(NOW.plus(Duration.ofHours(71)));
        store.add(b);
        return b;
    }

    private Booking online() {
        return pending("ONLINE", "VIDEO");
    }

    private Booking confirmedAt(String time) {
        Booking b = pending("ONLINE", "PHONE");
        b.setUserId(UUID.randomUUID());
        b.setStatus(BookingStatus.CONFIRMED);
        b.setPreferredDatetime(at(time));
        return b;
    }

    private static Instant at(String time) {
        return OffsetDateTime.parse(time).toInstant();
    }

    private static ConfirmRequest confirmBody(String slot, String method, String link, String address, String note) {
        return new ConfirmRequest(slot, method, link, address, note);
    }

    // ---- confirm ----

    @Test
    void confirmByPhoneStoresTheRightFields() {
        Booking b = pending("ONLINE", "PHONE");

        ProBookingView view = service.confirm(me, b.getId().toString(),
                confirmBody("preferred", " phone ", LINK, "ignored address", "  See you then. "));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(b.getPaymentDueAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(b.getPreferredDatetime()).isEqualTo(at(WED_18));
        assertThat(b.getMeetingMethod()).isEqualTo("PHONE");
        assertThat(b.getMeetingLink()).isNull();
        assertThat(b.getMeetingAddress()).isNull();
        assertThat(b.getProfessionalNote()).isEqualTo("See you then.");
        assertThat(b.getRespondedBy()).isEqualTo("PROFESSIONAL");
        assertThat(b.getRespondedAt()).isEqualTo(NOW);
        assertThat(view.status()).isEqualTo("AWAITING_PAYMENT");
        // The contact details are shown once the booking is paid and CONFIRMED.
        assertThat(view.contactPhone()).isNull();
        assertThat(view.contactEmail()).isNull();
        b.setStatus(BookingStatus.CONFIRMED);
        assertThat(service.get(me, b.getId().toString()).contactPhone()).isEqualTo("+919876543210");
        verify(professionals).findByIdForUpdate(professionalId);
    }

    @Test
    void confirmByVideoOnTheAlternateMakesItTheScheduledTime() {
        Booking b = online();

        ProBookingView view = service.confirm(me, b.getId().toString(),
                confirmBody("ALTERNATE", "VIDEO", "  " + LINK + " ", "ignored address", null));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(b.getPreferredDatetime()).isEqualTo(at(WED_1915));
        assertThat(b.scheduledTime()).isEqualTo(at(WED_1915));
        assertThat(b.getMeetingMethod()).isEqualTo("VIDEO");
        assertThat(b.getMeetingLink()).isEqualTo(LINK);
        assertThat(b.getMeetingAddress()).isNull();
        assertThat(b.getProfessionalNote()).isNull();
        assertThat(view.preferredDatetime()).isEqualTo(OffsetDateTime.parse(WED_1915));
        // The agreed time blocks the slot everywhere while it waits for payment.
        assertThat(slots.check(professional(professionalId), at(WED_1915), ZonedDateTime.now(CLOCK)))
                .isEqualTo(SlotService.SlotCheck.TAKEN);
    }

    @Test
    void confirmingAndAcceptingPublishTheAwaitingPaymentEventNotConfirmed() {
        Booking confirmed = online();
        service.confirm(me, confirmed.getId().toString(), confirmBody("PREFERRED", "VIDEO", LINK, null, null));
        Booking accepted = online();
        accepted.setUserId(userId);
        service.propose(me, accepted.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null));
        userService.acceptProposal(userId, accepted.getId().toString());

        verify(events).publishEvent(new BookingEvent(BookingEvent.Type.AWAITING_PAYMENT, confirmed.getId()));
        verify(events).publishEvent(new BookingEvent(BookingEvent.Type.AWAITING_PAYMENT, accepted.getId()));
        verify(events, never()).publishEvent(new BookingEvent(BookingEvent.Type.CONFIRMED, confirmed.getId()));
        verify(events, never()).publishEvent(new BookingEvent(BookingEvent.Type.CONFIRMED, accepted.getId()));
    }

    @Test
    void aFreeSessionIsConfirmedAtOnceWithoutAPaymentDeadline() {
        for (Integer fee : new Integer[] {null, 0}) {
            Professional free = professional(professionalId);
            free.setConsultationFee(fee);
            when(professionals.findByIdForUpdate(professionalId)).thenReturn(Optional.of(free));

            Booking confirmed = online();
            ProBookingView view = service.confirm(me, confirmed.getId().toString(),
                    confirmBody("PREFERRED", "VIDEO", LINK, null, null));
            assertThat(view.status()).as("fee " + fee).isEqualTo("CONFIRMED");
            assertThat(confirmed.getPaymentDueAt()).isNull();
            // Paid and confirmed, so the professional sees the contact details as for any confirmed booking.
            assertThat(view.contactPhone()).isEqualTo("+919876543210");

            Booking accepted = online();
            service.propose(me, accepted.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null));
            BookingView acceptedView = userService.acceptProposal(userId, accepted.getId().toString());
            assertThat(acceptedView.status()).isEqualTo("CONFIRMED");
            // Stored, but only the join endpoint gives the raw link out.
            assertThat(accepted.getMeetingLink()).isEqualTo(LINK);
            assertThat(acceptedView.meetingLink()).isNull();
            assertThat(accepted.getPaymentDueAt()).isNull();

            verify(events).publishEvent(new BookingEvent(BookingEvent.Type.CONFIRMED, confirmed.getId()));
            verify(events).publishEvent(new BookingEvent(BookingEvent.Type.CONFIRMED, accepted.getId()));
            verify(events, never()).publishEvent(new BookingEvent(BookingEvent.Type.AWAITING_PAYMENT, confirmed.getId()));
            verify(events, never()).publishEvent(new BookingEvent(BookingEvent.Type.AWAITING_PAYMENT, accepted.getId()));
            // Both times are booked now.
            assertThat(slots.check(free, at(WED_18), ZonedDateTime.now(CLOCK))).isEqualTo(SlotService.SlotCheck.TAKEN);
            store.clear();
        }
    }

    @Test
    void theInboxShowsThePaymentDeadlineWhileWaitingAndAfterAnUnpaidExpiryOnly() {
        Booking b = online();
        ProBookingView view = service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "VIDEO", LINK, null, null));
        assertThat(view.paymentDueAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        // No contact details before payment.
        assertThat(view.contactPhone()).isNull();

        b.setStatus(BookingStatus.EXPIRED);
        assertThat(service.get(me, b.getId().toString()).paymentDueAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        b.setStatus(BookingStatus.CONFIRMED);
        assertThat(service.get(me, b.getId().toString()).paymentDueAt()).isNull();

        // An unanswered request that expired has no payment deadline.
        Booking unanswered = online();
        unanswered.setStatus(BookingStatus.EXPIRED);
        assertThat(service.get(me, unanswered.getId().toString()).paymentDueAt()).isNull();
    }

    @Test
    void thePaymentWindowComesFromTheSetting() {
        service.setPaymentWindowHours(48);
        Booking b = online();

        service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "VIDEO", LINK, null, null));

        assertThat(b.getPaymentDueAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
    }

    @Test
    void confirmInPersonStoresTheAddressWithoutTheContactCheck() {
        Booking b = pending("IN_PERSON", null);
        String address = "Clinic 4, MG Road, Pune 411001. Ring 9876543210 at the gate.";

        service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "IN_PERSON", LINK, " " + address + " ", null));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(b.getMeetingMethod()).isEqualTo("IN_PERSON");
        assertThat(b.getMeetingAddress()).isEqualTo(address);
        assertThat(b.getMeetingLink()).isNull();
    }

    @Test
    void aMethodThatDoesNotMatchTheRequestedModeIsRejected() {
        Booking onlineRequest = online();
        assertThatThrownBy(() -> service.confirm(me, onlineRequest.getId().toString(),
                confirmBody("PREFERRED", "IN_PERSON", null, "Clinic 4, MG Road", null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("PHONE or VIDEO");

        Booking inPersonRequest = pending("IN_PERSON", null);
        inPersonRequest.setPreferredDatetime(at(THU_18));
        assertThatThrownBy(() -> service.confirm(me, inPersonRequest.getId().toString(),
                confirmBody("PREFERRED", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("must be IN_PERSON");
        assertThatThrownBy(() -> service.confirm(me, inPersonRequest.getId().toString(),
                confirmBody("PREFERRED", "PHONE", null, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("must be IN_PERSON");

        assertThat(onlineRequest.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(inPersonRequest.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void aBadMeetingLinkOrAddressIsRejected() {
        String id = online().getId().toString();
        for (String link : new String[]{"http://meet.google.com/abc", "meet.google.com/abc", "https://", "https://meet",
                "https://meet google.com/x", "https://meet.google.com/" + "a".repeat(300), null, "  "}) {
            assertThatThrownBy(() -> service.confirm(me, id, confirmBody("PREFERRED", "VIDEO", link, null, null)))
                    .as(String.valueOf(link)).isInstanceOf(ApiValidationException.class)
                    .hasMessageContaining("meetingLink");
        }
        String inPerson = pending("IN_PERSON", null).getId().toString();
        for (String address : new String[]{null, "Pune", "a".repeat(201)}) {
            assertThatThrownBy(() -> service.confirm(me, inPerson, confirmBody("PREFERRED", "IN_PERSON", null, address, null)))
                    .isInstanceOf(ApiValidationException.class).hasMessageContaining("meetingAddress");
        }
        assertThatThrownBy(() -> service.confirm(me, id, confirmBody("PREFERRED", "ZOOM", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("meetingMethod");
        assertThatThrownBy(() -> service.confirm(me, id, confirmBody("FIRST", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("chosenSlot");
        assertThatThrownBy(() -> service.confirm(me, id, null)).isInstanceOf(ApiValidationException.class);
    }

    @Test
    void theNoteIsLimitedAndMustNotContainContactDetails() {
        String id = online().getId().toString();
        assertThatThrownBy(() -> service.confirm(me, id, confirmBody("PREFERRED", "VIDEO", LINK, null, "a".repeat(501))))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("500");
        assertThatThrownBy(() -> service.confirm(me, id,
                confirmBody("PREFERRED", "VIDEO", LINK, null, "Call me on 98765 43210")))
                .isInstanceOf(ApiValidationException.class).hasMessage(ContactDetailsGuard.message("note"));
        assertThatThrownBy(() -> service.decline(me, id, new DeclineRequest("Write to asha@example.com")))
                .isInstanceOf(ApiValidationException.class).hasMessage(ContactDetailsGuard.message("note"));
    }

    @Test
    void anAlternateMustExistAndTheChosenTimeMustBeInTheFuture() {
        Booking noAlternate = online();
        noAlternate.setAlternateDatetime(null);
        assertThatThrownBy(() -> service.confirm(me, noAlternate.getId().toString(),
                confirmBody("ALTERNATE", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("PREFERRED");

        Booking past = online();
        past.setPreferredDatetime(NOW.minusSeconds(60));
        assertThatThrownBy(() -> service.confirm(me, past.getId().toString(),
                confirmBody("PREFERRED", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("already passed");
    }

    @Test
    void phoneNeedsAContactPhone() {
        Booking b = online();
        b.setContactPhone(null);
        assertThatThrownBy(() -> service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "PHONE", null, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("no phone number");
    }

    @Test
    void anExpiredRequestCannotBeConfirmed() {
        Booking b = online();
        b.setExpiresAt(NOW);

        assertThatThrownBy(() -> service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "VIDEO", LINK, null, null)))
                .isInstanceOf(ConflictException.class).hasMessage("This request has expired.");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void aSecondConfirmIsAConflict() {
        Booking b = online();
        service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "VIDEO", LINK, null, null));

        assertThatThrownBy(() -> service.confirm(me, b.getId().toString(), confirmBody("ALTERNATE", "VIDEO", LINK, null, null)))
                .isInstanceOf(ConflictException.class).hasMessage("This request is no longer waiting for a reply.");
        assertThat(b.getPreferredDatetime()).isEqualTo(at(WED_18));
    }

    @Test
    void confirmingATimeThatOverlapsAnotherConfirmedBookingIsAConflict() {
        confirmedAt(WED_18);
        Booking b = online();

        assertThatThrownBy(() -> service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "VIDEO", LINK, null, null)))
                .isInstanceOf(ConflictException.class).hasMessage("You already have an appointment at that time.");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(b.getMeetingMethod()).isNull();
        verify(bookings, never()).saveAndFlush(any());

        // A partial overlap counts too: an old booking at 18:30 (60 minutes) overlaps the 19:15 slot.
        confirmedAt("2026-10-14T18:30:00+05:30");
        assertThatThrownBy(() -> service.confirm(me, b.getId().toString(), confirmBody("ALTERNATE", "VIDEO", LINK, null, null)))
                .isInstanceOf(ConflictException.class);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    // ---- decline ----

    @Test
    void declineWorksOnce() {
        Booking b = online();

        ProBookingView view = service.decline(me, b.getId().toString(), new DeclineRequest(" I am fully booked. "));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.DECLINED);
        assertThat(b.getProfessionalNote()).isEqualTo("I am fully booked.");
        assertThat(b.getRespondedBy()).isEqualTo("PROFESSIONAL");
        assertThat(b.getRespondedAt()).isEqualTo(NOW);
        assertThat(view.contactPhone()).isNull();
        assertThatThrownBy(() -> service.decline(me, b.getId().toString(), null))
                .isInstanceOf(ConflictException.class).hasMessage("This request is no longer waiting for a reply.");

        Booking expired = online();
        expired.setExpiresAt(NOW.minusSeconds(1));
        assertThatThrownBy(() -> service.decline(me, expired.getId().toString(), null))
                .isInstanceOf(ConflictException.class).hasMessage("This request has expired.");
    }

    // ---- propose ----

    @Test
    void proposingATimeThatIsNotFreeIsRejected() {
        Booking b = online();
        String id = b.getId().toString();

        // Not on the weekly hours.
        assertThatThrownBy(() -> service.propose(me, id,
                new ProposeRequest("2026-10-14T18:30:00+05:30", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("not one of your available times");
        // Already started, and more than 60 days ahead.
        assertThatThrownBy(() -> service.propose(me, id, new ProposeRequest("2026-10-12T09:00:00+05:30", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("starts too soon");
        assertThatThrownBy(() -> service.propose(me, id, new ProposeRequest("2026-12-16T18:00:00+05:30", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("60 days");
        // Taken by a confirmed booking.
        confirmedAt(THU_18);
        assertThatThrownBy(() -> service.propose(me, id, new ProposeRequest(THU_18, "VIDEO", LINK, null, null)))
                .isInstanceOf(ConflictException.class).hasMessage("You already have an appointment at that time.");
        // Not a date and time with an offset.
        assertThatThrownBy(() -> service.propose(me, id, new ProposeRequest("2026-10-15T18:00", "VIDEO", LINK, null, null)))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("offset");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(b.getProposedDatetime()).isNull();
    }


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
    void aPendingRequestOnADayOffCanStillBeConfirmed() {
        dayOff(LocalDate.of(2026, 10, 14), null);
        Booking b = online();

        service.confirm(me, b.getId().toString(), confirmBody("PREFERRED", "VIDEO", LINK, null, null));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
    }

    @Test
    void proposingOrAcceptingATimeOnADayOffIsRefused() {
        Booking b = online();
        dayOff(LocalDate.of(2026, 10, 15), null);
        assertThatThrownBy(() -> service.propose(me, b.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null)))
                .isInstanceOf(ConflictException.class).hasMessage(ProBookingService.DAY_OFF_MESSAGE);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);

        // A suggestion made before the date was marked as off cannot be accepted afterwards.
        dayOff(LocalDate.of(2026, 12, 1), null);
        service.propose(me, b.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null));
        dayOff(LocalDate.of(2026, 10, 15), null);
        assertThatThrownBy(() -> userService.acceptProposal(userId, b.getId().toString()))
                .isInstanceOf(ConflictException.class).hasMessage("This time is no longer available.");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ALTERNATE_PROPOSED);
    }

    @Test
    void proposeThenAcceptAgreesTheProposedTimeAndWaitsForPayment() {
        Booking b = online();

        ProBookingView proposed = service.propose(me, b.getId().toString(),
                new ProposeRequest(THU_18, "VIDEO", LINK, null, "Thursday suits me better."));

        assertThat(proposed.status()).isEqualTo("ALTERNATE_PROPOSED");
        assertThat(b.getProposedDatetime()).isEqualTo(at(THU_18));
        assertThat(b.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(72)));
        assertThat(b.getMeetingLink()).isEqualTo(LINK);
        assertThat(b.getProfessionalNote()).isEqualTo("Thursday suits me better.");
        assertThat(b.getRespondedBy()).isEqualTo("PROFESSIONAL");
        assertThat(proposed.contactPhone()).isNull();
        // The user does not see the meeting details yet, and the slot is not blocked yet.
        BookingView before = userService.get(userId, b.getId().toString());
        assertThat(before.meetingLink()).isNull();
        assertThat(before.meetingMethod()).isNull();
        assertThat(slots.check(professional(professionalId), at(THU_18), ZonedDateTime.now(CLOCK)))
                .isEqualTo(SlotService.SlotCheck.FREE);

        BookingView accepted = userService.acceptProposal(userId, b.getId().toString());

        assertThat(accepted.status()).isEqualTo("AWAITING_PAYMENT");
        assertThat(accepted.paymentDueAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(accepted.preferredDatetime()).isEqualTo(OffsetDateTime.parse(THU_18));
        // The meeting details stay hidden until the booking is paid and CONFIRMED.
        assertThat(accepted.meetingLink()).isNull();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(b.getPaymentDueAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(b.getPreferredDatetime()).isEqualTo(at(THU_18));
        assertThat(b.scheduledTime()).isEqualTo(at(THU_18));
        assertThat(b.getRespondedBy()).isEqualTo("USER");
        assertThat(b.getRespondedAt()).isEqualTo(NOW);
        assertThat(slots.check(professional(professionalId), at(THU_18), ZonedDateTime.now(CLOCK)))
                .isEqualTo(SlotService.SlotCheck.TAKEN);
        verify(professionals, atLeast(2)).findByIdForUpdate(professionalId);

        assertThatThrownBy(() -> userService.acceptProposal(userId, b.getId().toString()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void acceptAfterExpiryIsAConflict() {
        Booking b = online();
        service.propose(me, b.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null));
        b.setExpiresAt(NOW.minusSeconds(1));

        assertThatThrownBy(() -> userService.acceptProposal(userId, b.getId().toString()))
                .isInstanceOf(ConflictException.class).hasMessage("This suggested time has expired.");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ALTERNATE_PROPOSED);
    }

    @Test
    void acceptIsAConflictWhenTheProposedTimeWasTakenMeanwhile() {
        Booking b = online();
        service.propose(me, b.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null));
        confirmedAt(THU_18);

        assertThatThrownBy(() -> userService.acceptProposal(userId, b.getId().toString()))
                .isInstanceOf(ConflictException.class).hasMessage("This time is no longer available.");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ALTERNATE_PROPOSED);
    }

    @Test
    void onlyTheOwnerCanAcceptAndOnlyAProposal() {
        Booking b = online();
        assertThatThrownBy(() -> userService.acceptProposal(userId, b.getId().toString()))
                .isInstanceOf(ConflictException.class);

        service.propose(me, b.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null));
        assertThatThrownBy(() -> userService.acceptProposal(UUID.randomUUID(), b.getId().toString()))
                .isInstanceOf(BookingExceptions.NotFoundException.class);
        assertThatThrownBy(() -> userService.acceptProposal(userId, "not-a-uuid"))
                .isInstanceOf(BookingExceptions.NotFoundException.class);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ALTERNATE_PROPOSED);
    }

    // ---- ownership ----

    @Test
    void anotherProfessionalsBookingIsNotFound() {
        Booking b = online();
        String id = b.getId().toString();

        assertThatThrownBy(() -> service.get(other, id)).isInstanceOf(ProExceptions.BookingNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(other, id, confirmBody("PREFERRED", "VIDEO", LINK, null, null)))
                .isInstanceOf(ProExceptions.BookingNotFoundException.class);
        assertThatThrownBy(() -> service.decline(other, id, null))
                .isInstanceOf(ProExceptions.BookingNotFoundException.class);
        assertThatThrownBy(() -> service.propose(other, id, new ProposeRequest(THU_18, "VIDEO", LINK, null, null)))
                .isInstanceOf(ProExceptions.BookingNotFoundException.class);
        // A missing or malformed id gets the same 404.
        assertThatThrownBy(() -> service.get(me, UUID.randomUUID().toString()))
                .isInstanceOf(ProExceptions.BookingNotFoundException.class);
        assertThatThrownBy(() -> service.get(me, "not-a-uuid")).isInstanceOf(ProExceptions.BookingNotFoundException.class);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(service.get(me, id).id()).isEqualTo(b.getId());

        assertThatThrownBy(() -> service.get(new ProPrincipal("not_a_professional"), id))
                .isInstanceOf(ProExceptions.NotRegisteredException.class);
    }

    @Test
    void aTwoSessionBookingShowsTheWholeMeetingInTheListAndTheDetailNeverTheBreak() {
        // Two sessions from 18:00: the meeting runs to 20:00, the time is blocked until 20:30.
        Booking b = confirmedAt(WED_18);
        b.setSessionCount(2);
        b.setEndsAt(at("2026-10-14T20:00:00+05:30"));
        b.setBlockedUntil(at("2026-10-14T20:30:00+05:30"));
        doReturn(new PageImpl<>(List.of(b))).when(bookings).findByProfessionalId(eq(professionalId), any(Pageable.class));

        ProBookingView detail = service.get(me, b.getId().toString());
        ProBookingView listed = service.list(me, null, 0, 20).items().get(0);

        for (ProBookingView view : List.of(detail, listed)) {
            assertThat(view.preferredDatetime()).isEqualTo(OffsetDateTime.parse(WED_18));
            assertThat(view.sessionCount()).isEqualTo(2);
            assertThat(view.endDatetime()).isEqualTo(OffsetDateTime.parse("2026-10-14T20:00:00+05:30"));
        }
    }

    @Test
    void theListAndSummaryOnlyUseTheTokensProfessional() {
        Booking b = online();
        doReturn(new PageImpl<>(List.of(b))).when(bookings).findByProfessionalId(eq(professionalId), any(Pageable.class));
        doReturn(new PageImpl<>(List.of())).when(bookings)
                .findByProfessionalIdAndStatus(eq(professionalId), eq(BookingStatus.CONFIRMED), any(Pageable.class));
        doReturn(2L).when(bookings)
                .countByProfessionalIdAndStatusAndExpiresAtAfter(professionalId, BookingStatus.PENDING, NOW);
        doReturn(1L).when(bookings)
                .countByProfessionalIdAndStatusAndExpiresAtAfter(professionalId, BookingStatus.ALTERNATE_PROPOSED, NOW);

        assertThat(service.list(me, null, 0, 20).items()).extracting(ProBookingView::id).containsExactly(b.getId());
        assertThat(service.list(me, "confirmed", 0, 20).items()).isEmpty();
        assertThat(service.summary(me)).isEqualTo(new ProBookingDtos.ProBookingSummary(2, 1));
        assertThatThrownBy(() -> service.list(me, "WAITING", 0, 20)).isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> service.list(me, null, 0, 51)).isInstanceOf(ApiValidationException.class);
        verify(bookings, never()).findByProfessionalId(eq(otherProfessionalId), any());
    }

    @Test
    void theSummaryLeavesOutExpiredProposals() {
        // Counted from the store with the query's rule: this professional, this status, expires_at after now.
        doAnswer(inv -> store.stream().filter(b -> b.getProfessionalId().equals(inv.getArgument(0))
                        && b.getStatus() == inv.getArgument(1)
                        && b.getExpiresAt().isAfter(inv.getArgument(2))).count())
                .when(bookings).countByProfessionalIdAndStatusAndExpiresAtAfter(any(), any(), any());
        online();
        Booking open = online();
        service.propose(me, open.getId().toString(), new ProposeRequest(THU_18, "VIDEO", LINK, null, null));
        Booking expired = online();
        expired.setStatus(BookingStatus.ALTERNATE_PROPOSED);
        expired.setExpiresAt(NOW.minusSeconds(1));
        Booking endsNow = online();
        endsNow.setStatus(BookingStatus.ALTERNATE_PROPOSED);
        endsNow.setExpiresAt(NOW);

        assertThat(service.summary(me)).isEqualTo(new ProBookingDtos.ProBookingSummary(1, 1));
        verify(bookings).countByProfessionalIdAndStatusAndExpiresAtAfter(professionalId,
                BookingStatus.ALTERNATE_PROPOSED, NOW);
    }

    // ---- contact visibility ----

    @Test
    void contactDetailsAreOnlyShownForACurrentConfirmedBooking() {
        for (BookingStatus status : List.of(BookingStatus.PENDING, BookingStatus.DECLINED,
                BookingStatus.ALTERNATE_PROPOSED, BookingStatus.CANCELLED, BookingStatus.EXPIRED)) {
            Booking b = online();
            b.setStatus(status);
            ProBookingView view = service.get(me, b.getId().toString());
            assertThat(view.contactEmail()).as(status.name()).isNull();
            assertThat(view.contactPhone()).as(status.name()).isNull();
            assertThat(view.contactName()).isEqualTo("Ravi Kumar");
        }

        Booking old = online();
        old.setStatus(BookingStatus.CONFIRMED);
        old.setPreferredDatetime(NOW.minus(Duration.ofDays(7)).minusSeconds(1));
        ProBookingView oldView = service.get(me, old.getId().toString());
        assertThat(oldView.contactEmail()).isNull();
        assertThat(oldView.contactPhone()).isNull();

        // The proposed time, when set, is the scheduled time.
        Booking proposedOld = online();
        proposedOld.setStatus(BookingStatus.CONFIRMED);
        proposedOld.setPreferredDatetime(NOW.plus(Duration.ofDays(1)));
        proposedOld.setProposedDatetime(NOW.minus(Duration.ofDays(8)));
        assertThat(service.get(me, proposedOld.getId().toString()).contactPhone()).isNull();

        Booking recent = online();
        recent.setStatus(BookingStatus.CONFIRMED);
        recent.setPreferredDatetime(NOW.minus(Duration.ofDays(6)));
        ProBookingView recentView = service.get(me, recent.getId().toString());
        assertThat(recentView.contactEmail()).isEqualTo("user@example.com");
        assertThat(recentView.contactPhone()).isEqualTo("+919876543210");

        Booking upcoming = online();
        upcoming.setStatus(BookingStatus.CONFIRMED);
        assertThat(service.get(me, upcoming.getId().toString()).contactPhone()).isEqualTo("+919876543210");
    }

    @Test
    void theViewNeverHasTheUserSessionOrAdminFields() {
        List<String> fields = Arrays.stream(ProBookingView.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertThat(fields).doesNotContain("userId", "clerkUserId", "sessionId", "adminNote", "respondedBy",
                "professionalNotifiedAt", "consentGivenAt");
        assertThat(fields).contains("contactName", "contactEmail", "contactPhone", "sharedAssessment");
    }
}
