-- booking_requests: what the background jobs have already done for a request, so each email goes out
-- once: the 24-hour reminder to the professional, and the expiry email to the user.
ALTER TABLE booking_requests
    ADD COLUMN reminder_sent_at timestamptz NULL,
    ADD COLUMN expiry_notified_at timestamptz NULL;

-- The jobs look for open requests by status and deadline.
CREATE INDEX idx_booking_requests_status_expires_at ON booking_requests (status, expires_at);
