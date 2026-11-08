package io.github.davifmn.ledger.ledger;

/** One side of a journal entry: a signed amount of one asset on one account. */
public record Leg(long accountId, String asset, long amount) {
}
