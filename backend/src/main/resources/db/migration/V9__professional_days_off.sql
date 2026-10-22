-- Dates a professional is not available (holidays, leave). No slots are offered on these dates. The
-- reason is private to the professional.
CREATE TABLE professional_days_off (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    professional_id uuid NOT NULL REFERENCES professionals(id) ON DELETE CASCADE,
    date_from       date NOT NULL,
    date_to         date NOT NULL,
    reason          text NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT professional_days_off_dates_check CHECK (date_to >= date_from)
);

CREATE INDEX idx_professional_days_off_professional_from ON professional_days_off (professional_id, date_from);
