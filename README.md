# settlement-ledger

The post-trade side of [matching-engine](https://github.com/davifmn/matching-engine): a
**double-entry ledger** that settles trades arriving on **Kafka** (delivery versus
payment), and moves money in and out through **SEPA credit transfers** (ISO 20022 pain.001,
pacs.008 and camt.053), with a **React + TypeScript** dashboard.

Java 21 · Spring Boot 4 · PostgreSQL · Kafka · Redis · React · Docker · Helm · Prometheus/Grafana · k6

- **Correct by construction.** Every entry balances per asset, balances never go negative, the
  journal is append-only. These rules are enforced by PostgreSQL itself (constraint triggers,
  checks, composite keys), not only by the Java code, and re-checked every 15 s.
- **Exactly the right number of times.** Kafka delivers at least once; settlement is idempotent
  per trade id. Payments require an `Idempotency-Key`: Redis answers retries fast, and a unique
  constraint in the database still catches duplicates when Redis forgets or is down.
- **No dual writes.** Events reach Kafka through a transactional outbox, so a settlement and its
  event are committed together or not at all.
- **Operable.** Metrics, alert rules with a runbook for each, a Grafana dashboard, a Helm chart
  tested on Kubernetes (kind) including a rolling restart, and a k6 load test in CI.

```
                          trades.executed (key = symbol)                ledger.events
 matching-engine ──────▶  ┌──────────────┐                           ┌──────────────┐
 (drop copy, or the       │    Kafka     │──▶ TradeListener          │    Kafka     │──▶ notifications,
  built-in simulator)     └──────────────┘    │ idempotent per        └──────────────┘    reporting, risk
                                 │ invalid     ▼ trade_id                     ▲
                                 ▼          SettlementService                 │
                       trades.executed.DLT      │                       OutboxRelay
                                                ▼                   (SKIP LOCKED, 200 ms)
 clearing bank ── pacs.008 ──▶ TransferService ─▶ LedgerService ──▶ PostgreSQL ◀┘
 participants ── JSON/pain.001 ──▶ (validate,     (locks accounts     journal_entry, posting,
            Idempotency-Key ▶ Redis  SEPA cycles)   in id order)      account, outbox, ...
 bank statement ── camt.053 ──▶ ReconciliationService ──▶ breaks report
 dashboard (React) ── nginx ──▶ /api/...
```

## Quick start

```sh
docker compose up --build        # ledger, dashboard, PostgreSQL, Kafka, Redis, Prometheus, Grafana
bash scripts/smoke_test.sh       # funds two participants, settles 200 trades, pays out, reconciles
```

Dashboard on http://localhost:8081, API on :8080, Grafana on :3000 (dashboard "Settlement
ledger"), Prometheus on :9090.

By hand:

```sh
# a participant gets a EUR account and a virtual IBAN
curl -s -XPOST localhost:8080/api/participants -H 'Content-Type: application/json' \
     -d '{"id":"alice","name":"Alice Trading"}'
# {"id":"alice","name":"Alice Trading","iban":"DE43999999990001000001"}
curl -s -XPOST localhost:8080/api/participants -H 'Content-Type: application/json' -d '{"id":"bob","name":"Bob"}'

# money arrives from the clearing bank (samples/pacs008.xml credits both IBANs)
curl -s -XPOST localhost:8080/api/sepa/inbound -H 'Content-Type: application/xml' --data-binary @samples/pacs008.xml

# a payment out; retrying with the same key returns the same transfer instead of paying twice
curl -s -XPOST localhost:8080/api/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: inv-42-attempt' \
     -d '{"participantId":"alice","amount":"250.00","creditorName":"Berlin Office Supplies","creditorIban":"DE89370400440532013000"}'
# {"id":"…","status":"VALIDATED",…}   → SETTLED after the next SEPA cycle

# trades (normally published by matching-engine)
curl -s -XPOST localhost:8080/api/admin/simulate/trades -H 'Content-Type: application/json' \
     -d '{"symbol":"PETR4","participants":["alice","bob"],"count":100,"basePrice":"34.15"}'

curl -s localhost:8080/api/admin/invariants
# {"ok":true,"unbalancedEntries":0,"accountsWithDrift":0,"assetImbalances":{},"outboxBacklog":0}
```

Without Docker: `mvn spring-boot:run` (needs PostgreSQL, Kafka and Redis on localhost) and
`cd web && npm install && npm run dev`.

## API

| Method and path | What it does |
|---|---|
| `POST /api/participants` | open a participant: EUR account + virtual IBAN |
| `GET /api/participants[/{id}]` | participants with their balances per asset |
| `GET /api/accounts/{id}/statement?afterId=&limit=` | postings with running balance (keyset pagination) |
| `POST /api/transfers` + `Idempotency-Key` | outbound SEPA transfer (JSON) |
| `POST /api/transfers/pain001` | outbound transfers from a pain.001 file; one status per instruction |
| `POST /api/sepa/inbound` | incoming credits from the clearing bank (pacs.008) |
| `GET /api/transfers[/{id}]` | transfers, with their status history |
| `GET /api/settlements[/{tradeId}]?status=` | trade settlements (SETTLED / FAILED and why) |
| `POST /api/reconciliation/camt053` | compare a bank statement with the ledger; returns the breaks |
| `GET /api/admin/invariants` | the ledger invariants, checked from the raw tables |
| `POST /api/admin/securities-deposits` | bring securities into the ledger (from the depository) |
| `POST /api/admin/simulate/trades` | publish random trades to Kafka (only with the simulator enabled) |

Errors are RFC 9457 problem details. Transfer rejections carry ISO 20022 reason codes:
`AC01` bad IBAN, `AM02` over the limit, `AM03` not EUR, `AM04` insufficient funds,
`RC01` bad BIC, `FF01` format.

## Design decisions

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-double-entry.md) | Double-entry, integer minor units, rules enforced in PostgreSQL, invariants re-checked |
| [0002](docs/adr/0002-locking.md) | `SELECT … ORDER BY id FOR UPDATE` and one entry per transaction: no lost updates, no deadlocks |
| [0003](docs/adr/0003-idempotent-consumer.md) | At-least-once Kafka + idempotent consumer = each trade settled once; poison messages to a DLT |
| [0004](docs/adr/0004-transactional-outbox.md) | Transactional outbox instead of dual writes to the database and Kafka |
| [0005](docs/adr/0005-redis-fast-path.md) | Redis as the fast path for idempotency and rate limiting; the database stays the source of truth |

