# Readiness is a reachability probe, not a pool health check

`/actuator/health/readiness` answers one question: **can this instance reach Postgres?** It answers it over a fresh, unpooled connection with short explicit timeouts, deliberately bypassing the application's HikariCP pool. It does not answer "is the connection pool healthy", and a saturated pool does not make an instance unready.

ADR-0013 settled *which* dependencies are fatal to readiness. This settles *how promptly* that fatality is reported, and what the answer is measuring.

## The problem this fixes

Actuator's auto-configured `DataSourceHealthIndicator` validates the datasource by acquiring a real pooled connection. With HikariCP's default 30s `connection-timeout` unoverridden, a Postgres outage made the probe block for up to 30 seconds per call. Observed in M8/T-004: with `docker stop seatvault-postgres` and the app still running, a direct request to the readiness endpoint returned nothing at all within a 15-second client timeout.

The container healthcheck was never affected — `wget --timeout=3s` bounded each probe long before the app-side 30s could elapse — so nothing was broken. The reason to fix it anyway is not the stall itself but **what the stall consumes**. The pool is small (10 by default). During an outage, real user requests are already queued on connection acquisition, and a readiness probe firing every 10 seconds adds another consumer to that queue, each holding a request thread for its full wait. On a system whose entire design claim is behaviour under contention (ADR-0001), the health check becomes a thread- and connection-exhaustion amplifier at precisely the moment the system is least able to absorb one.

## Why not simply shorten Hikari's connection-timeout

That was #20's literal deliverable and it is the wrong fix, because `spring.datasource.hikari.connection-timeout` is **global** — it governs real user requests, not just the probe.

This application takes Postgres row locks with no lock timeout (`findByIdForUpdate`), so a thread blocked on a contended seat holds its connection for the whole wait. Under legitimate peak contention, threads waiting a while for a connection is the system working as designed, not a fault. A short global timeout converts that queueing into spurious 500s — which is exactly the false defect `application-test.properties` raises `maximum-pool-size` to 30 to avoid, and exactly what `NoOversellIntegrationTest` and `BookingConfirmLoadIntegrationTest` exist to treat as real failures.

So the property that makes readiness fast is the same property that makes the system fragile under load. They cannot be tuned independently while they are the same property. Decoupling is what makes them separable.

## The trade-off being accepted

A separate connection path can report `UP` while the application pool is exhausted. That correlation is genuinely lost, and it is worth being explicit that it was traded away rather than overlooked.

It is the right trade because **pool saturation is a load signal, not a readiness signal.** Readiness exists to tell an orchestrator whether to route traffic here, and an instance struggling under contention is one you must *not* evict: removing it sheds capacity onto its siblings and makes their contention worse. This is the same argument ADR-0013 used to keep Redis out of the readiness group — do not pull a working instance out of rotation — applied to a load condition instead of a dependency.

A pool-saturation indicator on the authenticated parent aggregate was considered and deliberately not built here. It is real observability, but it answers an operator's question, not an orchestrator's, and adding it under cover of this fix would be scope decided by momentum rather than by need.

## Consequences

**The probe must not be a `DataSource` bean.** `DataSourceAutoConfiguration$PooledDataSourceConfiguration` carries `@ConditionalOnMissingBean({DataSource.class, XADataSource.class})`, so registering any `DataSource` bean suppresses Boot's auto-configured application datasource entirely — a silent, catastrophic takeover. `DataSourceHealthContributorAutoConfiguration` separately composes `Map<String, DataSource>`, so a second one would also be folded into a composite `db` indicator. Both verified against `spring-boot-jdbc` 4.1.0 with `javap -v` rather than assumed. The replacement seam is the bean *name*: that autoconfiguration backs off on `@ConditionalOnMissingBean(name = {"dbHealthIndicator", "dbHealthContributor"})`, so a bean named `dbHealthIndicator` substitutes cleanly and still resolves as `db` in the readiness group.

**The connection is fresh every probe, and that is deliberate.** Pooling would defeat the semantics: a cached idle connection can answer `UP` at a moment when no *new* connection can be established, which is the failure mode being probed for. One TCP connect every ten seconds is not a cost worth optimising against correctness. The probe runs `SELECT 1` rather than connecting and closing, because a server that completes TCP and authentication but cannot serve queries — in recovery, or out of WAL disk — is down for our purposes, and connect-only would report it `UP`. Dropping the validation query would make this replacement strictly weaker than the Boot indicator it replaces.

**Timeouts are integer seconds, which bounds how aggressive this can be.** pgjdbc's `connectTimeout`, `socketTimeout` and `loginTimeout` are all whole seconds (`Integer.parseInt`, verified in `PGProperty` bytecode), so one second is the floor. The chosen values are `connectTimeout=2`, `socketTimeout=2`, `loginTimeout=3`.

**The worst case is ~5s — the original figure, reached by a route the original reasoning had wrong.** That estimate came from treating the three timeouts as composing additively inside one connect attempt. They do not compose at all inside a connect; only one of them ever binds there. What actually composes is the probe's **two phases**: the login phase, bounded by `loginTimeout` (3s), and the `SELECT 1` read that follows it, bounded by `socketTimeout` (2s) and untouched by `loginTimeout`. A server that answers login slowly and then stops answering pays both.

That 5s is the arithmetic sum of two separately measured phase bounds, not an end-to-end observation — producing it in one run needs a server that logs in at just under 3s and then freezes, which was not simulated. Each phase bound below was measured directly.

