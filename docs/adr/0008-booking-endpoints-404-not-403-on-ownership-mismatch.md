# Booking endpoints return 404, not 403, when a booking exists but isn't owned by the caller

`confirmPayment` already did this implicitly — locking the booking row via `findByIdForUpdate` and rejecting "not found" and "not owned" identically. M6 generalizes it into an explicit rule for the whole Booking resource family: `GET /api/bookings/{id}`, `POST /api/bookings/{id}/cancel`, and `POST /api/bookings/{id}/confirm` never distinguish "this ID doesn't exist" from "this ID belongs to someone else" — both return 404. A caller who isn't the booking's owner gets no signal that the ID is otherwise valid.

## Consequences

Any future endpoint added to the Booking family must deliberately carry this convention forward rather than defaulting to 403 for an ownership mismatch — a well-intentioned "fix" toward 403 for API friendliness would silently reopen a booking-ID enumeration leak that was closed here on purpose. The cost is accepted deliberately: legitimate API consumers debugging a wrong ID get a less specific signal (404 either way) in exchange for not letting unauthorized callers learn that a given booking ID exists at all.
