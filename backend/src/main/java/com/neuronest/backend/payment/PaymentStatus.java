package com.neuronest.backend.payment;

// payments.status (the table's CHECK constraint lists exactly these).
public enum PaymentStatus {
    CREATED, PAID, FAILED
}
