package io.github.davifmn.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The message matching-engine's drop copy publishes, byte for byte. The same file is in
 * matching-engine (internal/dropcopy/testdata/trade-executed.golden.json), whose test checks
 * that the drop copy produces exactly it. Together the two tests pin the contract from both
 * sides; if one side changes the format, its own test fails first.
 */
class TradeContractTest {

    @Test
    void theDropCopyMessageIsAValidSettlementInstruction() throws IOException {
        String message;
        try (InputStream in = getClass().getResourceAsStream("/contract/trade-executed.json")) {
            message = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }

        SettlementInstruction trade = SettlementInstruction.parse(JsonMapper.builder().build(), message);

        assertThat(trade).isEqualTo(new SettlementInstruction(
                "SAP-42-1", "SAP", 13_415, 300, "1001", "1002", Instant.parse("2026-11-08T10:15:30.123456789Z")));
    }
}
