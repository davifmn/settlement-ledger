# Runbook

One section per alert in `deploy/prometheus/alerts.yml`. Each section answers three
questions: what it means, how to confirm it, and what to do. The Grafana dashboard
"Settlement ledger" (`deploy/grafana/dashboards/`) shows every signal used below.

Useful endpoints (port 8080):

| Endpoint | What it tells you |
|---|---|
| `GET /api/admin/invariants` | the three ledger invariants, plus the outbox backlog |
| `GET /api/settlements?status=FAILED` | the latest failed trades and why |
| `GET /api/transfers?status=REJECTED` | rejected transfers, with ISO reason codes |
| `GET /actuator/health/readiness` | database reachability (what Kubernetes routes on) |
| `GET /actuator/prometheus` | all metrics |

## LedgerInvariantBroken

**Meaning:** an entry does not balance, a balance differs from the sum of its postings,
or an asset does not sum to zero. The database constraints should make this impossible,
so something bypassed them: a manual SQL change, a restored backup, or a bug in a
migration.

**Confirm:** `curl :8080/api/admin/invariants`. The report names the counts and the
assets that are off. Then, in SQL:

```sql
-- accounts whose balance drifted from their postings
select a.id, a.participant_id, a.asset, a.balance, coalesce(sum(p.amount), 0) as postings
from account a left join posting p on p.account_id = a.id
group by a.id having a.balance <> coalesce(sum(p.amount), 0);
```

**Act:**
1. **Stop the money from moving.** Scale the ledger to 0 replicas, or pause the consumer
   group. The trades wait safely in Kafka.
2. Find the first bad entry (`journal_entry.created_at`) and what ran at that time (deploys,
   migrations, manual SQL in the audit log).
3. Fix it with a **new correcting entry**, never by editing postings (they are append-only
   by design).
4. Re-check the invariants before scaling back up. Write a post-mortem.

## OutboxBacklogGrowing

**Meaning:** events are being written but not published to `ledger.events`. Settlement
itself keeps working; downstream consumers are behind.

**Confirm:** `ledger_outbox_backlog` is rising; logs show `outbox relay failed, will retry`.
Check Kafka (`kafka-topics.sh --describe --topic ledger.events`) and the producer errors
in the logs (authorization, topic deleted, broker unreachable).

**Act:** fix Kafka connectivity. No replay is needed: the relay resumes from the oldest
unpublished row by itself. If one row is poisonous (it fails every time), the log names
its id. Inspect it with `select * from outbox where id = ...`.

## SettlementFailureRateHigh

**Meaning:** more than 5% of trades fail settlement. A fail is a business outcome (not
enough cash or shares, unknown participant), not a bug, but a spike usually has one cause.

**Confirm:** `GET /api/settlements?status=FAILED&limit=200`, then group by
`failureReason` and by participant.

**Act:**
- `insufficient_cash` / `insufficient_securities` concentrated on one participant: they
  are trading beyond what they hold. This is a pre-trade risk problem; contact risk
  operations.
- `unknown_participant`: a participant exists in the trading venue but not in the ledger
  (a missed onboarding step). Create it (`POST /api/participants`). Failed trades are not
  retried automatically; see the "fails management" note in ADR 0003.

## SettlementLagHigh

**Meaning:** p99 time from execution to settlement is above 5 s. Trades are waiting in
Kafka.

**Confirm:** consumer lag on the dashboard (`kafka_consumer_fetch_manager_records_lag_max`)
and the error logs of the listener.

**Act:**
- **Lag and errors in the logs** (database down, timeouts): the consumer retries with
  backoff up to 30 s and never skips a valid trade. Fix the database first; the backlog
  drains by itself.
- **Lag and no errors**: not enough consumers. The maximum parallelism is the number of
  partitions of `trades.executed` (3 by default). Add replicas or raise
  `LEDGER_CONSUMER_CONCURRENCY` up to that number, or add partitions. Adding partitions
  changes which partition a symbol maps to, so do it with the producers paused.
- **A hot instrument**: all its trades are on one partition, by design (ordering). Lag on
  one partition only points there.

Messages that can never be settled are in `trades.executed.DLT`. Inspect them; after
fixing the producer, re-publish them to `trades.executed`. Settlement is idempotent, so
re-publishing a trade that did settle is harmless.

## ReconciliationBreaks

**Meaning:** the bank's camt.053 statement and the ledger's settled transfers disagree.

**Confirm:** the report of `POST /api/reconciliation/camt053` lists each break:

| Break | Usual cause | Action |
|---|---|---|
| `MISSING_IN_LEDGER`, direction IN | money arrived but the pacs.008 never reached us | ask the clearing bank to resend; the duplicate check makes resending safe |
| `MISSING_IN_LEDGER`, direction OUT | the bank paid something we did not send | escalate: possible fraud or a bank error |
| `MISSING_IN_BANK` | settled after the statement's cut-off, or the bank rejected it | check the next day's statement; if still missing, look for a pacs.002 rejection |
| `AMOUNT_MISMATCH` | fees deducted by a bank in the chain, or a data error | compare with the original instruction; book a correcting entry |

## RedisUnavailable

**Meaning:** Redis is down or unreachable. Payments still work: idempotency falls back to
the database's unique constraint (ADR 0005). Rate limiting is **off** until Redis is back.

**Act:** restore Redis. Nothing to replay. Watch for clients abusing the API while the
limiter is off (`http_server_requests_seconds_count` by client).
