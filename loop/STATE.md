# STATE — append-only journal

Never edit past entries; only append. Spans all milestones.

## Loop engineering initialized
- Set up `loop/` memory files, `.claude/agents/` roster, `.claude/skills/` (`/plan-milestone`, `/orchestrate`, `/loop-handoff`, `/retro`), and `.claude/settings.json` hooks.

## 2026-08-13 — M6 milestone boundary
- Issue: #11 (Cancellation / booking management)
- Tasks planned: T-001 (BookingService.cancel), T-002 (GET detail + list-mine), T-003 (POST cancel wiring), T-004 (tests)
- ADRs written: 0008 (Booking endpoints return 404, not 403, on ownership mismatch)
- Grilling resolved without a decision needed: Hold/Payment untouched by cancel, isolation level matches confirmPayment, only real race is double-cancel (handled by existing row-lock pattern)

## 2026-08-13 — T-001 BookingService.cancel (service layer, row-locked)
- Agents involved: builder only (no escalation, no respins)
- Test result: full `./mvnw test` suite passed — 70 tests, 0 failures, 0 errors, 0 skipped
- Code review verdict: APPROVED by code-reviewer (one non-blocking minor note: `releaseBookingSeats` was widened to package-private when the task packet said only to do so if needed — harmless, not worth a respin)
- Files touched: `src/main/java/com/seatvault/seat_vault/service/BookingService.java`
- No supplementary ADR: task didn't surface any new architectural decision beyond what M6's grilling session already covered (see ADR-0008 and the "Decisions from grilling" section in `loop/PLAN.md`)

