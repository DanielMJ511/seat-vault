# SeatVault - System Guidelines

## Tech Stack
- Java 25
- Spring Boot 
- PostgreSQL (Database)
- Redis (Caching & Distributed Locking)
- Flyway (Database Migrations)
- Maven (Build Tool)
- JUnit 5, Mockito, Testcontainers (Testing)

## Architecture Guidelines
- Follow 3-tier architecture: Controller -> Service -> Repository.
- Controllers MUST NOT interact directly with Repositories.
- Use `record` classes for DTOs. Do NOT expose JPA `@Entity` objects in API responses.
- Enforce constructor injection (use `@RequiredArgsConstructor` or explicit constructors). No `@Autowired` on fields.
- Use Spring `@Transactional` explicitly with proper propagation and isolation levels for booking logic.

## Coding Conventions
- Read existing files before introducing new abstractions.
- Keep methods small, single-responsibility, and testable.
- Write Flyway SQL scripts (`V1__...sql`, `V2__...sql`) for any schema changes. Do NOT rely on `ddl-auto: update`.

## Git Guidelines
- Commits must be clean, semantic, and focused (e.g., `feat: add pessimistic lock on seat reservation`, `test: verify concurrent booking behavior`).
- Run `mvn test` before pushing any commit.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (uses the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context layout — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.