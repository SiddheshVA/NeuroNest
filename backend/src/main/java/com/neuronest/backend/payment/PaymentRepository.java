package com.neuronest.backend.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** The newest order for this booking in this status (an unpaid CREATED one is reused). */
    Optional<Payment> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(UUID bookingId, PaymentStatus status);

    // Without a lock: only to find which booking to lock first (the webhook).
    @Query("select p.bookingId from Payment p where p.razorpayOrderId = :orderId")
    Optional<UUID> findBookingIdByRazorpayOrderId(@Param("orderId") String orderId);

    // Locks the row, so two quick verify calls cannot both mark it paid.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.razorpayOrderId = :orderId")
    Optional<Payment> findByRazorpayOrderIdForUpdate(@Param("orderId") String orderId);
}
