package io.github.davifmn.ledger.transfer;

import io.github.davifmn.ledger.common.Amounts;
import io.github.davifmn.ledger.common.ApiException;
import io.github.davifmn.ledger.config.LedgerProperties;
import io.github.davifmn.ledger.ledger.AccountService;
import io.github.davifmn.ledger.ledger.Leg;
import io.github.davifmn.ledger.ledger.LedgerService;
import io.github.davifmn.ledger.ledger.PostingResult;
import io.github.davifmn.ledger.outbox.OutboxWriter;
import io.github.davifmn.ledger.participant.ParticipantService;
import io.github.davifmn.ledger.participant.ParticipantService.Participant;
import io.github.davifmn.ledger.sepa.Iban;
import io.github.davifmn.ledger.sepa.Pacs008;
import io.github.davifmn.ledger.sepa.Pain001;
import io.github.davifmn.ledger.sepa.RejectReason;
import io.github.davifmn.ledger.transfer.Transfer.Direction;
import io.github.davifmn.ledger.transfer.Transfer.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SEPA credit transfers.
 *
 * <ul>
 *   <li>Outbound: an instruction (JSON or pain.001) is RECEIVED, checked against the SEPA
 *   rules and becomes VALIDATED or REJECTED. A settlement cycle ({@link #settleBatch})
 *   later debits the participant and marks it SETTLED, or REJECTED with AM04 if the
 *   money is not there any more.</li>
 *   <li>Inbound: a pacs.008 from the clearing bank credits the participant at once.</li>
 * </ul>
 *
 * <p>Each ledger posting runs in its own short transaction, so a transaction never holds
 * account locks from one posting while it waits for the next (docs/adr/0002).
 */
@Service
public class TransferService {

    public record CreateResult(Transfer transfer, boolean created) {
    }

    /** The answer to one instruction of a file, in the spirit of a pain.002 status report. */
    public record PaymentStatus(String endToEndId, UUID transferId, String status, String reason) {
    }

    public record BatchResult(int settled, int rejected) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final AccountService accounts;
    private final ParticipantService participants;
    private final OutboxWriter outbox;
    private final LedgerProperties props;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public TransferService(JdbcClient jdbc, LedgerService ledger, AccountService accounts,
                           ParticipantService participants, OutboxWriter outbox, LedgerProperties props,
                           MeterRegistry meters, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.participants = participants;
        this.outbox = outbox;
        this.props = props;
        this.meters = meters;
        this.tx = new TransactionTemplate(txManager);
        for (Direction direction : Direction.values()) {
            for (Status status : List.of(Status.VALIDATED, Status.SETTLED, Status.REJECTED)) {
                counter(direction, status); // registered at zero, see SettlementService
            }
        }
    }

    // ---------------------------------------------------------------- outbound

    /**
     * Records an outbound instruction. The Idempotency-Key is unique per participant in
     * the database, which is the source of truth when Redis has forgotten the key or is
     * down (docs/adr/0005): the same key returns the original transfer, a different
     * request under the same key is refused.
     */
    @Transactional
    public CreateResult createOutbound(OutboundTransferRequest request, String idempotencyKey, String requestHash) {
        participants.require(request.participantId());
        Optional<CreateResult> replay = replay(request.participantId(), idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        long cents;
        try {
            cents = Amounts.parseCents(request.amount());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        String endToEndId = blank(request.endToEndId()) ? generatedEndToEndId() : request.endToEndId();
        UUID id = recordOutbound(request.participantId(), cents,
                blank(request.currency()) ? "EUR" : request.currency(),
                Iban.normalize(request.creditorIban()), request.creditorName(), request.creditorBic(),
                request.remittanceInformation(), endToEndId, idempotencyKey, requestHash);
        return new CreateResult(get(id), true);
    }

    /** The transfer created earlier with this key, if any. */
    public Optional<CreateResult> replay(String participantId, String idempotencyKey, String requestHash) {
        record Existing(UUID id, String hash) {
        }
        return jdbc.sql("select id, request_hash from transfer where participant_id = :p and idempotency_key = :k")
                .param("p", participantId)
                .param("k", idempotencyKey)
                .query((rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional()
                .map(existing -> {
                    if (!existing.hash().equals(requestHash)) {
                        throw ApiException.unprocessable("this Idempotency-Key was already used for a different request");
                    }
                    return new CreateResult(get(existing.id()), false);
                });
    }

    /** Records every instruction of a pain.001 file. Re-sending the same file creates nothing new. */
    @Transactional
    public List<PaymentStatus> submitPain001(byte[] xml) {
        Pain001 message = Pain001.parse(xml);
        List<PaymentStatus> statuses = new ArrayList<>();
        for (Pain001.Instruction i : message.instructions()) {
            Optional<Participant> debtor = i.debtorIban() == null ? Optional.empty() : participants.findByIban(i.debtorIban());
            if (debtor.isEmpty()) {
                statuses.add(new PaymentStatus(i.endToEndId(), null, Status.REJECTED.name(),
                        RejectReason.AC01.with("debtor IBAN " + i.debtorIban() + " is not an account of this ledger")));
                continue;
            }
            if (i.endToEndId() == null) {
                statuses.add(new PaymentStatus(null, null, Status.REJECTED.name(), RejectReason.FF01.with("missing EndToEndId")));
                continue;
            }
            Optional<Transfer> existing = findOutbound(debtor.get().id(), i.endToEndId());
            if (existing.isPresent()) {
                statuses.add(status(existing.get()));
                continue;
            }
            long cents;
            try {
                cents = Amounts.parseCents(i.amount());
            } catch (IllegalArgumentException e) {
                statuses.add(new PaymentStatus(i.endToEndId(), null, Status.REJECTED.name(), RejectReason.FF01.with(e.getMessage())));
                continue;
            }
            UUID id = recordOutbound(debtor.get().id(), cents, i.currency(), Iban.normalize(i.creditorIban()),
                    nonNull(i.creditorName()), i.creditorBic(), i.remittance(), i.endToEndId(), null, null);
            statuses.add(status(get(id)));
        }
        return statuses;
    }

    private UUID recordOutbound(String participantId, long cents, String currency, String creditorIban,
                                String creditorName, String creditorBic, String remittance, String endToEndId,
                                String idempotencyKey, String requestHash) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into transfer (id, direction, participant_id, amount_cents, currency, counterparty_iban,
                            counterparty_name, counterparty_bic, remittance_info, end_to_end_id, idempotency_key,
                            request_hash, status)
                        values (:id, 'OUTBOUND', :participant, :amount, :currency, :iban, :name, :bic, :remittance,
                            :e2e, :key, :hash, 'RECEIVED')""")
                .param("id", id)
                .param("participant", participantId)
                .param("amount", cents)
                .param("currency", currency == null ? "" : currency)
                .param("iban", creditorIban)
                .param("name", creditorName)
                .param("bic", blank(creditorBic) ? null : creditorBic)
                .param("remittance", blank(remittance) ? null : remittance)
                .param("e2e", endToEndId)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .update();
        event(id, Status.RECEIVED, null);
        Optional<String> rejection = TransferRules.validateOutbound(currency, cents, props.sepa().maxAmountCents(),
                creditorIban, creditorBic, creditorName, remittance, endToEndId);
        if (rejection.isPresent()) {
            reject(id, Direction.OUTBOUND, rejection.get());
        } else {
            setStatus(id, Status.VALIDATED, null, null);
            count(Direction.OUTBOUND, Status.VALIDATED);
        }
        return id;
    }

    /**
     * One settlement cycle: pays out VALIDATED outbound transfers, oldest first. Rows are
     * claimed with SKIP LOCKED, so several replicas can run cycles at the same time.
     */
    public BatchResult settleBatch() {
        List<UUID> candidates = jdbc.sql("""
                        select id from transfer
                        where status = 'VALIDATED' and direction = 'OUTBOUND'
                        order by created_at
                        limit :limit""")
                .param("limit", props.sepa().batchSize())
                .query((rs, n) -> rs.getObject(1, UUID.class))
                .list();
        int settled = 0;
        int rejected = 0;
        for (UUID id : candidates) {
            Status result = tx.execute(s -> settleOne(id));
            if (result == Status.SETTLED) {
                settled++;
            } else if (result == Status.REJECTED) {
                rejected++;
            }
        }
        return new BatchResult(settled, rejected);
    }

    private Status settleOne(UUID id) {
        record Claimed(String participantId, long amount, String iban) {
        }
        Optional<Claimed> claimed = jdbc.sql("""
                        select participant_id, amount_cents, counterparty_iban from transfer
                        where id = :id and status = 'VALIDATED'
                        for update skip locked""")
                .param("id", id)
                .query((rs, n) -> new Claimed(rs.getString(1), rs.getLong(2), rs.getString(3)))
                .optional();
        if (claimed.isEmpty()) {
            return null; // another replica has it, or it is already done
        }
        Claimed c = claimed.get();
        PostingResult result = ledger.post("TRANSFER_OUT", id.toString(), "SEPA credit transfer to " + c.iban(), List.of(
                new Leg(accounts.clientAccount(c.participantId(), AccountService.EUR), AccountService.EUR, -c.amount()),
                new Leg(accounts.systemAccount(AccountService.SEPA_CLEARING), AccountService.EUR, c.amount())));
        return switch (result) {
            case PostingResult.Posted p -> {
                settled(id, Direction.OUTBOUND, p.entryId());
                yield Status.SETTLED;
            }
            case PostingResult.Rejected r -> {
                reject(id, Direction.OUTBOUND, RejectReason.AM04.with(
                        "balance " + Amounts.formatCents(r.available()) + " EUR, needed " + Amounts.formatCents(r.required())));
                yield Status.REJECTED;
            }
        };
    }

    // ---------------------------------------------------------------- inbound

    /**
     * Books the credits of a pacs.008. Each credit is its own transaction and is
     * deduplicated by (debtor IBAN, end-to-end id), so a resent message is harmless.
     */
    public List<PaymentStatus> receivePacs008(byte[] xml) {
        Pacs008 message = Pacs008.parse(xml);
        List<PaymentStatus> statuses = new ArrayList<>();
        for (Pacs008.Credit credit : message.credits()) {
            statuses.add(tx.execute(s -> receive(credit)));
        }
        return statuses;
    }

    private PaymentStatus receive(Pacs008.Credit c) {
        if (c.endToEndId() == null || c.debtorIban() == null || blank(c.debtorName())) {
            return new PaymentStatus(c.endToEndId(), null, Status.REJECTED.name(),
                    RejectReason.FF01.with("EndToEndId, Dbtr/Nm and DbtrAcct/Id/IBAN are required"));
        }
        String debtorIban = Iban.normalize(c.debtorIban());
        Optional<UUID> existing = jdbc.sql("""
                        select id from transfer
                        where direction = 'INBOUND' and counterparty_iban = :iban and end_to_end_id = :e2e""")
                .param("iban", debtorIban)
                .param("e2e", c.endToEndId())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return status(get(existing.get()));
        }
        long cents;
        try {
            cents = Amounts.parseCents(c.amount());
        } catch (IllegalArgumentException e) {
            return new PaymentStatus(c.endToEndId(), null, Status.REJECTED.name(), RejectReason.FF01.with(e.getMessage()));
        }
        Optional<Participant> creditor = c.creditorIban() == null ? Optional.empty() : participants.findByIban(c.creditorIban());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into transfer (id, direction, participant_id, amount_cents, currency, counterparty_iban,
                            counterparty_name, remittance_info, end_to_end_id, status)
                        values (:id, 'INBOUND', :participant, :amount, :currency, :iban, :name, :remittance, :e2e,
                            'RECEIVED')""")
                .param("id", id)
                .param("participant", creditor.map(Participant::id).orElse(null))
                .param("amount", cents)
                .param("currency", c.currency() == null ? "" : c.currency())
                .param("iban", debtorIban)
                .param("name", c.debtorName())
                .param("remittance", c.remittance())
                .param("e2e", c.endToEndId())
                .update();
        event(id, Status.RECEIVED, null);
        if (!"EUR".equals(c.currency())) {
            reject(id, Direction.INBOUND, RejectReason.AM03.with("SEPA transfers are in EUR, got " + c.currency()));
        } else if (creditor.isEmpty()) {
            reject(id, Direction.INBOUND, RejectReason.AC01.with("creditor IBAN " + c.creditorIban() + " is not an account of this ledger"));
        } else {
            setStatus(id, Status.VALIDATED, null, null);
            PostingResult result = ledger.post("TRANSFER_IN", id.toString(), "SEPA credit transfer from " + debtorIban, List.of(
                    new Leg(accounts.systemAccount(AccountService.SEPA_CLEARING), AccountService.EUR, -cents),
                    new Leg(accounts.clientAccount(creditor.get().id(), AccountService.EUR), AccountService.EUR, cents)));
            if (!(result instanceof PostingResult.Posted p)) {
                throw new IllegalStateException("an inbound credit cannot be rejected by the ledger: " + result);
            }
            settled(id, Direction.INBOUND, p.entryId());
        }
        return status(get(id));
    }

    // ---------------------------------------------------------------- state changes

    private void settled(UUID id, Direction direction, long entryId) {
        setStatus(id, Status.SETTLED, null, entryId);
        Transfer t = get(id);
        Map<String, Object> event = eventPayload(t);
        event.put("entry_id", entryId);
        outbox.append("transfer", id.toString(), "TransferSettled", event);
        count(direction, Status.SETTLED);
    }

    private void reject(UUID id, Direction direction, String reason) {
        setStatus(id, Status.REJECTED, reason, null);
        Map<String, Object> event = eventPayload(get(id));
        event.put("reason", reason);
        outbox.append("transfer", id.toString(), "TransferRejected", event);
        count(direction, Status.REJECTED);
    }

    private void setStatus(UUID id, Status status, String reason, Long entryId) {
        jdbc.sql("""
                        update transfer
                        set status = :status, reject_reason = :reason, entry_id = coalesce(:entry, entry_id),
                            updated_at = now(),
                            settled_at = case when :status = 'SETTLED' then now() else settled_at end
                        where id = :id""")
                .param("status", status.name())
                .param("reason", reason)
                .param("entry", entryId)
                .param("id", id)
                .update();
        event(id, status, reason);
    }

    private void event(UUID id, Status status, String reason) {
        jdbc.sql("insert into transfer_event (transfer_id, status, reason) values (:id, :status, :reason)")
                .param("id", id)
                .param("status", status.name())
                .param("reason", reason)
                .update();
    }

    private void count(Direction direction, Status status) {
        counter(direction, status).increment();
    }

    private Counter counter(Direction direction, Status status) {
        return meters.counter("ledger.transfers", "direction", direction.name().toLowerCase(),
                "status", status.name().toLowerCase());
    }

    private static Map<String, Object> eventPayload(Transfer t) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("transfer_id", t.id());
        event.put("direction", t.direction());
        event.put("participant_id", t.participantId());
        event.put("amount_cents", t.amountCents());
        event.put("currency", t.currency());
        event.put("counterparty_iban", t.counterpartyIban());
        event.put("end_to_end_id", t.endToEndId());
        return event;
    }

    // ---------------------------------------------------------------- queries

    public Transfer get(UUID id) {
        return find(id).orElseThrow(() -> ApiException.notFound("transfer " + id + " does not exist"));
    }

    public Optional<Transfer> find(UUID id) {
        List<Transfer.Event> history = events(id);
        return jdbc.sql("select * from transfer where id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs, history))
                .optional();
    }

    private Optional<Transfer> findOutbound(String participantId, String endToEndId) {
        return jdbc.sql("""
                        select id from transfer
                        where direction = 'OUTBOUND' and participant_id = :p and end_to_end_id = :e2e""")
                .param("p", participantId)
                .param("e2e", endToEndId)
                .query(UUID.class)
                .optional()
                .map(this::get);
    }

    public List<Transfer> list(String participantId, Status status, int limit) {
        return jdbc.sql("""
                        select * from transfer
                        where (cast(:participant as text) is null or participant_id = :participant)
                          and (cast(:status as text) is null or status = :status)
                        order by created_at desc
                        limit :limit""")
                .param("participant", participantId)
                .param("status", status == null ? null : status.name())
                .param("limit", limit)
                .query((rs, n) -> map(rs, List.of()))
                .list();
    }

    /** SETTLED transfers booked on the given days (UTC), for reconciliation. */
    public List<Transfer> settledBetween(LocalDate from, LocalDate toInclusive) {
        return jdbc.sql("""
                        select * from transfer
                        where status = 'SETTLED'
                          and settled_at >= :from and settled_at < :to""")
                .param("from", from.atStartOfDay().atOffset(ZoneOffset.UTC))
                .param("to", toInclusive.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
                .query((rs, n) -> map(rs, List.of()))
                .list();
    }

    private List<Transfer.Event> events(UUID id) {
        return jdbc.sql("select status, reason, at from transfer_event where transfer_id = :id order by id")
                .param("id", id)
                .query((rs, n) -> new Transfer.Event(Status.valueOf(rs.getString(1)), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant()))
                .list();
    }

    private static Transfer map(ResultSet rs, List<Transfer.Event> events) throws SQLException {
        OffsetDateTime settledAt = rs.getObject("settled_at", OffsetDateTime.class);
        return new Transfer(
                rs.getObject("id", UUID.class),
                Direction.valueOf(rs.getString("direction")),
                rs.getString("participant_id"),
                rs.getLong("amount_cents"),
                rs.getString("currency"),
                rs.getString("counterparty_iban"),
                rs.getString("counterparty_name"),
                rs.getString("counterparty_bic"),
                rs.getString("remittance_info"),
                rs.getString("end_to_end_id"),
                Status.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                rs.getObject("entry_id", Long.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                settledAt == null ? null : settledAt.toInstant(),
                events);
    }

    private static PaymentStatus status(Transfer t) {
        return new PaymentStatus(t.endToEndId(), t.id(), t.status().name(), t.rejectReason());
    }

    private static String generatedEndToEndId() {
        return "LDG" + UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
