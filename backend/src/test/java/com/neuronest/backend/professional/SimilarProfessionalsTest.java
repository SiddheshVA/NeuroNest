package com.neuronest.backend.professional;

import com.neuronest.backend.booking.SlotService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SimilarProfessionalsTest {

    // Monday 12 Oct 2026, 10:00 in India.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T04:30:00Z"), AvailabilityService.ZONE);

    private final PublicProfessionalRepository repository = mock(PublicProfessionalRepository.class);
    private final PublicProfessionalService service = new PublicProfessionalService(repository, mock(SlotService.class), CLOCK);

    private static Professional listing(String name, boolean bookable) {
        Professional p = new Professional();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setFullName(name);
        p.setCredentialType(CredentialType.PSYCHOLOGIST);
        p.setCity("Pune");
        p.setVerificationStatus(VerificationStatus.VERIFIED);
        p.setVerified(true);
        p.setActive(true);
        p.setWeeklyHours(bookable ? Map.of("WED", List.of(Map.of("start", "18:00", "end", "20:00"))) : Map.of());
        return p;
    }

    @Test
    void onlyBookableListingsOfTheSameTypeAreSuggestedAtMostTheLimit() {
        Professional asked = listing("Dr. Asha Rao", true);
        Professional notBookable = listing("Dr. No Hours", false);
        List<Professional> bookable = List.of(listing("A", true), listing("B", true), listing("C", true), listing("D", true));
        when(repository.findSimilarCandidates(eq(LocalDate.of(2026, 10, 12)), eq("PSYCHOLOGIST"), eq("Pune"), eq(asked.getId())))
                .thenReturn(List.of(notBookable, bookable.get(0), bookable.get(1), bookable.get(2), bookable.get(3)));

        List<Professional> similar = service.similarBookable(asked, 3);

        assertThat(similar).extracting(Professional::getFullName).containsExactly("A", "B", "C");
        assertThat(service.similarBookable(asked, 0)).isEmpty();
    }
}
