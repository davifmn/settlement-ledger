package io.github.davifmn.ledger.sepa;

import java.util.List;

/**
 * pacs.008 FI to FI Customer Credit Transfer: the clearing bank tells the ledger that
 * money arrived for one of its participants (credited to the participant's virtual IBAN).
 */
public record Pacs008(String messageId, List<Credit> credits) {

    public record Credit(String endToEndId, String amount, String currency, String debtorName,
                         String debtorIban, String creditorIban, String remittance) {
    }

    public static Pacs008 parse(byte[] xml) {
        IsoXml.Document doc = IsoXml.parse(xml, "CdtTrfTxInf");
        if (!"FIToFICstmrCdtTrf".equals(doc.messageType())) {
            throw new XmlFormatException("expected a pacs.008 FIToFICstmrCdtTrf message, got " + doc.messageType());
        }
        String messageId = doc.header("GrpHdr/MsgId");
        if (messageId == null) {
            throw new XmlFormatException("missing GrpHdr/MsgId");
        }
        List<Credit> credits = doc.records().stream()
                .map(r -> new Credit(
                        r.get("PmtId/EndToEndId"),
                        r.get("IntrBkSttlmAmt"),
                        r.get("IntrBkSttlmAmt@Ccy"),
                        r.get("Dbtr/Nm"),
                        r.get("DbtrAcct/Id/IBAN"),
                        r.get("CdtrAcct/Id/IBAN"),
                        r.get("RmtInf/Ustrd")))
                .toList();
        if (credits.isEmpty()) {
            throw new XmlFormatException("the message has no CdtTrfTxInf");
        }
        return new Pacs008(messageId, credits);
    }
}
