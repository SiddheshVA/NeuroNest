-- professionals: replace the fee range (fee_min, fee_max) with a single consultation fee per session.

ALTER TABLE professionals ADD COLUMN consultation_fee integer;

-- Keep existing data: the lower bound of the old range, or the upper bound when there was none.
UPDATE professionals SET consultation_fee = COALESCE(fee_min, fee_max);

ALTER TABLE professionals
    ADD CONSTRAINT professionals_consultation_fee_check
        CHECK (consultation_fee IS NULL OR consultation_fee >= 0);

ALTER TABLE professionals DROP COLUMN fee_min;
ALTER TABLE professionals DROP COLUMN fee_max;
