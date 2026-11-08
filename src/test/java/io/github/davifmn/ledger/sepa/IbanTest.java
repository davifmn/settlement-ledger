package io.github.davifmn.ledger.sepa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class IbanTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "DE89370400440532013000",
            "DE89 3704 0044 0532 0130 00",
            "de89370400440532013000",
            "FR1420041010050500013M02606",
            "NL91ABNA0417164300",
            "CH9300762011623852957",
            "AT611904300234573201"})
    void acceptsValidSepaIbans(String iban) {
        assertThat(Iban.isValid(iban)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DE89370400440532013001", // checksum off by one digit
            "DE8937040044053201300",  // too short for DE
            "US64SVBKUS6S3300958879", // not a SEPA country
            "DE89-3704-0044-0532-0130-00",
            "",
            "DE"})
    void rejectsInvalidIbans(String iban) {
        assertThat(Iban.isValid(iban)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(Iban.isValid(null)).isFalse();
    }

    @Test
    void buildsIbansWithCorrectCheckDigits() {
        assertThat(Iban.of("DE", "370400440532013000")).isEqualTo("DE89370400440532013000");
        assertThat(Iban.of("DE", "999999990001000001")).isEqualTo("DE43999999990001000001");
    }

    @Test
    void refusesToBuildAnIbanOfTheWrongLength() {
        assertThatThrownBy(() -> Iban.of("DE", "123")).isInstanceOf(IllegalArgumentException.class);
    }
}
