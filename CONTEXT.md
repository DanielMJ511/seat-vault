# SeatVault

SeatVault is an event/venue seat-reservation backend — concerts, movies, theaters — where specific numbered seats are booked per scheduled event. The core challenge is correctness under concurrency: seats are held temporarily during checkout and a booking isn't final until payment is confirmed, while two users must never win the same seat. Single bounded context — no sub-contexts (e.g. Payment) exist yet.

## Language

**Venue**:
A physical location containing a fixed layout of Seats, grouped into sections and rows.

**Event**:
A specific scheduled happening (concert, show, screening) at a Venue, against which EventSeats are booked.

**Seat**:
A specific physical, numbered location within a Venue (section, row, seat number) — static, independent of any Event.
_Avoid_: Ticket.

**EventSeat**:
The bookable inventory unit for one Seat at one specific Event; the single source of truth for availability (AVAILABLE, HELD, or BOOKED).
_Avoid_: Ticket, Inventory.

**Hold**:
A temporary, expiring claim on one or more EventSeats (capped at 8 per Hold, to prevent bulk-hoarding) made while a user is checking out, before payment is confirmed.
_Avoid_: Reservation.

**Booking**:
The purchase record for one or more EventSeats, created from a Hold and tracked from PENDING through CONFIRMED, FAILED, or CANCELLED.
_Avoid_: Reservation, Order.

**Price Snapshot**:
The EventSeat price captured at Hold-creation time and carried through unchanged to the Booking, protecting the buyer from a price change occurring mid-checkout.

**Payment**:
The confirmation step that finalizes a Booking. A real domain concept — a Booking must be paid for before it's confirmed — independent of whether the underlying provider is simulated or real.
_Avoid_: Transaction, Charge, PaymentAttempt.

**User**:
The single identity representing both the authenticated account and the party booking seats. No separate "Customer" concept exists yet.
_Avoid_: Customer, Account.

## Explicit non-goals (MVP)

- **Event cancellation cascade**: what happens to EventSeats/Holds/Bookings when an Event itself is cancelled is not handled by the system yet — a deliberate scope boundary, not an oversight.
- **Refunds**: cancelling a CONFIRMED Booking releases its EventSeats back to AVAILABLE but does not touch the linked Payment (stays SUCCEEDED) — no Refund concept exists. Payment scope is simulated only.
- **Partial booking cancellation**: cancellation is all-or-nothing on the whole Booking; there is no per-seat cancellation within a multi-seat Booking.
