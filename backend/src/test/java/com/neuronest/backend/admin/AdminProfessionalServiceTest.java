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
                "online", consultationFee, 5, email, null, null, null, sample);
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
