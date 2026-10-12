-- professionals: verification fields
ALTER TABLE professionals
    ADD COLUMN IF NOT EXISTS registration_council text,
    ADD COLUMN IF NOT EXISTS registration_year    integer,
    ADD COLUMN IF NOT EXISTS registration_expiry  date,
    ADD COLUMN IF NOT EXISTS verification_status  text NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS verification_source  text,
    ADD COLUMN IF NOT EXISTS verified_by          text,
    ADD COLUMN IF NOT EXISTS verified_at          timestamptz,
    ADD COLUMN IF NOT EXISTS verification_notes   text,
    ADD COLUMN IF NOT EXISTS next_recheck_date    date,
    ADD COLUMN IF NOT EXISTS submitted_at         timestamptz;

ALTER TABLE professionals
    ADD CONSTRAINT professionals_verification_status_check
        CHECK (verification_status IN
               ('DRAFT','SUBMITTED','VERIFIED','NEEDS_INFO','REJECTED','EXPIRED','SUSPENDED'));

ALTER TABLE professionals
    ADD CONSTRAINT professionals_verification_source_check
        CHECK (verification_source IS NULL OR verification_source IN
                                              ('NMC','STATE_COUNCIL','RCI','DEGREE_CERTIFICATE','OTHER'));

-- existing verified rows stay listed
UPDATE professionals SET verification_status = 'VERIFIED' WHERE verified = true;

CREATE INDEX IF NOT EXISTS idx_professionals_verification_status
    ON professionals (verification_status);

-- professional_accounts: link to Clerk
ALTER TABLE professional_accounts
    ADD COLUMN IF NOT EXISTS clerk_user_id text;

CREATE UNIQUE INDEX IF NOT EXISTS professional_accounts_clerk_user_id_key
    ON professional_accounts (clerk_user_id) WHERE clerk_user_id IS NOT NULL;

-- booking_requests: new statuses and fields
ALTER TABLE booking_requests DROP CONSTRAINT booking_requests_status_check;
ALTER TABLE booking_requests ADD CONSTRAINT booking_requests_status_check
    CHECK (status IN ('PENDING','CONFIRMED','DECLINED','ALTERNATE_PROPOSED','CANCELLED','EXPIRED'));

ALTER TABLE booking_requests
    ADD COLUMN IF NOT EXISTS preferred_window  text,
    ADD COLUMN IF NOT EXISTS proposed_datetime timestamptz,
    ADD COLUMN IF NOT EXISTS expires_at        timestamptz,
    ADD COLUMN IF NOT EXISTS consent_given_at  timestamptz;

-- uploaded proof documents (files live in private storage, only the path is stored here)
CREATE TABLE professional_documents (
                                        id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                        professional_id   uuid NOT NULL REFERENCES professionals(id) ON DELETE CASCADE,
                                        doc_type          text NOT NULL CHECK (doc_type IN
                                                                               ('REGISTRATION_CERTIFICATE','ID_PROOF','DEGREE_CERTIFICATE','OTHER')),
                                        storage_path      text NOT NULL,
                                        original_filename text,
                                        content_type      text,
                                        size_bytes        bigint,
                                        uploaded_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_professional_documents_prof ON professional_documents (professional_id);

-- audit trail of every verification decision
CREATE TABLE verification_log (
                                  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                  professional_id      uuid NOT NULL REFERENCES professionals(id) ON DELETE CASCADE,
                                  action               text NOT NULL CHECK (action IN
                                      ('SUBMITTED','VERIFIED','NEEDS_INFO','REJECTED','EXPIRED','SUSPENDED','REINSTATED')),
  from_status          text,
  to_status            text,
  actor                text NOT NULL,
  source               text,
  note                 text,
  evidence_document_id uuid REFERENCES professional_documents(id),
  created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_verification_log_prof ON verification_log (professional_id);