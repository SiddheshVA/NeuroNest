-- The background jobs look for unpaid bookings by status and payment deadline.
CREATE INDEX idx_booking_requests_status_payment_due_at ON booking_requests (status, payment_due_at);
