package io.github.davifmn.ledger.sepa;

import java.util.List;

/**
 * pain.001 Customer Credit Transfer Initiation: a participant asks the ledger to pay
 * one or more creditors at other banks. Only the fields SEPA needs are read.
 */
public record Pain001(String messageId, List<Instruction> instructions) {

    public record Instruction(String debtorIban, String endToEndId, String amount, String currency,
                              String creditorName, String creditorIban, String creditorBic, String remittance) {
    }

    public static Pain001 parse(byte[] xml) {
        IsoXml.Document doc = IsoXml.parse(xml, "CdtTrfTxInf");
        if (!"CstmrCdtTrfInitn".equals(doc.messageType())) {
            throw new XmlFormatException("expected a pain.001 CstmrCdtTrfInitn message, got " + doc.messageType());
        }
        String messageId = doc.header("GrpHdr/MsgId");
        if (messageId == null) {
            throw new XmlFormatException("missing GrpHdr/MsgId");
        }
        List<Instruction> instructions = doc.records().stream()
                .map(r -> new Instruction(
                        r.context("PmtInf/DbtrAcct/Id/IBAN"),
                        r.get("PmtId/EndToEndId"),
                        r.get("Amt/InstdAmt"),
                        r.get("Amt/InstdAmt@Ccy"),
                        r.get("Cdtr/Nm"),
                        r.get("CdtrAcct/Id/IBAN"),
                        r.get("CdtrAgt/FinInstnId/BICFI") != null
                                ? r.get("CdtrAgt/FinInstnId/BICFI")
                                : r.get("CdtrAgt/FinInstnId/BIC"),
                        r.get("RmtInf/Ustrd")))
                .toList();
        if (instructions.isEmpty()) {
            throw new XmlFormatException("the message has no CdtTrfTxInf");
        }
        return new Pain001(messageId, instructions);
    }
}