## 2026-08-13 — T-002 GET /api/bookings/{id} and GET (list mine) read endpoints
- Agents involved: builder only for implementation. code-reviewer's first pass flagged a critical "no test coverage" finding; withdrawn on re-review once given the missing context that testing is deliberately deferred to T-004 (a separate, already-planned dependent task covering exactly these endpoints), not an omission in T-002. No builder respin needed — the finding was a review-context gap, not a code defect.
- Test result: full `./mvnw test` suite passed — 70 tests, 0 failures, 0 errors, 0 skipped (no new tests added yet, by design — T-004's job)
- Code review verdict: APPROVED, with one minor non-blocking note: N+1 query pattern in `listBookings` when mapping each booking through the existing `toResponse` helper — not worth a respin, flagged as a possible future optimization if `GET /api/bookings/me` sees heavy usage
- Files touched: `src/main/java/com/seatvault/seat_vault/controller/BookingController.java`, `src/main/java/com/seatvault/seat_vault/service/BookingService.java`, `src/main/java/com/seatvault/seat_vault/repository/BookingRepository.java`
- No supplementary ADR: straightforward extension of already-established patterns (confirmPayment's ownership-check style, ADR-0008's 404 convention, the grilling session's DTO-reuse decision) — nothing new to decide

## 2026-08-13 — T-003 POST /api/bookings/{id}/cancel controller wiring
- Agents involved: builder only (no escalation, no respins). code-reviewer approved cleanly on the first pass — given upfront context this time about T-004 owning test coverage, avoiding the false-positive review-context gap that happened on T-002.
- Test result: full `./mvnw test` suite passed — 70 tests, 0 failures, 0 errors, 0 skipped
- Code review verdict: APPROVED, no findings
- Files touched: `src/main/java/com/seatvault/seat_vault/controller/BookingController.java`
- No supplementary ADR: pure controller wiring following the already-established confirm-endpoint pattern — no new decision surfaced

## 2026-08-13 — T-004 Integration + concurrency tests for list/detail/cancel
- Agents involved: builder only (no escalation, no respins)
- Test result: full `./mvnw test` suite passed — 80 tests total (70 existing + 10 new), 0 failures, 0 errors, 0 skipped
- Code review verdict: APPROVED, no findings. Reviewer confirmed coverage matches all of issue #11's Verification scenarios plus the double-cancel concurrency scenario, and that the concurrency test correctly asserts the exact win/loss split rather than just "no crash."
- Files touched: `src/test/java/com/seatvault/seat_vault/service/BookingServiceTest.java` (6 new unit tests), `src/test/java/com/seatvault/seat_vault/controller/BookingIntegrationTest.java` (4 new integration tests, including a new `tokenFor` helper overload and a `PasswordEncoder` field for the two-user freed-seat scenario)
- No supplementary ADR: pure test coverage, no new architectural decision surfaced

## 2026-08-13 — M6 milestone (issue #11) implementation complete
- All tasks (T-001 through T-004) done. All of M6's Verification scenarios from `loop/PLAN.md` are now covered by passing tests: list/detail/cancel happy paths, cancel of a non-CONFIRMED booking rejected with 409, and cancellation verified to make the seat immediately holdable again by another user (plus the double-cancel concurrency race surfaced during grilling).
- Implementation is complete pending the user's security-review/retro/issue-close steps.

## 2026-08-13 — M7 milestone boundary
- Issue: #12
- Tasks planned: T-001..T-005
- ADRs written: 0009

## 2026-08-14 — T-001 No-oversell suite: hold-stage contention over a seat pool
- Agents involved: builder (2 passes — one respin after code review), test-runner (2 runs), code-reviewer (2 passes). No escalation to implementer.
- What was built: new `NoOversellIntegrationTest` with two race shapes — (a) `roundRobinFiveSeatsTwentyThreadsExactlyFiveWinAndBook`: 5 seats, 20 threads round-robin through hold→booking→confirm, asserting exact `booked_count == seat_count`, no seat double-booked, no 500s, and each CONFIRMED booking's payment matches the sum of its seats' price snapshots (ADR-0003); (b) `overlappingMultiSeatHoldsTwelveThreadsNoOversell`: 8 seats, 12 threads each requesting 3 overlapping seats, asserting the weaker honest invariant — no seat double-booked, no deadlock/timeout/500, no seat stranded HELD. Also added `spring.datasource.hikari.maximum-pool-size=30` to `application-test.properties`, because HikariCP's default 10-connection pool plus `findByIdForUpdate` having no lock timeout would make a 20-thread race measure connection starvation rather than Postgres row locking, surfacing as a spurious 500 via Hikari's 30s acquisition timeout.
- First code review pass: CHANGES REQUESTED. Two findings, both traced to the planning session's assumptions rather than builder error: (1) shape (b)'s Javadoc claimed it exercised `HoldService`'s ascending-id Postgres lock ordering, but `createHold` acquires all Redis locks (non-blocking SETNX) before any Postgres work and holds them through `finally`, so while Redis is up an overlapping-seat loser is rejected at the Redis tier and never reaches `findByIdForUpdate` — the ordering loop only ever runs uncontended; (2) at 5 seats with 3-seat requests, shape (b) was mathematically degenerate (any two 3-subsets of a 5-set must intersect), making it an expensive duplicate of shape (a).
- Resolution: shape (b) widened to an 8-seat pool (allowing up to 2 disjoint concurrent winners — builder measured 1 winner in 8 runs and 2 in 2 runs across 10 instrumented runs), Javadoc rewritten to state the non-coverage plainly. The deadlock-ordering coverage was relocated, not dropped: `loop/tasks/T-003.md` now owns it as "Race 2" (overlapping multi-seat race with Redis DOWN, the only configuration where `tryLock` fail-opens for every caller). `loop/PLAN.md` records this correction under "Decisions from grilling".
- Second code review pass: APPROVED, no findings. Reviewer additionally verified no cross-method fixture interference and no parallel-execution risk.
- Test result: full `./mvnw test` green — 82 tests (80 existing + 2 new), 0 failures, 0 errors, 0 skipped, 48.2s. New class also passed 3 independent standalone repeat runs post-respin plus 12 stability runs by the builder.
- Files touched: `src/test/java/com/seatvault/seat_vault/controller/NoOversellIntegrationTest.java` (new), `src/test/resources/application-test.properties`
- No supplementary ADR: ADR-0009 was already written during this milestone's grilling session and covers the error-code decisions. The Redis-gating insight from review is a factual clarification about existing behavior already documented in ADR-0001 and `RedisLockService`'s Javadoc — not a new decision — and it has been captured where it is actionable (the T-001/T-003 packets and the PLAN.md correction note).

## 2026-08-14 — T-002 Booking/confirm-stage load: declines and shared-hold races
- Agents involved: builder (1 pass, no respin), test-runner, code-reviewer (1 pass, APPROVED). No escalation to implementer.
- What was built: new `BookingConfirmLoadIntegrationTest` with two methods. `allDeclineUnderLoadReleasesEverySeatAndFailsEveryBooking` — 4 seats, 8 threads round-robin, payment forced to decline; asserts no 500s, exactly 4 hold winners (4 losers get 409), every winner's booking and confirm both return 200 (a decline is a successful state transition, not an HTTP error), every seat ends AVAILABLE with a null current hold, every Booking FAILED, every Payment FAILED, and `invocationCount() == 4`. `sharedHoldRaceProducesOneBookingAndOneCharge` — two-phase: phase 1 races 8 threads on `POST /api/bookings` against one shared 4-seat hold (exactly 1 winner/201, 7 losers/409 HOLD_NOT_ACTIVE, winning booking id captured); phase 2 feeds that one known id back into a fresh 8-thread race on `POST /api/bookings/{id}/confirm` (all 8 return 200 — one thread actually charges and reports CONFIRMED, the other 7 block on the Booking row lock and then re-read the already-CONFIRMED row via `confirmPayment`'s not-PENDING early return), asserting `invocationCount() == 1` and all 4 seats end BOOKED with the hold CONVERTED. The two-phase design was deliberate — only one thread ever receives a booking id in phase 1, so a single-pass version would make the idempotency assertion trivially true; this was caught during task-packet review before the builder started and the packet was updated to specify it.
- Disclosed limitation, verified and accepted: the decline method does not contend on a shared booking or seat (holds are single-owner, so each of the 4 winning threads declines and releases its own independent seat) — the builder stated this plainly in the Javadoc rather than overclaiming. Code review verified the coverage is nonetheless genuinely new: `BookingIntegrationTest#simulatedDeclineReleasesSeatImmediately` is the suite's only other decline test and is `@Transactional`/single-seat/single-connection, so it cannot exercise `releaseBookingSeats` from real independent concurrently-committing transactions at all — this new method is the only test in the suite that does.
- Code review verdict: APPROVED, no critical/major findings. Reviewer independently verified phase 2 genuinely serializes on `BookingRepository#findByIdForUpdate`'s PESSIMISTIC_WRITE lock and that `invocationCount() == 1` would fail under a real locking bug (not trivially true). Two informational notes, neither worth a respin: (a) the process-wide `SimulatedPaymentServiceImpl` singleton reset discipline is sound today only because Surefire runs sequentially, and would become silently unsound if parallel class execution were ever enabled — a pre-existing suite-wide pattern, not introduced here; (b) one redundant assertion (a no-500 check immediately subsumed by an all-200 check).
- Test result: full `./mvnw test` green — 84 tests (82 existing + 2 new), 0 failures, 0 errors, 0 skipped, 40.7s. Three independent standalone repeat runs of the new class passed (~18s each), plus 3 by the builder. No `invocationCount` cross-test pollution observed in either the full-suite run or standalone runs.
- Files touched: `src/test/java/com/seatvault/seat_vault/controller/BookingConfirmLoadIntegrationTest.java` (new)
- No supplementary ADR: pure test coverage following patterns already established by T-001 and the existing concurrency tests. No new architectural decision surfaced.

## 2026-08-14 — T-003 Redis-unavailable fallback proving DB-only locking still holds
- Agents involved: builder (1 pass), test-runner, code-reviewer (1 pass). No escalation to implementer for T-003 itself.
- **This task found and fixed a real production double-booking bug.** T-003's Race 2 (8 seats,
  12 threads, overlapping 3-seat requests, Redis down) is the suite's first configuration where
  multiple threads are genuinely simultaneously inside `HoldService`'s Postgres locking loop for
  overlapping seats — with Redis up, `SETNX` filters contention to one thread before Postgres is
  reached. That race reproducibly double-booked seats: two threads with overlapping requests both
  received 201.
- Root cause: `spansMultipleEvents` called `eventSeatRepository.findAllById(seatIds)`, an unlocked
  read that loads fully-managed `EventSeat` entities into the transaction's Hibernate persistence
  context before the locking loop runs. Hibernate's identity map then returns that already-managed,
  stale instance for the later `findByIdForUpdate` on the same id rather than refreshing from the
  freshly-locked row — the Postgres `SELECT ... FOR UPDATE` genuinely acquired the row lock, but the
  application read data from before it, defeating the lock for any request covering 2+ seats.
  Undetected until now because `spansMultipleEvents` returns early for single-seat requests, and no
  prior test combined multi-seat with Redis-down.
