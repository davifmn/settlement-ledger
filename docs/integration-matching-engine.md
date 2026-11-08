# Integration with matching-engine

This service is the post-trade side of
[matching-engine](https://github.com/davifmn/matching-engine): the engine decides *who
traded with whom*; the ledger moves the cash and the shares.

```
 orders ─▶ matchd ─▶ journal (SAP.wal) ──read only──▶ dropcopy ─▶ Kafka trades.executed ─▶ settlement-ledger
           (fsync, then reply)                        (replays the journal       (key = symbol)    (idempotent per trade_id,
                                                       into its own book)                           delivery versus payment)
```

## How trades get here: the drop copy

The engine's market data stream is public (no accounts) and drops slow subscribers, which
is right for market data and wrong for settlement. So matching-engine has a **drop copy**
(its ADR 0005):

- It **follows the engine's write-ahead journal** and replays every command into its own copy
  of the book, with the engine's own code. It sees exactly the trades the engine made, in
  the same order, without touching the matching path.
- Every trade gets a **deterministic id**, `<symbol>-<seq>-<n>`: seq is the command's position
  in the journal, and n numbers the trades of that command. Replaying gives the same ids.
- After Kafka acknowledges a batch, it saves a **cursor**. A crash before the cursor is saved
  republishes that batch with the same ids, and this ledger deduplicates on `trade_id`
  (ADR 0003). At least once on the wire, exactly once in the books.

## The contract: topic `trades.executed`

One JSON message per trade, **keyed by symbol** (so the trades of an instrument stay in
order on one partition):

```json
{"trade_id":"SAP-42-1","symbol":"SAP","price":"134.15","quantity":"300","currency":"EUR",
 "buyer_account":"1001","seller_account":"1002","executed_at":"2026-11-08T10:15:30.123456789Z","seq":42}
```

| Field | Rule |
|---|---|
| `trade_id` | unique and **stable**: the same trade always has the same id. The ledger deduplicates on it |
| `price`, `quantity` | decimal strings, never floating point; at most 2 decimals for the price, whole shares |
| `buyer_account`, `seller_account` | the engine's `account_id`s, which are participant ids in the ledger; never equal |
| `currency` | `EUR` (the only settlement currency for now); the engine's instrument definition carries it |
| `seq` and other fields | ignored by the ledger, so either side can add fields without breaking the other |

The contract is pinned **from both sides** with the same file:
- matching-engine: `internal/dropcopy/testdata/trade-executed.golden.json`, which the drop copy must produce byte for byte;
- this repository: `src/test/resources/contract/trade-executed.json`, which `TradeContractTest` must settle.

## Running the whole chain

```sh
# matching-engine checked out next to this repository (or set MATCHING_ENGINE_DIR)
docker compose -f docker-compose.yml -f docker-compose.exchange.yml up --build
bash scripts/exchange_e2e.sh
```

The script:
1. opens three participants in the ledger, with cash and SAP shares, and uses their ids as
   `account_id`s on the engine (`:8090`);
2. sends crossing orders, kills the drop copy with `kill -9`, keeps trading, restarts it, then
   kills and restarts matchd;
3. checks that every trade the engine reported is SETTLED, and that each participant's SAP
   position equals what the engine's fills say. A lost or doubly settled trade would break
   that equality.

CI runs it in `.github/workflows/exchange-e2e.yml` when it can read the matching-engine
repository (public, or a `MATCHING_ENGINE_TOKEN` secret).

Without the engine, `POST /api/admin/simulate/trades` (with `LEDGER_SIMULATOR_ENABLED=true`)
publishes messages with exactly this contract; the smoke test and the integration tests use it.
