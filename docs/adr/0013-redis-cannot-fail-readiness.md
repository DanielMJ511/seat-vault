# Redis cannot fail readiness, and the health aggregate is not asked to lie about it

Readiness is composed explicitly — `management.endpoint.health.group.readiness.include=readinessState,db` — so Postgres failing takes the instance out of rotation and Redis failing does not. Redis is absent from that list and present everywhere else: the top-level `/actuator/health` aggregate still contains its indicator and still reports `DOWN` when Redis is unreachable. Nothing is configured to suppress, downgrade, or misreport it.

## This is a consequence of ADR-0001, not a convenience

Actuator auto-configures a Redis health indicator whenever Spring Data Redis is on the classpath (`management.health.redis.enabled` defaults to `true`), and by default every indicator contributes to the aggregate. Left alone, Redis going down would make the health endpoint report `DOWN`, return 503, and cause an orchestrator to pull a **perfectly functional instance** out of rotation.

ADR-0001 says Redis is not the concurrency authority — it is a fail-fast optimization layer, and correctness is Postgres row locks plus the uniqueness constraint. `HoldRedisUnavailableRaceIntegrationTest` exists to prove the system stays correct with Redis unreachable. A readiness check that treated Redis as fatal would assert the exact opposite of what the architecture claims and what that test demonstrates. So this is a correctness statement about the dependency model, and the reason it lives in configuration rather than in a comment is that #18 asked for the ADR-0001 relationship to be *visible* rather than implied.

The configuration is unusually good at showing it. Boot's default `readiness` group contains only `readinessState` — **`db` is not a member unless you say so** — so the line has to name Postgres explicitly and conspicuously not name Redis. The two-tier dependency model is legible in one property.

## Why not a custom indicator, and why not a status aggregator

The aggregator route does not exist. `StatusAggregator.getAggregateStatus(Set<Status>)` receives a set of statuses and **no component names**, so a custom aggregator cannot selectively ignore Redis while keeping Postgres fatal. It has no way to tell which status came from where. This was verified against `spring-boot-health` 4.1.0 rather than assumed, because it is the first thing anyone will reach for.

That leaves a custom `HealthIndicator` bean named `redis` that always reports `UP` and carries real reachability in its details — rejected. Combined with `show-components=always` (ADR-0012), an anonymous caller would see `"redis":{"status":"UP"}` while Redis was down. A health endpoint is the one surface in a system whose entire purpose is to state what is true; making it report `UP` for a component that is `DOWN` trades a small configuration convenience for a false statement, and would also mean the architecture lived in a Java class instead of in one readable line.

## The departure from #16/#18's wording

Issue #19's verification reads *"health reports UP with Redis stopped."* Under this decision the **parent** `/actuator/health` reports `DOWN` in that scenario; **readiness** reports `UP`. The intent of that verification — do not evict a working instance — is fully met, and the parent aggregate being honestly degraded is the better system: an operator asking "is anything wrong?" should be told yes, because something is. The literal wording is not met, deliberately, and this paragraph is the record of that rather than a quiet reinterpretation.

## Consequences

`management.endpoint.health.validate-group-membership` defaults to `true`, so a typo in the include list fails at **startup** instead of silently dropping a member. That is a real guard and it comes free with putting the composition in configuration; the rejected custom-indicator approach had no equivalent.

The two dependencies are not equally testable, and the tests say so rather than implying parity. Redis-down is proven end to end: `BrokenRedisTestConfig` points the connection factory at a dead port, producing genuine `DataAccessException`s through the real Spring Data Redis stack while staying contained to one Spring context, and hold creation is asserted to still succeed. Postgres-down cannot be tested the same way, because the context cannot boot without a database — Flyway migrates at startup and `ddl-auto=validate` runs after it. What is asserted instead is that the live readiness group contains `db` and does not contain `redis`, which fails the moment anyone edits the composition; that a failing `db` indicator then produces `DOWN` is Boot's own behaviour, not ours. Any test covering this must say plainly in its Javadoc which half it proves.

The parent aggregate requires authentication to read (ADR-0012), so its honesty is available to an operator and not to the internet. The container healthcheck therefore polls the readiness group, which is both the anonymous path and the semantically correct one — `depends_on: condition: service_healthy` asks whether traffic may be routed here, not whether the process should be restarted.
