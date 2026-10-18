-- professionals: identity and verification details collected on the professional's own profile.
-- None of these are ever shown in the public directory.
ALTER TABLE professionals
    ADD COLUMN date_of_birth           date NULL,
    ADD COLUMN social_links            jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN declaration_accepted_at timestamptz NULL;
