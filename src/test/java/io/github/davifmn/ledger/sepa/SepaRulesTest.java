package io.github.davifmn.ledger.sepa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SepaRulesTest {

    @Test
    void textUsesTheSepaCharacterSet() {
        assertThat(SepaRules.isValidText("Invoice 2026-0042 (Q4), paid.", 140)).isTrue();
        assertThat(SepaRules.isValidText("Müller GmbH", 70)).isFalse(); // ü is outside the basic set
        assertThat(SepaRules.isValidText("a&b", 70)).isFalse();
        assertThat(SepaRules.isValidText("x".repeat(71), 70)).isFalse();
        assertThat(SepaRules.isValidText("   ", 70)).isFalse();
        assertThat(SepaRules.isValidText(null, 70)).isFalse();
    }

    @Test
    void referencesHaveNoSpacesNorDoubleSlashes() {
        assertThat(SepaRules.isValidReference("INV-2026-0042")).isTrue();
        assertThat(SepaRules.isValidReference("A/B")).isTrue();
        assertThat(SepaRules.isValidReference("INV 42")).isFalse();
        assertThat(SepaRules.isValidReference("/INV42")).isFalse();
        assertThat(SepaRules.isValidReference("INV42/")).isFalse();
        assertThat(SepaRules.isValidReference("A//B")).isFalse();
        assertThat(SepaRules.isValidReference("X".repeat(36))).isFalse();
    }

    @Test
    void bicHas8Or11Characters() {
        assertThat(SepaRules.isValidBic("COBADEFF")).isTrue();
        assertThat(SepaRules.isValidBic("COBADEFFXXX")).isTrue();
        assertThat(SepaRules.isValidBic("COBADEF")).isFalse();
        assertThat(SepaRules.isValidBic("cobadeff")).isFalse();
        assertThat(SepaRules.isValidBic("COBA1EFF")).isFalse();
    }
}
