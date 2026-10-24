-- Reviews of professionals by clients: one per CONFIRMED booking whose session has ended. Shown publicly
-- only as "Verified client" with the month; the user id never leaves the backend. An admin can hide a
-- review (never delete it); hidden reviews count nowhere.

CREATE TABLE professional_reviews (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id        uuid NOT NULL UNIQUE REFERENCES booking_requests(id),
    professional_id   uuid NOT NULL REFERENCES professionals(id),
    -- Copied from the booking: who may edit or delete it.
    user_id           uuid NOT NULL,
    rating            smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment           varchar(500),
    status            text NOT NULL DEFAULT 'VISIBLE' CHECK (status IN ('VISIBLE', 'HIDDEN')),
    -- The latest hide or unhide: why, when and by which admin.
    moderation_reason text,
    moderated_at      timestamptz,
    moderated_by      text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- The public list: a professional's visible reviews, newest first.
CREATE INDEX professional_reviews_public_idx ON professional_reviews (professional_id, status, created_at DESC);
CREATE INDEX professional_reviews_user_idx ON professional_reviews (user_id);

-- Kept up to date by the review service in the same transaction as every change, from VISIBLE reviews
-- only. rating_avg is null while there are none.
ALTER TABLE professionals
    ADD COLUMN rating_avg   numeric(3, 2),
    ADD COLUMN rating_count integer NOT NULL DEFAULT 0;
