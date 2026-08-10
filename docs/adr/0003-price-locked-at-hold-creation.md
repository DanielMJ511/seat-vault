# Price is locked in at Hold creation, not read live at confirm time

An EventSeat's price is copied onto the Hold (and carried through to the Booking) the moment the Hold is created, rather than being re-read from EventSeat when payment is confirmed. This protects a buyer from a price change happening during their checkout window silently altering what they're charged for seats they already believe they've claimed.

## Consequences

Requires a price-snapshot column on the Hold/Booking line items rather than just joining to EventSeat for price, and means a price change made after a Hold exists never affects that Hold's Booking — intentional, since the alternative (live pricing) would be surprising and hostile to a user mid-checkout.
