package io.github.davifmn.ledger.outbox;

import io.github.davifmn.ledger.config.LedgerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka (topic ledger.events, keyed by aggregate id).
 *
 * <p>Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several replicas can relay
 * at once without publishing the same row twice in parallel. Delivery is at-least-once:
 * if the process dies after Kafka acknowledged but before the commit, the row is sent
 * again, and consumers deduplicate on event_id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String eventId, String aggregateId, String eventType, String payload) {
    }

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final LedgerProperties props;
    private final Counter published;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager txManager,
                       LedgerProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
        this.published = Counter.builder("ledger.outbox.published").register(meters);
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms:200}")
    public void relay() {
        try {
            int sent;
            do {
                sent = tx.execute(status -> publishBatch());
            } while (sent == props.outbox().batchSize());
        } catch (RuntimeException e) {
            // Kafka or the database is unavailable: the rows stay unpublished and the
            // next run retries them. ledger_outbox_backlog shows how far behind we are.
            log.warn("outbox relay failed, will retry: {}", e.getMessage());
        }
    }

    private int publishBatch() {
        List<Row> rows = jdbc.sql("""
                        select id, event_id, aggregate_id, event_type, payload::text
                        from outbox
                        where published_at is null
                        order by id
                        limit :limit
                        for update skip locked""")
                .param("limit", props.outbox().batchSize())
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
        for (Row row : rows) {
            var record = new ProducerRecord<>(props.topics().events(), row.aggregateId(), row.payload());
            record.headers().add("event_type", row.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("event_id", row.eventId().getBytes(StandardCharsets.UTF_8));
            try {
                kafka.send(record).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("could not publish outbox row " + row.id(), e);
            }
        }
        if (!rows.isEmpty()) {
            jdbc.sql("update outbox set published_at = now() where id in (:ids)")
                    .param("ids", rows.stream().map(Row::id).toList())
                    .update();
            published.increment(rows.size());
        }
        return rows.size();
    }
}
