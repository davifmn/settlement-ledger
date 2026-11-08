# ADR 0003: at-least-once Kafka delivery made effectively-once by an idempotent consumer

**Status:** accepted

## Context

Trades arrive on `trades.executed`. Kafka guarantees at-least-once delivery to a consumer
that commits offsets after processing: after a crash or a rebalance, records since the
last committed offset are delivered again. Settling a trade twice would move the cash and
the shares twice.

Kafka's "exactly-once" (transactions) only covers Kafka-to-Kafka pipelines. Here the
result is written to PostgreSQL, so Kafka transactions cannot make the database write and
the offset commit atomic.

## Decision

- The settlement transaction starts with
  `INSERT INTO trade_settlement (trade_id, ...) ... ON CONFLICT (trade_id) DO NOTHING`.
  If no row was inserted, the trade was already handled and the consumer returns at once.
  Two concurrent copies of the same trade (after a rebalance) both try the insert. The
  second waits on the primary key until the first commits, then does nothing.
- The ledger entry, the settlement row and the outbox event are written in **the same
  transaction**. After it commits, the listener returns and the container commits the
  offset. A crash between the two causes a redelivery, which is a no-op.
- Messages are keyed by symbol, so the trades of one instrument stay in order on one
  partition.
- **Errors:**
  - An invalid message (unreadable JSON, bad price, unknown currency) can never succeed.
    `DefaultErrorHandler` sends it straight to `trades.executed.DLT`.
  - Any other error (database down, deadlock, timeout) is retried with exponential backoff
    (200 ms up to 30 s) **without limit**. Skipping a valid trade would leave the books
    wrong, so the partition waits instead and the consumer-lag alert fires.
  - A business failure (insufficient cash or securities, unknown participant) is not an
    error: the trade is recorded as FAILED with a reason, and a `SettlementFailed` event
    is published.

## Consequences

- `SettlementIT.aRedeliveredTradeSettlesOnce` publishes the same trade twice and finds one
  journal entry.
- Correctness depends on `trade_id` being unique and stable at the source. For
  matching-engine that means the drop copy derives it from the instrument and the trade
  sequence number (see docs/integration-matching-engine.md).
- Failed trades stay failed. A real post-trade system has a "fails management" process
  that retries them (for example when the buyer is funded) and eventually buys in. That
  is out of scope here.
