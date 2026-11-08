package io.github.davifmn.ledger.settlement;

import io.github.davifmn.ledger.common.ApiException;
import io.github.davifmn.ledger.settlement.SettlementService.SettlementView;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private static final Set<String> STATUSES = Set.of("SETTLED", "FAILED", "PENDING");

    private final SettlementService settlements;

    public SettlementController(SettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping
    List<SettlementView> recent(@RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "50") int limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be one of " + STATUSES);
        }
        if (limit < 1 || limit > 500) {
            throw ApiException.badRequest("limit must be between 1 and 500");
        }
        return settlements.recent(status, limit);
    }

    @GetMapping("/{tradeId}")
    SettlementView get(@PathVariable String tradeId) {
        return settlements.find(tradeId).orElseThrow(() -> ApiException.notFound("no settlement for trade " + tradeId));
    }
}
