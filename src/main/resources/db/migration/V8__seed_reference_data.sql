-- Deterministic seed data: 2 venues, a small seat grid per venue
-- (2 sections x 3 rows x 5 seats = 30 seats/venue), 1 event per venue,
-- and an event_seats row for every seat at its event. All inserts join
-- on natural keys (names) rather than hardcoding identity values.

INSERT INTO venues (name, address) VALUES
    ('The Grand Hall', '123 Main St, Springfield'),
    ('Riverside Theater', '456 River Rd, Springfield');

INSERT INTO seats (venue_id, section, row_label, seat_number)
SELECT v.id, sec.section, row_num::text, seat_num
FROM venues v
CROSS JOIN (VALUES ('A'), ('B')) AS sec(section)
CROSS JOIN generate_series(1, 3) AS row_num
CROSS JOIN generate_series(1, 5) AS seat_num
WHERE v.name IN ('The Grand Hall', 'Riverside Theater');

INSERT INTO events (venue_id, name, starts_at)
SELECT id, 'Opening Night Gala', TIMESTAMPTZ '2026-09-15 19:00:00+00'
FROM venues WHERE name = 'The Grand Hall';

INSERT INTO events (venue_id, name, starts_at)
SELECT id, 'Riverside Jazz Night', TIMESTAMPTZ '2026-09-22 20:00:00+00'
FROM venues WHERE name = 'Riverside Theater';

INSERT INTO event_seats (event_id, seat_id, status, price)
SELECT e.id, s.id, 'AVAILABLE',
    CASE WHEN s.section = 'A' THEN 150.00 ELSE 95.00 END
FROM events e
JOIN venues v ON v.id = e.venue_id
JOIN seats s ON s.venue_id = v.id
WHERE e.name = 'Opening Night Gala';

INSERT INTO event_seats (event_id, seat_id, status, price)
SELECT e.id, s.id, 'AVAILABLE',
    CASE WHEN s.section = 'A' THEN 85.00 ELSE 60.00 END
FROM events e
JOIN venues v ON v.id = e.venue_id
JOIN seats s ON s.venue_id = v.id
WHERE e.name = 'Riverside Jazz Night';
