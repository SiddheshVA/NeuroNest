-- A professional can block their own free grid slots (an emergency, a meeting, an event) and unblock them.
-- A block covers exactly what a one-session booking there would: the slot start to blocked_until (session
-- plus break). Blocked time can never be booked, and booked or held time can never be blocked: the
-- application checks both, and the database refuses both here as the last guard.

CREATE TABLE professional_blocked_slots (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    professional_id uuid        NOT NULL REFERENCES professionals (id) ON DELETE CASCADE,
    starts_at       timestamptz NOT NULL,
    blocked_until   timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT professional_blocked_slots_range_check CHECK (blocked_until > starts_at),
    -- btree_gist is installed by V15.
    CONSTRAINT professional_blocked_slots_no_overlap
        EXCLUDE USING gist (professional_id WITH =, tstzrange(starts_at, blocked_until, '[)') WITH &&)
);

-- The two tables cannot share one exclusion constraint, so each side checks the other in a trigger. Both
-- triggers first lock the professional's row (the same lock the booking and block requests take), so a
-- booking and a block for the same professional are checked one after the other and the second one sees the
-- first. Errors use the exclusion_violation code, so the application treats them like the no-overlap
-- constraint ("This time is no longer available").

-- A booking that holds or confirms time may not overlap a block. Only checked when the booking starts to
-- block time or its time changes: a held booking being confirmed keeps the time it already had (and takes
-- no extra lock, so the payment confirmation never waits on the professional's row).
CREATE FUNCTION booking_requests_check_blocked_slots() RETURNS trigger AS $$
BEGIN
    IF NEW.status NOT IN ('AWAITING_PAYMENT', 'CONFIRMED')
            OR NEW.preferred_datetime IS NULL OR NEW.blocked_until IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status IN ('AWAITING_PAYMENT', 'CONFIRMED')
            AND OLD.professional_id = NEW.professional_id
            AND OLD.preferred_datetime IS NOT DISTINCT FROM NEW.preferred_datetime
            AND OLD.blocked_until IS NOT DISTINCT FROM NEW.blocked_until THEN
        RETURN NEW;
    END IF;
    PERFORM 1 FROM professionals WHERE id = NEW.professional_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM professional_blocked_slots s
                WHERE s.professional_id = NEW.professional_id
                  AND tstzrange(s.starts_at, s.blocked_until, '[)')
                      && tstzrange(NEW.preferred_datetime, NEW.blocked_until, '[)')) THEN
        RAISE EXCEPTION 'The professional blocked this time.'
            USING ERRCODE = 'exclusion_violation', CONSTRAINT = 'booking_requests_not_blocked';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER booking_requests_not_blocked
    BEFORE INSERT OR UPDATE ON booking_requests
    FOR EACH ROW EXECUTE FUNCTION booking_requests_check_blocked_slots();

-- A block may not overlap a held or confirmed booking (an older one without blocked_until counts until its
-- ends_at; one with neither is left to the application's check).
CREATE FUNCTION professional_blocked_slots_check_bookings() RETURNS trigger AS $$
BEGIN
    PERFORM 1 FROM professionals WHERE id = NEW.professional_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM booking_requests b
                WHERE b.professional_id = NEW.professional_id
                  AND b.status IN ('AWAITING_PAYMENT', 'CONFIRMED')
                  AND b.preferred_datetime IS NOT NULL
                  AND coalesce(b.blocked_until, b.ends_at) IS NOT NULL
                  AND tstzrange(b.preferred_datetime, coalesce(b.blocked_until, b.ends_at), '[)')
                      && tstzrange(NEW.starts_at, NEW.blocked_until, '[)')) THEN
        RAISE EXCEPTION 'This time is booked.'
            USING ERRCODE = 'exclusion_violation', CONSTRAINT = 'professional_blocked_slots_not_booked';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER professional_blocked_slots_not_booked
    BEFORE INSERT ON professional_blocked_slots
    FOR EACH ROW EXECUTE FUNCTION professional_blocked_slots_check_bookings();
