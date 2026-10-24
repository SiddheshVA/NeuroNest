-- payments: NeuroNest's commission on each paid booking and the professional's share, in paise, with the
-- percent used stored on the row so a later config change never alters an old payment. Set when a
-- payment is marked PAID; NULL while CREATED or FAILED.
ALTER TABLE payments
    ADD COLUMN commission_percent       integer NULL CHECK (commission_percent BETWEEN 0 AND 100),
    ADD COLUMN commission_paise         bigint  NULL CHECK (commission_paise >= 0),
    ADD COLUMN professional_share_paise bigint  NULL CHECK (professional_share_paise >= 0);

-- Existing paid payments at 10%: commission rounded half up in whole paise, share = amount - commission.
UPDATE payments
   SET commission_percent = 10,
       commission_paise = (amount_paise * 10 + 50) / 100,
       professional_share_paise = amount_paise - (amount_paise * 10 + 50) / 100
 WHERE status = 'PAID';

-- The split always adds up to the amount, and a PAID payment always has it.
ALTER TABLE payments ADD CONSTRAINT payments_commission_split_check CHECK (
    (commission_percent IS NULL AND commission_paise IS NULL AND professional_share_paise IS NULL)
    OR (commission_percent IS NOT NULL AND commission_paise IS NOT NULL AND professional_share_paise IS NOT NULL
        AND commission_paise + professional_share_paise = amount_paise));
ALTER TABLE payments ADD CONSTRAINT payments_paid_has_split_check CHECK (
    status <> 'PAID' OR commission_percent IS NOT NULL);

-- Earnings and commission reports read paid payments by date.
CREATE INDEX idx_payments_status_paid_at ON payments (status, paid_at);
