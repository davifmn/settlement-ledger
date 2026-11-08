package io.github.davifmn.ledger.settlement;

import io.github.davifmn.ledger.config.LedgerProperties;
import io.github.davifmn.ledger.ledger.AccountService;
import io.github.davifmn.ledger.ledger.LedgerService;
import io.github.davifmn.ledger.ledger.PostingResult;
import io.github.davifmn.ledger.outbox.OutboxWriter;
import io.github.davifmn.ledger.participant.ParticipantService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles trades. Idempotent by trade_id (docs/adr/0003): Kafka delivers at least once,
 * the primary key on trade_settlement turns a redelivery into a no-op, and the ledger
 * entry, the settlement row and the outbox event commit together.
 */
@Service
public class SettlementService {

    public enum Outcome { SETTLED, FAILED, DUPLICATE }

    public record SettlementView(String tradeId, String symbol, long priceCents, long quantity, String buyer,
                                 String seller, String status, String failureReason, Long notionalCents,
                                 Long feeCents, Long entryId, Instant executedAt, Instant settledAt) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final ParticipantService participants;
    private final OutboxWriter outbox;
    private final LedgerProperties props;
    private final MeterRegistry meters;
    private final Timer lag;

    public SettlementService(JdbcClient jdbc, LedgerService ledger, AccountService accounts,
                             ParticipantService participants, OutboxWriter outbox, LedgerProperties props,
                             MeterRegistry meters) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.participants = participants;
        this.outbox = outbox;
        this.props = props;
        this.meters = meters;
        this.lag = Timer.builder("ledger.settlement.lag")
                .description("Time from execution to settlement")
                .register(meters);
        // Start every series at zero: alert expressions using rate() need them to exist
        // on every replica, also on one that has not settled anything yet.
        for (Outcome outcome : Outcome.values()) {
            count(outcome, 0);
        }
    }

    @Transactional
    public Outcome settle(SettlementInstruction t) {
        int claimed = jdbc.sql("""
                        insert into trade_settlement
                            (trade_id, symbol, price_cents, quantity, buyer, seller, executed_at, status)
                        values (:tradeId, :symbol, :price, :quantity, :buyer, :seller, :executedAt, 'PENDING')
                        on conflict (trade_id) do nothing""")
                .param("tradeId", t.tradeId())
                .param("symbol", t.symbol())
                .param("price", t.priceCents())
                .param("quantity", t.quantity())
                .param("buyer", t.buyer())
                .param("seller", t.seller())
                .param("executedAt", OffsetDateTime.ofInstant(t.executedAt(), ZoneOffset.UTC))
                .update();
        if (claimed == 0) {
            count(Outcome.DUPLICATE);
            return Outcome.DUPLICATE;
        }

        SettlementMath.Cash cash = SettlementMath.cash(t.priceCents(), t.quantity(), props.feeBps());
        if (participants.find(t.buyer()).isEmpty() || participants.find(t.seller()).isEmpty()) {
            return fail(t, "unknown_participant");
        }
        var accountIds = new SettlementMath.Accounts(
                accounts.clientAccount(t.buyer(), AccountService.EUR),
                accounts.clientAccount(t.seller(), AccountService.EUR),
                accounts.clientAccount(t.buyer(), t.symbol()),
                accounts.clientAccount(t.seller(), t.symbol()),
                accounts.systemAccount(AccountService.FEES));

        PostingResult result = ledger.post("TRADE", t.tradeId(),
                t.quantity() + " " + t.symbol() + " " + t.seller() + " -> " + t.buyer(),
                SettlementMath.legs(t.symbol(), t.quantity(), cash, accountIds));

        return switch (result) {
            case PostingResult.Rejected r -> fail(t, AccountService.EUR.equals(r.asset())
                    ? "insufficient_cash" : "insufficient_securities");
            case PostingResult.Posted p -> {
                jdbc.sql("""
                                update trade_settlement
                                set status = 'SETTLED', notional_cents = :notional, fee_cents = :fee,
                                    entry_id = :entry, settled_at = now()
                                where trade_id = :tradeId""")
                        .param("notional", cash.notional())
                        .param("fee", cash.feePerSide())
                        .param("entry", p.entryId())
                        .param("tradeId", t.tradeId())
                        .update();
                Map<String, Object> event = base(t);
                event.put("notional_cents", cash.notional());
                event.put("fee_cents_per_side", cash.feePerSide());
                event.put("entry_id", p.entryId());
                outbox.append("trade", t.tradeId(), "TradeSettled", event);
                count(Outcome.SETTLED);
                lag.record(Duration.between(t.executedAt(), Instant.now()));
                yield Outcome.SETTLED;
            }
        };
    }

    private Outcome fail(SettlementInstruction t, String reason) {
        jdbc.sql("update trade_settlement set status = 'FAILED', failure_reason = :reason where trade_id = :tradeId")
                .param("reason", reason)
                .param("tradeId", t.tradeId())
                .update();
        Map<String, Object> event = base(t);
        event.put("reason", reason);
        outbox.append("trade", t.tradeId(), "SettlementFailed", event);
        count(Outcome.FAILED);
        return Outcome.FAILED;
    }

    private static Map<String, Object> base(SettlementInstruction t) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("trade_id", t.tradeId());
        event.put("symbol", t.symbol());
        event.put("price_cents", t.priceCents());
        event.put("quantity", t.quantity());
        event.put("buyer", t.buyer());
        event.put("seller", t.seller());
        event.put("executed_at", t.executedAt());
        return event;
    }

    private void count(Outcome outcome) {
        count(outcome, 1);
    }

    private void count(Outcome outcome, double amount) {
        meters.counter("ledger.settlements", "outcome", outcome.name().toLowerCase()).increment(amount);
    }

    public List<SettlementView> recent(String status, int limit) {
        return jdbc.sql("""
                        select * from trade_settlement
                        where cast(:status as text) is null or status = :status
                        order by received_at desc
                        limit :limit""")
                .param("status", status)
                .param("limit", limit)
                .query(SettlementService::view)
                .list();
    }

    public Optional<SettlementView> find(String tradeId) {
        return jdbc.sql("select * from trade_settlement where trade_id = :tradeId")
                .param("tradeId", tradeId)
                .query(SettlementService::view)
                .optional();
    }

    private static SettlementView view(ResultSet rs, int n) throws SQLException {
        OffsetDateTime settledAt = rs.getObject("settled_at", OffsetDateTime.class);
        return new SettlementView(
                rs.getString("trade_id"), rs.getString("symbol"), rs.getLong("price_cents"),
                rs.getLong("quantity"), rs.getString("buyer"), rs.getString("seller"), rs.getString("status"),
                rs.getString("failure_reason"), rs.getObject("notional_cents", Long.class),
                rs.getObject("fee_cents", Long.class), rs.getObject("entry_id", Long.class),
                rs.getObject("executed_at", OffsetDateTime.class).toInstant(),
                settledAt == null ? null : settledAt.toInstant());
    }
}
