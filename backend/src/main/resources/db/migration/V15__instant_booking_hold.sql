-- Instant booking: the client picks one or more consecutive sessions, the booking is created as
-- AWAITING_PAYMENT with a 10-minute hold, and it becomes CONFIRMED when the payment is verified (by the
-- checkout callback or the Razorpay webhook). The professional no longer approves requests.

-- 1. How many consecutive sessions a booking covers, and when the time it occupies ends (the last
--    session's end plus the professional's buffer). Older rows count as one session.
ALTER TABLE booking_requests
    ADD COLUMN session_count integer NOT NULL DEFAULT 1,
    ADD COLUMN ends_at       timestamptz NULL;

ALTER TABLE booking_requests
    ADD CONSTRAINT booking_requests_session_count_check CHECK (session_count BETWEEN 1 AND 4),
    ADD CONSTRAINT booking_requests_ends_at_check CHECK (ends_at IS NULL OR ends_at > preferred_datetime);

-- 2. Holds from before this change: an agreed booking waiting for payment for more than 10 minutes is
--    released now (no email; it is marked as notified). A newer one keeps at most 10 minutes from when it
--    was agreed.
UPDATE booking_requests
   SET status = 'EXPIRED', payment_expired_at = now(), expiry_notified_at = now(), updated_at = now()
 WHERE status = 'AWAITING_PAYMENT'
   AND coalesce(responded_at, updated_at, created_at) < now() - interval '10 minutes';

UPDATE booking_requests
   SET payment_due_at = least(payment_due_at, coalesce(responded_at, updated_at, created_at) + interval '10 minutes'),
       updated_at = now()
 WHERE status = 'AWAITING_PAYMENT';

-- 3. Requests still waiting for the professional's approval are closed, since that step no longer
--    exists (no email; marked as notified). The client can book an open slot directly instead.
UPDATE booking_requests
   SET status = 'EXPIRED', responded_at = now(), responded_by = NULL, expiry_notified_at = now(), updated_at = now()
 WHERE status IN ('PENDING', 'ALTERNATE_PROPOSED');

-- 4. Double-booking guard in the database: two AWAITING_PAYMENT or CONFIRMED bookings of the same
--    professional can never cover overlapping time. Rows without ends_at (older ones) are not covered
--    here; the application's own check still applies to them.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE booking_requests
    ADD CONSTRAINT booking_requests_no_overlap
        EXCLUDE USING gist (professional_id WITH =, tstzrange(preferred_datetime, ends_at, '[)') WITH &&)
        WHERE (status IN ('AWAITING_PAYMENT', 'CONFIRMED') AND preferred_datetime IS NOT NULL AND ends_at IS NOT NULL);

-- Upcoming older bookings get their end time one at a time, oldest first, so they are covered too. One
-- that would overlap an earlier booking is left without ends_at (and logged) instead of failing the
-- migration; nothing about the booking itself changes.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT b.id, b.preferred_datetime + make_interval(mins => p.session_minutes + p.buffer_minutes) AS ends
          FROM booking_requests b
          JOIN professionals p ON p.id = b.professional_id
         WHERE b.status IN ('AWAITING_PAYMENT', 'CONFIRMED')
           AND b.preferred_datetime > now()
           AND (b.proposed_datetime IS NULL OR b.proposed_datetime = b.preferred_datetime)
           AND p.session_minutes IS NOT NULL
         ORDER BY b.created_at, b.id
    LOOP
        BEGIN
            UPDATE booking_requests SET ends_at = r.ends WHERE id = r.id;
        EXCEPTION WHEN exclusion_violation THEN
            RAISE NOTICE 'Booking % overlaps an earlier booking and was left without ends_at.', r.id;
        END;
    END LOOP;
END $$;

-- 5. Razorpay webhook deliveries already handled, by Razorpay's event id (x-razorpay-event-id), so a
--    repeated delivery does nothing twice.
CREATE TABLE razorpay_webhook_events (
    event_id    text PRIMARY KEY,
    event_type  text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
