package io.github.davifmn.ledger.settlement;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes trades.executed. Messages are keyed by symbol, so the trades of one
 * instrument arrive in order on one partition. The offset is committed after
 * {@link SettlementService#settle} has committed: at-least-once delivery, made
 * effectively-once by the idempotent settlement.
 */
@Component
public class TradeListener {

    private static final Logger log = LoggerFactory.getLogger(TradeListener.class);

    private final SettlementService settlements;
    private final JsonMapper json;

    public TradeListener(SettlementService settlements, JsonMapper json) {
        this.settlements = settlements;
        this.json = json;
    }

    @KafkaListener(topics = "${ledger.topics.trades}", groupId = "settlement-ledger")
    public void onTrade(ConsumerRecord<String, String> record) {
        SettlementInstruction trade = SettlementInstruction.parse(json, record.value());
        SettlementService.Outcome outcome = settlements.settle(trade);
        log.debug("trade {} partition {} offset {}: {}", trade.tradeId(), record.partition(), record.offset(), outcome);
    }
}
