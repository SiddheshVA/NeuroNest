package com.neuronest.backend.professional;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.professional.PublicProfessionalDtos.PublicProfessional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PublicProfessionalServiceTest {

    // 20:00 UTC on 12 Oct is already 13 Oct in India.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T20:00:00Z"),
            PublicProfessionalService.DIRECTORY_ZONE);
    private static final LocalDate TODAY_IST = LocalDate.of(2026, 10, 13);

    private final PublicProfessionalRepository repository = mock(PublicProfessionalRepository.class);
    private final PublicProfessionalService service = new PublicProfessionalService(repository, CLOCK);

    private static Professional professional(CredentialType type, boolean sample) {
        Professional p = new Professional();
        p.setFullName("Dr. Test");
        p.setCredentialType(type);
        p.setConsultationMode(ConsultationMode.ONLINE);
        p.setContactEmail("private@example.com");
        p.setContactPhone("+91 99999 99999");
        p.setRegistrationNumber("SECRET-123");
        p.setSampleData(sample);
        return p;
    }

    @Test
    void credentialsLabelFollowsSampleThenType() {
        assertThat(PublicProfessional.from(professional(CredentialType.PSYCHIATRIST, true)).credentialsLabel())
                .isEqualTo("Sample listing");
        assertThat(PublicProfessional.from(professional(CredentialType.COUNSELOR, false)).credentialsLabel())
                .isEqualTo("Credentials reviewed by admin");
        assertThat(PublicProfessional.from(professional(CredentialType.PSYCHOLOGIST, false)).credentialsLabel())
                .isEqualTo("Registration verified");
        assertThat(PublicProfessional.from(professional(CredentialType.PSYCHIATRIST, false)).credentialsLabel())
                .isEqualTo("Registration verified");
    }

    @Test
    void publicDtoHasNoPrivateFields() {
        List<String> components = java.util.Arrays.stream(PublicProfessional.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(components).doesNotContain("contactEmail", "contactPhone", "registrationNumber",
                "registrationCouncil", "verificationNotes", "verifiedBy", "verifiedAt", "verificationStatus",
                "verificationSource", "active", "verified", "createdAt", "updatedAt");
        assertThat(PublicProfessional.from(professional(CredentialType.COUNSELOR, false)).toString())
                .doesNotContain("private@example.com", "99999", "SECRET-123");
    }

    @Test
    void searchNormalisesFiltersAndUsesTodayInIndia() {
        Page<Professional> empty = new PageImpl<>(List.of(), PageRequest.of(0, 12), 0);
        when(repository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(empty);

        service.search(" counselor ", " Pune ", " hindi ", "online", " 50%_off ", 0, 12);

        verify(repository).search(eq(TODAY_IST), eq("COUNSELOR"), eq("Pune"), eq("ONLINE"), eq("hindi"),
                eq("%50\\%\\_off%"), eq(PageRequest.of(0, 12)));
    }

    @Test
    void rejectsBadFiltersWithReadableMessages() {
        assertThatThrownBy(() -> service.search("THERAPIST", null, null, null, null, 0, 12))
                .isInstanceOf(ApiValidationException.class)
                .hasMessage("credentialType must be one of PSYCHOLOGIST, PSYCHIATRIST, COUNSELOR (got 'THERAPIST').");
        assertThatThrownBy(() -> service.search(null, null, null, "PHONE", null, 0, 12))
                .hasMessage("mode must be one of ONLINE, IN_PERSON (got 'PHONE').");
        assertThatThrownBy(() -> service.search(null, null, null, "both", null, 0, 12))
                .hasMessageStartingWith("mode must be one of ONLINE, IN_PERSON (got 'BOTH').");
        assertThatThrownBy(() -> service.search(null, null, null, null, null, 0, 51))
                .hasMessage("size must be between 1 and 50.");
        assertThatThrownBy(() -> service.search(null, null, null, null, null, -1, 12))
                .hasMessage("page must be 0 or greater.");
        verifyNoInteractions(repository);
    }

    @Test
    void missingHiddenAndMalformedIdsAllGiveTheSameNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findVisibleById(id, TODAY_IST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id.toString()))
                .isInstanceOf(PublicProfessionalNotFoundException.class).hasMessage("Professional not found.");
        assertThatThrownBy(() -> service.get("not-a-uuid"))
                .isInstanceOf(PublicProfessionalNotFoundException.class).hasMessage("Professional not found.");
    }
}
