package com.neuronest.backend.payment;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

// One Razorpay order for a booking (V10). The amount is in paise and is copied from the professional's
// fee when the order is made, never taken from a request.
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "razorpay_order_id", nullable = false, updatable = false, unique = true)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    // Set when it is marked PAID (V13): the percent used, NeuroNest's commission and the professional's
    // share, which add up exactly to amount_paise.
    @Column(name = "commission_percent")
    private Integer commissionPercent;

    @Column(name = "commission_paise")
    private Long commissionPaise;

    @Column(name = "professional_share_paise")
    private Long professionalSharePaise;

    protected Payment() {}

    public Payment(UUID bookingId, String razorpayOrderId, long amountPaise, String currency, Instant createdAt) {
        this.bookingId = bookingId;
        this.razorpayOrderId = razorpayOrderId;
        this.amountPaise = amountPaise;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }

    public UUID getBookingId() { return bookingId; }

    public String getRazorpayOrderId() { return razorpayOrderId; }

    public String getRazorpayPaymentId() { return razorpayPaymentId; }

    public long getAmountPaise() { return amountPaise; }

    public String getCurrency() { return currency; }

    public PaymentStatus getStatus() { return status; }

    /** CREATED or FAILED only: a payment becomes PAID through markPaid, which also stores the commission. */
    public void setStatus(PaymentStatus status) {
        if (status == PaymentStatus.PAID) throw new IllegalStateException("Use markPaid to mark a payment PAID.");
        this.status = status;
    }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getPaidAt() { return paidAt; }

    public Integer getCommissionPercent() { return commissionPercent; }
    public Long getCommissionPaise() { return commissionPaise; }
    public Long getProfessionalSharePaise() { return professionalSharePaise; }

    /**
     * The only way a payment becomes PAID: with Razorpay's payment id, the time, and the commission split
     * (from CommissionSplit), which is stored and never recomputed.
     */
    public void markPaid(String razorpayPaymentId, Instant paidAt, int commissionPercent, long commissionPaise,
                         long professionalSharePaise) {
        if (commissionPaise + professionalSharePaise != amountPaise) {
            throw new IllegalStateException("The commission and the professional's share must add up to the amount.");
        }
        this.status = PaymentStatus.PAID;
        this.razorpayPaymentId = razorpayPaymentId;
        this.paidAt = paidAt;
        this.commissionPercent = commissionPercent;
        this.commissionPaise = commissionPaise;
        this.professionalSharePaise = professionalSharePaise;
    }
}
