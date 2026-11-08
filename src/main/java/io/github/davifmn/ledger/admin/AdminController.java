package io.github.davifmn.ledger.admin;

import io.github.davifmn.ledger.common.ApiException;
import io.github.davifmn.ledger.ledger.AccountService;
import io.github.davifmn.ledger.ledger.Leg;
import io.github.davifmn.ledger.ledger.LedgerService;
import io.github.davifmn.ledger.ledger.PostingResult;
import io.github.davifmn.ledger.participant.ParticipantService;
import io.github.davifmn.ledger.settlement.SettlementInstruction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operations endpoints: invariant report and securities deposits (from the depository). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record SecuritiesDeposit(
            @NotBlank @Pattern(regexp = ParticipantService.ID_PATTERN) String participantId,
            @NotBlank @Pattern(regexp = SettlementInstruction.SYMBOL_PATTERN) String symbol,
            @Min(1) @Max(1_000_000_000) long quantity,
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String reference) {
    }

    private final InvariantService invariants;
    private final ParticipantService participants;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final TransactionTemplate tx;

    public AdminController(InvariantService invariants, ParticipantService participants, AccountService accounts,
                           LedgerService ledger, PlatformTransactionManager txManager) {
        this.invariants = invariants;
        this.participants = participants;
        this.accounts = accounts;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
    }

    @GetMapping("/invariants")
    InvariantService.Report invariants() {
        return invariants.check();
    }

    @PostMapping("/securities-deposits")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> deposit(@Valid @RequestBody SecuritiesDeposit request) {
        if ("EUR".equals(request.symbol())) {
            throw ApiException.badRequest("EUR is cash, not a security; fund cash with an inbound transfer");
        }
        participants.require(request.participantId());
        String reference = request.reference() == null ? UUID.randomUUID().toString() : request.reference();
        try {
            PostingResult result = tx.execute(s -> ledger.post("SECURITIES_DEPOSIT", reference,
                    "deposit of " + request.quantity() + " " + request.symbol(), List.of(
                            new Leg(accounts.depositoryAccount(request.symbol()), request.symbol(), -request.quantity()),
                            new Leg(accounts.clientAccount(request.participantId(), request.symbol()), request.symbol(),
                                    request.quantity()))));
            long entryId = ((PostingResult.Posted) result).entryId();
            return Map.of("entryId", entryId, "reference", reference);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("deposit " + reference + " was already booked");
        }
    }
}