Method: `docker pause` on the Postgres container, probed by a standalone replica of `doHealthCheck` (same properties, same `SELECT 1`, no Spring), with the timeout values varied one at a time and the pause applied either before connecting or in a window held open between login and query. Pausing produced exactly the hang the earlier planning hoped for and could not obtain from `docker stop` — the kernel still completes the TCP handshake into the listen backlog while the frozen server never answers, so the probe reaches reads that a refused connection never gets near.

| Scenario | Values (c/s/l) | Elapsed | Bounded by |
|---|---|---|---|
| Frozen before connect | 2/2/3 | 3.03s | `loginTimeout` |
| Frozen before connect, `loginTimeout` disabled | 2/2/0 | 4.11s | `socketTimeout`, spent twice |
| Frozen before connect, `socketTimeout` disabled | 2/0/8 | 8.03s | `loginTimeout`, exactly |
| **Frozen after login, during `SELECT 1`** | 2/2/3 | **2.04s for the query alone** | `socketTimeout` |
| Blackholed address (packets dropped) | 2/2/3 | 2.07s | `connectTimeout` |
| Dead port (refused) | 2/2/3 | 0.08s | nothing — instant RST |

The fourth row is the one that sets the ceiling. `loginTimeout` is a deadline on login *only*; it has expired by the time the query runs, and the query gets its own full `socketTimeout`. Closing the broken connection afterwards cost 0ms, so teardown adds nothing.

*Sub-question 1 — does `socketTimeout` overlap with or run independently of the login-phase reads?* **Both, depending on the phase.** *During* login the two overlap: `loginTimeout` is a single overall deadline, `socketTimeout` a per-read limit, and whichever expires first ends the attempt — at the configured values `loginTimeout` always wins, so `socketTimeout` never binds there. *After* login they are fully independent: the `SELECT 1` read sees only `socketTimeout`. So the two do not sum within a phase and do sum across phases, which is why the ceiling is 3+2 rather than 3.

*Sub-question 2 — does each resolved address get a fresh `connectTimeout` budget?* **For multi-host URLs, yes.** A `blackhole,live` URL returned `UP` in 2.38s — a full `connectTimeout` spent on the dead host, then a normal connect — and `blackhole,refused` reported the *second* host's error at 2.09s, so the driver does walk the list. Moot for this application, whose `DB_URL` names a single host. One residual anomaly was observed and not explained: URLs with two or three *all*-blackholed hosts still returned in ~2.07s rather than 4s or 6s. Recorded as unexplained rather than rationalised; it cannot affect a single-host URL.

**A trap this measurement exposed: do not raise `loginTimeout` past 4 without re-measuring.** pgjdbc's default `sslmode=prefer` attempts SSL first and, on failure, retries the entire connection in plaintext — so a hung login spends `socketTimeout` *twice*. That is the 4.11s row above, confirmed by pinning the mode (`sslmode=disable` → 2.12s, `sslmode=prefer` → 4.12s). The 3s deadline currently fires before the second read completes. Raise it and this probe silently inherits the longer path.

**These values are coupled across two files, and nothing enforces the coupling.** The invariant is `loginTimeout + socketTimeout` (5s) < `HEALTHCHECK --timeout` (6s) < `--interval` (10s), the latter two in the `Dockerfile`. Note the left-hand side is the *sum* of two phase bounds, not `loginTimeout` alone — sizing against `loginTimeout` by itself is precisely the mistake that makes this invariant look safer than it is. Leaving `--timeout` at 3s would have meant our probe silencing the prompt 503 this decision exists to produce, reproducing the original opacity at smaller scale; 6s keeps probes from overlapping so the `healthy → unhealthy` transition timing is unchanged. The margin is 1s, thinner than the original sizing assumed, and it is spent on HTTP handling and `wget` startup. Overrunning it is a diagnosis loss rather than a correctness one — the healthcheck still records a failure, you just get `wget`'s ambiguous non-zero exit instead of the probe's own 503 naming the database. Breaking the ordering fails no build and reddens no test; it is asserted only by comments at both ends.

**What the 3.0s figure does not cover: DNS.** Hostname resolution happens before the socket is opened, and nothing measured here establishes that any of these three properties bound it. In the container the URL host is a Docker DNS name, so a resolver hang is a plausible path past this budget. Untested, and left recorded as untested rather than assumed in either direction.

Note also the asymmetry that makes these values cheap: a *refused* connection fails instantly whatever they are set to. They only bite where packets are dropped without an RST, or where a server accepts the connection and then stops answering.

**This partially supersedes ADR-0013's testability claim.** That ADR states Postgres-down cannot be tested the way Redis-down is, because the context cannot boot without a database. That was true when the health check shared the application datasource. Decoupling changes the layer: a test can now point *only* the health path at a dead port while the main datasource stays on a real container, so the context boots normally and readiness can be observed failing in-process — the same shape as `BrokenRedisTestConfig`, and the direct application of M8's retro lesson that "untestable" usually means "untestable at the layer I was thinking about."

What that test proves is bounded, and any test covering it must say so: it proves the fail-fast **mechanism** on a refused connection. It does not prove the timeout *values*, because a refused connection never reaches them, and it does not prove that the health path and the application datasource point at the same database — that is a wiring property, observable only at the container layer. A blackhole test against a reserved non-routable address was rejected: whether such an address drops or refuses depends on the host routing table, the Docker bridge and the CI provider's egress, so it would be green for reasons nobody controls — trading one environment-sensitive flake for another in a milestone whose purpose is removing them.
