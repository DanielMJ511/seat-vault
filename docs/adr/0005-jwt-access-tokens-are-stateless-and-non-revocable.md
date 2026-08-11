# JWT access tokens are stateless and non-revocable; short expiry is the only mitigation

There is no session store, refresh-token table, or blocklist — an issued JWT stays valid until it naturally expires, even after a password reset or a client-side "logout." We accept this to keep auth checks a pure in-memory signature verification with no Redis/DB round-trip on every request, preserving throughput for booking under load. To bound the exposure window given no revocation exists, M2's default access-token lifetime was cut from 60 to 10 minutes.

## Consequences

A compromised token or a password reset doesn't immediately invalidate outstanding tokens — they remain usable for up to 10 minutes. Real revocation (most likely a stateful refresh-token rotation pattern backed by Redis, consistent with Redis's existing role as a non-authoritative optimization layer — see [ADR-0001](./0001-postgres-is-the-concurrency-authority.md)) is deferred until user-account-management features actually need it, rather than built speculatively now. Tracked as [issue #13](https://github.com/DanielMJ511/seat-vault/issues/13).
