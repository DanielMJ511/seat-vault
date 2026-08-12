# A manually released Hold is marked EXPIRED, not a separate RELEASED status

`DELETE /api/holds/{id}` sets `Hold.status` to `EXPIRED` — the same value the sweep/lazy-expiry path already uses for a timed-out hold — rather than adding a fourth `HoldStatus` value. Nothing in the system needs to distinguish "the user gave the seat back" from "the countdown ran out": CONTEXT.md's non-goals already rule out refunds and per-seat audit trails, and the layer where "why did this end" will actually matter is Booking's own status (`CONFIRMED`/`FAILED`/`CANCELLED`, M5/M6), not Hold's.

## Consequences

Hold history can never answer "was this released voluntarily or did it expire?" — a future feature needing that distinction (analytics, abuse detection, a "you keep letting holds expire" nudge) requires a schema migration to add a `RELEASED` value to the `holds.status` CHECK constraint, not just an application-layer change. Accepted deliberately rather than adding speculative status granularity now.
