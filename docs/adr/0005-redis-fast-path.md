# ADR 0005: Redis is a fast path for idempotency and rate limiting, never the source of truth

**Status:** accepted

## Context

`POST /api/transfers` pays money out. Clients retry on timeouts, so the same request can
arrive twice, sometimes at the same moment. The API requires an `Idempotency-Key` header
(as Stripe and Adyen do): the same key must return the same transfer.

Redis is the usual place for idempotency keys: fast, with TTLs. But Redis can lose keys
(eviction, a failover to a replica that missed the last writes, a flush) and can be down.

## Decision

Two layers:

1. **Redis (fast path).** `SET idem:<participant>:<key> "P|<hash>" NX EX 86400`.
   - The first request claims the key.
   - A concurrent duplicate sees "P" (in progress) and gets **409**.
   - A later duplicate sees "D|<hash>|<transfer id>" and gets the original transfer with
     **200** and `Idempotent-Replayed: true`.
   - The same key with a different body (different hash) gets **422**.
2. **PostgreSQL (source of truth).** `UNIQUE (participant_id, idempotency_key)` on
   `transfer`, with the request hash stored next to it.
   - If Redis forgot the key, the service finds the existing row and replays it.
   - If Redis is down, `IdempotencyService` lets the request through (and counts
     `ledger_idempotency_redis_unavailable_total`). Two racing duplicates then both reach
     the insert; the unique constraint keeps one, and the other is answered with it.

The **rate limiter** (fixed window per participant, `INCR` + `EXPIRE`) **fails open**. It
protects the service from a runaway client, not the money, so an outage of Redis should
not become an outage of payments.

Redis is not part of the readiness probe for the same reason.

## Consequences

- `TransferApiIT.theDatabaseStillDeduplicatesWhenRedisForgotTheKey` deletes the Redis key
  and retries: still one transfer. `IdempotencyServiceTest` covers Redis being down.
- Keys are unique per participant **forever** in the database, not only for 24 h. That is
  stricter than Stripe but safer for payments.
- While Redis is down, rate limiting is off. The `RedisUnavailable` alert makes that
  visible.
