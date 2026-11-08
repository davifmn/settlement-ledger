package io.github.davifmn.ledger.transfer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A SEPA credit transfer and its history. */
public record Transfer(
        UUID id,
        Direction direction,
        String participantId,
        long amountCents,
        String currency,
        String counterpartyIban,
        String counterpartyName,
        String counterpartyBic,
        String remittanceInfo,
        String endToEndId,
        Status status,
        String rejectReason,
        Long entryId,
        Instant createdAt,
        Instant settledAt,
        List<Event> events) {

    public enum Direction { OUTBOUND, INBOUND }

    /**
     * RECEIVED → VALIDATED → SETTLED, or → REJECTED from RECEIVED (bad instruction) or from
     * VALIDATED (insufficient funds at settlement time).
     */
    public enum Status { RECEIVED, VALIDATED, SETTLED, REJECTED }

    public record Event(Status status, String reason, Instant at) {
    }
}
