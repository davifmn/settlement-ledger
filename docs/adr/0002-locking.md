# ADR 0002: pessimistic row locks in a fixed order, one entry per transaction

**Status:** accepted

## Context

Two entries that touch the same account at the same time must not both read the old
balance (a lost update could overdraw an account or lose money). The options:

1. **Optimistic locking** (a version column, retry on conflict). Cheap when conflicts are
   rare, but the hot accounts here (`SEPA_CLEARING`, `FEES`, an active trader's cash) are
   touched by almost every entry, so retries would pile up.
2. **SERIALIZABLE isolation.** Correct, but conflicts surface as serialization failures
   that every caller has to retry, and the failure rate grows with load.
3. **`SELECT ... FOR UPDATE`** on the accounts of the entry, then write.

## Decision

Option 3, with two rules:

- **Lock in ascending account id**, in one statement:
  `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`. PostgreSQL locks the rows in the
  order it returns them, so two transactions that need accounts {7, 3} and {3, 7} both
  lock 3 first: one waits, neither deadlocks.
- **One entry per transaction.** A transaction that posted entry A and then posts entry B
  would hold A's locks while it asks for B's, and lock ordering only holds *within* one
  acquisition. So the SEPA cycle and the inbound pacs.008 processing commit each transfer
  separately (`TransferService.settleOne`, `receive`). A Kafka record is one trade, so the
  consumer naturally posts one entry per transaction.

The balance check happens after the locks are taken, so it sees the latest committed
balance.

## Consequences

- `LedgerIT.concurrentTransfersInBothDirectionsNeitherDeadlockNorLoseMoney` runs 8 threads
  moving money in opposite directions between the same two accounts: no deadlock, and
  the total is conserved.
- Every SEPA transfer locks `SEPA_CLEARING`, so SEPA postings are serialized. At the
  volumes here that is a few milliseconds per transfer. If it became the bottleneck, the
  usual fixes are several clearing sub-accounts (sharded by transfer id) or posting the
  clearing side as one aggregated entry per settlement cycle.
- A deadlock is still possible if some future code breaks a rule. PostgreSQL detects it
  and aborts one transaction. Kafka redelivers the record and the SEPA cycle retries, so
  a deadlock costs a retry, never a wrong balance.
