package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalDetail;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalRequest;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalUpdateResult;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.VerificationLogEntry;
import com.neuronest.backend.professional.VerificationLogRepository;
import com.neuronest.backend.professional.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminProfessionalServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final ProfessionalRepository repository = mock(ProfessionalRepository.class);
    private final VerificationLogRepository log = mock(VerificationLogRepository.class);
    private final com.neuronest.backend.professional.ProfessionalAccountRepository accounts =
            mock(com.neuronest.backend.professional.ProfessionalAccountRepository.class);
    private final AdminProfessionalService service =
            new AdminProfessionalService(repository, log, accounts, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ProfessionalRequest request(String fullName, String credentialType, String registrationNumber,
                                               Integer consultationFee, Integer registrationYear,
                                               String email, Boolean sample) {
        return new ProfessionalRequest(fullName, credentialType, " M.Phil ", "rci", registrationNumber, null,
                registrationYear, null, null, List.of(" Anxiety ", "", "Anxiety", "Stress"), null, " Pune ", null,
                "online", consultationFee, 5, email, null, null, null, sample, null, null, null);
    }

    // The valid request plus weekly hours fields.
    private static ProfessionalRequest withHours(String weeklyHoursJson, Integer sessionMinutes, Integer bufferMinutes) {
        tools.jackson.databind.JsonNode hours = weeklyHoursJson == null ? null
                : tools.jackson.databind.json.JsonMapper.builder().build().readTree(weeklyHoursJson);
        return new ProfessionalRequest("  Dr. Asha Rao ", "psychologist", " M.Phil ", "rci", "A123", null,
                2015, null, null, List.of("Anxiety"), null, " Pune ", null,
                "online", 1000, 5, " asha@example.com ", null, null, null, null, hours, sessionMinutes, bufferMinutes);
    }

    private static ProfessionalRequest valid() {
        return request("  Dr. Asha Rao ", "psychologist", "A123", 1000, 2015, " asha@example.com ", null);
    }

    private Professional existing(VerificationStatus status, boolean verified, boolean active, boolean sample) {
        Professional p = new Professional();
        p.setFullName("Dr. Asha Rao");
        p.setCredentialType(CredentialType.PSYCHOLOGIST);
        p.setConsultationMode(com.neuronest.backend.professional.ConsultationMode.ONLINE);
        p.setContactEmail("asha@example.com");
        p.setRegistrationBody(com.neuronest.backend.professional.RegistrationBody.RCI);
        p.setRegistrationNumber("A123");
        p.setVerificationStatus(status);
        p.setVerified(verified);
        p.setActive(active);
        p.setSampleData(sample);
        when(repository.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private static ProfessionalRequest withText(String qualifications, String bio, List<String> specialties,
                                                String availabilityNote) {
        return new ProfessionalRequest("Dr. Asha Rao", "psychologist", qualifications, "rci", "A123", null,
                2015, null, bio, specialties, null, "Pune", null, "online", 1000, 5, "asha@example.com",
                "+919876543210", availabilityNote, "https://example.com/photo.jpg", null, null, null, null);
    }

    @Test
    void createAndUpdateRefuseContactDetailsInPublicText() {
        String suffix = " must not contain phone numbers, email addresses or website links. People reach you through NeuroNest.";
        existing(VerificationStatus.DRAFT, false, false, false);
        List<ProfessionalRequest> bad = List.of(
                withText("MA, asha@example.com", null, List.of("Anxiety"), null),
                withText("MA", "Call 98765 43210", List.of("Anxiety"), null),
                withText("MA", null, List.of("Anxiety", "Other: @asha_rao"), null),
                withText("MA", null, List.of("Anxiety"), "Book at example.in"));
        List<String> fields = List.of("Qualifications", "Bio", "Specializations", "Availability note");
        for (int i = 0; i < bad.size(); i++) {
            ProfessionalRequest request = bad.get(i);
            assertThatThrownBy(() -> service.create(request)).isInstanceOf(ApiValidationException.class)
                    .hasMessage(fields.get(i) + suffix);
            assertThatThrownBy(() -> service.update(id, request, "admin")).isInstanceOf(ApiValidationException.class)
                    .hasMessage(fields.get(i) + suffix);
        }
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());

        // The contact fields and the photo URL are not checked.
        ProfessionalDetail created = service.create(withText("B.Com, MA", "I help with stress.", List.of("Anxiety"),
                "Evenings"));
        assertThat(created.bio()).isEqualTo("I help with stress.");
    }

    @Test
    void createForcesDraftHiddenUnverifiedAndCleansInput() {
        ProfessionalDetail created = service.create(valid());

        assertThat(created.verificationStatus()).isEqualTo("DRAFT");
        assertThat(created.verified()).isFalse();
        assertThat(created.active()).isFalse();
        assertThat(created.isSampleData()).isFalse();
        assertThat(created.fullName()).isEqualTo("Dr. Asha Rao");
        assertThat(created.contactEmail()).isEqualTo("asha@example.com");
        assertThat(created.credentialType()).isEqualTo("PSYCHOLOGIST");
        assertThat(created.registrationBody()).isEqualTo("RCI");
        assertThat(created.qualifications()).isEqualTo("M.Phil");
        assertThat(created.specialties()).containsExactly("Anxiety", "Stress");
        assertThat(created.languages()).isEmpty();
    }

    @Test
    void rejectsInvalidInputWithReadableMessages() {
        assertThatThrownBy(() -> service.create(request("x", "THERAPIST", null, null, null, "a@b.co", null)))
                .isInstanceOf(ApiValidationException.class)
                .hasMessage("credentialType must be one of PSYCHOLOGIST, PSYCHIATRIST, COUNSELOR (got 'THERAPIST').");
        assertThatThrownBy(() -> service.create(request(" ", "COUNSELOR", null, null, null, "a@b.co", null)))
                .hasMessage("fullName is required.");
        assertThatThrownBy(() -> service.create(request("x", "COUNSELOR", null, 100_001, null, "a@b.co", null)))
                .hasMessage("consultationFee must be between 0 and 100000.");
        assertThatThrownBy(() -> service.create(request("x", "COUNSELOR", null, -1, null, "a@b.co", null)))
                .hasMessage("consultationFee must be between 0 and 100000.");
        assertThatThrownBy(() -> service.create(request("x", "COUNSELOR", null, null, 1949, "a@b.co", null)))
                .hasMessage("registrationYear must be between 1950 and 2026.");
        assertThatThrownBy(() -> service.create(request("x", "COUNSELOR", null, null, 2027, "a@b.co", null)))
                .hasMessage("registrationYear must be between 1950 and 2026.");
        assertThatThrownBy(() -> service.create(request("x", "COUNSELOR", null, null, null, "not-an-email", null)))
                .hasMessage("contactEmail is not a valid email address.");
        verify(repository, never()).save(any());
    }

    @Test
    void identityChangeOnVerifiedListingSendsItBackForReviewAndLogsIt() {
        Professional p = existing(VerificationStatus.VERIFIED, true, true, false);

        ProfessionalUpdateResult result = service.update(id,
                request("Dr. Asha Rao", "PSYCHOLOGIST", "B999", null, null, "asha@example.com", null), "Admin");

        assertThat(result.reReviewRequired()).isTrue();
        assertThat(p.getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(p.isVerified()).isFalse();
        assertThat(p.getSubmittedAt()).isEqualTo(NOW);
        assertThat(p.isActive()).isTrue(); // PUT never changes active
        ArgumentCaptor<VerificationLogEntry> entry = ArgumentCaptor.forClass(VerificationLogEntry.class);
        verify(log).save(entry.capture());
        assertThat(entry.getValue().getFromStatus()).isEqualTo("VERIFIED");
        assertThat(entry.getValue().getToStatus()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().getActor()).isEqualTo("Admin");
        assertThat(entry.getValue().getNote()).contains("registrationNumber");
    }

    @Test
    void nonIdentityChangeOrUnverifiedListingNeedsNoReReview() {
        Professional verified = existing(VerificationStatus.VERIFIED, true, true, false);
        ProfessionalUpdateResult r1 = service.update(id,
                request("Dr. Asha Rao", "PSYCHOLOGIST", "A123", 200, null, "new@example.com", null), "Admin");
        assertThat(r1.reReviewRequired()).isFalse();
        assertThat(verified.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);

        Professional draft = existing(VerificationStatus.DRAFT, false, false, true);
        ProfessionalUpdateResult r2 = service.update(id,
                request("Someone Else", "COUNSELOR", "Z1", null, null, "a@b.co", null), "Admin");
        assertThat(r2.reReviewRequired()).isFalse();
        assertThat(draft.getVerificationStatus()).isEqualTo(VerificationStatus.DRAFT);
        assertThat(draft.isSampleData()).isTrue(); // kept when omitted
        verify(log, never()).save(any());
    }

    @Test
    void weeklyHoursUseTheSharedRulesAreKeptWhenOmittedAndNeverTriggerReReview() {
        Professional p = existing(VerificationStatus.VERIFIED, true, true, false);

        ProfessionalUpdateResult result = service.update(id,
                withHours("{\"TUE\":[{\"start\":\"18:00\",\"end\":\"20:00\"}]}", 60, 10), "Admin");
        assertThat(result.reReviewRequired()).isFalse();
        assertThat(p.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(result.professional().weeklyHours()).containsOnlyKeys("TUE");
        assertThat(result.professional().sessionMinutes()).isEqualTo(60);
        assertThat(result.professional().bufferMinutes()).isEqualTo(10);

        // A request without them (as the current admin form sends) keeps what is stored.
        service.update(id, valid(), "Admin");
        assertThat(p.getWeeklyHours()).containsOnlyKeys("TUE");
        assertThat(p.getSessionMinutes()).isEqualTo(60);
        assertThat(p.getBufferMinutes()).isEqualTo(10);

        assertThatThrownBy(() -> service.update(id,
                withHours("{\"MON\":[{\"start\":\"09:00\",\"end\":\"09:30\"}]}", 45, null), "Admin"))
                .isInstanceOf(ApiValidationException.class)
                .hasMessage("weeklyHours: Monday 09:00 to 09:30 is shorter than one 45-minute session.");
        assertThatThrownBy(() -> service.update(id, withHours(null, null, 7), "Admin"))
                .hasMessage("bufferMinutes must be 0, 5, 10 or 15.");
        verify(log, never()).save(any());
    }

    @Test
    void enablingRequiresVerifiedOrSampleData() {
        existing(VerificationStatus.DRAFT, false, false, false);
        assertThatThrownBy(() -> service.setActive(id, true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("verification status is DRAFT");

        Professional sample = existing(VerificationStatus.DRAFT, false, false, true);
        assertThat(service.setActive(id, true).active()).isTrue();
        assertThat(sample.isActive()).isTrue();

        existing(VerificationStatus.VERIFIED, true, false, false);
        assertThat(service.setActive(id, true).active()).isTrue();
    }

    @Test
    void aProfessionalWithAnAccountIsNeverSampleData() {
        when(accounts.existsByProfessionalId(id)).thenReturn(true);
        Professional p = existing(VerificationStatus.DRAFT, false, false, false);
        assertThatThrownBy(() -> service.update(id,
                request("Dr. Asha Rao", "PSYCHOLOGIST", "A123", 200, null, "asha@example.com", true), "Admin"))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageStartingWith("isSampleData cannot be true for a professional who has a professional account.");
        assertThat(p.isSampleData()).isFalse();
        assertThat(service.update(id, request("Dr. Asha Rao", "PSYCHOLOGIST", "A123", 200, null, "asha@example.com", false),
                "Admin").professional().isSampleData()).isFalse();

        // An old sample flag on an account's row does not let it be enabled without verification.
        existing(VerificationStatus.DRAFT, false, false, true);
        assertThatThrownBy(() -> service.setActive(id, true)).isInstanceOf(ConflictException.class);
    }

    @Test
    void disablingAlwaysWorks() {
        existing(VerificationStatus.REJECTED, false, true, false);
        assertThat(service.setActive(id, false).active()).isFalse();
        assertThatThrownBy(() -> service.setActive(id, null)).isInstanceOf(ApiValidationException.class);
    }

    @Test
    void missingIdIsNotFound() {
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ProfessionalNotFoundException.class);
    }

    @Test
    void listRejectsUnknownFiltersAndBadPaging() {
        assertThatThrownBy(() -> service.list("APPROVED", null, null, null, 0, 20))
                .hasMessageStartingWith("verificationStatus must be one of DRAFT, SUBMITTED, VERIFIED");
        assertThatThrownBy(() -> service.list(null, null, null, null, 0, 101))
                .hasMessage("size must be between 1 and 100.");
        assertThatThrownBy(() -> service.list(null, null, null, null, -1, 20))
                .hasMessage("page must be 0 or greater.");
    }
}
