-- Razorpay payments for Find Professional bookings. A booking the professional confirms (or whose
-- suggested time the user accepts) now waits for payment first: AWAITING_PAYMENT until payment_due_at,
-- then CONFIRMED once the payment is verified.

ALTER TABLE booking_requests DROP CONSTRAINT booking_requests_status_check;
ALTER TABLE booking_requests ADD CONSTRAINT booking_requests_status_check
    CHECK (status IN ('PENDING','AWAITING_PAYMENT','CONFIRMED','DECLINED','ALTERNATE_PROPOSED','CANCELLED','EXPIRED'));

ALTER TABLE booking_requests
    ADD COLUMN payment_due_at timestamptz NULL;

-- One row per Razorpay order. amount_paise is copied from the professional's fee when the order is made.
CREATE TABLE payments (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id          uuid NOT NULL REFERENCES booking_requests(id),
    razorpay_order_id   text NOT NULL UNIQUE,
    razorpay_payment_id text NULL,
    amount_paise        bigint NOT NULL CHECK (amount_paise > 0),
    currency            text NOT NULL,
    status              text NOT NULL CHECK (status IN ('CREATED','PAID','FAILED')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    paid_at             timestamptz NULL
);
CREATE INDEX idx_payments_booking_id ON payments (booking_id);
CREATE UNIQUE INDEX payments_razorpay_payment_id_key
    ON payments (razorpay_payment_id) WHERE razorpay_payment_id IS NOT NULL;
