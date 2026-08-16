# SeatVault

[![CI](https://github.com/DanielMJ511/seat-vault/actions/workflows/ci.yml/badge.svg)](https://github.com/DanielMJ511/seat-vault/actions/workflows/ci.yml)

A seat-reservation backend for events — concerts, screenings, theatre — where specific numbered seats are booked against a scheduled event.

## What it is

Users browse an event's seat map, put specific seats on a temporary **hold** while they check out, and turn that hold into a **booking** once payment confirms. Holds expire on their own if checkout is abandoned, returning the seats to the pool.

The whole design problem is the middle of that sentence. A hold is a claim that has not been paid for yet, so the system has to hand out exclusive claims, expire them without a human in the loop, and survive many people wanting the same seat at the same instant — while never letting two users win the same seat. Everything else here is bookkeeping around that one guarantee.

## Why it's interesting

The concurrency guarantee is enforced by a full-pipeline test suite that runs real races — 20 threads contending for the same seats against real Postgres and Redis — and asserts exactly one winner. Writing that suite turned up three real defects that had passed both unit tests and code review:

**A row lock the ORM read around.** `SELECT ... FOR UPDATE` genuinely took the lock, but an earlier unlocked query in the same transaction had already put the seat in Hibernate's identity map — so the code decided using the snapshot from *before* the lock. Seats were genuinely double-booked. Not an isolation-level problem: it reproduces identically at `SERIALIZABLE`, because the stale value never came from the database on the second read at all. ([ADR-0010](docs/adr/0010-no-unlocked-entity-reads-before-a-row-lock.md), commit `267b533`)

**An ABBA deadlock with no visible second lock.** Two ordinary requests — releasing a hold while another user claimed one of its seats — deadlocked in Postgres because lock order between `holds` and `event_seats` was inverted on two paths. The line that inverted it was `staleHold.setStatus(EXPIRED)`, which doesn't read like lock acquisition at all: a dirtied entity becomes an `UPDATE` at flush time, and that takes the row lock at a point the caller never chose. Reviewing lock order by reading for lock *calls* misses this every time. ([ADR-0011](docs/adr/0011-event-seats-is-locked-before-holds-everywhere.md), commit `e51d37b`)

**Two user-scoped booking reads reachable anonymously.** `GET /api/bookings/me` and `GET /api/bookings/{id}` were matched by a permissive `GET /api/**` rule, so an unauthenticated caller got a 500 from the missing principal instead of a 401. The interesting part is what the fix did *not* do: patching the two routes left the shape that produced them intact — a default of allow, safe only while someone remembers to carve out every exception. The config's own comment had named "my bookings" as the case to watch for, and that didn't prevent it. So the chain was later inverted to deny by default, and a second rule now requires any handler consuming the caller's identity to declare that it needs a token — one makes a forgotten route fail closed, the other makes a forgotten annotation fail the build. (commits `89a2b43`, `daa4317`, `71f7da3`; [ADR-0004](docs/adr/0004-auth-boundary-is-response-identity-dependence-not-http-verb.md))

The [`docs/adr/`](docs/adr/) directory records all 11 decisions, including the ones that were rejected and why.

## Tech stack

Java 25 · Spring Boot 4.1 · PostgreSQL · Redis · Flyway · Maven · JUnit 5 · Mockito · Testcontainers

Two things that look like mistakes but aren't:

- **Boot 4 renamed the starters.** This project uses `spring-boot-starter-webmvc` (not `-web`) and per-technology test starters (`-data-jpa-test`, `-webmvc-test`, `-security-test`, …) rather than the classic unified `spring-boot-starter-test`. Testcontainers artifacts are renamed under Boot 4's BOM too (`testcontainers-junit-jupiter`, `testcontainers-postgresql`).
- **Integration tests use real Postgres and Redis**, started per-run by Testcontainers via `@ServiceConnection` — not H2, not mocks. A concurrency bug that only exists because of how Postgres takes row locks cannot be caught by anything less.

## Running it locally

Requires **JDK 25** and a running Docker daemon. If the default `java` on your PATH is older, point `JAVA_HOME` at a JDK 25 install first.

```bash
docker-compose up -d                                      # Postgres :5432, Redis :6379
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open **http://localhost:8080/swagger-ui.html**.

The `dev` profile matters. Without it Flyway loads schema only, and there is no venue, event, or seat to book against. The profile adds `classpath:db/seed` to Flyway's locations, which seeds demo data and two accounts:

| Email | Password |
| --- | --- |
| `alice@example.com` | `Password123!` |
| `bob@example.com` | `Password123!` |

Seed data is deliberately kept out of the default profile so demo accounts can never reach a non-dev environment.

### Or run it entirely in Docker

No local JDK needed — this builds the app image and starts it alongside Postgres and Redis:

```bash
docker compose --profile app up -d --build
```

The `app` service sits behind a compose profile, so plain `docker-compose up -d` still starts just the two backing services for local development.

## Running the tests

```bash
./mvnw test                      # whole suite
./mvnw test -Dtest=ClassName     # one class
```

The suite is 117 tests and takes about a minute and a half. Docker must be running — the integration tests start their own containers.

## API

| | |
| --- | --- |
| `POST /api/auth/register`, `/login`, `GET /api/auth/me` | Registration and JWT login |
| `GET /api/venues`, `/api/venues/{id}` | Venue and seat layout |
| `GET /api/events`, `/api/events/{id}` | Scheduled events |
| `GET /api/events/{eventId}/seats` | Seat map with live availability |
| `POST /api/holds`, `DELETE /api/holds/{id}` | Claim seats, give them up |
| `POST /api/bookings` | Turn a hold into a booking |
| `POST /api/bookings/{id}/confirm`, `/cancel` | Payment confirmation, cancellation |
| `GET /api/bookings/me`, `/api/bookings/{id}` | A user's own bookings |

Full request/response schemas are in the Swagger UI above, or at `/v3/api-docs`.

## Architecture

Three tiers, strictly: controller → service → repository. Controllers never touch repositories, entities are never returned from the API (DTOs are `record`s), and errors flow through one `ApiException` → `GlobalExceptionHandler` → `ErrorResponse` path rather than being assembled ad hoc.

Three decisions shape everything else:

- **Postgres is the concurrency authority** ([ADR-0001](docs/adr/0001-postgres-is-the-concurrency-authority.md)). Correctness rests on `SELECT ... FOR UPDATE` row locks inside a transaction. Redis sits in front as a fail-fast layer to shed obvious contention early, but it is explicitly *not* authoritative — if Redis is down or wrong, the database still cannot be made to oversell. There's an integration test that unplugs Redis and asserts exactly that.
- **Hold expiry is lazy** ([ADR-0002](docs/adr/0002-lazy-expiry-is-authoritative.md)). A hold whose window has passed is expired as a matter of fact, whether or not anything has noticed yet; readers evaluate expiry themselves rather than trusting a status column. A background sweeper tidies up, but nothing depends on it having run.
- **Authorization denies by default** ([ADR-0004](docs/adr/0004-auth-boundary-is-response-identity-dependence-not-http-verb.md)). A route is public only if its response is the same for everyone; the filter chain lists those routes and authenticates everything else, so a route added without an auth decision fails closed. Two tests enforce the boundary from opposite sides — one proves an unlisted route is denied, the other proves any handler consuming the caller's identity has declared that it requires a token.

Schema is owned entirely by Flyway migrations with `ddl-auto=validate`, so drift fails loudly at startup instead of being silently patched.

Start with [`CONTEXT.md`](CONTEXT.md) for the domain vocabulary, then [`docs/adr/`](docs/adr/) for the reasoning.

## Known follow-ups

Tracked as open issues rather than quietly left:

- [#16](https://github.com/DanielMJ511/seat-vault/issues/16) — `HoldSweepService` locks `event_seats` in plan-scan order rather than by id. The residual seat-vs-seat case left over from ADR-0011's table-vs-table fix: real, but it needs the 30-second sweep to fire mid-flight against an overlapping expired seat set.
- [#13](https://github.com/DanielMJ511/seat-vault/issues/13) — JWT access tokens are non-revocable. Deferred by design until account-management features exist to need it; the 10-minute expiry is what bounds the window meanwhile ([ADR-0005](docs/adr/0005-jwt-access-tokens-are-stateless-and-non-revocable.md)).
- [#3](https://github.com/DanielMJ511/seat-vault/issues/3) — Jackson 2 and Jackson 3 both ship in the jar, because `jjwt` and `springdoc` have no Jackson-3-native release yet. Nothing to do but wait.
- [#4](https://github.com/DanielMJ511/seat-vault/issues/4) — Testcontainers container reuse, filed when the suite was one class. Now measured as a smaller win than expected.

Out of scope for now, deliberately: event-cancellation cascade, refunds, and partial cancellation of a multi-seat booking. Payment is simulated.

## License

[MIT](LICENSE).
