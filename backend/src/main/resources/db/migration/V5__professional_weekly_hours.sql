-- professionals: structured weekly hours for booking, alongside the free-text availability_note (kept).
-- weekly_hours: {"MON": [{"start": "09:00", "end": "12:00"}], ...} in Asia/Kolkata local time.
ALTER TABLE professionals
    ADD COLUMN weekly_hours    jsonb   NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN session_minutes integer NULL,
    ADD COLUMN buffer_minutes  integer NOT NULL DEFAULT 0;

ALTER TABLE professionals
    ADD CONSTRAINT professionals_session_minutes_check
        CHECK (session_minutes IN (30, 45, 60));

ALTER TABLE professionals
    ADD CONSTRAINT professionals_buffer_minutes_check
        CHECK (buffer_minutes IN (0, 5, 10, 15));
