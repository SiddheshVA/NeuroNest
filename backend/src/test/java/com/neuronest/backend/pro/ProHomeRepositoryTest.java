package com.neuronest.backend.pro;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.professional.ProfessionalDayOff;
import com.neuronest.backend.professional.ProfessionalDayOffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The new appointment, count and days-off queries against a real PostgreSQL (tables built from the
// entities). Skipped when Docker is not available on the machine.
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProHomeRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Autowired
    private BookingRepository bookings;
    @Autowired
    private ProfessionalDayOffRepository daysOff;

    private final UUID mine = UUID.randomUUID();
    private final UUID theirs = UUID.randomUUID();

    private Booking booking(UUID professionalId, BookingStatus status, Instant scheduled, Instant expires) {
        Booking b = new Booking();
        b.setProfessionalId(professionalId);
        b.setUserId(UUID.randomUUID());
        b.setContactName("Ravi Kumar");
        b.setContactEmail("user@example.com");
        b.setStatus(status);
        b.setPreferredDatetime(scheduled);
        b.setExpiresAt(expires);
        return bookings.saveAndFlush(b);
    }

    @Test
    void appointmentsAreOnlyThisProfessionalsConfirmedOnesInTheRightOrder() {
        Booking later = booking(mine, BookingStatus.CONFIRMED, NOW.plus(Duration.ofDays(5)), null);
        Booking soon = booking(mine, BookingStatus.CONFIRMED, NOW.plus(Duration.ofHours(2)), null);
        Booking yesterday = booking(mine, BookingStatus.CONFIRMED, NOW.minus(Duration.ofDays(1)), null);
        Booking lastMonth = booking(mine, BookingStatus.CONFIRMED, NOW.minus(Duration.ofDays(30)), null);
        booking(mine, BookingStatus.PENDING, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)));
        booking(mine, BookingStatus.CANCELLED, NOW.plus(Duration.ofDays(1)), null);
        booking(theirs, BookingStatus.CONFIRMED, NOW.plus(Duration.ofDays(1)), null);

        var upcoming = bookings.findByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(mine,
                BookingStatus.CONFIRMED, NOW, PageRequest.of(0, 20, Sort.by(Sort.Order.asc("preferredDatetime"))));
        var past = bookings.findByProfessionalIdAndStatusAndPreferredDatetimeLessThan(mine,
                BookingStatus.CONFIRMED, NOW, PageRequest.of(0, 20, Sort.by(Sort.Order.desc("preferredDatetime"))));

        assertThat(upcoming.getContent()).extracting(Booking::getId).containsExactly(soon.getId(), later.getId());
        assertThat(past.getContent()).extracting(Booking::getId).containsExactly(yesterday.getId(), lastMonth.getId());
        assertThat(bookings.countByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(mine, BookingStatus.CONFIRMED, NOW))
                .isEqualTo(2);
        assertThat(bookings.findFirstByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                mine, BookingStatus.CONFIRMED, NOW)).get().extracting(Booking::getId).isEqualTo(soon.getId());
    }

    @Test
    void bookingsOnARangeOfDatesAreCounted() {
        Instant start = Instant.parse("2026-10-13T18:30:00Z"); // 14 Oct 00:00 in India
        Instant end = Instant.parse("2026-10-15T18:30:00Z");   // 16 Oct 00:00 in India
        booking(mine, BookingStatus.CONFIRMED, Instant.parse("2026-10-14T12:30:00Z"), null);
        booking(mine, BookingStatus.CONFIRMED, Instant.parse("2026-10-15T18:29:00Z"), null);
        booking(mine, BookingStatus.CONFIRMED, end, null);
        booking(theirs, BookingStatus.CONFIRMED, Instant.parse("2026-10-14T12:30:00Z"), null);
        booking(mine, BookingStatus.PENDING, Instant.parse("2026-10-14T12:30:00Z"), NOW.plus(Duration.ofDays(2)));
        booking(mine, BookingStatus.ALTERNATE_PROPOSED, Instant.parse("2026-10-14T13:30:00Z"), NOW.plus(Duration.ofDays(2)));
        booking(mine, BookingStatus.PENDING, Instant.parse("2026-10-14T14:30:00Z"), NOW.minus(Duration.ofHours(1)));

        assertThat(bookings.countScheduledBetween(mine, Set.of(BookingStatus.CONFIRMED), start, end)).isEqualTo(2);
        assertThat(bookings.countOpenRequestedBetween(mine, ProHomeService.OPEN, NOW, start, end)).isEqualTo(2);
    }

    @Test
    void daysOffQueriesFindOverlapsAndCurrentEntriesOfThisProfessionalOnly() {
        ProfessionalDayOff past = daysOff.saveAndFlush(new ProfessionalDayOff(mine, TODAY.minusDays(10), TODAY.minusDays(5), null));
        ProfessionalDayOff endsToday = daysOff.saveAndFlush(new ProfessionalDayOff(mine, TODAY.minusDays(2), TODAY, null));
        ProfessionalDayOff later = daysOff.saveAndFlush(new ProfessionalDayOff(mine, TODAY.plusDays(10), TODAY.plusDays(12), "Leave"));
        daysOff.saveAndFlush(new ProfessionalDayOff(theirs, TODAY.plusDays(10), TODAY.plusDays(12), null));

        assertThat(daysOff.findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(mine, TODAY))
                .extracting(ProfessionalDayOff::getId).containsExactly(endsToday.getId(), later.getId());
        assertThat(daysOff.countByProfessionalIdAndDateToGreaterThanEqual(mine, TODAY)).isEqualTo(2);
        // Touching at either end counts as overlapping; the day after does not.
        assertThat(daysOff.findOverlapping(mine, TODAY.plusDays(12), TODAY.plusDays(20)))
                .extracting(ProfessionalDayOff::getId).containsExactly(later.getId());
        assertThat(daysOff.findOverlapping(mine, TODAY.plusDays(5), TODAY.plusDays(10)))
                .extracting(ProfessionalDayOff::getId).containsExactly(later.getId());
        assertThat(daysOff.findOverlapping(mine, TODAY.plusDays(13), TODAY.plusDays(20))).isEmpty();
        assertThat(daysOff.findByIdAndProfessionalId(later.getId(), theirs)).isEmpty();
        assertThat(daysOff.findByIdAndProfessionalId(later.getId(), mine)).isPresent();
        assertThat(past.getId()).isNotNull();
    }
}
