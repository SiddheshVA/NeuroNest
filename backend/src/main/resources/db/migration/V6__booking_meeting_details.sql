-- booking_requests: how the user would like to meet online, the meeting details the professional sets
-- once a request is confirmed, and an optional one-time snapshot of the user's latest assessment result.
ALTER TABLE booking_requests
    ADD COLUMN preferred_method text NULL
        CONSTRAINT booking_requests_preferred_method_check CHECK (preferred_method IN ('PHONE', 'VIDEO')),
    ADD COLUMN meeting_method text NULL
        CONSTRAINT booking_requests_meeting_method_check CHECK (meeting_method IN ('PHONE', 'VIDEO', 'IN_PERSON')),
    ADD COLUMN meeting_link text NULL,
    ADD COLUMN meeting_address text NULL,
    ADD COLUMN shared_assessment jsonb NULL,
    ADD COLUMN assessment_shared_at timestamptz NULL;
