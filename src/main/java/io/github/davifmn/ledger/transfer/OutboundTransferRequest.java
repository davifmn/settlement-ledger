package io.github.davifmn.ledger.transfer;

import io.github.davifmn.ledger.participant.ParticipantService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A payment from a participant to an account at another bank. The amount is a decimal
 * string ("250.00"). Structural problems (missing fields) are a 400; SEPA rule
 * violations (bad IBAN, over the limit) are recorded as a REJECTED transfer with an ISO
 * reason code, as a bank would answer with a pain.002.
 */
public record OutboundTransferRequest(
        @NotBlank @Pattern(regexp = ParticipantService.ID_PATTERN) String participantId,
        @NotBlank @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?", message = "must be a positive amount with at most 2 decimals")
        String amount,
        String currency,
        @NotBlank @Size(max = 140) String creditorName,
        @NotBlank @Size(max = 40) String creditorIban,
        @Size(max = 11) String creditorBic,
        @Size(max = 200) String remittanceInformation,
        @Size(max = 35) String endToEndId) {
}
