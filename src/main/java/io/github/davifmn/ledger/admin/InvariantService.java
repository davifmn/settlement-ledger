package io.github.davifmn.ledger.admin;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the properties that must always hold, from the raw tables:
 * <ol>
 *   <li>every journal entry sums to zero per asset;</li>
 *   <li>every account's stored balance equals the sum of its postings;</li>
 *   <li>every asset sums to zero over all accounts (nothing is created or destroyed).</li>
 * </ol>
 * They run every 15 s and feed the ledger_invariant_violations gauge, which must stay at
 * zero (docs/runbook.md). The scans are full scans: fine at this size; a big ledger would
 * check incrementally, per day or per account range.
 */
@Service
public class InvariantService {

    private static final Logger log = LoggerFactory.getLogger(InvariantService.class);

    public record Report(boolean ok, long unbalancedEntries, long accountsWithDrift,
                         Map<String, Long> assetImbalances, long outboxBacklog) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final AtomicLong violations = new AtomicLong();
    private final AtomicLong backlog = new AtomicLong();

    public InvariantService(JdbcClient jdbc, PlatformTransactionManager txManager, MeterRegistry meters) {
        this.jdbc = jdbc;
        // one snapshot for all the queries, so a posting committed in between cannot
        // make the report contradict itself
        this.snapshot = new TransactionTemplate(txManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        Gauge.builder("ledger.invariant.violations", violations, AtomicLong::get)
                .description("Ledger invariants broken in the last check; must be 0")
                .register(meters);
        Gauge.builder("ledger.outbox.backlog", backlog, AtomicLong::get)
                .description("Outbox events not yet published to Kafka")
                .register(meters);
    }

    public Report check() {
        return snapshot.execute(status -> runChecks());
    }

    private Report runChecks() {
        long unbalanced = jdbc.sql("""
                        select count(*) from (
                            select entry_id from posting group by entry_id, asset having sum(amount) <> 0
                        ) broken""")
                .query(Long.class).single();
        long drift = jdbc.sql("""
                        select count(*)
                        from account a
                        left join (select account_id, sum(amount) as total from posting group by account_id) p
                               on p.account_id = a.id
                        where a.balance <> coalesce(p.total, 0)""")
                .query(Long.class).single();
        Map<String, Long> imbalances = new LinkedHashMap<>();
        jdbc.sql("select asset, sum(balance) from account group by asset having sum(balance) <> 0 order by asset")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(e -> imbalances.put(e.getKey(), e.getValue()));
        long unpublished = jdbc.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
        return new Report(unbalanced == 0 && drift == 0 && imbalances.isEmpty(), unbalanced, drift, imbalances, unpublished);
    }

    @Scheduled(fixedDelayString = "${ledger.invariants.check-interval-ms:15000}", initialDelay = 1000)
    public void refreshMetrics() {
        try {
            Report report = check();
            violations.set(report.unbalancedEntries() + report.accountsWithDrift() + report.assetImbalances().size());
            backlog.set(report.outboxBacklog());
            if (!report.ok()) {
                log.error("LEDGER INVARIANT BROKEN: {}", report);
            }
        } catch (RuntimeException e) {
            log.warn("invariant check failed: {}", e.getMessage());
        }
    }
}
