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
