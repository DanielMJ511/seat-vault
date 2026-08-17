# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# SeatVault - System Guidelines

## Project Overview
SeatVault is an event/venue seat-reservation backend (concerts, movies, theaters — specific numbered seats within sections/rows, booked per scheduled event). The core engineering challenge is correctness under concurrency: seats are temporarily held with an expiry window during checkout, and a booking isn't final until a payment-confirmation step succeeds, all while preventing two users from ever winning the same seat.

## Tech Stack
- Java 25
- Spring Boot 4.x — note the starter naming: this project uses `spring-boot-starter-webmvc` (not the old `-web`) and per-technology test starters (`spring-boot-starter-data-jpa-test`, `-data-redis-test`, `-flyway-test`, `-validation-test`, `-webmvc-test`, `-security-test`) instead of the classic unified `spring-boot-starter-test`. Testcontainers artifacts are also renamed under Boot 4's BOM (`org.testcontainers:testcontainers-junit-jupiter`, `testcontainers-postgresql`, not the old unqualified names). Check the effective POM before assuming Boot 3.x artifact names are correct.
- PostgreSQL (Database)
- Redis (Caching & Distributed Locking)
- Flyway (Database Migrations)
- Maven (Build Tool)
- JUnit 5, Mockito, Testcontainers (Testing) — integration tests run against real Postgres/Redis containers via `@ServiceConnection`, not H2 or mocks. Docker must be running locally for `./mvnw test` to pass.

## Commands
- Build: `./mvnw compile`
- Run all tests: `./mvnw test`
- Run a single test class: `./mvnw test -Dtest=ClassName`
- Run a single test method: `./mvnw test -Dtest=ClassName#methodName`
- Run the app locally: `./mvnw spring-boot:run`
- Start local Postgres + Redis for dev/tests: `docker-compose up -d` (Postgres on 5432, Redis on 6379; db/user/password are all `seatvault` — matches the defaults in `application.properties`)
- Requires JDK 25. If the default `java` on PATH is older, point `JAVA_HOME` at a JDK 25 install before running Maven.

## Architecture Guidelines
- Follow 3-tier architecture: Controller -> Service -> Repository.
- Controllers MUST NOT interact directly with Repositories.
- Use `record` classes for DTOs. Do NOT expose JPA `@Entity` objects in API responses.
- Enforce constructor injection (use `@RequiredArgsConstructor` or explicit constructors). No `@Autowired` on fields.
- Use Spring `@Transactional` explicitly with proper propagation and isolation levels for booking logic.
- Error handling: services throw `ApiException(HttpStatus, code, message)` (`src/main/java/com/seatvault/seat_vault/exception/ApiException.java`); `GlobalExceptionHandler` (`@RestControllerAdvice` in the same package) translates any `ApiException` — and any unhandled exception, as a 500 fallback — into the shared `ErrorResponse` record (`dto/ErrorResponse.java`). Don't build ad hoc error responses in controllers.

## Coding Conventions
- Read existing files before introducing new abstractions.
- Keep methods small, single-responsibility, and testable.
- Write Flyway SQL scripts (`V1__...sql`, `V2__...sql`) for any schema changes. Do NOT rely on `ddl-auto: update` — `application.properties` sets `spring.jpa.hibernate.ddl-auto=validate` deliberately; schema drift should fail loudly, not get silently patched.

## Git Guidelines
- Commits must be clean, semantic, and focused (e.g., `feat: add pessimistic lock on seat reservation`, `test: verify concurrent booking behavior`).
- Run `./mvnw test` before pushing any commit. Use the wrapper, not a bare `mvn` — the wrapper pins Maven 3.9.16, which is what CI runs; whatever `mvn` is on PATH may be a different version.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (uses the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Milestone roadmap

The M0-M7 implementation plan is tracked as GitHub issues labeled `milestone` (M0 = #5 ... M7 = #12), chained in order via native GitHub blocking dependencies. Run `gh issue list --label milestone --state all` to see current status before starting work on any milestone. Each issue holds that milestone's goal/deliverables/verification steps; the full original design detail (domain model, concurrency design, API surface, migration plan) lives in `CONTEXT.md` and `docs/adr/`.

### Domain docs

Single-context layout — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Loop engineering

Milestone implementation runs through a foreground, session-driven loop (no scheduled/unattended automation): `/plan-milestone` derives a task queue from the next open milestone issue and grills it with `mattpocock-skills:grill-with-docs` before any code is written (writing decisions to `docs/adr/` and `CONTEXT.md`); `/orchestrate` drives each task through `builder` → `test-runner` → `code-reviewer` → `docs-writer`, escalating Sonnet → Opus (`implementer`) on repeated failure; `/loop-handoff` checkpoints an in-progress session; `/retro` banks recurring friction into `loop/LESSONS.md`, retiring any lesson that has since become permanent skill text. Every lesson is tagged with its audience (`[planning]`, `[builder]`, `[reviewer]`, `[docs]`) and each stage is handed only its own slice, so a constraint reaches the stage that can act on it — `/plan-milestone` reads the `[planning]` slice, `/orchestrate` distributes the rest at spawn time. Working state lives in `loop/` (`PLAN.md`, `STATE.md`, `LESSONS.md`, `HANDOFF.md`, `tasks/`) — regenerated per milestone, distinct from the permanent `docs/adr/`/`CONTEXT.md` records.