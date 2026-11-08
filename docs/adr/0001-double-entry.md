# ADR 0001: a double-entry ledger with integer amounts, enforced by the database

**Status:** accepted

## Context

The service moves money and securities between accounts. The simplest model, a
`balance` column updated in place, cannot answer "why is this balance what it is?", and a
bug that adds without subtracting silently creates money. Every bank and broker keeps
double-entry books for this reason.

## Decision

- Every change is a **journal entry** made of **postings** (signed amounts on accounts).
  Within an entry, the postings of each asset **sum to zero**: money and securities move,
  they are never created or destroyed. Money that comes from outside (a SEPA credit, a
  securities deposit) comes from a system account that mirrors the outside world
  (`SEPA_CLEARING`, `CSD:<symbol>`), which may go negative.
- Amounts are **64-bit integers in minor units** (euro cents, shares). Decimal strings from
  the outside are converted exactly (`Amounts.parseCents`) and anything that would need
  rounding is refused. Overflow is an error (`Math.addExact`), not a wrong number.
- The rules live in **PostgreSQL**, not only in Java:
  - a deferred constraint trigger refuses to commit an entry that does not balance;
  - `CHECK (allow_negative OR balance >= 0)`;
  - a composite foreign key makes a posting's asset equal its account's asset;
  - triggers make `journal_entry` and `posting` append-only (no UPDATE, DELETE or TRUNCATE).
- `account.balance` is a cached sum of the postings, updated in the same transaction. Each
  posting stores `balance_after`, so a statement never needs a running sum.
- `InvariantService` re-checks from the raw tables, every 15 s: entries balance, balances
  match their postings, every asset sums to zero. The `ledger_invariant_violations` gauge
  must stay at 0, and the alert pages if it does not.

## Consequences

- A bug in the Java code cannot commit an unbalanced entry or overdraw a client: the
  database refuses it (`LedgerIT` tries both with raw SQL).
- Corrections are new, reversing entries. The history is never rewritten, which is what
  auditors expect.
- The cached balance is a second source of truth that could drift. The invariant check
  exists to catch exactly that.
- Single currency for cash (EUR). Several currencies would need an FX entry type with one
  leg per currency, plus a revaluation process.
