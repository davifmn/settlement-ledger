package io.github.davifmn.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code ledger.*}. Scheduling intervals are read directly by the
 * {@code @Scheduled} annotations (ledger.outbox.poll-interval-ms and so on).
 */
@ConfigurationProperties("ledger")
public record LedgerProperties(
        @DefaultValue("2") int feeBps,
        @DefaultValue Topics topics,
        @DefaultValue Outbox outbox,
        @DefaultValue Sepa sepa,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Simulator simulator) {

    public LedgerProperties {
        if (feeBps < 0 || feeBps > 1_000) {
            throw new IllegalArgumentException("ledger.fee-bps must be between 0 and 1000, got " + feeBps);
        }
    }

    public record Topics(
            @DefaultValue("trades.executed") String trades,
            @DefaultValue("ledger.events") String events,
            @DefaultValue("3") int partitions,
            @DefaultValue("1") int replicas) {

        public String tradesDeadLetter() {
            return trades + ".DLT";
        }
    }

    public record Outbox(@DefaultValue("100") int batchSize) {
    }

    public record Sepa(
            @DefaultValue("200") int batchSize,
            @DefaultValue("100000000") long maxAmountCents,
            @DefaultValue("99999999") String virtualIbanBankCode) {
    }

    public record RateLimit(@DefaultValue("600") int requestsPerMinute) {
    }

    public record Simulator(@DefaultValue("false") boolean enabled) {
    }
}
