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
