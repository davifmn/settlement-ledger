package io.github.davifmn.ledger.reconciliation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares what the ledger booked with what the bank booked, keyed by (direction,
 * end-to-end id). Pure logic, separate from parsing and the database.
 */
public final class ReconciliationMatcher {

    private ReconciliationMatcher() {
    }

    public enum Direction { IN, OUT }

    public enum BreakType { AMOUNT_MISMATCH, MISSING_IN_LEDGER, MISSING_IN_BANK }

    public record Item(Direction direction, String endToEndId, long amountCents) {
    }

    public record Break(BreakType type, Direction direction, String endToEndId, Long ledgerAmountCents,
                        Long bankAmountCents) {
    }

    public record Result(int matched, List<Break> breaks) {
    }

    private record Key(Direction direction, String endToEndId) {
    }

    public static Result match(Collection<Item> ledger, Collection<Item> bank) {
        Map<Key, List<Long>> ours = group(ledger);
        Map<Key, List<Long>> theirs = group(bank);
        Set<Key> keys = new LinkedHashSet<>(ours.keySet());
        keys.addAll(theirs.keySet());

        int matched = 0;
        List<Break> breaks = new ArrayList<>();
        for (Key key : keys) {
            List<Long> l = new ArrayList<>(ours.getOrDefault(key, List.of()));
            List<Long> b = new ArrayList<>(theirs.getOrDefault(key, List.of()));
            // pair equal amounts first
            for (var it = l.iterator(); it.hasNext(); ) {
                Long amount = it.next();
                if (b.remove(amount)) {
                    it.remove();
                    matched++;
                }
            }
            if (l.size() == 1 && b.size() == 1) {
                breaks.add(new Break(BreakType.AMOUNT_MISMATCH, key.direction(), key.endToEndId(), l.getFirst(), b.getFirst()));
                continue;
            }
            l.forEach(amount -> breaks.add(new Break(BreakType.MISSING_IN_BANK, key.direction(), key.endToEndId(), amount, null)));
            b.forEach(amount -> breaks.add(new Break(BreakType.MISSING_IN_LEDGER, key.direction(), key.endToEndId(), null, amount)));
        }
        return new Result(matched, breaks);
    }

    private static Map<Key, List<Long>> group(Collection<Item> items) {
        Map<Key, List<Long>> grouped = new LinkedHashMap<>();
        for (Item item : items) {
            grouped.computeIfAbsent(new Key(item.direction(), item.endToEndId()), k -> new ArrayList<>()).add(item.amountCents());
        }
        return grouped;
    }
}
