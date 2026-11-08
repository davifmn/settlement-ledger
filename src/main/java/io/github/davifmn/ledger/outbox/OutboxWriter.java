package io.github.davifmn.ledger.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event to the outbox table in the caller's transaction (docs/adr/0004). The
 * event exists if and only if the ledger change it describes was committed.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID append(String aggregateType, String aggregateId, String eventType, Map<String, ?> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox events must be written inside the business transaction");
        }
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("event_type", eventType);
        payload.put("occurred_at", Instant.now());
        payload.putAll(data);
        jdbc.sql("""
                        insert into outbox (event_id, aggregate_type, aggregate_id, event_type, payload)
                        values (:eventId, :aggregateType, :aggregateId, :eventType, cast(:payload as jsonb))""")
                .param("eventId", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
        return eventId;
    }
}
