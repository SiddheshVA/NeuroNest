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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    void validationRules() {
        existing(VerificationStatus.DRAFT, CredentialType.PSYCHIATRIST);
        assertThatThrownBy(() -> put("{\"registrationBody\":\"RCI\"}")).isInstanceOf(ApiValidationException.class)
                .hasMessage("registrationBody must be NMC for a psychiatrist.");
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

    // ---- submit ----

    @Test
    void submitListsExactlyWhatIsMissing() {
        existing(VerificationStatus.DRAFT, CredentialType.PSYCHOLOGIST);
        professional.setRegistrationExpiry(TODAY);
        when(documents.findByProfessionalIdOrderByUploadedAtAsc(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(principal))
                .isInstanceOfSatisfying(ProExceptions.SubmissionIncompleteException.class, e ->
                        assertThat(e.missing()).extracting(MissingItem::item).containsExactly(
                                "qualifications", "city", "languages", "specialties", "bio", "registrationBody",
                                "registrationCouncil", "registrationYear", "registrationExpiry",
                                "document:REGISTRATION_CERTIFICATE", "document:ID_PROOF"));
        verify(log, never()).save(any());
    }

    @Test
    void completeSubmitMovesToSubmittedAndLogsIt() {
        existing(VerificationStatus.NEEDS_INFO, CredentialType.COUNSELOR);
        professional.setQualifications("MA Counselling");
        professional.setCity("Pune");
        professional.setLanguages(List.of("English"));
        professional.setSpecialties(List.of("Stress"));
        professional.setBio("I help students and young professionals manage stress and anxiety.");
        ProfessionalDocument degree = new ProfessionalDocument(UUID.randomUUID(), DocumentType.DEGREE_CERTIFICATE,
                "x/y.pdf", "degree.pdf", "application/pdf", 10);
        when(documents.findByProfessionalIdOrderByUploadedAtAsc(any())).thenReturn(List.of(degree));

        ProMeResponse me = service.submit(principal);

        assertThat(me.profile().verificationStatus()).isEqualTo("SUBMITTED");
        assertThat(me.profile().submittedAt()).isEqualTo(NOW);
        assertThat(me.canEditCredentials()).isFalse();
        ArgumentCaptor<VerificationLogEntry> entry = ArgumentCaptor.forClass(VerificationLogEntry.class);
        verify(log).save(entry.capture());
        assertThat(entry.getValue().getAction()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().getFromStatus()).isEqualTo("NEEDS_INFO");
        assertThat(entry.getValue().getToStatus()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().getActor()).isEqualTo("PROFESSIONAL");

        assertThatThrownBy(() -> service.submit(principal)).isInstanceOf(ConflictException.class);
    }

    @Test
    void checklistRequiresFutureExpiryAndCorrectBody() {
        Professional p = new Professional();
        p.setCredentialType(CredentialType.PSYCHIATRIST);
        p.setRegistrationBody(RegistrationBody.RCI);
        p.setRegistrationExpiry(TODAY.minusDays(1));
        List<MissingItem> missing = SubmissionChecklist.missing(p, Set.of(DocumentType.ID_PROOF), TODAY);
        assertThat(missing).extracting(MissingItem::message).contains(
                "Your registration body must be NMC.", "Your registration expiry date must be in the future.",
                "Upload your registration certificate.");
        assertThat(missing).extracting(MissingItem::item).doesNotContain("document:ID_PROOF");
    }
}
