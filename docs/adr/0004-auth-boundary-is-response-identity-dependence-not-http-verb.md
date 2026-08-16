# Public access is determined by whether the response depends on the caller, not by HTTP verb

M2 shipped a blanket `GET /api/**` `permitAll` rule as a placeholder, since M3's browse endpoints didn't exist yet to scope it against. The actual rule: a GET is public if and only if its result is the same regardless of who's asking (or whether anyone's asking at all) — catalog/availability endpoints like venue, event, and seat browsing. Any GET whose answer is scoped to "the current user" (my bookings, my holds, `/api/auth/me`) requires authentication despite being a GET.

## The boundary is enforced by a deny-by-default chain, not by enumerated exceptions

*(Amended by #14. The original consequence is preserved below, because how it failed is the argument for what replaced it.)*

`SecurityConfig` now enumerates the public routes — the two auth entry points, the five catalog reads, the API docs — and ends at `anyRequest().authenticated()`. The rule above is unchanged; what changed is which way a mistake falls. "Forgot to carve out an exception" fails open and stays invisible until someone notices data leaking. "Forgot to permit a public route" fails closed with a 401 and is obvious the first time anyone calls it.

The catalog reads are listed route by route rather than as a prefix. A prefix would make any future route under `/api/events/**` public by inheritance, which is the same failure mode one level down.

Two consequences are accepted deliberately:

- **An unmapped path under `/api` now returns 401 rather than reaching the dispatcher.** A caller who typos a URL learns only that they are unauthenticated. That is the normal cost of deny-by-default and is worth more than the diagnostic precision it removes. (Before this change such a path produced a *500*, not a 404 — the blanket rule let it through to handler resolution, and `GlobalExceptionHandler`'s catch-all turned the resulting `NoResourceFoundException` into an internal error. That is a separate defect in the error handler, still live for authenticated callers.)
- **A user-scoped route whose path matches an existing public pattern is still permitted.** A hypothetical `GET /api/events/mine` would slide under `/api/events/{id}`. Deny-by-default cannot see this, because from the matcher's point of view the route *is* on the public list.

That second residual is covered from the opposite direction by the guardrail added in #15: such a handler must take `@AuthenticationPrincipal` to know who is asking, which obliges it to declare `@SecurityRequirement(name = "bearerAuth")`, which puts it in the set that `OpenApiDocumentationTest`'s runtime walk fires anonymously and requires a 401 from. Configuration makes a forgotten *route* fail closed; the annotation rule makes a forgotten *declaration* fail the build. Neither alone is sufficient, and the gap each leaves is the one the other covers.

## Original consequence (superseded)

M3 and later milestones must carve out explicit `authenticated()` matchers for any user-scoped GET route ahead of the general GET `permitAll` rule in `SecurityConfig` — matcher order matters, since Spring Security uses first-match-wins. A future dev extending the blanket rule to a new "my X" endpoint without an explicit carve-out would silently leak one user's data to any caller.

This is worth keeping visible because it is exactly what happened. M6 shipped `GET /api/bookings/me` and `GET /api/bookings/{id}` with no carve-out; both were served to anonymous callers until T-008 (#12) patched them. The paragraph above had named "my bookings" as the example to watch for. A correct warning, in the right file, naming the right route, did not prevent the bug — which is the case for a structural default over a documented convention.
