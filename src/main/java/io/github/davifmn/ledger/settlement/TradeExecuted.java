package io.github.davifmn.ledger.settlement;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * The message on topic {@code trades.executed}: one trade between two accounts, in the
 * shape a drop copy of matching-engine would publish (docs/integration-matching-engine.md).
 * Prices and quantities are decimal strings, as in the matching-engine API, so no
 * floating point is ever involved.
 */
public record TradeExecuted(
        @JsonProperty("trade_id") String tradeId,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("price") String price,
        @JsonProperty("quantity") String quantity,
        @JsonProperty("currency") String currency,
        @JsonProperty("buyer_account") String buyerAccount,
        @JsonProperty("seller_account") String sellerAccount,
        @JsonProperty("executed_at") Instant executedAt) {
}
