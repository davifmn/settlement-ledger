package io.github.davifmn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.davifmn.ledger.settlement.SettlementMath;
import io.github.davifmn.ledger.settlement.SettlementService;
import io.github.davifmn.ledger.settlement.SettlementService.SettlementView;
import io.github.davifmn.ledger.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

class SettlementIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    SettlementService settlements;

    private static String trade(String tradeId, String symbol, String price, long qty, String buyer, String seller) {
        return """
                {"trade_id":"%s","symbol":"%s","price":"%s","quantity":"%d","currency":"EUR",
                 "buyer_account":"%s","seller_account":"%s","executed_at":"%s"}"""
                .formatted(tradeId, symbol, price, qty, buyer, seller, Instant.now());
    }

    private void publish(String symbol, String message) throws Exception {
        kafka.send("trades.executed", symbol, message).get();
    }

    private SettlementView awaitSettlement(String tradeId) {
        return await().atMost(WAIT).until(() -> settlements.find(tradeId),
                s -> s.isPresent() && !s.get().status().equals("PENDING")).orElseThrow();
    }

    @Test
    void aTradeSettlesDeliveryVersusPayment() throws Exception {
        String buyer = unique("buyer");
        String seller = unique("seller");
        fund(createParticipant(buyer), "20000.00");
        createParticipant(seller);
        depositShares(seller, "ACME", 1_000);
        String tradeId = unique("ACME");

        publish("ACME", trade(tradeId, "ACME", "34.15", 300, buyer, seller));

        SettlementView view = awaitSettlement(tradeId);
        SettlementMath.Cash cash = SettlementMath.cash(3415, 300, 2);
        assertThat(view.status()).isEqualTo("SETTLED");
        assertThat(view.notionalCents()).isEqualTo(1_024_500);
        assertThat(balance(buyer, "EUR")).isEqualTo(2_000_000 - cash.buyerPays());
        assertThat(balance(buyer, "ACME")).isEqualTo(300);
        assertThat(balance(seller, "EUR")).isEqualTo(cash.sellerReceives());
        assertThat(balance(seller, "ACME")).isEqualTo(700);
    }

    @Test
    void aRedeliveredTradeSettlesOnce() throws Exception {
        String buyer = unique("buyer");
        String seller = unique("seller");
        fund(createParticipant(buyer), "1000.00");
        createParticipant(seller);
        depositShares(seller, "ACME", 10);
        String tradeId = unique("ACME");
        String message = trade(tradeId, "ACME", "10.00", 5, buyer, seller);

        publish("ACME", message);
        publish("ACME", message);
        awaitSettlement(tradeId);
        // the second copy is behind the first on the same partition; give it time to be processed
        String marker = unique("ACME");
        publish("ACME", trade(marker, "ACME", "10.00", 1, buyer, seller));
        awaitSettlement(marker);

        assertThat(jdbc.sql("select count(*) from journal_entry where kind = 'TRADE' and reference = :r")
                .param("r", tradeId).query(Long.class).single()).isEqualTo(1);
        assertThat(balance(buyer, "ACME")).isEqualTo(6);
    }

    @Test
    void aTradeWithoutEnoughCashFailsAndMovesNothing() throws Exception {
        String buyer = unique("buyer");
        String seller = unique("seller");
        fund(createParticipant(buyer), "1.00");
        createParticipant(seller);
        depositShares(seller, "ACME", 10);
        String tradeId = unique("ACME");

        publish("ACME", trade(tradeId, "ACME", "50.00", 10, buyer, seller));

        SettlementView view = awaitSettlement(tradeId);
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureReason()).isEqualTo("insufficient_cash");
        assertThat(balance(buyer, "EUR")).isEqualTo(100);
        assertThat(balance(seller, "ACME")).isEqualTo(10);
    }

    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopic() throws Exception {
        String key = unique("poison");

        kafka.send("trades.executed", key, "this is not a trade").get();

        ConsumerRecord<String, String> dead = consumeUntil("trades.executed.DLT", r -> key.equals(r.key()));
        assertThat(dead.value()).isEqualTo("this is not a trade");
    }

    @Test
    void settlementsArePublishedThroughTheOutbox() throws Exception {
        String buyer = unique("buyer");
        String seller = unique("seller");
        fund(createParticipant(buyer), "100.00");
        createParticipant(seller);
        depositShares(seller, "ACME", 1);
        String tradeId = unique("ACME");

        publish("ACME", trade(tradeId, "ACME", "10.00", 1, buyer, seller));

        ConsumerRecord<String, String> event = consumeUntil("ledger.events", r -> tradeId.equals(r.key()));
        assertThat(new String(event.headers().lastHeader("event_type").value(), StandardCharsets.UTF_8))
                .isEqualTo("TradeSettled");
        assertThat(event.value()).contains("\"trade_id\":\"" + tradeId + "\"").contains("\"entry_id\"");
    }

    @Test
    void theSimulatorProducesTradesThatGetSettled() throws Exception {
        String a = unique("sim");
        String b = unique("sim");
        fund(createParticipant(a), "100000.00");
        fund(createParticipant(b), "100000.00");
        depositShares(a, "SIMX", 10_000);
        depositShares(b, "SIMX", 10_000);

        mvc.perform(post("/api/admin/simulate/trades")
                        .contentType("application/json")
                        .content("{\"symbol\":\"SIMX\",\"participants\":[\"%s\",\"%s\"],\"count\":50,\"basePrice\":\"20.00\"}"
                                .formatted(a, b)))
                .andExpect(status().isOk());

        await().atMost(WAIT).until(() -> jdbc.sql("""
                        select count(*) from trade_settlement
                        where symbol = 'SIMX' and status = 'SETTLED' and (buyer = :a or seller = :a)""")
                .param("a", a).query(Long.class).single(), n -> n == 50);
        assertThat(balance(a, "SIMX") + balance(b, "SIMX")).isEqualTo(20_000);
    }

    private ConsumerRecord<String, String> consumeUntil(String topic, Predicate<ConsumerRecord<String, String>> match) {
        Properties props = new Properties();
        props.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            return await().atMost(WAIT).until(() -> {
                Optional<ConsumerRecord<String, String>> found = Optional.empty();
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(r)) {
                        found = Optional.of(r);
                    }
                }
                return found;
            }, Optional::isPresent).orElseThrow();
        }
    }
}
