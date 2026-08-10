-- JPA's @PreUpdate only fires for entity-managed updates; bulk JPQL/native
-- updates (e.g. the ADR-0002 hold-expiry sweep) bypass it entirely. A
-- BEFORE UPDATE trigger keeps updated_at correct regardless of update path.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_seats_updated_at
    BEFORE UPDATE ON event_seats
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_bookings_updated_at
    BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payments_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