- Fix: builder root-caused it empirically (confirmed `entityManager.clear()` before the locking loop
  also fixed it) then applied the proper fix — a new `EventSeatRepository.findDistinctEventIdsByIdIn`
  scalar projection that never materializes an `EventSeat` entity, so it cannot poison the later
  locked read. Both the repository method and call site carry Javadoc naming the race that caught it.
- What else was built: `RedisLockServiceTest` (7 Mockito unit tests covering fail-fast contention,
  fail-open sentinel on `DataAccessException`, and `unlock` behavior on both the sentinel and a real
  token), `BrokenRedisTestConfig` (`@TestConfiguration` Lettuce factory on a dead port with fast
  timeouts and `autoReconnect(false)`), `HoldRedisUnavailableRaceIntegrationTest` (own isolated Spring
  context/Postgres container via `PostgresTestcontainersConfig` + `BrokenRedisTestConfig`; wiring
  sanity check, uncontended-hold smoke test, Race 1 single-seat/20-thread, a minimal 2-thread
  regression repro, and Race 2), and a mechanical stub update in `HoldServiceTest`.
- Code review verdict: CHANGES REQUESTED — but explicitly not against T-003's own diff, which the
  reviewer confirmed correct and ready to ship as-is (fix shape verified better than the
  alternatives; projection logic verified equivalent to the old distinct-count check including edge
  cases; new test infrastructure sound; Race 2 verified via an `AtomicInteger` in-flight high-water
  mark to genuinely establish concurrent Postgres entry rather than assuming it). The CHANGES
  REQUESTED was raised for a critical pre-existing defect the review surfaced by pattern-matching
  against the fix: the identical stale-entity pattern is still live in `HoldService.releaseHold`
  (`findByCurrentHoldId` returning managed entities before the locked read), exploitable without any
  Redis outage — confirmed able to silently destroy a valid concurrent hold. Because that code is not
  part of T-003's diff, T-003 was committed on its own merits and the defect was tracked as new task
  **T-006** (already added to `loop/PLAN.md`), escalated directly to `implementer`. Review also
  confirmed two other sites (`BookingService.createFromHold`, `releaseBookingSeats`) are NOT
  exploitable — both reach `EventSeat` only via lazy `@ManyToOne` proxies, never a materialized stale
  instance.
