# ADR 0004: publish ledger events through a transactional outbox

**Status:** accepted

## Context

Other systems (notifications, reporting, risk) need to know when a trade settles or a
transfer is paid. Publishing to Kafka directly from the settlement code has two failure
modes:

- publish, then the database commit fails: consumers hear about a settlement that did not
  happen;
- commit, then the publish fails or the process dies: the settlement happened and nobody
  hears about it.

This is the "dual write" problem. Distributed transactions (XA) across PostgreSQL and
Kafka are not an option.

## Decision

- The business transaction inserts the event into an `outbox` table (`OutboxWriter`). The
  event exists if and only if the change it describes committed.
- `OutboxRelay` runs every 200 ms. It claims up to 100 unpublished rows with
  `FOR UPDATE SKIP LOCKED`, sends each to `ledger.events` (key = aggregate id, headers
  `event_type` and `event_id`), waits for the broker's acknowledgement (`acks=all`,
  idempotent producer), and marks the rows published.
- Several replicas can relay at once: SKIP LOCKED gives each one different rows.

## Consequences

- Delivery is at-least-once. If the relay dies after Kafka acknowledged but before its
  UPDATE committed, the rows are sent again. Consumers deduplicate on `event_id`
  (`SettlementIT.settlementsArePublishedThroughTheOutbox` checks the event arrives).
- Events of one aggregate are in order on one partition. Events of different aggregates
  claimed by different replicas can interleave, which is fine because no consumer needs a
  global order.
- If Kafka is down, the business keeps working and the outbox grows.
  `ledger_outbox_backlog` shows it, and the `OutboxBacklogGrowing` alert fires after 5 min.
- The table grows forever. In production, published rows older than a few days would be
  deleted by a scheduled job or by partitioning the table by day. Change data capture
  (Debezium reading the WAL) is the alternative to polling at higher volumes.
