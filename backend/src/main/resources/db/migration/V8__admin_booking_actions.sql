-- booking_requests: when an admin last reminded the professional about this request (at most once
-- every 12 hours).
ALTER TABLE booking_requests
    ADD COLUMN admin_nudged_at timestamptz NULL;
