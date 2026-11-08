package io.github.davifmn.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TransferRulesTest {

    private static Optional<String> check(String currency, long cents, String iban, String bic, String name,
                                          String remittance, String e2e) {
        return TransferRules.validateOutbound(currency, cents, 100_000_000, iban, bic, name, remittance, e2e);
    }

    @Test
    void acceptsAValidInstruction() {
        assertThat(check("EUR", 100_00, "DE89370400440532013000", "COBADEFFXXX", "Berlin Office Supplies",
                "Invoice 42", "INV-42")).isEmpty();
        assertThat(check("EUR", 100_00, "CH9300762011623852957", null, "Zurich Flats AG", null, "RENT-11")).isEmpty();
    }

    @Test
    void rejectsWithIsoReasonCodes() {
        assertThat(check("USD", 100, "DE89370400440532013000", null, "X", null, "E1")).hasValueSatisfying(r -> assertThat(r).startsWith("AM03"));
        assertThat(check("EUR", 100_000_001, "DE89370400440532013000", null, "X", null, "E1")).hasValueSatisfying(r -> assertThat(r).startsWith("AM02"));
        assertThat(check("EUR", 100, "DE89370400440532013001", null, "X", null, "E1")).hasValueSatisfying(r -> assertThat(r).startsWith("AC01"));
        assertThat(check("EUR", 100, "DE89370400440532013000", "BAD", "X", null, "E1")).hasValueSatisfying(r -> assertThat(r).startsWith("RC01"));
        assertThat(check("EUR", 100, "DE89370400440532013000", null, "Müller", null, "E1")).hasValueSatisfying(r -> assertThat(r).startsWith("FF01"));
        assertThat(check("EUR", 100, "DE89370400440532013000", null, "X", "a".repeat(141), "E1")).hasValueSatisfying(r -> assertThat(r).startsWith("FF01"));
        assertThat(check("EUR", 100, "DE89370400440532013000", null, "X", null, "E 1")).hasValueSatisfying(r -> assertThat(r).startsWith("FF01"));
    }
}
