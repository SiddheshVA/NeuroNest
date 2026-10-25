-- One session grid for every professional (bookings.session-minutes = 60, bookings.break-minutes = 15):
-- slots start every 75 minutes. A booking of N sessions is one meeting from preferred_datetime to ends_at
-- (N x 60 minutes, no breaks inside it), and it blocks the professional's time until blocked_until
-- (N x 75 minutes: the skipped breaks are added at the end). The no-overlap guard now uses blocked_until.

-- 1. Until when a booking blocks the professional's time.
ALTER TABLE booking_requests
    ADD COLUMN blocked_until timestamptz NULL;

-- 2. Existing bookings keep their stored times. Their ends_at already included the professional's buffer,
--    so it is exactly the time they block. Rows without ends_at are left as they were.
UPDATE booking_requests
   SET blocked_until = ends_at
 WHERE ends_at IS NOT NULL;

ALTER TABLE booking_requests
    ADD CONSTRAINT booking_requests_blocked_until_check
        CHECK (blocked_until IS NULL OR (ends_at IS NOT NULL AND blocked_until >= ends_at));

-- 3. The double-booking guard covers [preferred_datetime, blocked_until) instead of [preferred_datetime,
--    ends_at). For every existing row the two are the same span (step 2), so the swap cannot fail.
ALTER TABLE booking_requests
    DROP CONSTRAINT booking_requests_no_overlap;

ALTER TABLE booking_requests
    ADD CONSTRAINT booking_requests_no_overlap
        EXCLUDE USING gist (professional_id WITH =, tstzrange(preferred_datetime, blocked_until, '[)') WITH &&)
        WHERE (status IN ('AWAITING_PAYMENT', 'CONFIRMED') AND preferred_datetime IS NOT NULL
               AND blocked_until IS NOT NULL);
