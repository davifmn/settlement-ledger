package io.github.davifmn.ledger.ledger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Checks that a set of legs is a valid double-entry: per asset, the amounts sum to zero. */
public final class Legs {

    private Legs() {
    }

    public static void requireBalanced(List<Leg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two legs");
        }
        Map<String, Long> totals = new TreeMap<>();
        for (Leg leg : legs) {
            if (leg.amount() == 0) {
                throw new IllegalArgumentException("a leg cannot be zero: " + leg);
            }
            totals.merge(leg.asset(), leg.amount(), Math::addExact);
        }
        totals.forEach((asset, total) -> {
            if (total != 0) {
                throw new IllegalArgumentException("legs do not balance for " + asset + ": off by " + total);
            }
        });
    }
}
