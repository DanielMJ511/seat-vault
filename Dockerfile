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

# Every setting this image needs is already environment-driven in
# application.properties: DB_URL, DB_USER, DB_PASSWORD, REDIS_HOST,
# REDIS_PORT, JWT_SECRET. Set JWT_SECRET to a real value outside dev: the
# built-in placeholder is rejected at startup by JwtProperties unless the
# 'dev' or 'test' profile is active, so the container will refuse to boot
# rather than sign tokens with a public secret.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
