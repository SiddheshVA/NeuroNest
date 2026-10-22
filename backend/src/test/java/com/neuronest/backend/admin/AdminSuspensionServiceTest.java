package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.StatusNoteRequest;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.professional.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminSuspensionServiceTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T04:30:00Z"), AdminVerificationService.ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final StatusNoteRequest NOTE = new StatusNoteRequest("Complaint under investigation.");

    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final ProfessionalAccountRepository accounts = mock(ProfessionalAccountRepository.class);
    private final VerificationLogRepository verificationLog = mock(VerificationLogRepository.class);
    private final AdminSuspensionService service = new AdminSuspensionService(professionals, accounts, verificationLog, CLOCK);

    private final UUID id = UUID.randomUUID();
    private Professional professional;
    private ProfessionalAccount account;

    @BeforeEach
    void setUp() {
        professional = new Professional();
        ReflectionTestUtils.setField(professional, "id", id);
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(CredentialType.PSYCHOLOGIST);
        professional.setConsultationMode(ConsultationMode.ONLINE);
        professional.setContactEmail("asha@example.com");
        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        professional.setVerified(true);
        professional.setActive(true);
        account = new ProfessionalAccount(id, "asha@example.com", "pro_clerk");
        when(professionals.findByIdForUpdate(id)).thenReturn(Optional.of(professional));
        when(professionals.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accounts.findAllByProfessionalId(id)).thenReturn(List.of(account));
    }

    private VerificationLogEntry lastLog() {
        ArgumentCaptor<VerificationLogEntry> entry = ArgumentCaptor.forClass(VerificationLogEntry.class);
        verify(verificationLog, atLeastOnce()).save(entry.capture());
        return entry.getValue();
    }

    @Test
    void suspendDisablesTheAccountHidesTheListingAndWritesTheLog() {
        service.suspend(id, NOTE);

        assertThat(professional.getVerificationStatus()).isEqualTo(VerificationStatus.SUSPENDED);
        assertThat(professional.isVerified()).isFalse();
        assertThat(professional.isActive()).isFalse();
        assertThat(professional.getVerificationNotes()).isEqualTo("Complaint under investigation.");
        assertThat(account.getStatus()).isEqualTo(ProfessionalAccountStatus.DISABLED);
        verify(accounts).saveAndFlush(account);
        VerificationLogEntry log = lastLog();
        assertThat(ReflectionTestUtils.getField(log, "action")).isEqualTo("SUSPENDED");
        assertThat(ReflectionTestUtils.getField(log, "fromStatus")).isEqualTo("VERIFIED");
        assertThat(ReflectionTestUtils.getField(log, "toStatus")).isEqualTo("SUSPENDED");
        assertThat(ReflectionTestUtils.getField(log, "actor")).isEqualTo("ADMIN");
        assertThat(ReflectionTestUtils.getField(log, "note")).isEqualTo("Complaint under investigation.");
    }

    @Test
    void onlyAVerifiedProfessionalCanBeSuspendedAndTheNoteIsRequired() {
        professional.setVerificationStatus(VerificationStatus.SUBMITTED);
        assertThatThrownBy(() -> service.suspend(id, NOTE)).isInstanceOf(ConflictException.class);
        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        assertThatThrownBy(() -> service.suspend(id, new StatusNoteRequest("short"))).isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> service.suspend(id, null)).isInstanceOf(ApiValidationException.class);
        assertThatThrownBy(() -> service.suspend(UUID.randomUUID(), NOTE)).isInstanceOf(ProfessionalNotFoundException.class);
        verifyNoInteractions(verificationLog);
        assertThat(account.getStatus()).isEqualTo(ProfessionalAccountStatus.ACTIVE);
    }

    @Test
    void reinstateRestoresTheListingAndTheAccount() {
        service.suspend(id, NOTE);
        professional.setRegistrationExpiry(TODAY);

        service.reinstate(id, new StatusNoteRequest("Investigation closed, no issue found."));

        assertThat(professional.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(professional.isVerified()).isTrue();
        assertThat(professional.isActive()).isTrue();
        assertThat(professional.getVerificationNotes()).isEqualTo("Investigation closed, no issue found.");
        assertThat(account.getStatus()).isEqualTo(ProfessionalAccountStatus.ACTIVE);
        VerificationLogEntry log = lastLog();
        assertThat(ReflectionTestUtils.getField(log, "action")).isEqualTo("REINSTATED");
        assertThat(ReflectionTestUtils.getField(log, "fromStatus")).isEqualTo("SUSPENDED");
        assertThat(ReflectionTestUtils.getField(log, "toStatus")).isEqualTo("VERIFIED");
        assertThat(ReflectionTestUtils.getField(log, "actor")).isEqualTo("ADMIN");
        verify(verificationLog, times(2)).save(any());
    }

    @Test
    void reinstateIsRefusedWithAnExpiredRegistrationOrWhenNotSuspended() {
        assertThatThrownBy(() -> service.reinstate(id, NOTE)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only a suspended professional");
        service.suspend(id, NOTE);
        professional.setRegistrationExpiry(TODAY.minusDays(1));

        assertThatThrownBy(() -> service.reinstate(id, NOTE)).isInstanceOf(ConflictException.class)
                .hasMessage("The registration has expired. The professional must update it and be reviewed again.");
        assertThat(professional.getVerificationStatus()).isEqualTo(VerificationStatus.SUSPENDED);
        assertThat(account.getStatus()).isEqualTo(ProfessionalAccountStatus.DISABLED);
        verify(verificationLog, times(1)).save(any());
    }
}
