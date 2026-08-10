-- Postgres does not auto-index FK columns. current_hold_id is filtered on by
-- both the lazy-expiry check (hot booking path) and the scheduled sweep
-- (ADR-0002), and is non-null on only a small fraction of rows, so a partial
-- index keeps it small and cheap to maintain.
CREATE INDEX idx_event_seats_current_hold ON event_seats (current_hold_id) WHERE current_hold_id IS NOT NULL;
