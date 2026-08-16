# Actuator shares the application's filter chain, and reaches it through an explicit allowlist

Spring Boot Actuator runs on the same port and the same `SecurityFilterChain` as the API. The health group paths are the only ones granted `permitAll`, listed route by route; `/actuator/metrics` is exposed over HTTP but has **no matcher at all**, so ADR-0004's `anyRequest().authenticated()` catches it. Endpoint exposure is an explicit allowlist (`management.endpoints.web.exposure.include=health,metrics`), never `*`.

## Why the chain, and not a management port

A separate `management.server.port` is the conventional production answer, and it was rejected deliberately. Boot gives the management port its own filter chain, so choosing it would mean two authorization postures to reason about instead of one — in a codebase whose stated security posture (ADR-0004) is precisely that there is *one* chain and its default is deny.

More to the point, it would route around the decision rather than make it. #14 inverted this chain to deny-by-default; the value of that inversion is that a new endpoint is unreachable until somebody writes down why it should be reachable. Moving actuator to a port where that chain does not apply would restore the default-allow shape one listener over instead of one path prefix over.

## The absent matcher is the mechanism

`/actuator/metrics` is protected by nothing being written about it. That is not an oversight to be tidied up later — it is the inversion working, and it is the cheapest possible demonstration of why #14 mattered. Exposure (does this endpoint exist over HTTP?) and authorization (who may call it?) are separate levers, and this uses both: exposed so it can be read at all, unmatched so only an authenticated caller reads it.

A blanket `/actuator/**` permitAll would have been the natural way to make health reachable, and it would have silently published `/actuator/env` and `/actuator/configprops` — which serialize `security.jwt.secret` and the datasource password — the moment anyone widened the exposure list. The health paths are therefore enumerated individually, matching how the public catalog reads are already listed in `SecurityConfig`.

## What an anonymous caller learns

`show-components=always` with `show-details=when-authorized`. An anonymous caller sees each indicator's `UP`/`DOWN` and nothing else; an authenticated one sees the detail payloads. The default (`show-details=never`) would have hidden which dependency was down, which ADR-0013 needs visible. The opposite (`show-details=always`) would have published the database engine, the Redis version, and a container filesystem path to anyone, permanently — small individually, and exactly the free reconnaissance a deny-by-default posture exists to withhold.

## The accepted cost: nothing can scrape the metrics

This is the part a future reader will want explained. Tokens in this system are user JWTs that expire in ten minutes and cannot be revoked (ADR-0005). No scraper can hold one. So `/actuator/metrics` — including the sweep-volume metrics, the one custom instrumentation this system carries — is readable only by a human who logs in and asks for it.

That is **known and accepted**, not an unfinished edge. It is the honest scope of a milestone whose own non-goals rule out alerting, dashboards, and log aggregation: the deliverable is a number the system can report, not a pipeline that carries it somewhere. The counter's continuous proof of correctness is its test, not a scrape.

The trap this records is the tempting one-line "fix": adding `permitAll` for `/actuator/metrics` so a scraper can reach it. That publishes contention rates and JVM internals to the internet to solve a problem nobody currently has. If real scraping is ever needed, the right move is the one rejected above — a management port bound to an internal network — and it should be taken as a deliberate reopening of this decision, with the ADR updated, rather than as a matcher tweak.

## Consequences

Anyone authenticated can read `/actuator/metrics`, because this application has no roles — nothing uses `hasRole`/`hasAuthority`, and `ErrorCode.ACCESS_DENIED` is documented as unreachable for that reason. "Authenticated" therefore means "any user who registered." Narrowing it would require introducing an authority model, which is a larger change than this boundary is worth today.

`springdoc.show-actuator` remains at its default of `false`, so actuator paths stay out of `/v3/api-docs`. `OpenApiDocumentationTest`'s runtime walk enumerates that document to fire anonymous requests at every operation declaring `bearerAuth`; were actuator endpoints to appear there, that guard would start making assertions about endpoints it was never written for.
