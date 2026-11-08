package io.github.davifmn.ledger.reconciliation;

import io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.Direction;
import io.github.davifmn.ledger.reconciliation.ReconciliationMatcher.Item;
import io.github.davifmn.ledger.sepa.Camt053;
import io.github.davifmn.ledger.transfer.Transfer;
import io.github.davifmn.ledger.transfer.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Daily reconciliation: the bank's camt.053 statement of the clearing account against
 * the SEPA transfers the ledger settled on the same days. Every difference is a "break"
 * for operations to investigate (docs/runbook.md).
 */
@Service
public class ReconciliationService {

    public record Report(String statementId, LocalDate from, LocalDate to, int bankEntries, int ledgerTransfers,
                         int matched, List<ReconciliationMatcher.Break> breaks) {
    }

    private final TransferService transfers;
    private final MeterRegistry meters;

    public ReconciliationService(TransferService transfers, MeterRegistry meters) {
        this.transfers = transfers;
        this.meters = meters;
    }

    public Report reconcile(byte[] camt053) {
        Camt053 statement = Camt053.parse(camt053);
        if (statement.entries().isEmpty()) {
            return new Report(statement.statementId(), null, null, 0, 0, 0, List.of());
        }
        LocalDate from = statement.entries().stream().map(Camt053.Entry::bookingDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = statement.entries().stream().map(Camt053.Entry::bookingDate).max(Comparator.naturalOrder()).orElseThrow();

        List<Item> bank = statement.entries().stream()
                .map(e -> new Item(e.direction() == Camt053.Direction.CREDIT ? Direction.IN : Direction.OUT,
                        e.endToEndId(), e.amountCents()))
                .toList();
        List<Transfer> settled = transfers.settledBetween(from, to);
        List<Item> ledger = settled.stream()
                .map(t -> new Item(t.direction() == Transfer.Direction.INBOUND ? Direction.IN : Direction.OUT,
                        t.endToEndId(), t.amountCents()))
                .toList();

        ReconciliationMatcher.Result result = ReconciliationMatcher.match(ledger, bank);
        result.breaks().forEach(b -> meters.counter("ledger.reconciliation.breaks", "type", b.type().name().toLowerCase()).increment());
        return new Report(statement.statementId(), from, to, bank.size(), ledger.size(), result.matched(), result.breaks());
    }
}
