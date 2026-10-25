package com.neuronest.backend.pro;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProDtos.ProMeResponse;
import com.neuronest.backend.pro.ProDtos.RegisterRequest;
import com.neuronest.backend.pro.ProExceptions.MissingItem;
import com.neuronest.backend.professional.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T20:00:00Z"); // 13 Oct in India
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final String CLERK_ID = "user_abc123";

    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final ProfessionalAccountRepository accounts = mock(ProfessionalAccountRepository.class);
    private final ProfessionalDocumentRepository documents = mock(ProfessionalDocumentRepository.class);
    private final VerificationLogRepository log = mock(VerificationLogRepository.class);
    private final ClerkBackendClient clerk = mock(ClerkBackendClient.class);
    private final ProRegistrationRateLimiter limiter = mock(ProRegistrationRateLimiter.class);
    private final ProProfileService service = new ProProfileService(professionals, accounts, documents, log, clerk,
            limiter, new TransactionTemplate(mock(PlatformTransactionManager.class)),
            Clock.fixed(NOW, ProProfileService.ZONE));
    private final JsonMapper json = JsonMapper.builder().build();
    private final ProPrincipal principal = new ProPrincipal(CLERK_ID);

    private Professional professional;

    @BeforeEach
    void setUp() {
        when(professionals.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accounts.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(limiter.tryAcquire(any())).thenReturn(Optional.empty());
        when(log.findFirstByProfessionalIdAndNoteIsNotNullAndActorNotOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
    }

    private void existing(VerificationStatus status, CredentialType type) {
        professional = new Professional();
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(type);
        professional.setConsultationMode(ConsultationMode.ONLINE);
        professional.setContactEmail("asha@example.com");
        professional.setRegistrationNumber("A123");
        professional.setVerificationStatus(status);
        ProfessionalAccount account = new ProfessionalAccount(UUID.randomUUID(), "asha@example.com", CLERK_ID);
        when(accounts.findByClerkUserId(CLERK_ID)).thenReturn(Optional.of(account));
        when(professionals.findById(any())).thenReturn(Optional.of(professional));
    }

    private ProMeResponse put(String body) {
        return service.update(principal, json.readTree(body));
    }

    private ProMeResponse submit(String body) {
        return service.submit(principal, body == null ? null : json.readTree(body));
    }

    private static final String ACCEPTED = "{\"declarationAccepted\":true}";

    // ---- status fields are the admin's ----

    @Test
    void theProfessionalsOwnUpdateNeverChangesTheStatusTheListingOrTheAccount() {
        existing(VerificationStatus.SUSPENDED, CredentialType.PSYCHOLOGIST);
        professional.setVerificationNotes("Complaint under investigation.");
        ProfessionalAccount account = accounts.findByClerkUserId(CLERK_ID).orElseThrow();
        account.setStatus(com.neuronest.backend.professional.ProfessionalAccountStatus.DISABLED);
        String body = """
                {"verificationStatus":"VERIFIED","verified":true,"active":true,"status":"ACTIVE",
                 "verificationNotes":"cleared","verifiedBy":"me","verifiedAt":"2026-10-01T00:00:00Z",
                 "isSampleData":true,"sampleData":true,"bio":"I help people manage stress and sleep better."}""";

        try {
            put(body);
        } catch (RuntimeException refused) {
            // Refusing the whole update is fine too; either way nothing below may change.
        }

        assertThat(professional.getVerificationStatus()).isEqualTo(VerificationStatus.SUSPENDED);
        assertThat(professional.isVerified()).isFalse();
        assertThat(professional.isActive()).isFalse();
        assertThat(professional.isSampleData()).isFalse();
        assertThat(professional.getVerificationNotes()).isEqualTo("Complaint under investigation.");
        assertThat(professional.getVerifiedBy()).isNull();
        assertThat(account.getStatus()).isEqualTo(com.neuronest.backend.professional.ProfessionalAccountStatus.DISABLED);
    }

    // ---- register ----

    @Test
    void registerCreatesHiddenDraftWithClerkEmail() {
        when(accounts.findByClerkUserId(CLERK_ID)).thenReturn(Optional.empty());
        when(clerk.primaryEmail(CLERK_ID)).thenReturn(Optional.of(new ClerkBackendClient.PrimaryEmail("Asha@Example.com", true)));

        ProMeResponse me = service.register(principal, new RegisterRequest(" Dr. Asha ", "psychologist", "online"), "1.2.3.4");

        assertThat(me.profile().verificationStatus()).isEqualTo("DRAFT");
        assertThat(me.profile().verified()).isFalse();
        assertThat(me.profile().active()).isFalse();
        assertThat(me.profile().contactEmail()).isEqualTo("asha@example.com");
        assertThat(me.profile().fullName()).isEqualTo("Dr. Asha");
        assertThat(me.account().status()).isEqualTo("ACTIVE");
        ArgumentCaptor<Professional> saved = ArgumentCaptor.forClass(Professional.class);
        verify(professionals).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isSampleData()).isFalse();
        ArgumentCaptor<ProfessionalAccount> account = ArgumentCaptor.forClass(ProfessionalAccount.class);
        verify(accounts).saveAndFlush(account.capture());
        assertThat(account.getValue().getClerkUserId()).isEqualTo(CLERK_ID);
    }

    @Test
    void registerRefusesExistingAccountUnverifiedEmailAndRateLimit() {
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);
        assertThatThrownBy(() -> service.register(principal, new RegisterRequest("A", "COUNSELOR", "ONLINE"), "ip"))
                .isInstanceOf(ConflictException.class);

        when(accounts.findByClerkUserId(CLERK_ID)).thenReturn(Optional.empty());
        when(clerk.primaryEmail(CLERK_ID)).thenReturn(Optional.of(new ClerkBackendClient.PrimaryEmail("a@b.co", false)));
        assertThatThrownBy(() -> service.register(principal, new RegisterRequest("A", "COUNSELOR", "ONLINE"), "ip"))
                .isInstanceOf(ProExceptions.EmailNotVerifiedException.class);

        when(limiter.tryAcquire("ip")).thenReturn(Optional.of(java.time.Duration.ofMinutes(5)));
        assertThatThrownBy(() -> service.register(principal, new RegisterRequest("A", "COUNSELOR", "ONLINE"), "ip"))
                .isInstanceOf(ProExceptions.RateLimitedException.class);
        verify(professionals, never()).saveAndFlush(any());
    }

    // ---- update ----

    @Test
    void lockedFieldChangeAfterSubmitIsRefusedButUnchangedValuesAndOpenFieldsAreFine() {
        existing(VerificationStatus.SUBMITTED, CredentialType.PSYCHOLOGIST);

        assertThatThrownBy(() -> put("{\"registrationNumber\":\"B999\",\"bio\":\"x\"}"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("These details can only be changed by the admin once your profile has been submitted: "
                        + "registrationNumber.");
        assertThat(professional.getBio()).isNull();

        ProMeResponse me = put("{\"fullName\":\" Dr. Asha Rao \",\"registrationNumber\":\"A123\",\"bio\":\" New bio \",\"city\":\"Pune\"}");
        assertThat(me.profile().bio()).isEqualTo("New bio");
        assertThat(me.profile().city()).isEqualTo("Pune");
    }

    @Test
    void forbiddenFieldsAreIgnored() {
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);

        ProMeResponse me = put("{\"verificationStatus\":\"VERIFIED\",\"verified\":true,\"active\":true,\"isSampleData\":true,"
                + "\"contactEmail\":\"evil@example.com\",\"verifiedBy\":\"me\",\"id\":\"" + UUID.randomUUID() + "\",\"city\":\"Pune\"}");

        assertThat(me.profile().verificationStatus()).isEqualTo("DRAFT");
        assertThat(me.profile().verified()).isFalse();
        assertThat(me.profile().active()).isFalse();
        assertThat(professional.isSampleData()).isFalse();
        assertThat(me.profile().contactEmail()).isEqualTo("asha@example.com");
        assertThat(me.profile().city()).isEqualTo("Pune");
    }

    @Test
    void counselorBodiesAreOptionalButNeedTheirCouncil() {
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);
        assertThatThrownBy(() -> put("{\"registrationBody\":\"NMC\"}"))
                .hasMessage("registrationCouncil is required when a registration body is chosen.");
        assertThat(put("{\"registrationBody\":\"RCI\",\"registrationCouncil\":\"Rehabilitation Council of India\"}")
                .profile().registrationBody()).isEqualTo("RCI");
        assertThat(put("{\"registrationBody\":null,\"registrationCouncil\":null}").profile().registrationBody()).isNull();

        existing(VerificationStatus.DRAFT, CredentialType.PSYCHOLOGIST);
        assertThatThrownBy(() -> put("{\"registrationBody\":\"NMC\"}"))
                .hasMessage("registrationBody must be RCI or OTHER for a psychologist.");
    }

    @Test
    void validationRules() {
        existing(VerificationStatus.DRAFT, CredentialType.PSYCHIATRIST);
        assertThatThrownBy(() -> put("{\"registrationBody\":\"RCI\"}")).isInstanceOf(ApiValidationException.class)
                .hasMessage("registrationBody must be NMC or OTHER for a psychiatrist.");
        assertThatThrownBy(() -> put("{\"registrationBody\":\"OTHER\"}"))
                .hasMessage("registrationCouncil must contain the name of the registration body when registrationBody is OTHER.");
        assertThatThrownBy(() -> put("{\"yearsExperience\":61}")).hasMessage("yearsExperience must be between 0 and 60.");
        // The availability note is optional free text with no minimum.
        assertThat(put("{\"availabilityNote\":\"Evenings\"}").profile().availabilityNote()).isEqualTo("Evenings");
        assertThatThrownBy(() -> put("{\"availabilityNote\":\"" + "x".repeat(201) + "\"}"))
                .hasMessage("availabilityNote must be at most 200 characters.");
        assertThatThrownBy(() -> put("{\"photoUrl\":\"http://example.com/a.jpg\"}")).hasMessage("photoUrl must start with https://.");
        for (String bad : List.of("12345", "5876543210", "+91 98765 4321", "+44 9876543210", "98765432100")) {
            assertThatThrownBy(() -> put("{\"contactPhone\":\"" + bad + "\"}")).hasMessageStartingWith("contactPhone must be a 10-digit");
        }
        for (String good : List.of("9876543210", "+91 98765 43210", "098765-43210", "+91(98765)43210")) {
            assertThat(put("{\"contactPhone\":\"" + good + "\"}").profile().contactPhone()).isEqualTo("+919876543210");
        }
        assertThat(put("{\"registrationBody\":\"OTHER\",\"registrationCouncil\":\"Indian Psychiatric Society\"}")
                .profile().registrationBody()).isEqualTo("OTHER");
        assertThat(put("{\"consultationFee\":0,\"yearsExperience\":0}").profile().consultationFee()).isZero();
        assertThatThrownBy(() -> put("{\"consultationFee\":100001}")).hasMessage("consultationFee must be between 0 and 100000.");
        assertThatThrownBy(() -> put("{\"consultationFee\":-5}")).hasMessage("consultationFee must be between 0 and 100000.");
        assertThatThrownBy(() -> put("{\"registrationYear\":1949}")).hasMessage("registrationYear must be between 1950 and 2026.");
        assertThatThrownBy(() -> put("{\"registrationYear\":2027}")).hasMessage("registrationYear must be between 1950 and 2026.");
        assertThatThrownBy(() -> put("{\"consultationFee\":\"abc\"}")).hasMessage("consultationFee must be a whole number.");
        assertThatThrownBy(() -> put("{\"consultationMode\":null}")).hasMessageStartingWith("consultationMode is required.");
        assertThatThrownBy(() -> put("{\"credentialType\":\"THERAPIST\"}")).hasMessageStartingWith("credentialType must be one of");
        assertThatThrownBy(() -> put("[]")).hasMessage("Request body must be a JSON object.");

        assertThat(put("{\"registrationBody\":\"nmc\",\"specialties\":[\" Anxiety \",\"anxiety\",\"\"]}").profile())
                .satisfies(p -> {
                    assertThat(p.registrationBody()).isEqualTo("NMC");
                    assertThat(p.specialties()).containsExactly("Anxiety");
                });
    }

    @Test
    void theNoteComesFromVerificationNotesOnlyWhileItExplainsTheStatus() {
        existing(VerificationStatus.NEEDS_INFO, CredentialType.COUNSELOR);
        professional.setVerificationNotes("Please upload a clearer certificate.");
        assertThat(service.me(principal).verificationNote().note()).isEqualTo("Please upload a clearer certificate.");

        // A note the admin wrote when verifying stays internal.
        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        professional.setVerificationNotes("Checked on the register by phone.");
        assertThat(service.me(principal).verificationNote()).isNull();
    }

    // ---- submit ----

    @Test
    void submitListsExactlyWhatIsMissing() {
        existing(VerificationStatus.DRAFT, CredentialType.PSYCHOLOGIST);
        professional.setRegistrationExpiry(TODAY);
        when(documents.findByProfessionalIdOrderByUploadedAtAsc(any())).thenReturn(List.of());

        assertThatThrownBy(() -> submit(null))
                .isInstanceOfSatisfying(ProExceptions.SubmissionIncompleteException.class, e ->
                        assertThat(e.missing()).extracting(MissingItem::item).containsExactly(
                                "dateOfBirth", "qualifications", "state", "city", "languages", "specialties", "bio",
                                "yearsExperience", "consultationFee", "weeklyHours", "contactPhone",
                                "registrationBody", "registrationCouncil", "registrationYear", "registrationExpiry",
                                "document:REGISTRATION_CERTIFICATE", "document:DEGREE_CERTIFICATE",
                                "document:ID_PROOF", "declaration"));
        verify(log, never()).save(any());
    }

    @Test
    void completeSubmitMovesToSubmittedAndLogsIt() {
        existing(VerificationStatus.NEEDS_INFO, CredentialType.COUNSELOR);
        professional.setQualifications("MA Counselling");
        professional.setState("Maharashtra");
        professional.setCity("Pune");
        professional.setLanguages(List.of("English"));
        professional.setSpecialties(List.of("Stress"));
        professional.setBio("I help students and young professionals manage stress and anxiety.");
        professional.setYearsExperience(4);
        professional.setConsultationFee(0);
        professional.setSessionMinutes(60);
        professional.setWeeklyHours(Map.of("TUE", List.of(Map.of("start", "18:00", "end", "20:00"))));
        professional.setContactPhone("+919876543210");
        professional.setDateOfBirth(LocalDate.of(1990, 5, 1));
        ProfessionalDocument degree = new ProfessionalDocument(UUID.randomUUID(), DocumentType.DEGREE_CERTIFICATE,
                "x/y.pdf", "degree.pdf", "application/pdf", 10);
        ProfessionalDocument id = new ProfessionalDocument(UUID.randomUUID(), DocumentType.ID_PROOF,
                "x/z.pdf", "pan.pdf", "application/pdf", 10);
        when(documents.findByProfessionalIdOrderByUploadedAtAsc(any())).thenReturn(List.of(degree, id));

        // Without the declaration (no body, false, or not a boolean) only the declaration is missing.
        for (String body : new String[]{null, "{}", "{\"declarationAccepted\":false}", "{\"declarationAccepted\":\"true\"}"}) {
            assertThatThrownBy(() -> submit(body))
                    .isInstanceOfSatisfying(ProExceptions.SubmissionIncompleteException.class, e ->
                            assertThat(e.missing()).extracting(MissingItem::item).containsExactly("declaration"));
        }
        assertThat(professional.getDeclarationAcceptedAt()).isNull();

        ProMeResponse me = submit(ACCEPTED);

        assertThat(me.profile().verificationStatus()).isEqualTo("SUBMITTED");
        assertThat(me.profile().submittedAt()).isEqualTo(NOW);
        assertThat(professional.getDeclarationAcceptedAt()).isEqualTo(NOW);
        assertThat(me.canEditCredentials()).isFalse();
        ArgumentCaptor<VerificationLogEntry> entry = ArgumentCaptor.forClass(VerificationLogEntry.class);
        verify(log).save(entry.capture());
        assertThat(entry.getValue().getAction()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().getFromStatus()).isEqualTo("NEEDS_INFO");
        assertThat(entry.getValue().getToStatus()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().getActor()).isEqualTo("PROFESSIONAL");

        assertThatThrownBy(() -> submit(ACCEPTED)).isInstanceOf(ConflictException.class);
    }

    private static String noContact(String field) {
        return field + " must not contain phone numbers, email addresses or website links. "
                + "People reach you through NeuroNest.";
    }

    @Test
    void contactDetailsAreRefusedInPublicTextButNotInContactFields() {
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);

        assertThatThrownBy(() -> put("{\"bio\":\"Call me on 98765 43210 any time.\"}"))
                .isInstanceOf(ApiValidationException.class).hasMessage(noContact("Bio"));
        assertThatThrownBy(() -> put("{\"availabilityNote\":\"Book at www.example.com\"}"))
                .hasMessage(noContact("Availability note"));
        assertThatThrownBy(() -> put("{\"qualifications\":\"MA, see asha@example.com\"}"))
                .hasMessage(noContact("Qualifications"));
        assertThatThrownBy(() -> put("{\"specialties\":[\"Anxiety\",\"Other: insta @asha_rao\"]}"))
                .hasMessage(noContact("Specializations"));
        assertThat(professional.getBio()).isNull();
        assertThat(professional.getSpecialties()).isEmpty();

        // Contact fields, the photo URL and social links are not checked.
        ProMeResponse me = put("{\"bio\":\"I help students manage stress.\",\"contactPhone\":\"9876543210\","
                + "\"photoUrl\":\"https://example.com/me.jpg\",\"qualifications\":\"B.Com, MA Counselling\"}");
        assertThat(me.profile().bio()).isEqualTo("I help students manage stress.");
        assertThat(professional.getContactPhone()).isEqualTo("+919876543210");
        assertThat(professional.getQualifications()).isEqualTo("B.Com, MA Counselling");
    }

    @Test
    void oldTextWithContactDetailsIsListedOnSubmitAndNeverBreaksReading() {
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);
        professional.setBio("Reach me at asha@example.com for sessions.");
        professional.setQualifications("MA, www.asha.in");
        professional.setSpecialties(List.of("Stress", "WhatsApp: 98765 43210"));
        professional.setAvailabilityNote("Evenings, call +91 98765 43210");

        // Reading still works and shows the problems.
        ProMeResponse me = service.me(principal);
        assertThat(me.profile().bio()).isEqualTo("Reach me at asha@example.com for sessions.");
        assertThat(me.missingForSubmit()).contains(new MissingItem("bio", noContact("Bio")),
                new MissingItem("qualifications", noContact("Qualifications")),
                new MissingItem("specialties", noContact("Specializations")),
                new MissingItem("availabilityNote", noContact("Availability note")));

        assertThatThrownBy(() -> submit(ACCEPTED))
                .isInstanceOfSatisfying(ProExceptions.SubmissionIncompleteException.class, e ->
                        assertThat(e.missing()).contains(new MissingItem("bio", noContact("Bio")),
                                new MissingItem("qualifications", noContact("Qualifications")),
                                new MissingItem("specialties", noContact("Specializations")),
                                new MissingItem("availabilityNote", noContact("Availability note"))));

        // Once submitted, an unchanged locked qualification does not block unrelated edits.
        professional.setVerificationStatus(VerificationStatus.SUBMITTED);
        assertThat(put("{\"qualifications\":\"MA, www.asha.in\",\"city\":\"Pune\"}").profile().city()).isEqualTo("Pune");
    }

    @Test
    void checklistRequiresFutureExpiryAndCorrectBody() {
        Professional p = new Professional();
        p.setCredentialType(CredentialType.PSYCHIATRIST);
        p.setRegistrationBody(RegistrationBody.RCI);
        p.setRegistrationExpiry(TODAY.minusDays(1));
        List<MissingItem> missing = SubmissionChecklist.missing(p, Set.of(DocumentType.ID_PROOF), TODAY);
        assertThat(missing).extracting(MissingItem::message).contains(
"Your registration body must be NMC or OTHER for a psychiatrist.",
                "Your registration expiry date must be in the future.",
                "Add your consultation fee. Enter 0 if you do not charge a fee.",
                "Upload your registration certificate.");
        assertThat(missing).extracting(MissingItem::item).doesNotContain("document:ID_PROOF");

        p.setRegistrationBody(RegistrationBody.OTHER);
        p.setContactPhone("12345");
        assertThat(SubmissionChecklist.missing(p, Set.of(), TODAY)).extracting(MissingItem::message).contains(
                "Add the name of your registration body.", "Your contact phone must be a 10-digit Indian mobile number.")
                .doesNotContain("Your registration body must be NMC or OTHER for a psychiatrist.");

        Professional counselor = new Professional();
        counselor.setCredentialType(CredentialType.COUNSELOR);
        assertThat(SubmissionChecklist.missing(counselor, Set.of(), TODAY)).extracting(MissingItem::item)
                .doesNotContain("registrationBody", "registrationCouncil");
        counselor.setRegistrationBody(RegistrationBody.NMC);
        assertThat(SubmissionChecklist.missing(counselor, Set.of(), TODAY)).extracting(MissingItem::item)
                .contains("registrationCouncil").doesNotContain("registrationBody");
    }

    // ---- date of birth, specializations, social links, documents ----

    @Test
    void dateOfBirthAgeIsCheckedInIndiaAndLockedAfterSubmit() {
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);
        // TODAY is 13 Oct 2026 in India (still 12 Oct in UTC): turning 21 on 13 Oct counts.
        assertThat(put("{\"dateOfBirth\":\"2005-10-13\"}").profile().dateOfBirth()).isEqualTo(LocalDate.of(2005, 10, 13));
        assertThatThrownBy(() -> put("{\"dateOfBirth\":\"2005-10-14\"}"))
                .hasMessage("dateOfBirth must give an age between 21 and 90 years.");
        assertThat(put("{\"dateOfBirth\":\"1935-10-14\"}").profile().dateOfBirth()).isEqualTo(LocalDate.of(1935, 10, 14));
        assertThatThrownBy(() -> put("{\"dateOfBirth\":\"1935-10-13\"}"))
                .hasMessage("dateOfBirth must give an age between 21 and 90 years.");
        assertThatThrownBy(() -> put("{\"dateOfBirth\":\"13/10/1990\"}"))
                .hasMessage("dateOfBirth must be a date in YYYY-MM-DD format.");

        for (VerificationStatus locked : List.of(VerificationStatus.SUBMITTED, VerificationStatus.VERIFIED,
                VerificationStatus.EXPIRED, VerificationStatus.SUSPENDED, VerificationStatus.REJECTED)) {
            existing(locked, CredentialType.COUNSELOR);
            assertThatThrownBy(() -> put("{\"dateOfBirth\":\"1990-01-01\"}")).isInstanceOf(ConflictException.class)
                    .hasMessageEndingWith("dateOfBirth.");
        }
        existing(VerificationStatus.NEEDS_INFO, CredentialType.COUNSELOR);
        assertThat(put("{\"dateOfBirth\":\"1990-01-01\"}").profile().dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
    }

    @Test
    void specializationsAreTrimmedDedupedAndLimited() {
        existing(VerificationStatus.DRAFT, CredentialType.PSYCHOLOGIST);
        assertThat(put("{\"specialties\":[\" Clinical Psychologist \",\"clinical psychologist\",\"Play therapist\"]}")
                .profile().specialties()).containsExactly("Clinical Psychologist", "Play therapist");
        assertThatThrownBy(() -> put("{\"specialties\":[\"A\"]}"))
                .hasMessage("Each entry in specialties must be between 2 and 60 characters.");
        assertThatThrownBy(() -> put("{\"specialties\":[\"" + "x".repeat(61) + "\"]}"))
                .hasMessage("Each entry in specialties must be at most 60 characters.");
        String sixteen = String.join(",", IntStream.range(0, 16).mapToObj(i -> "\"Role " + i + "\"").toList());
        assertThatThrownBy(() -> put("{\"specialties\":[" + sixteen + "]}"))
                .hasMessage("specialties can have at most 15 entries.");
    }

    @Test
    void socialLinksAreValidatedPerPlatformAndStayEditableAfterSubmit() {
        existing(VerificationStatus.VERIFIED, CredentialType.COUNSELOR);
        ProMeResponse me = put("{\"socialLinks\":{\"website\":\"https://asha.example.in/about\","
                + "\"LINKEDIN\":\" https://www.linkedin.com/in/asha \",\"X\":\"https://twitter.com/asha\","
                + "\"YOUTUBE\":\"https://youtu.be/abc\",\"INSTAGRAM\":\"\",\"FACEBOOK\":null}}");
        assertThat(me.profile().socialLinks()).containsExactly(
                Map.entry("LINKEDIN", "https://www.linkedin.com/in/asha"),
                Map.entry("X", "https://twitter.com/asha"),
                Map.entry("YOUTUBE", "https://youtu.be/abc"),
                Map.entry("WEBSITE", "https://asha.example.in/about"));

        assertThat(put("{\"socialLinks\":{\"LINKEDIN\":\"https://linkedin.com/in/asha\"}}").profile().socialLinks())
                .containsOnlyKeys("LINKEDIN");
        assertThatThrownBy(() -> put("{\"socialLinks\":{\"LINKEDIN\":\"http://linkedin.com/in/asha\"}}"))
                .hasMessage("socialLinks.LINKEDIN must start with https://.");
        assertThatThrownBy(() -> put("{\"socialLinks\":{\"LINKEDIN\":\"https://notlinkedin.com/in/asha\"}}"))
                .hasMessage("socialLinks.LINKEDIN must be a link on linkedin.com.");
        assertThatThrownBy(() -> put("{\"socialLinks\":{\"INSTAGRAM\":\"https://instagram.com.evil.io/a\"}}"))
                .hasMessage("socialLinks.INSTAGRAM must be a link on instagram.com.");
        assertThatThrownBy(() -> put("{\"socialLinks\":{\"WEBSITE\":\"https://exa mple.com\"}}"))
                .hasMessage("socialLinks.WEBSITE must be a valid link.");
        assertThatThrownBy(() -> put("{\"socialLinks\":{\"WEBSITE\":\"https://" + "a".repeat(200) + ".com\"}}"))
                .hasMessage("socialLinks.WEBSITE must be at most 200 characters.");
        assertThatThrownBy(() -> put("{\"socialLinks\":{\"TIKTOK\":\"https://tiktok.com/@a\"}}"))
                .hasMessageStartingWith("socialLinks can only contain");
        assertThat(put("{\"socialLinks\":{}}").profile().socialLinks()).isEmpty();
    }

    @Test
    void weeklyHoursAreSoftFieldsCheckedAgainstTheGlobalSessionLength() {
        existing(VerificationStatus.VERIFIED, CredentialType.PSYCHOLOGIST);
        professional.setVerified(true);
        professional.setActive(true);

        ProMeResponse me = put("{\"sessionMinutes\":45,\"bufferMinutes\":15,\"weeklyHours\":{"
                + "\"SAT\":[{\"start\":\"14:00\",\"end\":\"16:00\"},{\"start\":\"09:00\",\"end\":\"12:00\"}],\"MON\":[]}}");
        // Editing hours on a VERIFIED listing keeps it verified and listed.
        assertThat(me.profile().verificationStatus()).isEqualTo("VERIFIED");
        assertThat(me.profile().active()).isTrue();
        // Older forms may still send a session length and buffer; the profile shows the global grid.
        assertThat(me.profile().sessionMinutes()).isEqualTo(60);
        assertThat(me.profile().bufferMinutes()).isEqualTo(15);
        // Sorted by start, and empty days left out.
        assertThat(me.profile().weeklyHours()).containsOnlyKeys("SAT");
        assertThat(me.profile().weeklyHours().get("SAT")).extracting(r -> r.get("start")).containsExactly("09:00", "14:00");
        verify(log, never()).save(any());

        // Every range must fit one 60-minute session.
        assertThatThrownBy(() -> put("{\"weeklyHours\":{\"MON\":[{\"start\":\"09:00\",\"end\":\"09:45\"}]}}"))
                .hasMessage("weeklyHours: Monday 09:00 to 09:45 is shorter than one 60-minute session.");
        professional.setWeeklyHours(Map.of("TUE", List.of(Map.of("start", "18:00", "end", "18:45"))));
        assertThatThrownBy(() -> put("{\"sessionMinutes\":60}"))
                .hasMessage("weeklyHours: Tuesday 18:00 to 18:45 is shorter than one 60-minute session.");
        assertThatThrownBy(() -> put("{\"sessionMinutes\":50}")).hasMessage("sessionMinutes must be 30, 45 or 60.");
        assertThatThrownBy(() -> put("{\"bufferMinutes\":7}")).hasMessage("bufferMinutes must be 0, 5, 10 or 15.");
        assertThat(put("{\"weeklyHours\":null,\"sessionMinutes\":null}").profile().weeklyHours()).isEmpty();

        // Submit asks for weekly hours only; the session length is the same for everyone.
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);
        assertThat(service.me(principal).missingForSubmit()).extracting(MissingItem::message)
                .contains("Add at least one time range to your weekly hours.")
                .noneMatch(message -> message.startsWith("Choose how long one session lasts"));
    }

    @Test
    void bioMustBeAtLeastTenCharactersToSubmit() {
        Professional p = new Professional();
        p.setCredentialType(CredentialType.COUNSELOR);
        p.setBio(" 123456789 ");
        assertThat(SubmissionChecklist.missing(p, Set.of(), TODAY)).extracting(MissingItem::message)
                .contains("Write a bio of at least 10 characters.");
        // Letters, since ten digits in a row would now count as a phone number.
        p.setBio("abcdefghij");
        assertThat(SubmissionChecklist.missing(p, Set.of(), TODAY)).extracting(MissingItem::item).doesNotContain("bio");
    }

    @Test
    void stateMustBeListedAndIsStoredWithTheListedSpelling() {
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);
        assertThat(put("{\"state\":\"  tamil nadu \"}").profile().state()).isEqualTo("Tamil Nadu");
        assertThat(put("{\"state\":\"Delhi\"}").profile().state()).isEqualTo("Delhi");
        assertThatThrownBy(() -> put("{\"state\":\"Bombay\"}"))
                .hasMessage("state must be one of the Indian states or union territories in the list.");
        assertThat(put("{\"state\":null}").profile().state()).isNull();
        assertThat(service.me(principal).stateOptions()).hasSize(36).contains("Maharashtra", "Puducherry");

        // A value saved earlier that is not on the list must be chosen again before submitting.
        professional.setState("Maharastra");
        assertThat(service.me(principal).missingForSubmit()).extracting(MissingItem::message)
                .contains("Choose your state or union territory from the list.");
        professional.setState(null);
        assertThat(service.me(principal).missingForSubmit()).extracting(MissingItem::message)
                .contains("Choose the state or union territory you practise in.");
    }

    @Test
    void requiredAndOptionalDocumentsDependOnCredentialType() {
        when(documents.findByProfessionalIdOrderByUploadedAtAsc(any())).thenReturn(List.of());
        existing(VerificationStatus.DRAFT, CredentialType.COUNSELOR);
        ProMeResponse me = service.me(principal);
        assertThat(me.requiredDocuments()).containsExactly("DEGREE_CERTIFICATE", "ID_PROOF");
        assertThat(me.optionalDocuments()).containsExactly("REGISTRATION_CERTIFICATE");
        assertThat(me.documentRules()).containsOnlyKeys("PSYCHOLOGIST", "PSYCHIATRIST", "COUNSELOR");
        assertThat(me.documentRules().get("COUNSELOR").optional()).containsExactly("REGISTRATION_CERTIFICATE");
        assertThat(me.documentRules().get("PSYCHIATRIST").required())
                .containsExactly("REGISTRATION_CERTIFICATE", "DEGREE_CERTIFICATE", "ID_PROOF");
        assertThat(me.missingForSubmit()).extracting(MissingItem::message)
                .contains("Upload your degree certificate.", "Upload your government ID proof.", "Add your date of birth.",
                        "Choose at least one specialization.")
                .doesNotContain("Upload your registration certificate.");

        for (CredentialType type : List.of(CredentialType.PSYCHIATRIST, CredentialType.PSYCHOLOGIST)) {
            existing(VerificationStatus.DRAFT, type);
            me = service.me(principal);
            assertThat(me.requiredDocuments()).containsExactly("REGISTRATION_CERTIFICATE", "DEGREE_CERTIFICATE", "ID_PROOF");
            assertThat(me.optionalDocuments()).isEmpty();
        }
    }
}
