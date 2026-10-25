-- Notifications: the client's consent to updates on WhatsApp (asked on the booking form, used once that
-- channel is turned on), and whether the 10-minute session reminder has been sent. reminder_sent_at already
-- exists for a different reminder (the professional's nudge about an unanswered request).
ALTER TABLE booking_requests
    ADD COLUMN whatsapp_opt_in       boolean NOT NULL DEFAULT false,
    ADD COLUMN session_reminder_sent boolean NOT NULL DEFAULT false;

-- What the reminder job looks for every minute: confirmed sessions still waiting for their reminder.
CREATE INDEX booking_requests_session_reminder_due
    ON booking_requests (preferred_datetime)
    WHERE status = 'CONFIRMED' AND NOT session_reminder_sent;