- **ADR note:** ADR-0010 is required but is deliberately assigned to T-006, not written here — T-006
  fixes the second instance of the same bug (`releaseHold`) and will record the discipline covering
  both sites ("never materialize a managed entity from an unlocked read, in the same transaction, for
  an id later read under `findByIdForUpdate`") in one place rather than splitting it across two ADRs.
- Test result: full `./mvnw test` green — 96 tests, 0 failures, 0 errors, 0 skipped, 50.1s (up from
  84 tests / 40.7s). Three independent standalone repeat runs each of
  `HoldRedisUnavailableRaceIntegrationTest` (~17.5s) and `RedisLockServiceTest` (~1.7s) all passed.
  Pre-existing `HoldServiceTest` (11) and `HoldIntegrationTest` (8) confirmed green — no regression
  from the production fix.
- Files touched: `src/main/java/com/seatvault/seat_vault/repository/EventSeatRepository.java`,
  `src/main/java/com/seatvault/seat_vault/service/HoldService.java`,
  `src/test/java/com/seatvault/seat_vault/service/HoldServiceTest.java`,
  `src/test/java/com/seatvault/seat_vault/service/RedisLockServiceTest.java` (new),
  `src/test/java/com/seatvault/seat_vault/config/BrokenRedisTestConfig.java` (new),
  `src/test/java/com/seatvault/seat_vault/controller/HoldRedisUnavailableRaceIntegrationTest.java` (new)

## 2026-08-14 — T-006 Fix stale-entity lock defeat in HoldService.releaseHold + ADR-0010
- Origin: unplanned, added mid-milestone from T-003's code review.
- Agents involved: implementer (spawned directly, no builder attempt — code review classified this
  as a critical production concurrency defect needing careful Hibernate reasoning), test-runner,
  code-reviewer (1 pass, APPROVED).
- What was fixed: `HoldService.releaseHold` used `eventSeatRepository.findByCurrentHoldId(holdId)`
  — an entity query returning fully-managed `EventSeat` instances — before its locking loop, so
  Hibernate's identity map returned those stale instances from the later `findByIdForUpdate`, and
  the ownership guard re-checked pre-lock data. Replaced with a scalar projection
  `findIdsByCurrentHoldId` (`select es.id from EventSeat es where es.currentHold.id = :holdId`),
  mirroring T-003's fix. `findByCurrentHoldId` had no other production callers and was deleted
  rather than left beside the safe method as an attractive shortcut. `entityManager.clear()` was
  rejected — more strongly than in T-003, because `releaseHold` has deliberately locked a `Hold` it
  is about to mutate, and clearing would detach it mid-transaction.
- **Important correction to the task packet's premise, verified empirically:** the exploit trace in
  the T-006 packet (and asserted by both the orchestrator and the previous code review) was wrong
  in one step. It claimed a competing thread could reassign the seat and *commit* underneath the
  release. It cannot: `releaseHold` locks the `holds` row first, and every production path that
  re-homes a seat away from that hold also writes the hold's row in the same transaction. The
  competitor therefore queues behind the release. The implementer confirmed this with two throwaway
  probes against real Postgres (one observing the competing `createHold` blocked with
  `ungrantedLocks=1`; the other observing Postgres raise `deadlock detected`). Code review
  independently re-derived the same conclusion and extended it:
  `HoldSweepService.sweepExpiredHolds()` is affected the same way. Consequence: the clobber this
  task fixes is **latent**, masked by the hold-row lock, and the ownership guard it protects is
  currently unreachable. The fix is therefore defense-in-depth rather than a live-bug fix — correct
  and worth having, since the masking is an accident of the current call graph rather than a
  guarantee, but it should not be described as closing an actively-exploitable hole. ADR-0010 and
  the code comments are hedged accordingly.
- **A third defect was discovered while proving the above** and is tracked as new task **T-007**
  (already added to `loop/PLAN.md`): `releaseHold` acquires holds→seats while `createHold` and
  `HoldSweepService` acquire seats→holds — an ABBA lock-order inversion that deadlocks, so Postgres
  kills one transaction and a legitimate release racing a create returns a 500. This one is live.
  The second lock on the seats→holds side is invisible at the call site: it is Hibernate flushing a
  dirtied entity, not an explicit lock call, which is why it survived review.
- Regression test: `HoldReleaseSeatLockRaceIntegrationTest.java`, written before the fix and
  confirmed to fail against unfixed code — 4/4 deterministic failures pre-fix
  (`expected: HELD but was: AVAILABLE`, the release clearing a seat owned by another user's live
  ACTIVE hold), 6/6 passes post-fix. Code review independently verified both halves by stashing the
  fix, re-running (clean fast failure in 1.47s, not a timeout), and restoring. Because the realistic
  reassignment paths are all blocked by the hold-row lock, the test drives the reassignment through
  a deliberately constructed transaction that bypasses `HoldSweepService` — legitimate as a
  forward-looking guard for ADR-0010's discipline, but manufactured rather than a
  currently-reachable production sequence.
- ADR-0010 written by the implementer as part of this task's diff:
  `docs/adr/0010-no-unlocked-entity-reads-before-a-row-lock.md` — "A row lock is only as fresh as
  the persistence context: no unlocked entity read may precede it in the same transaction." States
  the rule in checkable form, names all dependent call sites, records both real bugs and the
  rejected alternatives, and notes that Mockito structurally cannot catch this bug class.
- Code review verdict: APPROVED, no critical findings. Three non-blocking items were raised and are
  NOT yet addressed — a follow-up attempt was cut short by an API session limit before making any
  changes, so they are carried into T-007's packet: (1, most significant)
  `awaitBlockedOnARowLock()` polls `select count(*) from pg_locks where not granted` unscoped by
  relation or PID; `HoldSweepService`'s ungated 30s `@Scheduled` sweep could in principle unblock
  the thief early and pass the test for the wrong reason given the test's deliberately-expired
  fixture — not observed in practice (isolated runs ~15-17s, poll resolving in ~1.5s, full-suite
  green), so a latent flakiness risk rather than an active failure; (2) the test javadoc should
  state plainly that its thief transaction is manufactured and bypasses `HoldSweepService`; (3)
  trivia — a javadoc line reference cites `HoldService.java:163` where the actual lock is at 165,
  and ADR-0010's paragraphs are denser than the house style set by ADR-0001/0002.
- Test result: full `./mvnw test` green — 97 tests, 0 failures, 0 errors, 0 skipped, 53.8s. Three
  independent standalone repeat runs of the new regression test all passed (~17s each).
  `HoldServiceTest` (11), `HoldIntegrationTest` (8), `NoOversellIntegrationTest` (2) all green — no
  regression from the `HoldService` change.
- Files touched: `src/main/java/com/seatvault/seat_vault/service/HoldService.java`,
  `src/main/java/com/seatvault/seat_vault/repository/EventSeatRepository.java`,
  `src/test/java/com/seatvault/seat_vault/service/HoldServiceTest.java`,
  `src/test/java/com/seatvault/seat_vault/service/HoldReleaseSeatLockRaceIntegrationTest.java`
  (new), `docs/adr/0010-no-unlocked-entity-reads-before-a-row-lock.md` (new)
- No supplementary ADR written by docs-writer: ADR-0010 was already written by the implementer as
  part of this task's diff.

## 2026-08-14 — T-007 Fix the holds↔seats lock-order inversion (deadlock → 500) + ADR-0011
- Origin: unplanned, discovered while proving T-006. M7's second unplanned task.
- Agents involved: implementer (spawned directly; a first attempt at unrelated T-006 follow-up
  work had been killed by an API session limit, but this run completed), test-runner,
  code-reviewer (2 attempts — the first stalled on a watchdog before reading the diff; the second
  completed and returned APPROVED).
- The defect: `HoldService.releaseHold` and `BookingService.createFromHold` locked the `holds` row
  before `event_seats` rows, while `HoldService.createHold` and `HoldSweepService` lock
  `event_seats` before `holds` — an ABBA deadlock. Postgres detects the cycle and aborts one
  transaction with `CannotAcquireLockException`, which `GlobalExceptionHandler`'s catch-all turns
  into a 500 on a legitimate request. Reproduced deterministically before the fix, with Postgres
  reporting `deadlock detected ... while updating tuple in relation "holds"` raised from
  `AbstractFlushingEventListener.performExecutions` — i.e. from Hibernate flushing a dirtied
  entity, the invisible lock at the heart of the defect. Postgres chose the create as the victim,
  so the user who merely grabbed a legitimately-expired seat received the 500.
- Three offenders, not two. The task packet listed `releaseHold` (holds-first) against
  `createHold` and the sweep (seats-first). Implementation found `BookingService.createFromHold`
  was a second holds-first site, on a mundane trigger: a user clicking "book" on a hold that
  lazily expired moments earlier while someone else takes the seat. A fix covering only
  `releaseHold` would have left it live.
- Fix direction chosen: option A (invert the holds-first sites to seats-first), covering all three
  offenders. Option B (stop the seats-first sides writing holds inside the seat loop) was rejected
  on its own terms rather than on cost: `createFromHold` had no per-seat ownership check, so an
  expired-but-still-ACTIVE hold whose seat had been re-homed would pass both its
  `hold.getStatus() != ACTIVE` and `effectiveStatus != HELD` checks (the seat IS legitimately
  HELD — by the new owner) and would book another user's seat. `createHold`'s inline
  reconciliation was the only thing preventing that, so removing it would have traded a 500 for a
  silent double-booking. A "make everything holds-first" variant was also rejected as not
  implementable: which `holds` rows `createHold` must write is discovered from the seats, so any
  pre-read of them is unlocked and can be stale — a best-effort ordering, which is not an ordering.
  `releaseHold` and `createFromHold` now gather seat ids unlocked (projection / untouched lazy
  proxies, per ADR-0010), lock every seat ascending, then lock the `holds` row and authorize.
  `createFromHold` also gained the per-seat ownership guard it was missing. `createHold` and
  `HoldSweepService` needed no behavioural change and gained comments stating the order and why.
- Most significant finding — T-006's fix is now load-bearing, not defense-in-depth. Pre-T-007,
  `releaseHold` pinned the `holds` row for its whole transaction, so T-006's clobber interleaving
  was unreachable and its ownership guard was effectively dead code. With the hold lock now taken
  last, a competitor CAN commit a seat re-homing while a release is parked on an earlier seat, so
  the interleaving became reachable the moment this task landed. Verified twice independently — by
  the implementer and again by the code reviewer — by deleting the guard post-fix and observing
  T-006's regression test fail (`expected: HELD but was: AVAILABLE`), then restoring it and
  confirming green. ADR-0010's caveat had said the masking was "one lock in one method away from
  being false"; it was false one task later, and that paragraph was rewritten. Reverting ADR-0010's
  discipline would now cost a live double-booking rather than a theoretical one.
- Two deliberate behaviour changes, both pinned by tests: (1) Releasing a hold that a concurrent
  `createHold` just reconciled now returns 409 `HOLD_NOT_ACTIVE`. The code review corrected the
  framing here: this is not "204 → 409" as originally described, because the losing side of that
  race was previously the deadlock victim — the honest comparison is "500 → 409", which is
  unambiguously an improvement and reuses ADR-0007's existing semantics rather than inventing
  behaviour. (2) `createFromHold` on a non-ACTIVE hold now takes and rolls back seat locks before
  rejecting — mechanically forced by the reordering, since seat ids must come from an unlocked
  read before any lock exists to check status against. A test named `...IsRejectedBeforeLocking`
  was rewritten as `createFromHoldWithNonActiveHoldIsRejectedAfterTheSeatsAreLocked`. Review
  scrutinised this specifically and judged it a legitimate contract change rather than a
  normalised regression: the new test asserts the lock WAS taken and rolled back (verifying
  `eventSeatRepository.findByIdForUpdate(12L)`) rather than deleting the inconvenient assertion.
- ADR-0011 written by the implementer as part of this task's diff:
  `docs/adr/0011-event-seats-is-locked-before-holds-everywhere.md`. A new ADR rather than an
  extension of 0010, because the ordering rule is separately checkable, applies to code with no
  ORM involvement (the sweep's bulk statements, which ADR-0010 has nothing to say about), and
  fails differently — a loud 500 versus silent corruption. Its headline is the invisible-lock
  hazard ("dirtying an entity counts as locking it"), and it states the checkable form as "in what
  order does this transaction first touch a row in each table, by any means" rather than "in what
  order does it call `findByIdForUpdate`". ADR-0010 gained a cross-reference and the corrected
  caveat.
- Carried-forward items from T-006's review, all addressed here: the `pg_locks` poll in
  `HoldReleaseSeatLockRaceIntegrationTest` is now scoped by both backend PID and relation, derived
  by probing real Postgres rather than guessed. The thief javadoc now states plainly that the
  transaction is manufactured and bypasses `HoldSweepService`. The stale `HoldService.java:163`
  line reference was removed and ADR-0010's two densest paragraphs were split into five.
- Deliberately not done, recorded in ADR-0011's consequences: isolation level left at default
  `READ COMMITTED` on all touched methods — none of T-003/T-006/T-007 is an isolation problem,
  all three reproduce identically at `SERIALIZABLE`, since the mechanism is row-lock ordering and
  the ORM persistence context rather than snapshot visibility. No `@Transactional(timeout)` /
  `lock_timeout` backstop was added: deadlocks are auto-detected in about a second, and a timeout
  risks turning legitimate load-test contention into spurious failures.
- Known residual, disclosed not fixed: the sweep's bulk `UPDATE` locks `event_seats` rows in
  plan-scan order rather than ascending id, so seat-versus-seat ordering between the sweep and a
  multi-seat hold remains theoretically deadlock-prone — independent of the table-versus-table
  ordering fixed here. Recorded in ADR-0011's consequences as a candidate follow-up, judged
  acceptable to leave since it is now a rare seat-vs-seat case rather than the ABBA cycle this
  task closed.
- Code review verdict: APPROVED, no critical findings. Lock order verified consistent on every
  path touching both tables, including implicit flush-time locks. ADR-0008's 404-not-403
  semantics verified intact. One minor disclosed finding: because seat locks are now taken from an
  ownership-blind read, a caller who does not own a hold but knows its id can cause brief row
  locks on another user's seats before receiving the 404 — bounded to microseconds and rolled
  back, already disclosed in ADR-0011's "What the reordering cost", but a residual worth awareness
  as a minor timing side-channel.
- Test result: full `./mvnw test` green — 99 tests (97 + a deadlock reproduction test + a
  `createFromHold` ownership-guard unit test), 0 failures, 0 errors, 0 skipped, 61s.
  `HoldLockOrderDeadlockIntegrationTest` and `HoldReleaseSeatLockRaceIntegrationTest` each passed 3
  consecutive standalone runs; the concurrency suites (`NoOversellIntegrationTest`,
  `BookingConfirmLoadIntegrationTest`, `HoldRedisUnavailableRaceIntegrationTest`) passed 2
  consecutive runs; the behavioural classes (`BookingIntegrationTest`, `BookingServiceTest`,
  `HoldServiceTest`, `HoldIntegrationTest`, 46 tests) passed. No deadlocks, lock-acquisition
  exceptions, timeouts, or poll failures anywhere.
- Files touched: `src/main/java/com/seatvault/seat_vault/service/HoldService.java`,
  `.../service/BookingService.java`, `.../service/HoldSweepService.java`,
  `src/test/java/com/seatvault/seat_vault/service/HoldLockOrderDeadlockIntegrationTest.java`
  (new), `.../service/HoldReleaseSeatLockRaceIntegrationTest.java`, `.../service/BookingServiceTest.java`,
  `src/test/java/com/seatvault/seat_vault/controller/BookingIntegrationTest.java`,
  `docs/adr/0011-event-seats-is-locked-before-holds-everywhere.md` (new),
  `docs/adr/0010-no-unlocked-entity-reads-before-a-row-lock.md` (amended)
- No supplementary ADR written by docs-writer: ADR-0011 was already written by the implementer as
  part of this task's diff, and ADR-0010 was amended in the same diff.

## 2026-08-14 — T-004 ErrorCode enum catalogue and HOLD_EXPIRED semantics (implements ADR-0009)
- Agents involved: builder (1 pass, no respin), test-runner, code-reviewer (1 pass, APPROVED).
- What was built: `exception/ErrorCode.java`, a closed enum carrying each code's `HttpStatus` and
  description, with both raw-string `ApiException` constructors removed so there is no bypass —
  every throw site across `AuthService`, `VenueService`, `EventService`, `EventSeatService`,
  `HoldService`, `BookingService`, `GlobalExceptionHandler`, and `SecurityConfig`'s two inline
  handlers (`UNAUTHENTICATED`, `ACCESS_DENIED`) now sources its code from the enum. New
  `service/HoldExpiry.java` is a static sibling of `EventSeatAvailability` (private constructor,
  pure function, deliberately not a Spring bean): `isExpired(Hold)` is true when the stored status
  is `EXPIRED`, or `ACTIVE` with `expiresAt` in the past. No drift test was added — per ADR-0009, a
  closed enum with no raw-string constructor makes the compiler enforce structurally what such a
  test would assert.
- Catalogue count corrected to 22, not the 21 the task packet (and grilling) claimed: the builder
  re-derived it rather than trusting the packet and found the packet's arithmetic omitted
  `HOLD_EXPIRED` itself (21 pre-existing + `HOLD_EXPIRED` = 22, verified directly against the enum
  source). Code review independently reported 21, which was its own miscount; the review's
  substantive checks — zero remaining `ApiException(HttpStatus` call sites, and every code's HTTP
  status matching its pre-refactor value with no silent regression — were verified and are what
  the APPROVED verdict actually rests on.
- The `HOLD_EXPIRED` split is keyed on domain state, not throw site, per ADR-0009 — verified by
  review at all five sites, so a client sees the same code whether `HoldSweepService` already
  flipped the row to `EXPIRED` or it is still stored `ACTIVE` with `expiresAt` past.
- T-007's per-seat ownership guard (the fifth, genuinely ambiguous site the packet flagged for a
  deliberate decision) was also keyed on `HoldExpiry.isExpired(hold)`: `HOLD_EXPIRED` when the hold
  has actually timed out, falling back to `HOLD_NOT_ACTIVE` for the guard's own "impossible"
  invariant-violation case, reasoned in a comment at the throw site. Corroborated by
  `createFromHoldDoesNotBookASeatThatNowBelongsToADifferentHold` passing unchanged (its fixture's
  hold expires 300s in the future, correctly still `HOLD_NOT_ACTIVE`).
- Deliberately not routed through `HoldExpiry`: `HoldService`'s lazy-reconciliation idempotency
  gate (`staleHold.getStatus() == ACTIVE`) — builder argued, and review accepted, this asks "has
  something already reconciled this Hold?" rather than repeating the time comparison that already
  happened in `EventSeatAvailability.effectiveStatus`, so routing it through the helper would
  dress up a different question as the same one. Recorded as an inline comment.
- Five test assertions changed, each traced to the fixture's actual domain state and confirmed by
  review as warranted rather than flipped to match the implementation:
  `BookingIntegrationTest#createBookingFromLazilyExpiredHoldIsRejected`,
  `BookingServiceTest#createFromHoldWithNonActiveHoldIsRejectedAfterTheSeatsAreLocked`,
  `BookingServiceTest#createFromHoldRejectsWhenHoldHasLazilyExpiredSinceCreation`,
  `HoldServiceTest#releaseAlreadyNonActiveHoldIsRejected`, and
  `HoldLockOrderDeadlockIntegrationTest#releaseRacingCreateOnTheSameHoldMustNotDeadlock` (the
  losing release reads a hold the create's lazy-expiry reconciliation already flipped to
  `EXPIRED`) — all now `HOLD_EXPIRED`. Verified unchanged, deliberately:
  `createFromHoldDoesNotBookASeatThatNowBelongsToADifferentHold`, `createFromHoldWithNoHoldSeatsIsRejected`,
  and `BookingConfirmLoadIntegrationTest#sharedHoldRaceProducesOneBookingAndOneCharge` (races a
  fresh, non-expired hold whose losers see `CONVERTED`) all correctly keep `HOLD_NOT_ACTIVE`.
  `HoldReleaseSeatLockRaceIntegrationTest` had no code assertion, only a doc comment referencing
  the old name, updated for accuracy.
- Wire contract unchanged: `ErrorResponse.code` remains a plain `String`; `GlobalExceptionHandler`
  still threads `ex.getCode()`/`ex.getStatus()` unchanged. Internal refactor only, JSON shape did
  not change.
- Test result: full `./mvnw test` green — 99 tests, 0 failures, 0 errors, 0 skipped, 54.7s
  (unchanged count; this task adds no tests by design). The two touched concurrency classes passed
  2 consecutive standalone runs each. All 56 tests carrying `$.code` assertions
  (`BookingIntegrationTest`, `BookingServiceTest`, `HoldServiceTest`, `HoldIntegrationTest`,
  `AuthIntegrationTest`) passed with no error-code assertion failures.
- Code review verdict: APPROVED, no critical or major findings. No scope creep — touched files
  match the packet's list plus the two new files it anticipated.
- Files touched: new — `exception/ErrorCode.java`, `service/HoldExpiry.java`. Modified —
  `exception/ApiException.java`, `exception/GlobalExceptionHandler.java`,
  `config/SecurityConfig.java`, `service/AuthService.java`, `service/EventSeatService.java`,
  `service/EventService.java`, `service/VenueService.java`, `service/HoldService.java`,
  `service/BookingService.java`, plus tests `controller/BookingIntegrationTest.java`,
  `service/BookingServiceTest.java`, `service/HoldServiceTest.java`,
  `service/HoldLockOrderDeadlockIntegrationTest.java`,
  `service/HoldReleaseSeatLockRaceIntegrationTest.java`.
- No supplementary ADR: ADR-0009 already governs this task, written during M7's grilling session
  before implementation started. This task is its implementation; nothing new was decided.
- Note for T-005: `ErrorCode`'s description field is the source the OpenAPI `@ApiResponse`
  examples must draw from, per ADR-0009 — the generated document is the consumer-facing catalogue,
  and there is deliberately no hand-maintained Markdown table.

## 2026-08-14 — T-005 OpenAPI operation/response examples across the API surface
- Agents involved: builder (1 pass, no respin), test-runner, code-reviewer (1 pass, APPROVED).
- What was built: `@Operation` summaries, `@SecurityRequirement`, and `@ApiResponse` entries at
  depth (b) across all six controllers (`AuthController`, `VenueController`, `EventController`,
  `EventSeatController`, `HoldController`, `BookingController`), covering 13 operations. An
  `@OpenAPIDefinition` title/description block was added to `OpenApiConfig`, which previously
  registered the `bearerAuth` scheme but had nothing referencing it, so Swagger UI showed an
  Authorize button that applied to no operation. `@Schema` examples were added on `ErrorResponse`
  only — the one DTO the packet permitted; the other 14 were deliberately excluded as depth (c)
  noise. New `src/test/java/com/seatvault/seat_vault/config/OpenApiDocumentationTest.java` (7
  tests) fetches `/v3/api-docs` via MockMvc and asserts, for all 13 operations: the operation
  exists, has a non-blank summary, its response-status set matches exactly (not as a superset),
  and its security requirement matches expected `bearerAuth` presence; also asserts
  `components.securitySchemes.bearerAuth` is registered as `type: http`, `scheme: bearer`.
- Status sets were derived from the code, not boilerplate. The builder walked each service
  method's actual `ApiException` throws, plus `GlobalExceptionHandler`'s three handler-owned codes
  and `SecurityConfig`'s `UNAUTHENTICATED` entry point. Review spot-checked this in both
  directions — a documented status an endpoint cannot return is as much a defect as a missing
  one — and found no over-documentation. It specifically verified the subtle `PAYMENT_NOT_FOUND`
  case: `confirm`, `cancel`, `getById` and `listMine` all route through `BookingService`'s private
  single-argument `toResponse(Booking)` helper, which can genuinely throw it, while `create` uses
  the three-argument overload and correctly does not document it.
- `bearerAuth` placement follows ADR-0004, not a GET/POST heuristic: venue, event and event-seat
  GETs carry none (caller-independent responses); `GET /api/auth/me`, all hold operations and all
  booking operations carry it.
- **A security defect was found and deliberately not fixed here — now tracked as T-008.**
  `GET /api/bookings/me` and `GET /api/bookings/{id}` are user-scoped but fall through
  `SecurityConfig`'s blanket `GET /api/**` permitAll; only `GET /api/auth/me` has a carve-out
  ahead of it. An anonymous request is admitted with no `Authentication`,
  `@AuthenticationPrincipal AuthenticatedUser` resolves to null, and `principal.id()` throws an
  NPE that `GlobalExceptionHandler`'s catch-all turns into a 500 rather than a 401. Confirmed
  empirically with a throwaway MockMvc probe (created, run, deleted — not in the diff) and
  independently verified against `SecurityConfig`'s matcher order. No data is disclosed today —
  the NPE fires before any repository call — but the auth boundary holds by accident of a null
  dereference rather than by configuration, and a future null-tolerant rewrite of that controller
  would convert it into a real leak. `SecurityConfig`'s own Javadoc already names "my bookings" as
  the example of a future user-scoped GET requiring a carve-out — M6 added exactly that endpoint
  without one, so a written instruction in the right file naming the right endpoint still failed
  to prevent the bug. T-008 therefore requires a test pinning the boundary rather than another
  comment.
- Review endorsed documenting the intended contract over current reality: both operations are
  annotated `bearerAuth` even though `SecurityConfig` does not currently enforce it. The
  alternative — omitting it because enforcement is absent — would make the generated document
  complicit in the bug by presenting an accidental gap as intended design, and would need
  reverting the moment T-008 lands. `BookingController` carries a class-level Javadoc explaining
  the gap and the NPE-to-500 mechanism, and `OpenApiDocumentationTest` asserts `bearerAuth` on
  both with a cross-reference comment.
- Code review verdict: APPROVED, no critical or major findings. All five priority questions
  verified against the actual service, security and exception-handler source rather than the
  diff's self-description. Two non-blocking observations: (a) ADR-0009's "cannot drift from the
  enum" guarantee is aspirational for the free-text `description` attributes specifically —
  `@ApiResponse`/`@ExampleObject` values must be compile-time constants, so
  `ErrorCode.X.name()`/`.getDescription()` cannot be referenced and literals are unavoidable;
  review checked several description/enum pairs and found no semantic drift, but the coupling is
  by convention rather than mechanically enforced; (b) a nit — the `// ErrorCode.X` line comments
  above several operations duplicate information already in the `@ApiResponse` descriptions.
- No production behaviour change — annotations and Javadoc only; review confirmed no method
  bodies changed and no logic smuggled into controllers.
- Test result: full `./mvnw test` green — 106 tests (99 + 7 new), 0 failures, 0 errors, 0 skipped,
  56.7s. `OpenApiDocumentationTest` passed standalone (21.5s). No status-set mismatches.
- Files touched: all six controllers in `src/main/java/com/seatvault/seat_vault/controller/`,
  `config/OpenApiConfig.java`, `dto/ErrorResponse.java`, and new
  `src/test/java/com/seatvault/seat_vault/config/OpenApiDocumentationTest.java`.
- No supplementary ADR: ADR-0004, ADR-0008 and ADR-0009 already govern everything decided here —
  nothing new surfaced.
- Milestone note: T-005 was M7's last originally-planned task. All of issue #12's deliverables are
  now implemented. The issue's second verification step — a manual Swagger UI smoke test —
  remains for the user, and T-008 remains open.

## 2026-08-15 — T-008 Booking read endpoints reachable anonymously (ADR-0004 violation)
- Origin: unplanned, found while annotating the API in T-005. M7's third unplanned task.
- Agents involved: builder (1 pass). **test-runner and code-reviewer both terminated on an API session limit before completing**, so verification was done by the orchestrator directly — see below.
- The defect: `SecurityConfig`'s matchers are first-match-wins, and only `GET /api/auth/me` had a carve-out ahead of the blanket `GET /api/**` permitAll. `GET /api/bookings/me` and `GET /api/bookings/{id}` fell through it, so an anonymous request was admitted with no `Authentication`, `@AuthenticationPrincipal` resolved to null, and `principal.id()` NPEd into a 500 instead of a 401. No data was disclosed — the NPE fires before any repository call — but the auth boundary held by accident of a null dereference rather than by configuration.
- Notable: `SecurityConfig`'s own Javadoc already named "my bookings" as the example of a user-scoped GET requiring a carve-out. M6 then shipped exactly that endpoint without one. A written instruction, in the right file, naming the right endpoint, did not prevent the bug — which is why this task required a test rather than another comment.
- Fix: extended the existing carve-out line to `.requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/bookings/me", "/api/bookings/{id}").authenticated()`, registered ahead of the blanket rule.
- Tests added (3): a general guardrail `OpenApiDocumentationTest#everyDeclaredBearerAuthOperationRejectsAnonymousRequestsWith401`, plus two explicit per-route tests in `BookingIntegrationTest` asserting the full 401 `UNAUTHENTICATED` body shape.
- **Orchestrator verification in place of the lost review**: full suite run directly — 109 tests, 0 failures, 1m35s. The general test was read and confirmed genuinely general: it enumerates paths from the live `/v3/api-docs` document, filters to operations declaring `security`, substitutes concrete values for path templates, fires each anonymously and asserts 401, with an `operationsChecked >= 6` floor guarding against a silent no-op walk. A future endpoint declaring `bearerAuth` without a matcher carve-out fails it automatically, with no edit to the test file.
- **Gap found by the orchestrator, tracked as T-009**: T-008 did not update the two operations' `@ApiResponse` sets, so the OpenAPI document still says neither can return 401 — 3 of `BookingController`'s 5 operations declare it, and the two that don't are exactly the two this task made return it. `OpenApiDocumentationTest` asserts status sets exactly rather than as a superset, specifically so drift fails loudly; because neither the annotations nor the expectations were updated, it instead pinned the wrong set and passed.
- Known residual, recorded in T-009: the general guardrail catches an operation that *declares* `bearerAuth` without a carve-out, but cannot catch a new user-scoped endpoint missing both the annotation and the carve-out.
- Builder's recommendation, not implemented: replace the blanket `GET /api/**` permitAll with explicit permitAll matchers for the three genuinely public read families (venues, events, event seats), making the default deny rather than allow, so a forgotten route fails closed instead of open. Deserves its own task and an ADR-0004 amendment rather than being folded into a security patch.
- Files touched: `config/SecurityConfig.java`, `controller/BookingController.java` (Javadoc), `src/test/.../config/OpenApiDocumentationTest.java`, `src/test/.../controller/BookingIntegrationTest.java`

## 2026-08-15 — T-009 Document the 401 T-008 made reachable on the two booking reads
- Origin: unplanned, found by the orchestrator while verifying T-008 by hand after that task's own reviewer died on an API session limit. M7's fourth unplanned task.
- Agents involved: builder (1 pass, no respin), test-runner, code-reviewer (1 pass, APPROVED).
- The gap: T-008 made `GET /api/bookings/me` and `GET /api/bookings/{id}` return 401 to anonymous callers but did not update their `@ApiResponse` sets, so the generated OpenAPI document still claimed neither could return 401. Three of `BookingController`'s five operations declared `responseCode = "401"`; the two that did not were exactly the two T-008 had just made return it.
- Worth recording for `/retro`: `OpenApiDocumentationTest` asserts each operation's status set exactly rather than as a superset — deliberately, so documentation drift fails loudly. It did not catch this, because T-008 changed the behaviour without touching either the annotations or the test's expectations, so the test simply pinned the stale set and passed. An exact-match assertion only detects drift when one side of it is updated; when a change moves the behaviour and leaves both the documentation and its expectation untouched, the strictness provides no signal at all.
- What was done: added `@ApiResponse(responseCode = "401")` with the `UNAUTHENTICATED` example to `BookingController.listMine` and `getById`, matching the pattern the other three booking operations already used. Updated `OpenApiDocumentationTest`'s exact-match expectations in lockstep: `/api/bookings/me` from `{200,404}` to `{200,404,401}`, `/api/bookings/{id}` from `{200,400,404}` to `{200,400,404,401}`. Review confirmed this tightened the expectations to match a corrected reality rather than loosening a failing assertion. Renamed `userScopedBookingReadsDeclareBearerAuthDespiteBeingUserScopedGets` to `userScopedBookingReadsDeclareBearerAuthAndDocumentTheirRealResponseSet`, since the old "despite" referred to the enforcement gap T-008 closed and had come to parse as saying nothing. Reworded `SecurityConfig`'s class Javadoc opening clause, which still said "plus any `GET /api/**` request are currently public" after three GETs had been carved out.
- Six-controller sweep, independently verified by review: only `BookingController` had the gap. `HoldController`'s two operations and `AuthController.me` already documented 401 correctly. `AuthController.register`/`login` are correctly public with no `bearerAuth` and no 401. `VenueController`, `EventController` and `EventSeatController` carry no `bearerAuth` at all and correctly document no 401. `HoldController` has no GET routes, so the blanket GET rule never affected it.
- Code review verdict: APPROVED for T-009, and — importantly — **APPROVED for T-008 as well**, since T-008 (commit 89a2b43) shipped without a code review after its reviewer terminated on an API session limit mid-run, and it was security-critical, so this review covered both. T-008's matcher was independently verified: `spring-security-web:7.1.0` defaults `requestMatchers(String...)` to `PathPatternRequestMatcher`, the same `PathPattern` parser Spring MVC uses, so `{id}` captures exactly one segment with semantics identical to the `@GetMapping` it protects — corroborated empirically by `BookingIntegrationTest#getByIdWithoutTokenIsUnauthorized` firing a real anonymous request through the live filter chain. A full route enumeration across all six controllers found no user-scoped GET still falling through the blanket permitAll, and no unintended widening (the `{id}` matcher is GET-only, so it does not touch `POST /api/bookings/{id}/confirm` or `/cancel`). The 401 body was confirmed to be the shared `ErrorResponse` with `ErrorCode.UNAUTHENTICATED`, not an empty body or container default page. Review also confirmed T-008's general guardrail is genuinely general rather than a per-route test in disguise: it pulls `paths` from the live `/v3/api-docs` document at runtime, filters to operations with a non-empty `security` array, substitutes path variables, and fires each anonymously asserting 401 — so a new endpoint declaring `bearerAuth` without a matcher carve-out fails it with no edit to the test file. Eight operations currently qualify against an `operationsChecked >= 6` floor; review noted the floor is looser than it could be (a nit, not worth reopening a merged commit).
- Residual gap, recorded as accepted: neither guardrail can catch a wholly new user-scoped endpoint that is missing both the `bearerAuth` annotation and the matcher carve-out, because nothing would mark it as user-scoped in the first place. Closing it needs a source of truth independent of the endpoint's own annotations — for example an ArchUnit rule tying `@AuthenticationPrincipal`-consuming methods to a required `@SecurityRequirement` and matcher pair. Both builder and review judged that a separate, larger piece of work; it is recorded rather than silently ignored, and is a candidate future task.
- Test result: full `./mvnw test` green — 109 tests, 0 failures, 0 errors, 0 skipped, 1m11s (count unchanged; this task corrects existing assertions rather than adding tests). `OpenApiDocumentationTest` (8) and `BookingIntegrationTest` (12) passed as a group.
- Files touched: `src/main/java/com/seatvault/seat_vault/controller/BookingController.java`, `src/main/java/com/seatvault/seat_vault/config/SecurityConfig.java` (Javadoc), `src/test/java/com/seatvault/seat_vault/config/OpenApiDocumentationTest.java`.
- No supplementary ADR: ADR-0004 and ADR-0009 already govern this task — nothing new was decided.
- Milestone note: with T-009 complete, every task in M7 is done — the five originally planned plus four added mid-milestone (T-006, T-007, T-008, T-009). Issue #12's first verification step (full suite green) is satisfied; the second (manual Swagger UI smoke test) remains for the user, along with the `security-review` gate, `/retro`, and closing the issue.
