package io.github.davifmn.ledger.transfer;

import io.github.davifmn.ledger.sepa.Iban;
import io.github.davifmn.ledger.sepa.RejectReason;
import io.github.davifmn.ledger.sepa.SepaRules;
import java.util.Optional;

/** The SEPA checks on an outbound instruction. Returns the rejection reason, if any. */
public final class TransferRules {

    private TransferRules() {
    }

    public static Optional<String> validateOutbound(String currency, long amountCents, long maxAmountCents,
                                                    String creditorIban, String creditorBic, String creditorName,
                                                    String remittance, String endToEndId) {
        if (!"EUR".equals(currency)) {
            return Optional.of(RejectReason.AM03.with("SEPA transfers are in EUR, got " + currency));
        }
        if (amountCents > maxAmountCents) {
            return Optional.of(RejectReason.AM02.with("above the limit of " + maxAmountCents / 100 + " EUR"));
        }
        if (!Iban.isValid(creditorIban)) {
            return Optional.of(RejectReason.AC01.with("creditor IBAN is not a valid SEPA IBAN"));
        }
        if (creditorBic != null && !creditorBic.isBlank() && !SepaRules.isValidBic(creditorBic)) {
            return Optional.of(RejectReason.RC01.with("creditor BIC " + creditorBic));
        }
        if (!SepaRules.isValidText(creditorName, SepaRules.MAX_NAME)) {
            return Optional.of(RejectReason.FF01.with("creditor name must be 1-70 characters of the SEPA character set"));
        }
        if (remittance != null && !remittance.isEmpty() && !SepaRules.isValidText(remittance, SepaRules.MAX_REMITTANCE)) {
            return Optional.of(RejectReason.FF01.with("remittance information must be up to 140 characters of the SEPA character set"));
        }
        if (!SepaRules.isValidReference(endToEndId)) {
            return Optional.of(RejectReason.FF01.with("end-to-end id must be up to 35 characters, no spaces or '//'"));
        }
        return Optional.empty();
    }
}
