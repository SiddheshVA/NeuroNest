package com.neuronest.backend.notification;

import com.neuronest.backend.assessment.AssessmentResultRepository;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.meeting.MeetingLinkService;
import com.neuronest.backend.booking.BookingService;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.PaymentAmounts;
import com.neuronest.backend.booking.SharedAssessment;
import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.notification.BookingEvent.Type;
import com.neuronest.backend.payment.PaymentRepository;
import com.neuronest.backend.payment.Payment;
import com.neuronest.backend.payment.PaymentStatus;
import com.neuronest.backend.pro.ProBookingDtos.ConfirmRequest;
import com.neuronest.backend.pro.ProBookingDtos.DeclineRequest;
import com.neuronest.backend.pro.ProBookingService;
import com.neuronest.backend.pro.ProPrincipal;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalDayOffRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.PublicProfessionalService;
import com.neuronest.backend.safety.CrisisResource;
import com.neuronest.backend.safety.CrisisResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BookingNotificationTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, AvailabilityService.ZONE);
    private static final String BASE_URL = "https://app.neuronest.example";
    private static final String USER_EMAIL = "ravi.user@example.com";
    private static final String USER_PHONE = "+919876543210";
    private static final String PRO_EMAIL = "asha.pro@example.com";
    private static final String PRO_PHONE = "+919812345678";
    private static final String REQUEST_NOTE = "PRIVATE-REQUEST-NOTE about my sleep";
    private static final String PRO_NOTE = "PRIVATE-PROFESSIONAL-NOTE";
    private static final String VIDEO_LINK = "https://meet.example.com/room-42";
    private static final String ADDRESS = "Clinic 4, MG Road, Pune";

    /** Records what would have been sent; can be told to fail. */
    private static final class FakeSender implements EmailSender {
        final List<OutgoingEmail> sent = new ArrayList<>();
        int failuresLeft;

        @Override
        public void send(OutgoingEmail email) {
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new IllegalStateException("SMTP down for " + email.to());
            }
            sent.add(email);
        }
    }

    private final BookingRepository bookings = mock(BookingRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final MailRetryScheduler retries = mock(MailRetryScheduler.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PublicProfessionalService directory = mock(PublicProfessionalService.class);
    private final FakeSender sender = new FakeSender();
    private final MeetingLinkService meetingLinks = mock(MeetingLinkService.class);
    // The join window as of the test's "now" (Monday 12 Oct, 10:00); a test may move it.
    private com.neuronest.backend.meeting.MeetingWindow window = new com.neuronest.backend.meeting.MeetingWindow(5, CLOCK);
    private final MailSettings enabled = new MailSettings("noreply@neuronest.example", "app-password", "NeuroNest", BASE_URL + "/");
    private final MailSettings disabled = new MailSettings("", "", "NeuroNest", BASE_URL);

    private final UUID professionalId = UUID.randomUUID();
    private Professional professional;
    private Booking booking;

    @BeforeEach
    void setUp() {
        professional = new Professional();
        ReflectionTestUtils.setField(professional, "id", professionalId);
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(com.neuronest.backend.professional.CredentialType.PSYCHOLOGIST);
        professional.setContactEmail(PRO_EMAIL);
        professional.setContactPhone(PRO_PHONE);
        when(professionals.findById(professionalId)).thenReturn(Optional.of(professional));
        when(professionals.findByIdForUpdate(professionalId)).thenReturn(Optional.of(professional));

        booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", UUID.randomUUID());
        booking.setProfessionalId(professionalId);
        booking.setUserId(UUID.randomUUID());
        booking.setContactName("Ravi Kumar");
        booking.setContactEmail(USER_EMAIL);
        booking.setContactPhone(USER_PHONE);
        booking.setPreferredMode("ONLINE");
        booking.setPreferredMethod("VIDEO");
        booking.setPreferredDatetime(OffsetDateTime.parse("2026-10-14T18:00:00+05:30").toInstant());
        booking.setAlternateDatetime(OffsetDateTime.parse("2026-10-14T19:00:00+05:30").toInstant());
        booking.setNote(REQUEST_NOTE);
        booking.setProfessionalNote(PRO_NOTE);
        booking.setSharedAssessment(new SharedAssessment("PHQ9", "PHQ-9", 17, "moderately severe", "2026-10-01T10:00:00Z"));
        booking.setStatus(BookingStatus.PENDING);
        booking.setCreatedAt(NOW);
        booking.setExpiresAt(NOW.plus(Duration.ofHours(72)));
        doReturn(Optional.of(booking)).when(bookings).findById(booking.getId());
        doReturn(Optional.of(booking)).when(bookings).findByIdForUpdate(booking.getId());
        doAnswer(inv -> inv.getArgument(0)).when(bookings).saveAndFlush(any());
        doReturn(List.of()).when(bookings).findInWindow(any(), any(), any(), any());
    }

    private BookingNotificationListener listener(MailSettings settings) {
        return new BookingNotificationListener(bookings, professionals, directory, new BookingEmails(settings), sender,
                settings, retries, new SessionMessages(settings, window),
                new NotificationService(List.of(new EmailChannel(sender, settings)), retries), meetingLinks, CLOCK);
    }

    private List<OutgoingEmail> send(Type type) {
        listener(enabled).handle(new BookingEvent(type, booking.getId()));
        return sender.sent;
    }

    private void confirmedWith(String method, String link, String address) {
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setMeetingMethod(method);
        booking.setMeetingLink(link);
        booking.setMeetingAddress(address);
    }

    private static boolean isConfirmation(OutgoingEmail e) {
        return e.subject().startsWith("Your session with ") || e.subject().startsWith("New session at ");
    }

    private static OutgoingEmail forPro(List<OutgoingEmail> emails) {
        return emails.stream().filter(e -> e.to().equals(PRO_EMAIL)).findFirst().orElseThrow();
    }

    private static OutgoingEmail toUserEmail(List<OutgoingEmail> emails) {
        return emails.stream().filter(e -> e.to().equals(USER_EMAIL)).findFirst().orElseThrow();
    }

    private static String all(OutgoingEmail e) {
        return e.subject() + "\n" + e.text() + "\n" + e.html();
    }

    private static String forUser(List<OutgoingEmail> emails) {
        return emails.stream().filter(e -> e.to().equals(USER_EMAIL)).map(OutgoingEmail::text).findFirst().orElseThrow();
    }

    // ---- recipients ----

    @Test
    void eachEventGoesToExactlyTheRightRecipients() {
        assertThat(send(Type.REQUEST_CREATED)).extracting(OutgoingEmail::to).containsExactly(PRO_EMAIL, USER_EMAIL);
        sender.sent.clear();
        confirmedWith("PHONE", null, null);
        assertThat(send(Type.CONFIRMED)).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL, PRO_EMAIL);
        sender.sent.clear();
        assertThat(send(Type.PROPOSED)).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL);
        sender.sent.clear();
        assertThat(send(Type.DECLINED)).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL);
        sender.sent.clear();
        assertThat(send(Type.CANCELLED_BY_USER)).extracting(OutgoingEmail::to).containsExactly(PRO_EMAIL);
    }

    @Test
    void subjectsAreNeutralAndTheFooterIsOnEveryEmail() {
        booking.setProposedDatetime(OffsetDateTime.parse("2026-10-15T18:00:00+05:30").toInstant());
        confirmedWith("VIDEO", VIDEO_LINK, null);
        for (Type type : Type.values()) send(type);

        assertThat(sender.sent).hasSize(15).allSatisfy(e -> {
            // The confirmations have their own warm subjects; every other email starts with "NeuroNest: ".
            if (!isConfirmation(e)) assertThat(e.subject()).startsWith("NeuroNest: ");
            assertThat(e.subject().toLowerCase()).doesNotContain("mental", "therapy", "therapist", "psych", "counsel");
            assertThat(e.text()).contains(BookingEmails.FOOTER);
            assertThat(e.html()).contains(BookingEmails.FOOTER);
        });
        assertThat(sender.sent).extracting(OutgoingEmail::subject).contains(
                "NeuroNest: a new request is waiting", "NeuroNest: your request was sent",
                "Your session with Dr. Asha Rao is confirmed", "New session at 6:00 PM IST on Wed, 14 Oct: Ravi", "NeuroNest: an update on your request");
    }

    @Test
    void theRequestEmailsSayWhatWasAskedAndMarkTheProfessionalNotified() {
        List<OutgoingEmail> emails = send(Type.REQUEST_CREATED);

        assertThat(emails.get(0).text()).contains("You have a new appointment request from Ravi Kumar. Open NeuroNest "
                + "to read it and reply within 72 hours.", BASE_URL + "/pro/requests/" + booking.getId());
        assertThat(emails.get(1).text()).contains("Your request was sent to Dr. Asha Rao. They have 72 hours to reply.",
                "Wed, 14 Oct 2026, 6:00 PM IST", BASE_URL + "/appointments");
        verify(bookings).markProfessionalNotified(booking.getId(), NOW);
    }

    // ---- disabled mail and failures ----

    @Test
    void disabledMailSendsNothingAndTheBookingStillSucceeds() {
        BookingNotificationListener off = listener(disabled);
        for (Type type : Type.values()) off.handle(new BookingEvent(type, booking.getId()));
        assertThat(sender.sent).isEmpty();
        verify(bookings, never()).markProfessionalNotified(any(), any());

        BookingView cancelled = userService(off).cancel(booking.getUserId(), booking.getId().toString());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void aSenderThatThrowsNeverFailsTheBookingAndIsRetriedOnce() {
        sender.failuresLeft = 100;
        BookingNotificationListener failing = listener(enabled);

        var view = proService(failing).decline(new ProPrincipal("pro_clerk"), booking.getId().toString(), new DeclineRequest(null));

        assertThat(view.status()).isEqualTo("DECLINED");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.DECLINED);
        verify(bookings).saveAndFlush(booking);
        assertThat(sender.sent).isEmpty();
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(retries).schedule(retry.capture(), eq(Duration.ofSeconds(30)));

        // The single retry fails too: nothing more is scheduled.
        retry.getValue().run();
        verify(retries, times(1)).schedule(any(), any());
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void aRetryThatWorksSendsTheEmailAndThenMarksTheProfessionalNotified() {
        sender.failuresLeft = 1;
        send(Type.REQUEST_CREATED);
        // The professional's email failed; the user's went through.
        assertThat(sender.sent).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL);
        verify(bookings, never()).markProfessionalNotified(any(), any());

        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(retries).schedule(retry.capture(), eq(Duration.ofSeconds(30)));
        retry.getValue().run();

        assertThat(sender.sent).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL, PRO_EMAIL);
        verify(bookings).markProfessionalNotified(booking.getId(), NOW);
    }

    @Test
    void aMissingBookingOrABrokenRepositoryIsSwallowed() {
        listener(enabled).handle(new BookingEvent(Type.CONFIRMED, UUID.randomUUID()));
        doThrow(new IllegalStateException("database down")).when(bookings).findById(booking.getId());
        listener(enabled).handle(new BookingEvent(Type.CONFIRMED, booking.getId()));
        assertThat(sender.sent).isEmpty();
    }

    // ---- content ----

    @Test
    void noEmailContainsTheNotesTheAssessmentPhoneNumbersOrTheOtherPartysEmail() {
        booking.setProposedDatetime(OffsetDateTime.parse("2026-10-15T18:00:00+05:30").toInstant());
        for (String method : List.of("PHONE", "VIDEO", "IN_PERSON")) {
            confirmedWith(method, VIDEO_LINK, ADDRESS);
            for (Type type : Type.values()) send(type);
        }

        assertThat(sender.sent).isNotEmpty().allSatisfy(e -> {
            String content = all(e);
            assertThat(content).doesNotContain(REQUEST_NOTE, "PRIVATE", PRO_NOTE, "PHQ-9", "moderately severe", "17,",
                    "9876543210", "9812345678", "+91");
            String otherParty = e.to().equals(USER_EMAIL) ? PRO_EMAIL : USER_EMAIL;
            assertThat(content).doesNotContain(otherParty, "noreply@neuronest.example");
            // The address is never in an email; the video link only in a confirmation (of a video session).
            assertThat(content).doesNotContain(ADDRESS);
            if (!isConfirmation(e)) assertThat(content).doesNotContain(VIDEO_LINK);
        });
    }

    @Test
    void anAdminCloseSendsTheExpiryEmailToTheUserAndTellsTheProfessionalWithoutTheAdminNote() {
        String adminNote = "INTERNAL-ADMIN-NOTE about this case";
        booking.setStatus(BookingStatus.EXPIRED);
        booking.setRespondedBy("ADMIN");
        booking.setAdminNote(adminNote);
        Professional other = new Professional();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        other.setFullName("Dr. Meera Iyer");
        when(directory.similarBookable(professional, 3)).thenReturn(List.of(other));

        List<OutgoingEmail> emails = send(Type.CLOSED_BY_ADMIN);

        assertThat(emails).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL, PRO_EMAIL);
        OutgoingEmail toUser = emails.get(0);
        assertThat(toUser.subject()).isEqualTo("NeuroNest: an update on your request");
        assertThat(toUser.text()).contains("Your request to Dr. Asha Rao expired because it was not answered in time.",
                BASE_URL + "/professionals", "Dr. Meera Iyer", BookingEmails.SUPPORT_INTRO);
        for (CrisisResource r : CrisisResources.INDIA_RESOURCES) {
            assertThat(toUser.text()).contains(BookingEmails.supportLine(r));
        }
        assertThat(emails.get(1).text()).contains("A request from Ravi Kumar was closed by NeuroNest. Open NeuroNest to "
                + "see your requests.", BASE_URL + "/pro/requests");
        assertThat(emails).allSatisfy(e -> assertThat(all(e)).doesNotContain(adminNote, "INTERNAL", REQUEST_NOTE, PRO_NOTE));
        verify(bookings).markExpiryNotified(booking.getId(), NOW);
        // Not a background-job expiry, so the usual 30-second retry applies to a failed send.
        sender.failuresLeft = 1;
        sender.sent.clear();
        send(Type.CLOSED_BY_ADMIN);
        verify(retries).schedule(any(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void theDeclineEmailHasEveryCrisisResourceAndNoProfessionalNote() {
        OutgoingEmail email = send(Type.DECLINED).get(0);

        assertThat(email.text()).contains("Dr. Asha Rao could not take this appointment. You can look for another professional.",
                "If you need support right now:", BASE_URL + "/professionals");
        for (CrisisResource r : CrisisResources.INDIA_RESOURCES) {
            assertThat(email.text()).contains(BookingEmails.supportLine(r));
            assertThat(email.html()).contains(BookingEmails.escape(BookingEmails.supportLine(r)));
        }
        assertThat(all(email)).doesNotContain(PRO_NOTE);
    }

    @Test
    void namesWithHtmlCharactersAreEscapedInTheHtmlPart() {
        booking.setContactName("<b>Ravi</b> & \"Co\"");
        professional.setFullName("Dr. <script>alert(1)</script> O'Neil");
        confirmedWith("VIDEO", VIDEO_LINK, null);
        send(Type.REQUEST_CREATED);
        send(Type.CONFIRMED);

        assertThat(sender.sent).allSatisfy(e -> assertThat(e.html()).doesNotContain("<b>Ravi", "<script>", "O'Neil"));
        assertThat(sender.sent.get(0).html()).contains("&lt;b&gt;Ravi&lt;/b&gt; &amp; &quot;Co&quot;");
        assertThat(sender.sent.get(1).html()).contains("Dr. &lt;script&gt;alert(1)&lt;/script&gt; O&#39;Neil");
        // The plain-text part is not HTML and keeps the names as written.
        assertThat(sender.sent.get(0).text()).contains("<b>Ravi</b> & \"Co\"");
    }

    @Test
    void theConfirmationIsAFriendlyMessageToBothSidesWithOneLinkAndNoPrivateDetail() {
        // A phone session: no video link, even if one were stored.
        confirmedWith("PHONE", VIDEO_LINK, ADDRESS);

        List<OutgoingEmail> emails = send(Type.CONFIRMED);

        assertThat(emails).hasSize(2);
        OutgoingEmail toUser = toUserEmail(emails);
        assertThat(toUser.subject()).isEqualTo("Your session with Dr. Asha Rao is confirmed");
        assertThat(toUser.text()).startsWith("Hi Ravi, thank you for taking this step.\n\n"
                + "Your session with Dr. Asha Rao is booked for Wed, 14 Oct 2026, 6:00 PM IST.\n\n"
                + "Dr. Asha Rao will call you on the phone number you gave.\n\n"
                + "You don't need to prepare anything. If you'd like to share something with Dr. Asha Rao, you can just "
                + "say it at the start.\n\nView your booking: " + BASE_URL + "/appointments\n\nTake care,\nNeuroNest");
        // The support lines close the client's email.
        assertThat(toUser.text()).contains(BookingEmails.SUPPORT_INTRO);
        OutgoingEmail toProfessional = forPro(emails);
        assertThat(toProfessional.subject()).isEqualTo("New session at 6:00 PM IST on Wed, 14 Oct: Ravi");
        assertThat(toProfessional.text()).startsWith("Hi Dr. Asha Rao, a new session is booked for Wed, 14 Oct 2026, "
                + "6:00 PM IST.\n\nPlease call Ravi on the phone number on your NeuroNest booking page.\n\n"
                + "The client's contact details and note are available on your NeuroNest booking page.\n\n"
                + "Thank you for the care you give.\n\nOpen your booking page: " + BASE_URL + "/pro/requests/"
                + booking.getId() + "\n\nNeuroNest");
        // No video link for a phone session: only the button to the booking page.
        assertThat(toUser.html()).containsOnlyOnce("<a href=").contains("href=\"" + BASE_URL + "/appointments\"");
        // Never a note, an assessment, a meeting link, an address or a phone number.
        assertThat(emails).allSatisfy(e -> assertThat(all(e)).doesNotContain(REQUEST_NOTE, PRO_NOTE, "PHQ",
                "moderately severe", VIDEO_LINK, ADDRESS, "9876543210", "9812345678"));
    }

    @Test
    void aTwoSessionConfirmationShowsTheWholeMeetingToBothAndNeverTheBreak() {
        // Two sessions from 7:45 pm: the meeting runs to 9:45 pm, the time is blocked until 10:15 pm.
        Instant start = OffsetDateTime.parse("2026-10-29T19:45:00+05:30").toInstant();
        booking.setPreferredDatetime(start);
        booking.setSessionCount(2);
        booking.setEndsAt(start.plus(Duration.ofMinutes(120)));
        booking.setBlockedUntil(start.plus(Duration.ofMinutes(150)));
        confirmedWith("VIDEO", VIDEO_LINK, null);

        List<OutgoingEmail> emails = send(Type.CONFIRMED);

        String meeting = "Thu, 29 Oct 2026, 7:45 PM to 9:45 PM IST (2 sessions, 2 hours)";
        assertThat(forUser(emails)).contains("Your session with Dr. Asha Rao is booked for " + meeting + ".");
        assertThat(forPro(emails).text()).contains("a new session is booked for " + meeting + ".");
        assertThat(emails).allSatisfy(e -> assertThat(all(e)).doesNotContain("10:15"));
    }

    // ---- payments ----

    private static final Instant PAYMENT_DUE = OffsetDateTime.parse("2026-10-13T10:00:00+05:30").toInstant();

    private void awaitingPaymentWith(String method) {
        booking.setStatus(BookingStatus.AWAITING_PAYMENT);
        booking.setPaymentDueAt(PAYMENT_DUE);
        booking.setMeetingMethod(method);
        booking.setMeetingLink(VIDEO_LINK);
        booking.setMeetingAddress(ADDRESS);
        professional.setConsultationFee(1500);
    }

    @Test
    void theAwaitingPaymentEmailsAskForPaymentAndShareNoMeetingDetailOrPhone() {
        for (String method : List.of("PHONE", "VIDEO", "IN_PERSON")) {
            awaitingPaymentWith(method);
            send(Type.AWAITING_PAYMENT);
        }

        assertThat(sender.sent).hasSize(6).allSatisfy(e -> {
            String content = all(e);
            assertThat(content).doesNotContain(VIDEO_LINK, ADDRESS, "9876543210", "9812345678", "+91",
                    "will call you", REQUEST_NOTE, PRO_NOTE);
            assertThat(content.toLowerCase()).doesNotContain("confirmed");
            assertThat(content).doesNotContain(e.to().equals(USER_EMAIL) ? PRO_EMAIL : USER_EMAIL);
        });
        OutgoingEmail toUser = sender.sent.get(0);
        assertThat(toUser.to()).isEqualTo(USER_EMAIL);
        assertThat(toUser.subject()).isEqualTo("NeuroNest: your request was accepted");
        assertThat(toUser.text()).contains("Dr. Asha Rao accepted your request for Wed, 14 Oct 2026, 6:00 PM IST.",
                "Please pay by Tue, 13 Oct 2026, 10:00 AM IST to confirm your slot.", "Amount to pay: \u20B91,500.00.",
                "Pay now: " + BASE_URL + "/appointments");
        OutgoingEmail toProfessional = sender.sent.get(1);
        assertThat(toProfessional.to()).isEqualTo(PRO_EMAIL);
        assertThat(toProfessional.subject()).isEqualTo("NeuroNest: a request was accepted");
        assertThat(toProfessional.text()).contains("You accepted the request from Ravi Kumar for Wed, 14 Oct 2026, "
                + "6:00 PM IST. It is waiting for their payment, due by Tue, 13 Oct 2026, 10:00 AM IST.");
    }

    @Test
    void theProfessionalsConfirmSendsOnlyTheAwaitingPaymentEmails() {
        BookingNotificationListener l = listener(enabled);
        professional.setConsultationFee(1500);

        var view = proService(l).confirm(new ProPrincipal("pro_clerk"), booking.getId().toString(),
                new ConfirmRequest("PREFERRED", "VIDEO", VIDEO_LINK, null, null));

        assertThat(view.status()).isEqualTo("AWAITING_PAYMENT");
        assertThat(sender.sent).extracting(OutgoingEmail::subject).containsExactly(
                "NeuroNest: your request was accepted", "NeuroNest: a request was accepted");
        assertThat(sender.sent).allSatisfy(e -> assertThat(all(e)).doesNotContain(VIDEO_LINK, "9876543210"));
    }

    @Test
    void aFreeSessionsConfirmSendsTheConfirmedEmailsAtOnceWithoutAnAmount() {
        professional.setConsultationFee(0);

        var view = proService(listener(enabled)).confirm(new ProPrincipal("pro_clerk"), booking.getId().toString(),
                new ConfirmRequest("PREFERRED", "VIDEO", VIDEO_LINK, null, null));

        assertThat(view.status()).isEqualTo("CONFIRMED");
        // The professional's subject says when the session is.
        assertThat(sender.sent).extracting(OutgoingEmail::subject).containsExactly("Your session with Dr. Asha Rao is confirmed", "New session at 6:00 PM IST on Wed, 14 Oct: Ravi");
        assertThat(forUser(sender.sent)).contains("is booked for").doesNotContain("Amount", "pay");
    }

    @Test
    void aVideoSessionGetsItsSpaceFirstAndBothConfirmationsCarryTheSameLinkInTheirOwnWords() {
        String meet = withMeetLinkMadeOnConfirmation();

        List<OutgoingEmail> emails = send(Type.CONFIRMED);

        verify(meetingLinks).ensureLink(booking.getId());
        OutgoingEmail toUser = toUserEmail(emails);
        OutgoingEmail toPro = forPro(emails);
        // Both: the same link as a big button and as text, when it opens and closes, and the booking page.
        for (OutgoingEmail e : List.of(toUser, toPro)) {
            assertThat(e.text()).contains("Join your session: " + meet);
            assertThat(e.html()).contains(">Join your session</a>", "Or open this link: <a href=\"" + meet + "\"");
            assertThat(e.html()).containsOnlyOnce(">Join your session</a>");
            // Never a note, an assessment, a phone number, or anything about letting someone in.
            assertThat(all(e)).doesNotContain(REQUEST_NOTE, PRO_NOTE, "PHQ", "moderately severe", "9876543210",
                    "9812345678", "admit", "host", "spaces/");
        }
        assertThat(toUser.subject()).isEqualTo("Your session with Dr. Asha Rao is confirmed");
        assertThat(toUser.text()).contains("Hi Ravi, thank you for taking this step.",
                "Your private video link is below. It becomes active 5 minutes before your session and closes when the "
                        + "session ends, so if you open it earlier it won't let you in yet.",
                "find a quiet spot, and take a breath", "View your booking: " + BASE_URL + "/appointments",
                BookingEmails.SUPPORT_INTRO);
        assertThat(toPro.subject()).isEqualTo("New session at 6:00 PM IST on Wed, 14 Oct: Ravi");
        assertThat(toPro.text()).contains("Hi Dr. Asha Rao, a new session is booked for Wed, 14 Oct 2026, 6:00 PM IST.",
                "Your video link is below. It becomes active 5 minutes before the start and closes at the end of the "
                        + "session",
                "The client's contact details and note are available on your NeuroNest booking page.",
                "Thank you for the care you give.", "Open your booking page: " + BASE_URL + "/pro/requests/" + booking.getId())
                .doesNotContain("take a breath", BookingEmails.SUPPORT_INTRO);
        // The two are written for their reader.
        assertThat(toUser.text()).isNotEqualTo(toPro.text());
    }

    // What MeetingLinkService does for a video session before the confirmation is built: the link exists.
    private String withMeetLinkMadeOnConfirmation() {
        String meet = "https://meet.google.com/abc-mnop-xyz";
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPreferredMode("ONLINE");
        booking.setPreferredMethod("VIDEO");
        doAnswer(inv -> {
            booking.setMeetingMethod("VIDEO");
            booking.setMeetingSpaceName("spaces/jQCFfuBOdN5z");
            booking.setMeetingLink(meet);
            return null;
        }).when(meetingLinks).ensureLink(booking.getId());
        return meet;
    }

    @Test
    void aLastMinuteVideoSessionConfirmedInsideItsWindowSaysYouCanJoinNow() {
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPreferredMode("ONLINE");
        booking.setPreferredMethod("VIDEO");
        String meet = withMeetLinkMadeOnConfirmation();
        // Paid at 5:58 PM for 6:00 PM: the join window opened at 5:55 PM.
        window = new com.neuronest.backend.meeting.MeetingWindow(5,
                Clock.fixed(OffsetDateTime.parse("2026-10-14T17:58:00+05:30").toInstant(), AvailabilityService.ZONE));

        List<OutgoingEmail> emails = send(Type.CONFIRMED);

        assertThat(emails).hasSize(2).allSatisfy(e -> {
            assertThat(e.text()).contains("You can join now.", "Join your session: " + meet);
            assertThat(all(e)).doesNotContain("becomes active", "minutes before");
        });
        assertThat(forUser(emails)).contains("Your private video link is below. You can join now. The link closes when the "
                + "session ends.");
        // The professional hears about it at once, with the time in the subject.
        assertThat(forPro(emails).subject()).isEqualTo("New session at 6:00 PM IST on Wed, 14 Oct: Ravi");
    }

    @Test
    void whenNoLinkCouldBeMadeTheConfirmationStillGoesOutWithoutOne() {
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPreferredMode("ONLINE");
        booking.setPreferredMethod("VIDEO");
        // Google failed (the booking is flagged), or the link step itself broke: either way, both are told.
        doThrow(new IllegalStateException("database hiccup")).when(meetingLinks).ensureLink(booking.getId());

        List<OutgoingEmail> emails = send(Type.CONFIRMED);

        assertThat(forUser(emails)).contains("is booked for", "We're setting up your private video link")
                .doesNotContain("Join your session");
        assertThat(forPro(emails).text()).contains("We're setting up the video link").doesNotContain("Join your session");
    }

    @Test
    void aCancelledBookingHasItsMeetSpaceClosed() {
        send(Type.CANCELLED_BY_USER);
        send(Type.CLOSED_BY_ADMIN);
        verify(meetingLinks, times(2)).closeSpace(booking.getId());
        verify(meetingLinks, never()).ensureLink(any());
    }

    @Test
    void theConfirmationKeepsToTheShortMessageAndSendsNothingWhenEmailIsTurnedOff() {
        confirmedWith("PHONE", null, null);

        // The receipt is on the appointment page; the message only says who, when and where to look.
        assertThat(forUser(send(Type.CONFIRMED))).contains("View your booking: " + BASE_URL + "/appointments")
                .doesNotContain("Amount paid");

        // notifications.email.enabled=false: no email at all.
        sender.sent.clear();
        listener(new MailSettings("noreply@neuronest.example", "app-password", "NeuroNest", BASE_URL, false))
                .handle(new BookingEvent(Type.CONFIRMED, booking.getId()));
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void anUnpaidBookingThatExpiredGetsTheNeutralPaymentWording() {
        awaitingPaymentWith("VIDEO");
        booking.setStatus(BookingStatus.EXPIRED);

        List<OutgoingEmail> emails = send(Type.EXPIRED);

        // A hold that ended unpaid was never a booking for the professional: only the user is told.
        assertThat(emails).extracting(OutgoingEmail::to).containsExactly(USER_EMAIL);
        OutgoingEmail toUser = emails.get(0);
        assertThat(toUser.subject()).isEqualTo("NeuroNest: an update on your request");
        assertThat(toUser.text()).contains("Your booking with Dr. Asha Rao for Wed, 14 Oct 2026, 6:00 PM IST was released "
                + "because the payment was not made by Tue, 13 Oct 2026, 10:00 AM IST.", BASE_URL + "/professionals",
                BookingEmails.SUPPORT_INTRO, BookingEmails.FOOTER);
        assertThat(emails).allSatisfy(e -> assertThat(all(e)).doesNotContain(VIDEO_LINK, ADDRESS, "9876543210",
                "not answered"));
        verify(bookings).markExpiryNotified(booking.getId(), NOW);
    }

    @Test
    void everyNeuroNestLinkStartsWithTheBaseUrl() {
        booking.setProposedDatetime(OffsetDateTime.parse("2026-10-15T18:00:00+05:30").toInstant());
        confirmedWith("VIDEO", VIDEO_LINK, null);
        for (Type type : Type.values()) send(type);

        Pattern url = Pattern.compile("https?://[^\\s\"<]+");
        assertThat(sender.sent).allSatisfy(e -> {
            for (String part : List.of(e.text(), e.html())) {
                Matcher m = url.matcher(part);
                int links = 0;
                while (m.find()) {
                    links++;
                    // A sentence's full stop after a link is not part of it.
                    String link = m.group().replaceAll("\\.$", "");
                    if (!link.equals(VIDEO_LINK)) assertThat(link).startsWith(BASE_URL + "/");
                }
                assertThat(links).isPositive();
            }
        });
        assertThat(sender.sent.stream().map(OutgoingEmail::text)).anyMatch(t -> t.contains(BASE_URL + "/pro/requests"));
    }

    // ---- the services, with the listener called directly in place of the after-commit dispatch ----

    private BookingService userService(BookingNotificationListener l) {
        ApplicationEventPublisher publisher = event -> l.handle((BookingEvent) event);
        BookingService service = new BookingService(bookings, new SlotService(new AvailabilityService(), bookings, mock(ProfessionalDayOffRepository.class),
                mock(com.neuronest.backend.professional.ProfessionalBlockedSlotRepository.class)),
                mock(PublicProfessionalService.class), professionals, mock(ProfessionalAccountRepository.class),
                mock(UserRepository.class), mock(AssessmentResultRepository.class), publisher,
                new PaymentAmounts(payments));
        ReflectionTestUtils.setField(service, "clock", CLOCK);
        return service;
    }

    private ProBookingService proService(BookingNotificationListener l) {
        ApplicationEventPublisher publisher = event -> l.handle((BookingEvent) event);
        ProfessionalAccountRepository accounts = mock(ProfessionalAccountRepository.class);
        when(accounts.findByClerkUserId("pro_clerk"))
                .thenReturn(Optional.of(new ProfessionalAccount(professionalId, PRO_EMAIL, "pro_clerk")));
        ProBookingService service = new ProBookingService(bookings, new SlotService(new AvailabilityService(), bookings, mock(ProfessionalDayOffRepository.class),
                mock(com.neuronest.backend.professional.ProfessionalBlockedSlotRepository.class)),
                professionals, accounts, publisher);
        ReflectionTestUtils.setField(service, "clock", CLOCK);
        return service;
    }
}
