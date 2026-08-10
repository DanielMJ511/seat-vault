# Hold expiry is authoritative via lazy check-on-read, not the scheduled sweep

A Hold's `hold_expires_at` is checked authoritatively inside the same locked transaction that's about to reuse an `EventSeat` — a `HELD` row past its expiry is simply treated as available right there, reconciling the stale `Hold` in the same transaction. The `@Scheduled` sweep that proactively flips expired holds back to `AVAILABLE` every 30s exists purely so casual browsers see accurate availability without needing to attempt a hold — it is not required for correctness.

## Consequences

A stalled or delayed scheduler can never cause an oversell or a seat stuck HELD forever; the worst it can do is show slightly stale availability to someone not actively trying to hold the seat. This was chosen over an event-driven approach (e.g. Redis key-expiry notifications) specifically to keep the correctness-critical path free of any dependency on message delivery guarantees.
