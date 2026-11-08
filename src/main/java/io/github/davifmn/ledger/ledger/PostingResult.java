package io.github.davifmn.ledger.ledger;

/**
 * The outcome of {@link LedgerService#post}. A business rejection is a value, not an
 * exception, so the caller can record it in the same transaction (an exception crossing
 * a transactional boundary would mark the whole transaction rollback-only).
 */
public sealed interface PostingResult {

    record Posted(long entryId) implements PostingResult {
    }

    /** The account would go below zero. Nothing was written. */
    record Rejected(long accountId, String asset, long available, long required) implements PostingResult {
    }
}
