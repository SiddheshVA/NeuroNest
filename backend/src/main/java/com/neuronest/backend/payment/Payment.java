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
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }

    public long getAmountPaise() { return amountPaise; }

    public String getCurrency() { return currency; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
}
