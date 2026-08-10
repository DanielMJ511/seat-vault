# Postgres is the concurrency authority; Redis is a non-authoritative optimization

When two users race for the same seat, correctness must not depend on Redis being available or correctly configured. We decided that `SELECT ... FOR UPDATE` row locks on `event_seat` plus the `unique(event_id, seat_id)` constraint are what actually prevent double-booking; a short-lived Redis lock (`SET NX PX`) only exists to fail losing requests fast under contention, and is never load-bearing for correctness.

## Consequences

If Redis is down, hold requests still work correctly — they just queue on the Postgres row lock instead of failing fast. This trades peak throughput under extreme contention for a correctness argument that never has to reason about Redis failure modes, split-brain, or lock-token bugs.