## How it is tested

| Layer | What runs |
|---|---|
| Unit (`mvn test`, 76 tests) | IBAN mod-97, SEPA field rules, the ISO 20022 readers (including XXE and entity-bomb attempts), settlement and fee math, trade validation, reconciliation matching, idempotency and rate limiting with Redis down |
| Integration (`mvn verify`, Testcontainers) | real PostgreSQL 18, Kafka 4.3 and Redis 8: the database refuses unbalanced and edited entries; 8 threads moving money both ways neither deadlock nor lose a cent; a redelivered trade settles once; poison messages land in the DLT; events go out through the outbox; idempotency still holds after Redis forgets the key; rate limiting; reconciliation breaks |
| Dashboard (`npm test`) | IBAN and amount parsing, the transfer form reusing its Idempotency-Key on retry, the settlements table |
| End to end (CI) | `docker compose up`, then `scripts/smoke_test.sh` and a k6 load test (`scripts/load/transfers.js`), then the invariants again |
| Kubernetes (CI) | the Helm chart on kind with 2 replicas, the smoke test through the Service, then a rolling restart and the smoke test again |

### Measured in CI

On a standard GitHub-hosted runner, with everything in docker compose and **one** ledger instance:

| Scenario | Result |
|---|---|
| Smoke test: 200 trades through Kafka, a pacs.008, a payout, a camt.053 | all 200 settled; 3 of 3 statement entries matched, 0 breaks |
| k6: 50 outbound transfers/s for 30 s, every 10th retried with the same key | 1,489 transfers + 150 retries, **0 errors**; every retry returned the original transfer |
| Latency of `POST /api/transfers` under that load | median 10 ms, p95 243 ms, p99 585 ms (includes JVM warm-up) |
| After the load | invariants hold, outbox backlog 0 |

## Operations

- **Metrics** (`/actuator/prometheus`):
  - `ledger_settlements_total{outcome}`, `ledger_settlement_lag_seconds` (execution → settlement);
  - `ledger_transfers_total{direction,status}`, `ledger_reconciliation_breaks_total{type}`;
  - `ledger_outbox_backlog`, `ledger_invariant_violations`;
  - Kafka consumer lag, HTTP latency per endpoint.
- **Alerts:** `deploy/prometheus/alerts.yml`, each with a procedure in [docs/runbook.md](docs/runbook.md).
- **Dashboard:** `deploy/grafana/dashboards/settlement-ledger.json`, provisioned by docker compose.
- **Kubernetes:** `deploy/helm/settlement-ledger`:
  - non-root pods with a read-only root filesystem;
  - startup, liveness and readiness probes (readiness = database; Redis is optional by design);
  - a PodDisruptionBudget and rolling updates with `maxUnavailable: 0`;
  - an optional ServiceMonitor.

## Repository layout

```
src/main/java/io/github/davifmn/ledger/
  ledger/          LedgerService (the only code that changes balances), accounts, statements
  settlement/      Kafka listener, trade validation, delivery-versus-payment, simulator
  transfer/        SEPA transfers: rules, state machine, settlement cycles, API
  sepa/            IBAN, SEPA field rules, ISO 20022 readers (pain.001, pacs.008, camt.053)
  reconciliation/  camt.053 vs ledger
  outbox/          transactional outbox and its relay
  common/          idempotency (Redis), rate limiter, errors, amounts
  admin/           invariants, securities deposits
src/main/resources/db/migration/   the schema and its constraint triggers
web/               React + TypeScript dashboard (Vite, Vitest), served by nginx
deploy/            Helm chart, kind dependencies, Prometheus rules, Grafana dashboard
scripts/           smoke test, k6 load test
samples/           example pain.001, pacs.008 and camt.053 messages
docs/              ADRs, runbook, matching-engine integration
```

## What a production version would add

- **Authentication.** mTLS or OAuth2, so that participants are identified by credentials,
  not by a field in the body.
- **Real banking connectivity.** EBICS or SWIFT to the clearing bank, plus pacs.002
  status reports and returns (pacs.004).
- **Fails management.** Retrying failed settlements, partial settlement, buy-ins.
- **Multi-currency.** FX entries, revaluation.
- **Data lifecycle.** Archiving or partitioning of the journal and the outbox; Debezium
  instead of polling at higher volumes.
- **The drop copy in matching-engine.** See [docs/integration-matching-engine.md](docs/integration-matching-engine.md).

## License

MIT
