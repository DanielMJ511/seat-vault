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
