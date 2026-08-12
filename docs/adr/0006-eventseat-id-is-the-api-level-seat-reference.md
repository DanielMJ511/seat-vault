# `EventSeat.id`, not `Seat.id`, is the API-level reference to a bookable seat

M3's `EventSeatResponse` and M4's `HoldRequest`/`HoldSeatResponse` all address a bookable unit by its `EventSeat` primary key, never the underlying `Seat`'s id. `Seat` is a static, event-independent physical location (CONTEXT.md); `EventSeat` is the actual bookable inventory row a Hold or Booking locks onto. Using `Seat.id` in these payloads would force every consumer to also carry an `eventId` to disambiguate which event's availability it means — `EventSeat.id` already encodes that.

## Consequences

Any future milestone (M5 Booking/Payment, M6 cancellation) referencing "a seat" in a request or response body must use the `EventSeat` id already returned by the browse/hold endpoints, never introduce a `Seat` id parameter. A client also can't address a seat before an `EventSeat` row exists for it — i.e. before an Event is scheduled against a Venue's seat map — which is fine, since nothing is bookable before then anyway.
