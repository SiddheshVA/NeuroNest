package com.neuronest.backend.review;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionalReviewRepository extends JpaRepository<ProfessionalReview, UUID> {

    Optional<ProfessionalReview> findByBookingId(UUID bookingId);

    @Query("select r.professionalId from ProfessionalReview r where r.id = :id")
    Optional<UUID> findProfessionalIdById(@Param("id") UUID id);

    /** A user's bookings in one status, latest scheduled first (read only; bookings are never changed here). */
    @Query("select b from Booking b where b.userId = :userId and b.status = :status order by b.preferredDatetime desc, b.id")
    List<Booking> findBookingsOfUser(@Param("userId") UUID userId, @Param("status") BookingStatus status);

    List<ProfessionalReview> findByBookingIdIn(Collection<UUID> bookingIds);

    /** Sort with the Pageable (newest first). */
    Page<ProfessionalReview> findByProfessionalIdAndStatus(UUID professionalId, ReviewStatus status, Pageable pageable);

    /** Every review of a professional, hidden ones included: the admin's list. */
    Page<ProfessionalReview> findByProfessionalId(UUID professionalId, Pageable pageable);

    /**
     * THE place professionals.rating_avg and rating_count are written: both recomputed from this
     * professional's VISIBLE reviews. Called in the same transaction as every review change, after the
     * professional row is locked (see ReviewService), so concurrent changes cannot leave stale numbers.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE professionals SET
              rating_avg = (SELECT round(avg(r.rating)::numeric, 2) FROM professional_reviews r
                            WHERE r.professional_id = :professionalId AND r.status = 'VISIBLE'),
              rating_count = (SELECT count(*) FROM professional_reviews r
                              WHERE r.professional_id = :professionalId AND r.status = 'VISIBLE')
            WHERE id = :professionalId""", nativeQuery = true)
    void refreshRating(@Param("professionalId") UUID professionalId);
}
