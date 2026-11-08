package io.github.davifmn.ledger.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs a SEPA settlement cycle every few seconds (real SEPA clears in several cycles a day). */
@Component
public class SepaSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(SepaSettlementJob.class);

    private final TransferService transfers;

    public SepaSettlementJob(TransferService transfers) {
        this.transfers = transfers;
    }

    @Scheduled(fixedDelayString = "${ledger.sepa.settlement-interval-ms:5000}",
            initialDelayString = "${ledger.sepa.initial-delay-ms:5000}")
    public void run() {
        try {
            TransferService.BatchResult result = transfers.settleBatch();
            if (result.settled() + result.rejected() > 0) {
                log.info("SEPA cycle: {} settled, {} rejected", result.settled(), result.rejected());
            }
        } catch (RuntimeException e) {
            log.warn("SEPA cycle failed, will retry next cycle: {}", e.getMessage());
        }
    }
}
