# Public access is determined by whether the response depends on the caller, not by HTTP verb

M2 shipped a blanket `GET /api/**` `permitAll` rule as a placeholder, since M3's browse endpoints didn't exist yet to scope it against. The actual rule: a GET is public if and only if its result is the same regardless of who's asking (or whether anyone's asking at all) — catalog/availability endpoints like venue, event, and seat browsing. Any GET whose answer is scoped to "the current user" (my bookings, my holds, `/api/auth/me`) requires authentication despite being a GET.

## Consequences

M3 and later milestones must carve out explicit `authenticated()` matchers for any user-scoped GET route ahead of the general GET `permitAll` rule in `SecurityConfig` — matcher order matters, since Spring Security uses first-match-wins. A future dev extending the blanket rule to a new "my X" endpoint without an explicit carve-out would silently leak one user's data to any caller.
