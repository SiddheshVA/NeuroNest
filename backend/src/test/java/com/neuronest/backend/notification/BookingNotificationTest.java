package com.neuronest.backend.notification;

import com.neuronest.backend.assessment.AssessmentResultRepository;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingService;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.SharedAssessment;
import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.notification.BookingEvent.Type;
import com.neuronest.backend.pro.ProBookingDtos.DeclineRequest;
import com.neuronest.backend.pro.ProBookingService;
import com.neuronest.backend.pro.ProPrincipal;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
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
    private final PublicProfessionalService directory = mock(PublicProfessionalService.class);
    private final FakeSender sender = new FakeSender();
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
                settings, retries, CLOCK);
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

        assertThat(sender.sent).hasSize(13).allSatisfy(e -> {
            assertThat(e.subject()).startsWith("NeuroNest: ");
            assertThat(e.subject().toLowerCase()).doesNotContain("mental", "therapy", "therapist", "psych", "counsel");
            assertThat(e.text()).contains(BookingEmails.FOOTER);
            assertThat(e.html()).contains(BookingEmails.FOOTER);
        });
        assertThat(sender.sent).extracting(OutgoingEmail::subject).contains(
                "NeuroNest: a new request is waiting", "NeuroNest: your request was sent",
                "NeuroNest: your appointment is confirmed", "NeuroNest: an update on your request");
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
            // The meeting link and address only ever go to the user, and only once confirmed.
            if (e.to().equals(PRO_EMAIL) || !e.subject().equals("NeuroNest: your appointment is confirmed")) {
                assertThat(content).doesNotContain(VIDEO_LINK, ADDRESS);
            }
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
    void theUsersConfirmationHasTheLinkTheAddressOrTheCallSentence() {
        confirmedWith("VIDEO", VIDEO_LINK, null);
        assertThat(forUser(send(Type.CONFIRMED))).contains(VIDEO_LINK, "Your appointment with Dr. Asha Rao is confirmed.",
                "Wed, 14 Oct 2026, 6:00 PM IST");
        sender.sent.clear();
        confirmedWith("IN_PERSON", null, ADDRESS);
        assertThat(forUser(send(Type.CONFIRMED))).contains("Address: " + ADDRESS).doesNotContain(VIDEO_LINK);
        sender.sent.clear();
        confirmedWith("PHONE", null, null);
        assertThat(forUser(send(Type.CONFIRMED))).contains(BookingEmails.PHONE_SENTENCE);

        OutgoingEmail toProfessional = sender.sent.stream().filter(e -> e.to().equals(PRO_EMAIL)).findFirst().orElseThrow();
        assertThat(toProfessional.text()).contains("The appointment with Ravi Kumar on Wed, 14 Oct 2026, 6:00 PM IST is "
                + "confirmed. Open NeuroNest to see the contact details.");
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
                    if (!m.group().equals(VIDEO_LINK)) assertThat(m.group()).startsWith(BASE_URL + "/");
                }
                assertThat(links).isPositive();
            }
        });
        assertThat(sender.sent.stream().map(OutgoingEmail::text)).anyMatch(t -> t.contains(BASE_URL + "/pro/requests"));
    }

    // ---- the services, with the listener called directly in place of the after-commit dispatch ----

    private BookingService userService(BookingNotificationListener l) {
        ApplicationEventPublisher publisher = event -> l.handle((BookingEvent) event);
        BookingService service = new BookingService(bookings, new SlotService(new AvailabilityService(), bookings),
                mock(PublicProfessionalService.class), professionals, mock(ProfessionalAccountRepository.class),
                mock(UserRepository.class), mock(AssessmentResultRepository.class), publisher);
        ReflectionTestUtils.setField(service, "clock", CLOCK);
        return service;
    }

    private ProBookingService proService(BookingNotificationListener l) {
        ApplicationEventPublisher publisher = event -> l.handle((BookingEvent) event);
        ProfessionalAccountRepository accounts = mock(ProfessionalAccountRepository.class);
        when(accounts.findByClerkUserId("pro_clerk"))
                .thenReturn(Optional.of(new ProfessionalAccount(professionalId, PRO_EMAIL, "pro_clerk")));
        ProBookingService service = new ProBookingService(bookings, new SlotService(new AvailabilityService(), bookings),
                professionals, accounts, publisher);
        ReflectionTestUtils.setField(service, "clock", CLOCK);
        return service;
    }
}
