# `event_seats` is locked before `holds`, everywhere — and dirtying an entity counts as locking it

Any transaction that touches rows in both tables takes every `event_seats` row it needs **first**, in ascending id order, and the `holds` row(s) **second**. Four call sites are bound by this: `HoldService#createHold`, `HoldService#releaseHold`, `BookingService#createFromHold`, and `HoldSweepService#sweepExpiredHolds`. Two of them (the first and the last) already worked this way; the other two locked the `holds` row first to authorize the caller before doing any work, which is the natural way to write them and was how they were written until T-007.

The checkable form is deliberately not "in what order does this method call `findByIdForUpdate`?" but **"in what order does this transaction first touch a row in each table, by any means — locked read, bulk `UPDATE`, or a write Hibernate will flush later?"** Those are different questions, and the difference is what hid this bug through several reviews.

## Why seats first, when authorizing first reads better

Both orders eliminate the deadlock; only one of them is implementable everywhere. The dependency between the two tables points one way: `event_seats.current_hold_id` tells you which hold owns a seat, and there is no cheap inverse. `createHold` cannot know which `holds` rows it will write until it has read the seats, because the hold it reconciles is whichever expired hold happens to own the seat it is claiming (ADR-0002). Locking holds first there would mean an unlocked pre-read of `current_hold_id`, locking those holds, and then discovering under the seat lock that the answer had changed — a best-effort ordering with a residual window, which is not an ordering at all. Every other site, by contrast, knows its seat set up front: from the request in `createHold`, from `hold_seats` in `createFromHold`, from the `current_hold_id` projection in `releaseHold`.

Seats-first has a second property worth naming: it puts the table that gets written *implicitly* last. Hibernate's flush order is not something a caller controls, so any row a transaction dirties can have its `UPDATE` — and therefore its row lock — land at an unpredictable point. That is only safe when the row is already locked (seats, always explicitly locked before being mutated) or when nothing later in the transaction takes a lock that could complete a cycle (holds, being last). Reversing the order would make every dirty `Hold` in the codebase a potential violation.

## The invisible lock

The line that inverted the order in `createHold` is `staleHold.setStatus(HoldStatus.EXPIRED)`. Nothing about it reads like lock acquisition, and it sits four lines below a comment about deadlock avoidance that does not cover it (that comment is about ordering seats among *themselves* by ascending id, which is a real and separate rule). But a dirtied managed entity becomes an `UPDATE` at flush time, an `UPDATE` takes the row's write lock, and that lock is then held until the transaction ends exactly as `SELECT ... FOR UPDATE` would be. Reviewing lock order by reading for lock calls will therefore miss it every time; the whole write set has to be considered.

`HoldSweepService` is the same violation with no ORM involved at all: two bulk `@Modifying` statements in one transaction, `releaseExpiredHeldSeats` then `expireOverdueHolds`, matching the same expired holds by construction. Nothing in it is a lock call either. It is also the site that matters most operationally, since it fires on a 30-second timer whether or not anyone is using the system.

## This was live, and it was measured

Pre-fix, `HoldLockOrderDeadlockIntegrationTest` reproduces it deterministically against real Postgres — a release of a lazily-expired two-seat hold racing another user's create on one of those seats, which is two ordinary requests:

```
ERROR: deadlock detected
  Detail: Process 65 waits for ShareLock on transaction 764; blocked by process 64.
          Process 64 waits for ShareLock on transaction 765; blocked by process 65.
  Where: while updating tuple (0,2) in relation "holds"
```

Postgres killed the *creating* transaction (the side that had waited longest), surfacing as `CannotAcquireLockException` on the flush of `update holds set expires_at=?,status=?,user_id=? where id=?` — an exception no handler expects, so `GlobalExceptionHandler`'s catch-all turned a valid request into a 500. A deadlocked path is not a safe path merely because it cannot corrupt data: ADR-0001 makes Postgres the concurrency authority, and an authority that answers "500, try again" to a legitimate request has failed at the thing it was chosen for.

## What the reordering cost

`releaseHold` and `createFromHold` now authorize *after* taking their seat locks, so a request for a hold that does not exist, is not yours, or is no longer ACTIVE may briefly hold seat locks it immediately rolls back. Accepted: those locks last microseconds, where the deadlock cost a user-visible error. ADR-0008's 404-not-403 collapse and ADR-0007's EXPIRED-on-release are unaffected — the same checks run against the same locked read, later.

One behaviour genuinely changes. Because the hold's status is now read after the seats are pinned, a `createHold` that lazily expires this hold (ADR-0002) can win the race, and the release that would previously have returned 204 now returns 409 `HOLD_NOT_ACTIVE`. That is the correct answer for a hold that really did expire, and the losing side of that race was previously the deadlock victim anyway.

`createFromHold` also gained a per-seat ownership guard mirroring `releaseHold`'s: an ACTIVE hold whose seat now points at a different hold is rejected rather than booked. The invariant says this is unreachable — a seat only ever leaves a hold in the same transaction that takes that hold out of ACTIVE — but before T-007 that unreachability rested partly on the deadlock, which is not a mechanism anyone should be relying on.

## Relationship to ADR-0010

They are complementary rules about the same underlying discomfort — the ORM decides when a lock is taken and what state a read reflects, and neither is visible at the call site — but they are separately checkable and fail differently. ADR-0010 is about freshness, and violating it corrupts data silently. This one is about order, and violating it produces a loud 500.

The sequencing between them matters in one direction. `releaseHold` no longer pins the `holds` row for its whole transaction, so the interleaving ADR-0010 describes as blocked by that lock is now genuinely reachable: a competing `createHold` can commit a re-homing while a release is parked on an earlier seat. ADR-0010's guard has therefore gone from dead code to live code, and its projection is what makes the guard able to see the change. Confirmed by deleting the guard and re-running `HoldReleaseSeatLockRaceIntegrationTest`, which then fails with the seat clobbered back to `AVAILABLE`. **Reverting ADR-0010 now costs a live double-booking bug, not a latent one.**

## Consequences

Reviewing a change against this rule means reading for the transaction's whole write set, not just its locking calls — which is more work than it sounds, since a JPA setter three frames deep in a helper counts. The four sites each carry a comment saying which order they take and why, so a fifth site has something to copy.

Two limits are known and not addressed here. First, the sweep's bulk `UPDATE` locks many `event_seats` rows in whatever order the plan scans them, which is not the ascending-id order the per-seat loops use; seat-versus-seat ordering between the sweep and a multi-seat hold is therefore still theoretically deadlock-prone, independently of the holds/seats ordering fixed here. Second, no statement or transaction lock timeout backs the discipline up: Postgres's deadlock detector (1s by default) catches genuine cycles, but a future one-directional pile-up would block rather than fail fast. A `lock_timeout` was considered as a backstop and left out for now rather than risk turning load-test contention into spurious failures — it is the obvious next hardening if the ordering ever needs enforcing rather than documenting.
