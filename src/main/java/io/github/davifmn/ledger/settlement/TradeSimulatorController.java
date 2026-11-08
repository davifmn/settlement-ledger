package io.github.davifmn.ledger.settlement;

import io.github.davifmn.ledger.common.Amounts;
import io.github.davifmn.ledger.config.LedgerProperties;
import io.github.davifmn.ledger.participant.ParticipantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes random trades to trades.executed, standing in for matching-engine in demos
 * and load tests. Only exists when ledger.simulator.enabled=true.
 */
@RestController
@ConditionalOnBooleanProperty("ledger.simulator.enabled")
public class TradeSimulatorController {

    public record SimulateTrades(
            @NotBlank @Pattern(regexp = SettlementInstruction.SYMBOL_PATTERN) String symbol,
            @Size(min = 2, max = 100) List<@Pattern(regexp = ParticipantService.ID_PATTERN) String> participants,
            @Min(1) @Max(10_000) int count,
            @NotBlank String basePrice) {
    }

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final LedgerProperties props;

    public TradeSimulatorController(KafkaTemplate<String, String> kafka, JsonMapper json, LedgerProperties props) {
        this.kafka = kafka;
        this.json = json;
        this.props = props;
    }

    @PostMapping("/api/admin/simulate/trades")
    Map<String, Object> simulate(@Valid @RequestBody SimulateTrades request) throws Exception {
        long base = Amounts.parseCents(request.basePrice());
        var random = ThreadLocalRandom.current();
        List<CompletableFuture<?>> sends = new ArrayList<>(request.count());
        for (int i = 0; i < request.count(); i++) {
            int buyer = random.nextInt(request.participants().size());
            int seller = (buyer + 1 + random.nextInt(request.participants().size() - 1)) % request.participants().size();
            long price = Math.max(1, base + random.nextLong(-base / 100, base / 100 + 1));
            var trade = new TradeExecuted(
                    request.symbol() + "-SIM-" + UUID.randomUUID(),
                    request.symbol(),
                    Amounts.formatCents(price),
                    Long.toString(1 + random.nextInt(100)),
                    "EUR",
                    request.participants().get(buyer),
                    request.participants().get(seller),
                    Instant.now());
            sends.add(kafka.send(props.topics().trades(), request.symbol(), json.writeValueAsString(trade)));
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        return Map.of("produced", request.count(), "topic", props.topics().trades());
    }
}
