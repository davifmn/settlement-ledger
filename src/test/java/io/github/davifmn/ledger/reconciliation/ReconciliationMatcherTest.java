package io.github.davifmn.ledger.reconciliation;

import static io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.BreakType.AMOUNT_MISMATCH;
import static io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.BreakType.MISSING_IN_BANK;
import static io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.BreakType.MISSING_IN_LEDGER;
import static io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.Direction.IN;
import static io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.Direction.OUT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.Break;
import io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.Item;
import io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationMatcherTest {

    @Test
    void everythingMatches() {
        List<Item> items = List.of(new Item(IN, "A", 100), new Item(OUT, "B", 250));

        assertThat(ReconciliationMatcher.match(items, items)).isEqualTo(new Result(2, List.of()));
    }

    @Test
    void reportsEachKindOfBreak() {
        Result result = ReconciliationMatcher.match(
                List.of(new Item(IN, "A", 100), new Item(OUT, "B", 250), new Item(OUT, "C", 75)),
                List.of(new Item(IN, "A", 100), new Item(OUT, "B", 205), new Item(IN, "D", 10)));

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.breaks()).containsExactlyInAnyOrder(
                new Break(AMOUNT_MISMATCH, OUT, "B", 250L, 205L),
                new Break(MISSING_IN_BANK, OUT, "C", 75L, null),
                new Break(MISSING_IN_LEDGER, IN, "D", null, 10L));
    }

    @Test
    void directionIsPartOfTheKey() {
        Result result = ReconciliationMatcher.match(List.of(new Item(IN, "A", 100)), List.of(new Item(OUT, "A", 100)));

        assertThat(result.matched()).isZero();
        assertThat(result.breaks()).extracting(Break::type).containsExactlyInAnyOrder(MISSING_IN_BANK, MISSING_IN_LEDGER);
    }

    @Test
    void duplicateReferencesArePairedByAmount() {
        Result result = ReconciliationMatcher.match(
                List.of(new Item(IN, "A", 100), new Item(IN, "A", 300)),
                List.of(new Item(IN, "A", 300), new Item(IN, "A", 100), new Item(IN, "A", 100)));

        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.breaks()).containsExactly(new Break(MISSING_IN_LEDGER, IN, "A", null, 100L));
    }
}
