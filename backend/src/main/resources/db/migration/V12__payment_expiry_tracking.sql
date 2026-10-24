-- booking_requests: when the background jobs expired an agreed booking that was not paid. Recorded here
-- instead of in responded_at / responded_by, so the professional's own response (who accepted, and when)
-- is kept for the admin's response metrics.
ALTER TABLE booking_requests
    ADD COLUMN payment_expired_at timestamptz NULL;
