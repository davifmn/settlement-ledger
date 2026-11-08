package io.github.davifmn.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class SettlementInstructionTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static String message(String field, String value) {
        String base = """
                {"trade_id":"PETR4-981","symbol":"PETR4","price":"34.15","quantity":"300","currency":"EUR",
                 "buyer_account":"1001","seller_account":"1002","executed_at":"2026-11-08T10:15:30.123Z"}""";
        if (field == null) {
            return base;
        }
        return base.replaceFirst("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":\"" + value + "\"");
    }

    @Test
    void readsTheMessageFormat() {
        SettlementInstruction t = SettlementInstruction.parse(json, message(null, null));

        assertThat(t).isEqualTo(new SettlementInstruction("PETR4-981", "PETR4", 3415, 300, "1001", "1002",
                Instant.parse("2026-11-08T10:15:30.123Z")));
    }

    @Test
    void ignoresFieldsItDoesNotKnow() {
        String withExtra = message(null, null).replace("}", ",\"venue\":\"matching-engine\"}");
        assertThat(SettlementInstruction.parse(json, withExtra).tradeId()).isEqualTo("PETR4-981");
    }

    @ParameterizedTest
    @CsvSource({
            "price, 34.155",
            "price, -1",
            "quantity, 1.5",
            "quantity, 0",
            "currency, BRL",
            "symbol, petr4",
            "symbol, EUR",
            "seller_account, 1001",
            "buyer_account, not an id",
            "trade_id, ''"})
    void refusesInvalidTrades(String field, String value) {
        assertThatThrownBy(() -> SettlementInstruction.parse(json, message(field, value)))
                .isInstanceOf(InvalidTradeException.class);
    }

    @Test
    void refusesUnreadableMessages() {
        assertThatThrownBy(() -> SettlementInstruction.parse(json, "not json"))
                .isInstanceOf(InvalidTradeException.class)
                .hasMessageContaining("unreadable");
        assertThatThrownBy(() -> SettlementInstruction.parse(json, "{}")).isInstanceOf(InvalidTradeException.class);
    }
}
