package io.github.davifmn.ledger.transfer;

import io.github.davifmn.ledger.common.ApiException;
import io.github.davifmn.ledger.common.Hashing;
import io.github.davifmn.ledger.common.IdempotencyService;
import io.github.davifmn.ledger.common.RateLimiter;
import io.github.davifmn.ledger.transfer.TransferService.CreateResult;
import io.github.davifmn.ledger.transfer.TransferService.PaymentStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api")
public class TransferController {

    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final RateLimiter rateLimiter;
    private final JsonMapper json;

    public TransferController(TransferService transfers, IdempotencyService idempotency, RateLimiter rateLimiter,
                              JsonMapper json) {
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.json = json;
    }

    /**
     * Creates an outbound transfer. The Idempotency-Key header is required: retrying
     * with the same key and body returns the original transfer (200 and
     * Idempotent-Replayed: true) instead of paying twice.
     */
    @PostMapping(path = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Transfer> create(@RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody OutboundTransferRequest request) {
        if (!key.matches("[A-Za-z0-9_.:-]{8,64}")) {
            throw ApiException.badRequest("Idempotency-Key must be 8-64 characters of [A-Za-z0-9_.:-]");
        }
        rateLimiter.check(request.participantId());
        String hash = Hashing.sha256(json.writeValueAsString(request));
        String scope = request.participantId();

        IdempotencyService.Decision decision = idempotency.begin(scope, key, hash);
        if (decision instanceof IdempotencyService.Decision.Replay replay) {
            return replayed(transfers.get(UUID.fromString(replay.resourceId())));
        }
        CreateResult result;
        try {
            result = transfers.createOutbound(request, key, hash);
        } catch (DuplicateKeyException e) {
            // Two requests with the same key raced past Redis (it was down): the database
            // kept one; answer with it. A clash on the end-to-end id is a real conflict.
            idempotency.abandon(scope, key);
            result = transfers.replay(request.participantId(), key, hash)
                    .orElseThrow(() -> ApiException.conflict("endToEndId " + request.endToEndId() + " was already used"));
        } catch (RuntimeException e) {
            idempotency.abandon(scope, key);
            throw e;
        }
        idempotency.complete(scope, key, hash, result.transfer().id().toString());
        if (!result.created()) {
            return replayed(result.transfer());
        }
        return ResponseEntity.created(URI.create("/api/transfers/" + result.transfer().id())).body(result.transfer());
    }

    private static ResponseEntity<Transfer> replayed(Transfer transfer) {
        return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(transfer);
    }

    /** A pain.001 file with one or more payments. Answers with a status per payment. */
    @PostMapping(path = "/transfers/pain001", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    List<PaymentStatus> pain001(@RequestBody byte[] xml) {
        return transfers.submitPain001(xml);
    }

    /** Inbound credit transfers from the clearing bank (pacs.008). */
    @PostMapping(path = "/sepa/inbound", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    List<PaymentStatus> inbound(@RequestBody byte[] xml) {
        return transfers.receivePacs008(xml);
    }

    @GetMapping("/transfers/{id}")
    Transfer get(@PathVariable UUID id) {
        return transfers.get(id);
    }

    @GetMapping("/transfers")
    List<Transfer> list(@RequestParam(required = false) String participantId,
                        @RequestParam(required = false) Transfer.Status status,
                        @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            throw ApiException.badRequest("limit must be between 1 and 500");
        }
        return transfers.list(participantId, status, limit);
    }
}
