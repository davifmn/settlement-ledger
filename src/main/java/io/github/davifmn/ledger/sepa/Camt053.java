package io.github.davifmn.ledger.sepa;

import io.github.davifmn.ledger.common.Amounts;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * camt.053 Bank to Customer Statement: the clearing bank's view of the ledger's
 * settlement account for a day. Used for reconciliation.
 */
public record Camt053(String statementId, String accountIban, List<Entry> entries) {

    public enum Direction { CREDIT, DEBIT }

    /** One booked movement. CREDIT = money came in (an inbound transfer), DEBIT = money went out. */
    public record Entry(String endToEndId, long amountCents, Direction direction, LocalDate bookingDate) {
    }

    public static Camt053 parse(byte[] xml) {
        IsoXml.Document doc = IsoXml.parse(xml, "Ntry");
        if (!"BkToCstmrStmt".equals(doc.messageType())) {
            throw new XmlFormatException("expected a camt.053 BkToCstmrStmt message, got " + doc.messageType());
        }
        List<Entry> entries = doc.records().stream().map(Camt053::entry).toList();
        return new Camt053(doc.header("Stmt/Id"), doc.header("Stmt/Acct/Id/IBAN"), entries);
    }

    private static Entry entry(IsoXml.Record r) {
        String e2e = r.get("NtryDtls/TxDtls/Refs/EndToEndId");
        if (e2e == null) {
            throw new XmlFormatException("an Ntry has no NtryDtls/TxDtls/Refs/EndToEndId");
        }
        if (!"EUR".equals(r.get("Amt@Ccy"))) {
            throw new XmlFormatException("entry " + e2e + " is not in EUR");
        }
        long cents;
        try {
            cents = Amounts.parseCents(r.get("Amt"));
        } catch (IllegalArgumentException e) {
            throw new XmlFormatException("entry " + e2e + ": " + e.getMessage());
        }
        Direction direction = switch (String.valueOf(r.get("CdtDbtInd"))) {
            case "CRDT" -> Direction.CREDIT;
            case "DBIT" -> Direction.DEBIT;
            default -> throw new XmlFormatException("entry " + e2e + ": CdtDbtInd must be CRDT or DBIT");
        };
        String date = r.get("BookgDt/Dt") != null ? r.get("BookgDt/Dt") : r.get("BookgDt/DtTm");
        if (date == null) {
            throw new XmlFormatException("entry " + e2e + " has no booking date");
        }
        try {
            return new Entry(e2e, cents, direction, LocalDate.parse(date.substring(0, Math.min(10, date.length()))));
        } catch (DateTimeParseException e) {
            throw new XmlFormatException("entry " + e2e + ": bad booking date " + date);
        }
    }
}
