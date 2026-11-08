# Integration with matching-engine

This service is the post-trade side of
[matching-engine](https://github.com/davifmn/matching-engine): the engine decides *who
traded with whom*; the ledger moves the cash and the shares.

## The contract: topic `trades.executed`

One JSON message per trade, **keyed by symbol** (so the trades of an instrument stay in
order on one partition):

```json
{
  "trade_id": "PETR4-98311-1",
  "symbol": "PETR4",
  "price": "34.15",
  "quantity": "300",
  "currency": "EUR",
  "buyer_account": "1001",
  "seller_account": "1002",
  "executed_at": "2026-11-08T10:15:30.123Z"
}
```

| Field | Rule |
|---|---|
| `trade_id` | unique and **stable**: the same trade always has the same id. The ledger deduplicates on it |
| `price`, `quantity` | decimal strings, never floating point; at most 2 decimals for the price, whole shares |
| `buyer_account`, `seller_account` | participant ids in the ledger; different from each other |
| `currency` | `EUR` (the only settlement currency for now) |

Unknown fields are ignored, so the producer can add fields without breaking the consumer.

## What matching-engine needs to produce it

matching-engine's market data stream (`GET /v1/instruments/{symbol}/stream`) is
**public** by design: trade prints have no accounts, and slow subscribers are
disconnected. That is right for market data and wrong for settlement, which needs the
counterparties and must not lose a trade.

The missing piece is a **drop copy**, which is already listed in matching-engine's
"out of scope" section:

1. In `book.Event`, add the maker's account to trade events (`MakerOwner`); the taker's
   account is already on the incoming order.
2. Give each trade a deterministic id: `<symbol>-<command seq>-<n>`, where `n` is the
   trade's index within the command. Replaying the journal after a crash produces the
   same ids, so the ledger's deduplication covers the replay too.
3. A drop-copy publisher reads the engine's trade events and writes them to
   `trades.executed`. To survive restarts without losing or re-ordering trades, it keeps
   the last published command sequence per instrument and, on start, replays the journal
   from that point.

Until then, `POST /api/admin/simulate/trades` (enabled with
`LEDGER_SIMULATOR_ENABLED=true`) publishes trades with exactly this contract. The smoke
test and the integration tests use it.
