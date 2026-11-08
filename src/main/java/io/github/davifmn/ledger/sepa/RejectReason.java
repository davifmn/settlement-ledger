package io.github.davifmn.ledger.sepa;

/** The ISO 20022 status reason codes used when a transfer is rejected (as in a pain.002/pacs.002). */
public enum RejectReason {
    AC01("incorrect account number"),
    AM02("amount not allowed"),
    AM03("currency not allowed"),
    AM04("insufficient funds"),
    FF01("invalid format"),
    RC01("bank identifier incorrect");

    private final String description;

    RejectReason(String description) {
        this.description = description;
    }

    /** "AC01 incorrect account number: creditor IBAN fails the checksum". */
    public String with(String detail) {
        return name() + " " + description + (detail == null ? "" : ": " + detail);
    }
}
