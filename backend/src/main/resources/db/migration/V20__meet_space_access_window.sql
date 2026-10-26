-- Google Meet spaces (the Meet REST API) instead of Calendar events: the space behind an automatic video
-- link (meeting_link keeps its meetingUri), and its time window. A space is created RESTRICTED (nobody can
-- enter), set OPEN shortly before the session (anyone with the link joins without knocking) and RESTRICTED
-- again once it has ended. The old google_event_id column stays, unused.
ALTER TABLE booking_requests
    ADD COLUMN meeting_space_name    text    NULL,
    ADD COLUMN meeting_access_open   boolean NOT NULL DEFAULT false,
    ADD COLUMN meeting_access_closed boolean NOT NULL DEFAULT false;

-- What the access job looks for every minute: spaces to open, and open spaces to close.
CREATE INDEX booking_requests_meeting_space_due
    ON booking_requests (ends_at)
    WHERE meeting_space_name IS NOT NULL AND NOT meeting_access_closed;
