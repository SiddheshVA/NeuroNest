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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * THE taken-slot query, used by the public availability endpoint, the booking check and the
     * professional's confirm, propose and accept checks (all through SlotService): the
     * scheduled times (see {@link Booking#scheduledTime()}) of this professional's CONFIRMED bookings in
     * [from, to). PENDING and ALTERNATE_PROPOSED requests never block a slot. The database narrows the
     * rows to the window; which rows count is decided here, in one place.
     */
    default List<Instant> findConfirmedScheduledTimes(UUID professionalId, Instant from, Instant to) {
        return findInWindow(professionalId, BookingStatus.CONFIRMED, from, to).stream()
                .map(Booking::scheduledTime)
                .filter(Objects::nonNull)
                .filter(time -> !time.isBefore(from) && time.isBefore(to))
                .toList();
    }

    // Candidates for findConfirmedScheduledTimes only; use that method instead.
    @Query("""
            select b from Booking b
            where b.professionalId = :professionalId and b.status = :status
              and ((b.proposedDatetime >= :fromTime and b.proposedDatetime < :toTime)
                or (b.preferredDatetime >= :fromTime and b.preferredDatetime < :toTime))""")
    List<Booking> findInWindow(@Param("professionalId") UUID professionalId, @Param("status") BookingStatus status,
                               @Param("fromTime") Instant from, @Param("toTime") Instant to);

    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);

    // Only the professional id, so the professional row can be locked before the booking is loaded.
    @Query("select b.professionalId from Booking b where b.id = :id and b.userId = :userId")
    Optional<UUID> findProfessionalIdByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<Booking> findByIdAndProfessionalId(UUID id, UUID professionalId);

    Page<Booking> findByProfessionalId(UUID professionalId, Pageable pageable);

    Page<Booking> findByProfessionalIdAndStatus(UUID professionalId, BookingStatus status, Pageable pageable);

    long countByProfessionalIdAndStatusAndExpiresAtAfter(UUID professionalId, BookingStatus status, Instant now);

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

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
