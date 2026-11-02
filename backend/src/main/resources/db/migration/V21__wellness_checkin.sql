-- Wellness check-ins of signed-in users (anonymous check-ins are never stored). Each row is the answers that
-- were sent to the ML service and its result, kept so the user can see their own history. Owner-only: no
-- professional, admin screen or assessment-sharing feature reads this table. Deleted with the user's account.

CREATE TABLE wellness_checkin (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    segment          text NOT NULL CHECK (segment IN ('student', 'working', 'both')),
    -- Copied out of result for listing and summaries; result stays the full ML response.
    mood_band        text NOT NULL CHECK (mood_band IN ('Low', 'Moderate', 'High')),
    mood_probability double precision NOT NULL CHECK (mood_probability BETWEEN 0 AND 1),
    mood_confidence  text NOT NULL CHECK (mood_confidence IN ('low', 'medium', 'high')),
    -- Null when the sleep inputs were outside the model's range (sleep_in_range = false).
    sleep_class      text CHECK (sleep_class IN ('none', 'insomnia_like', 'apnea_like')),
    sleep_in_range   boolean NOT NULL,
    inputs           jsonb NOT NULL,
    result           jsonb NOT NULL,
    model_versions   jsonb NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (sleep_in_range OR sleep_class IS NULL)
);

-- A user's history, newest first.
CREATE INDEX wellness_checkin_user_created_idx ON wellness_checkin (user_id, created_at DESC);
