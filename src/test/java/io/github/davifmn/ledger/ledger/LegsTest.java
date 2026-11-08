package io.github.davifmn.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LegsTest {

    @Test
    void acceptsAnEntryThatBalancesPerAsset() {
        assertThatCode(() -> Legs.requireBalanced(List.of(
                new Leg(1, "EUR", -100), new Leg(2, "EUR", 100),
                new Leg(3, "ACME", -5), new Leg(4, "ACME", 5)))).doesNotThrowAnyException();
    }

    @Test
    void refusesAnEntryThatBalancesOnlyAcrossAssets() {
        // -100 EUR + 100 ACME sums to zero, but that is not a balanced entry
        assertThatThrownBy(() -> Legs.requireBalanced(List.of(new Leg(1, "EUR", -100), new Leg(2, "ACME", 100))))
                .hasMessageContaining("do not balance");
    }

    @Test
    void refusesZeroLegsAndSingleLegs() {
        assertThatThrownBy(() -> Legs.requireBalanced(List.of(new Leg(1, "EUR", 0), new Leg(2, "EUR", 0))))
                .hasMessageContaining("zero");
        assertThatThrownBy(() -> Legs.requireBalanced(List.of(new Leg(1, "EUR", 5))))
                .hasMessageContaining("two legs");
    }

    @Test
    void detectsOverflowInsteadOfWrappingAround() {
        assertThatThrownBy(() -> Legs.requireBalanced(List.of(
                new Leg(1, "EUR", Long.MAX_VALUE), new Leg(2, "EUR", 1), new Leg(3, "EUR", Long.MIN_VALUE))))
                .isInstanceOf(ArithmeticException.class);
    }
}
