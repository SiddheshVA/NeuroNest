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
    private final PublicProfessionalService service = new PublicProfessionalService(repository,
            new com.neuronest.backend.booking.SlotService(new AvailabilityService(),
                    mock(com.neuronest.backend.booking.BookingRepository.class),
                    mock(ProfessionalDayOffRepository.class)), CLOCK);

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
        assertThat(components).contains("weeklyHours", "sessionMinutes", "bookable");
        assertThat(components).doesNotContain("bufferMinutes", "dateOfBirth", "socialLinks", "contactEmail",
                "contactPhone", "registrationNumber",
                "registrationCouncil", "verificationNotes", "verifiedBy", "verifiedAt", "verificationStatus",
                "verificationSource", "active", "verified", "createdAt", "updatedAt");
        assertThat(PublicProfessional.from(professional(CredentialType.COUNSELOR, false)).toString())
                .doesNotContain("private@example.com", "99999", "SECRET-123");
    }

    @Test
    void noPublicResponseContainsTheProfessionalsContactDetails() {
        Professional p = professional(CredentialType.PSYCHOLOGIST, false);
        UUID id = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", id);
        p.setContactEmail("asha.private@example.com");
        p.setContactPhone("+919812345678");
        p.setCity("Pune");
        p.setLanguages(List.of("English"));
        when(repository.findVisibleById(eq(id), any())).thenReturn(Optional.of(p));
        when(repository.search(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 12), 1));

        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (Object response : List.of(service.get(id.toString()), service.search(null, null, null, null, null, 0, 12),
                service.availability(id.toString(), null, null))) {
            String json = mapper.writeValueAsString(response);
            assertThat(json).doesNotContain("asha.private@example.com", "9812345678", "contactEmail", "contactPhone");
        }
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

    // ---- availability ----

    private Professional withHours() {
        Professional p = professional(CredentialType.PSYCHOLOGIST, false);
        p.setSessionMinutes(60);
        p.setBufferMinutes(0);
        // 13 Oct 2026 (today in India) is a Tuesday.
        p.setWeeklyHours(java.util.Map.of("WED", List.of(java.util.Map.of("start", "18:00", "end", "20:00"))));
        return p;
    }

    @Test
    void availabilityDefaultsToTwoWeeksAndListsOnlyDaysWithSlots() {
        UUID id = UUID.randomUUID();
        when(repository.findVisibleById(id, TODAY_IST)).thenReturn(Optional.of(withHours()));

        PublicProfessionalDtos.PublicAvailability a = service.availability(id.toString(), null, null);

        assertThat(a.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(a.sessionMinutes()).isEqualTo(60);
        // The buffer between sessions is private: it is not part of the public availability.
        assertThat(java.util.Arrays.stream(PublicProfessionalDtos.PublicAvailability.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("bufferMinutes");
        // 13 to 27 Oct: Wednesdays 14, 21 (the 28th is outside the default range).
        assertThat(a.days()).extracting(PublicProfessionalDtos.AvailabilityDay::date)
                .containsExactly(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 21));
        assertThat(a.days().get(0).slots()).containsExactly("2026-10-14T18:00:00+05:30", "2026-10-14T19:00:00+05:30");
    }

    @Test
    void availabilityRejectsBadRangesAndHidesMissingListings() {
        UUID id = UUID.randomUUID();
        when(repository.findVisibleById(id, TODAY_IST)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.availability(id.toString(), null, null))
                .isInstanceOf(PublicProfessionalNotFoundException.class);
        assertThatThrownBy(() -> service.availability("not-a-uuid", null, null))
                .isInstanceOf(PublicProfessionalNotFoundException.class);

        assertThatThrownBy(() -> service.availability(id.toString(), "2026-10-13", "2026-11-14"))
                .isInstanceOf(ApiValidationException.class).hasMessage("The date range can be at most 31 days long.");
        assertThatThrownBy(() -> service.availability(id.toString(), "2026-10-12", "2026-10-20"))
                .hasMessageStartingWith("The dates must be between 2026-10-13 and 2026-12-12");
        assertThatThrownBy(() -> service.availability(id.toString(), "2026-12-01", "2026-12-13"))
                .hasMessageStartingWith("The dates must be between 2026-10-13 and 2026-12-12");
        assertThatThrownBy(() -> service.availability(id.toString(), "13/10/2026", null))
                .hasMessage("from must be a date in YYYY-MM-DD format.");
        assertThatThrownBy(() -> service.availability(id.toString(), "2026-10-20", "2026-10-19"))
                .hasMessage("to must be on or after from.");
    }

    @Test
    void onlyARealActiveVerifiedListingWithHoursIsBookable() {
        Professional p = withHours();
        p.setVerificationStatus(VerificationStatus.VERIFIED);
        p.setVerified(true);
        p.setActive(true);
        assertThat(PublicProfessional.from(p).bookable()).isTrue();

        p.setSessionMinutes(null);
        assertThat(PublicProfessional.from(p).bookable()).isFalse();
        p.setSessionMinutes(60);
        p.setWeeklyHours(java.util.Map.of());
        assertThat(PublicProfessional.from(p).bookable()).isFalse();
        p.setWeeklyHours(withHours().getWeeklyHours());
        p.setSampleData(true);
        assertThat(PublicProfessional.from(p).bookable()).isFalse();
    }
}
