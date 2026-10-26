-- Automatic Google Meet links for confirmed video sessions: the Google Calendar event behind the link
-- (deleted again when the booking is cancelled), and a flag for a booking whose link could not be created
-- after the retries, so the professional adds one by hand.
ALTER TABLE booking_requests
    ADD COLUMN google_event_id            text    NULL,
    ADD COLUMN manual_meeting_link_needed boolean NOT NULL DEFAULT false;
