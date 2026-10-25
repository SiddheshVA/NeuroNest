package com.neuronest.backend.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** A span of time a booking blocks, from its scheduled start to its blocked_until (breaks included). */
    record BookedRange(Instant start, Instant end) {
        public boolean overlaps(Instant otherStart, Instant otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    /** How far back a booking's start can be and still reach into a window (4 sessions of up to 2 hours). */
    Duration LONGEST_BOOKING = Duration.ofHours(8);

    /**
     * THE taken-time query for multi-session bookings: the spans of this professional's AWAITING_PAYMENT and
     * CONFIRMED bookings that reach into [from, to). A booking's span ends at blocked_until (ends_at for a
     * row from before V16 that somehow lacks it); an older one with neither blocks {@code defaultOccupied}.
     */
    default List<BookedRange> findBookedRanges(UUID professionalId, Instant from, Instant to,
                                               Duration defaultOccupied) {
        return findInWindow(professionalId, BookingStatus.BLOCKS_SLOT, from.minus(LONGEST_BOOKING), to).stream()
                .filter(b -> b.scheduledTime() != null)
                .map(b -> new BookedRange(b.scheduledTime(),
                        b.getBlockedUntil() != null ? b.getBlockedUntil()
                                : b.getEndsAt() != null ? b.getEndsAt() : b.scheduledTime().plus(defaultOccupied)))
                .filter(range -> range.overlaps(from, to))
                .toList();
    }

    // Candidates for findBookedRanges only; use that method instead. PENDING and ALTERNATE_PROPOSED
    // requests never block a slot.
    @Query("""
            select b from Booking b
            where b.professionalId = :professionalId and b.status in :statuses
              and ((b.proposedDatetime >= :fromTime and b.proposedDatetime < :toTime)
                or (b.preferredDatetime >= :fromTime and b.preferredDatetime < :toTime))""")
    List<Booking> findInWindow(@Param("professionalId") UUID professionalId,
                               @Param("statuses") Collection<BookingStatus> statuses,
                               @Param("fromTime") Instant from, @Param("toTime") Instant to);

    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);

    // Only the professional id, so the professional row can be locked before the booking is loaded.
    @Query("select b.professionalId from Booking b where b.id = :id and b.userId = :userId")
    Optional<UUID> findProfessionalIdByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<Booking> findByIdAndProfessionalId(UUID id, UUID professionalId);

    Page<Booking> findByProfessionalId(UUID professionalId, Pageable pageable);

    Page<Booking> findByProfessionalIdAndStatus(UUID professionalId, BookingStatus status, Pageable pageable);

    long countByProfessionalIdAndStatusAndExpiresAtAfter(UUID professionalId, BookingStatus status, Instant now);

    // ---- A professional's appointments (CONFIRMED; preferred_datetime is the scheduled time) ----

    /** Upcoming: scheduled now or later. Sort with the Pageable (soonest first). */
    Page<Booking> findByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(UUID professionalId,
                                                                                   BookingStatus status, Instant now,
                                                                                   Pageable pageable);

    /** Past: scheduled before now. Sort with the Pageable (newest first). */
    Page<Booking> findByProfessionalIdAndStatusAndPreferredDatetimeLessThan(UUID professionalId, BookingStatus status,
                                                                           Instant now, Pageable pageable);

    long countByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(UUID professionalId, BookingStatus status,
                                                                           Instant now);

    Optional<Booking> findFirstByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
            UUID professionalId, BookingStatus status, Instant now);

    /** Bookings in these statuses scheduled in [from, to). */
    @Query("""
            select count(b) from Booking b
            where b.professionalId = :professionalId and b.status in :statuses
              and b.preferredDatetime >= :fromTime and b.preferredDatetime < :toTime""")
    long countScheduledBetween(@Param("professionalId") UUID professionalId,
                               @Param("statuses") Collection<BookingStatus> statuses,
                               @Param("fromTime") Instant from, @Param("toTime") Instant to);

    /** Open requests (not expired at {@code now}) asking for a time in [from, to). */
    @Query("""
            select count(b) from Booking b
            where b.professionalId = :professionalId and b.status in :statuses and b.expiresAt > :now
              and b.preferredDatetime >= :fromTime and b.preferredDatetime < :toTime""")
    long countOpenRequestedBetween(@Param("professionalId") UUID professionalId,
                                   @Param("statuses") Collection<BookingStatus> statuses, @Param("now") Instant now,
                                   @Param("fromTime") Instant from, @Param("toTime") Instant to);

    // ---- A professional's agreed bookings waiting for the user's payment ----

    long countByProfessionalIdAndStatusAndPaymentDueAtAfter(UUID professionalId, BookingStatus status, Instant now);

    /** Earliest payment deadline first. */
    List<Booking> findTop20ByProfessionalIdAndStatusAndPaymentDueAtAfterOrderByPaymentDueAtAscIdAsc(UUID professionalId,
                                                                                                   BookingStatus status,
                                                                                                   Instant now);

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    // ---- The user's own summary ----

    /** Agreed bookings still waiting for payment whose deadline is after {@code now}, earliest deadline first. */
    List<Booking> findByUserIdAndStatusAndPaymentDueAtAfterOrderByPaymentDueAtAscIdAsc(UUID userId, BookingStatus status,
                                                                                    Instant now);

    long countByUserIdAndStatusAndExpiresAtAfter(UUID userId, BookingStatus status, Instant now);

    Optional<Booking> findFirstByUserIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
            UUID userId, BookingStatus status, Instant now);

    // Locks the row, so two quick cancels cannot both see an open status.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select count(b) from Booking b
            where b.userId = :userId and b.status in :statuses and b.expiresAt > :now""")
    long countOpenByUser(@Param("userId") UUID userId, @Param("statuses") Collection<BookingStatus> statuses,
                         @Param("now") Instant now);

    @Query("""
            select count(b) from Booking b
            where b.userId = :userId and b.professionalId = :professionalId
              and b.status in :statuses and b.expiresAt > :now""")
    long countOpenByUserAndProfessional(@Param("userId") UUID userId, @Param("professionalId") UUID professionalId,
                                        @Param("statuses") Collection<BookingStatus> statuses,
                                        @Param("now") Instant now);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /** Bookings this user is still holding (AWAITING_PAYMENT before its payment deadline). */
    long countByUserIdAndStatusAndPaymentDueAtAfter(UUID userId, BookingStatus status, Instant now);

    long countByUserIdAndProfessionalIdAndStatusAndPaymentDueAtAfter(UUID userId, UUID professionalId,
                                                                     BookingStatus status, Instant now);

    // Set once the professional's "new request" email has been sent. Only this column changes.
    @Modifying
    @Transactional
    @Query("update Booking b set b.professionalNotifiedAt = :at where b.id = :id")
    int markProfessionalNotified(@Param("id") UUID id, @Param("at") Instant at);

    // Set once the user's "your request expired" email has been sent. Only this column changes.
    @Modifying
    @Transactional
    @Query("update Booking b set b.expiryNotifiedAt = :at where b.id = :id")
    int markExpiryNotified(@Param("id") UUID id, @Param("at") Instant at);
}
