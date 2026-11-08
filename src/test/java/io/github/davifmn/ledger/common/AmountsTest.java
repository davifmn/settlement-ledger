package io.github.davifmn.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AmountsTest {

    @Test
    void parsesDecimalsExactly() {
        assertThat(Amounts.parseCents("34.15")).isEqualTo(3415);
        assertThat(Amounts.parseCents("0.1")).isEqualTo(10);
        assertThat(Amounts.parseCents("100")).isEqualTo(10_000);
        assertThat(Amounts.parseCents("999999999999.99")).isEqualTo(99_999_999_999_999L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5", "34.155", "1e3", "12,50", " 1", "abc", ""})
    void refusesAnythingItWouldHaveToRound(String bad) {
        assertThatThrownBy(() -> Amounts.parseCents(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesWholeUnits() {
        assertThat(Amounts.parseUnits("300")).isEqualTo(300);
        assertThatThrownBy(() -> Amounts.parseUnits("1.5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Amounts.parseUnits("0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsCents() {
        assertThat(Amounts.formatCents(123_456)).isEqualTo("1234.56");
        assertThat(Amounts.formatCents(5)).isEqualTo("0.05");
    }
}
