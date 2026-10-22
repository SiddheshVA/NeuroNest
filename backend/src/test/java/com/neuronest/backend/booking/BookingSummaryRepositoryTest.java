package com.neuronest.backend.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The user summary queries against a real PostgreSQL (tables built from the entities): only the user's own
// bookings, and only open requests that have not expired. Skipped when Docker is not available.
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class BookingSummaryRepositoryTest {

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

    @Autowired
    private BookingRepository bookings;

    private final UUID me = UUID.randomUUID();
    private final UUID someoneElse = UUID.randomUUID();
    // A professional who is also a user: requests made to their listing are not their own bookings.
    private final UUID professionalAsUser = UUID.randomUUID();
    private final UUID theirListing = UUID.randomUUID();

    private Booking booking(UUID userId, UUID professionalId, BookingStatus status, Instant scheduled, Instant expires) {
        Booking b = new Booking();
        b.setProfessionalId(professionalId);
        b.setUserId(userId);
        b.setContactName("Ravi Kumar");
        b.setContactEmail("user@example.com");
        b.setStatus(status);
        b.setPreferredDatetime(scheduled);
        b.setExpiresAt(expires);
        return bookings.saveAndFlush(b);
    }

    @Test
    void theCountsAndTheNextAppointmentAreTheUsersOwnAndLeaveOutExpiredRequests() {
        UUID p = UUID.randomUUID();
        booking(me, p, BookingStatus.PENDING, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofHours(10)));
        booking(me, p, BookingStatus.PENDING, NOW.plus(Duration.ofDays(3)), NOW.plus(Duration.ofHours(30)));
        booking(me, p, BookingStatus.PENDING, NOW.plus(Duration.ofDays(1)), NOW.minus(Duration.ofMinutes(1)));
        booking(me, p, BookingStatus.ALTERNATE_PROPOSED, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofHours(5)));
        booking(me, p, BookingStatus.ALTERNATE_PROPOSED, NOW.plus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(5)));
        Booking later = booking(me, p, BookingStatus.CONFIRMED, NOW.plus(Duration.ofDays(5)), null);
        Booking soonest = booking(me, p, BookingStatus.CONFIRMED, NOW.plus(Duration.ofHours(3)), null);
        booking(me, p, BookingStatus.CONFIRMED, NOW.minus(Duration.ofHours(3)), null);
        booking(me, p, BookingStatus.CANCELLED, NOW.plus(Duration.ofHours(1)), null);
        booking(someoneElse, p, BookingStatus.CONFIRMED, NOW.plus(Duration.ofHours(1)), null);
        booking(someoneElse, p, BookingStatus.PENDING, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofHours(10)));

        assertThat(bookings.countByUserIdAndStatusAndExpiresAtAfter(me, BookingStatus.PENDING, NOW)).isEqualTo(2);
        assertThat(bookings.countByUserIdAndStatusAndExpiresAtAfter(me, BookingStatus.ALTERNATE_PROPOSED, NOW)).isEqualTo(1);
        assertThat(bookings.findFirstByUserIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                me, BookingStatus.CONFIRMED, NOW)).get().extracting(Booking::getId).isEqualTo(soonest.getId());
        assertThat(later.getId()).isNotEqualTo(soonest.getId());
        assertThat(bookings.findFirstByUserIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                UUID.randomUUID(), BookingStatus.CONFIRMED, NOW)).isEmpty();
    }

    @Test
    void requestsToAProfessionalsListingAreNotTheirOwnBookings() {
        booking(someoneElse, theirListing, BookingStatus.PENDING, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofHours(10)));
        booking(someoneElse, theirListing, BookingStatus.CONFIRMED, NOW.plus(Duration.ofDays(1)), null);

        assertThat(bookings.countByUserIdAndStatusAndExpiresAtAfter(professionalAsUser, BookingStatus.PENDING, NOW)).isZero();
        assertThat(bookings.findFirstByUserIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                professionalAsUser, BookingStatus.CONFIRMED, NOW)).isEmpty();
    }
}
