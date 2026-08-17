# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# Wrapper and POM first, so the dependency layer is only rebuilt when the
# POM actually changes - not on every source edit.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/

# Tests are skipped here deliberately, and this is not a shortcut: the
# integration suite provisions real Postgres and Redis through
# Testcontainers, which needs a Docker daemon this build stage does not
# have. The suite runs on every push in .github/workflows/ci.yml, which is
# the right place for it.
RUN ./mvnw -B -q package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run unprivileged: nothing here needs root, and the JRE image defaults to it.
RUN addgroup -S seatvault && adduser -S seatvault -G seatvault

COPY --from=build /build/target/seat-vault-*.jar app.jar
USER seatvault

EXPOSE 8080

# Polls readiness, not the parent /actuator/health: the parent is authenticated
# (ADR-0012), so an anonymous healthcheck could not reach it even if it wanted
# to, and readiness is also the semantically correct target here -
# `depends_on: condition: service_healthy` in docker-compose.yml asks whether
# traffic may be routed to this container, not whether the process should be
# restarted. Polling the parent would additionally let Redis mark the app
# unhealthy, undoing ADR-0013's whole point (Redis is a fail-fast optimization,
# not the concurrency authority, so it must not be able to evict a working
# instance) through the back door.
#
# wget, not curl: this runtime base is Alpine/BusyBox, which ships wget but
# not curl - confirmed against eclipse-temurin:25-jre-alpine directly rather
# than assumed. `--spider -q` makes it a pure existence/status check with no
# output, exiting non-zero on anything but a 2xx/3xx response.
#
# start-period=40s covers Flyway migrations plus Spring context startup,
# which take far longer than Postgres/Redis's own healthchecks in
# docker-compose.yml (interval/timeout/retries: 5s/5s/5) budget for; failures
# during the start period don't count against retries, so a normal boot
# doesn't get flagged unhealthy or thrash the container.
#
# timeout=6s (widened from 3s - see ADR-0014/T-002): the readiness probe's
# own dbHealthIndicator bounds a Postgres outage at ~5s worst case. That is
# two measured phase bounds added together, not the single figure the earlier
# comment here implied: pgjdbc's loginTimeout (3.0s) caps the login phase, and
# socketTimeout (2.0s) separately caps the SELECT 1 read that follows, which
# loginTimeout has stopped governing by then. A blackholed host ends at
# connectTimeout instead (2.1s), and a merely refused connection fails in
# milliseconds. Leaving this at 3s would have this wget cut the request off
# before that probe could ever return its own prompt 503, reproducing the
# original opacity at smaller scale. 6s stays under the 10s interval, so
# probes still never overlap and the healthy -> unhealthy transition timing is
# unchanged - but the margin over the probe is 1s, not the 3s it looks like if
# you size against loginTimeout alone.
#
# INVARIANT, enforced by nothing but this comment and the one on
# PostgresReachabilityHealthIndicator: loginTimeout + socketTimeout (5s) <
# --timeout (6s) < --interval (10s). Change any of them and check the others.
HEALTHCHECK --start-period=40s --interval=10s --timeout=6s --retries=3 \
  CMD wget --spider -q http://localhost:8080/actuator/health/readiness || exit 1

# Every setting this image needs is already environment-driven in
# application.properties: DB_URL, DB_USER, DB_PASSWORD, REDIS_HOST,
# REDIS_PORT, JWT_SECRET. Set JWT_SECRET to a real value outside dev: the
# built-in placeholder is rejected at startup by JwtProperties unless the
# 'dev' or 'test' profile is active, so the container will refuse to boot
# rather than sign tokens with a public secret.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
