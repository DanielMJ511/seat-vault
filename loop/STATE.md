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
