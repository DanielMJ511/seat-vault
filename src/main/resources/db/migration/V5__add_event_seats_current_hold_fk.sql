-- Deferred FK: event_seats.current_hold_id was added in V3, before the holds
-- table existed. Now that holds exists (V4), wire up the actual constraint.
ALTER TABLE event_seats
    ADD CONSTRAINT fk_event_seats_current_hold FOREIGN KEY (current_hold_id) REFERENCES holds (id);
