package io.github.davifmn.ledger.settlement;

import io.github.davifmn.ledger.common.Amounts;
import io.github.davifmn.ledger.participant.ParticipantService;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** A trade checked for everything that does not need the database. */
public record SettlementInstruction(String tradeId, String symbol, long priceCents, long quantity,
                                    String buyer, String seller, Instant executedAt) {

    public static final String SYMBOL_PATTERN = "[A-Z0-9][A-Z0-9.-]{0,15}";
    private static final String TRADE_ID_PATTERN = "[A-Za-z0-9._:-]{1,64}";

    /** Parses and validates a message from trades.executed. */
    public static SettlementInstruction parse(JsonMapper json, String message) {
        TradeExecuted trade;
        try {
            trade = json.readValue(message, TradeExecuted.class);
        } catch (JacksonException e) {
            throw new InvalidTradeException("unreadable trade message: " + e.getOriginalMessage());
        }
        if (trade == null) {
            throw new InvalidTradeException("empty trade message");
        }
        return of(trade);
    }

    public static SettlementInstruction of(TradeExecuted t) {
        require(t.tradeId() != null && t.tradeId().matches(TRADE_ID_PATTERN), "trade_id", t.tradeId());
        require(t.symbol() != null && t.symbol().matches(SYMBOL_PATTERN) && !t.symbol().equals("EUR"), "symbol", t.symbol());
        require("EUR".equals(t.currency()), "currency (only EUR is settled)", t.currency());
        require(isParticipantId(t.buyerAccount()), "buyer_account", t.buyerAccount());
        require(isParticipantId(t.sellerAccount()), "seller_account", t.sellerAccount());
        require(!t.buyerAccount().equals(t.sellerAccount()), "buyer_account = seller_account", t.buyerAccount());
        require(t.executedAt() != null, "executed_at", null);
        long price;
        long quantity;
        try {
            price = Amounts.parseCents(t.price());
            quantity = Amounts.parseUnits(t.quantity());
        } catch (IllegalArgumentException e) {
            throw new InvalidTradeException("trade " + t.tradeId() + ": " + e.getMessage());
        }
        return new SettlementInstruction(t.tradeId(), t.symbol(), price, quantity,
                t.buyerAccount(), t.sellerAccount(), t.executedAt());
    }

    private static boolean isParticipantId(String id) {
        return id != null && id.matches(ParticipantService.ID_PATTERN);
    }

    private static void require(boolean ok, String field, String value) {
        if (!ok) {
            throw new InvalidTradeException("invalid " + field + ": " + value);
        }
    }
}
