package io.github.davifmn.ledger.sepa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Parses the sample messages in samples/, which the README and the smoke test also use. */
class SepaMessagesTest {

    private static byte[] sample(String name) throws IOException {
        return Files.readAllBytes(Path.of("samples", name));
    }

    @Test
    void readsPain001() throws IOException {
        Pain001 message = Pain001.parse(sample("pain001.xml"));

        assertThat(message.messageId()).isEqualTo("ALICE-20261108-001");
        assertThat(message.instructions()).hasSize(2);
        Pain001.Instruction first = message.instructions().getFirst();
        assertThat(first.debtorIban()).isEqualTo("DE43999999990001000001");
        assertThat(first.endToEndId()).isEqualTo("INV-2026-0042");
        assertThat(first.amount()).isEqualTo("1000.00");
        assertThat(first.currency()).isEqualTo("EUR");
        assertThat(first.creditorName()).isEqualTo("Berlin Office Supplies GmbH");
        assertThat(first.creditorIban()).isEqualTo("DE89370400440532013000");
        assertThat(first.creditorBic()).isEqualTo("COBADEFFXXX");
        assertThat(first.remittance()).isEqualTo("Invoice 2026-0042");
        Pain001.Instruction second = message.instructions().get(1);
        assertThat(second.debtorIban()).isEqualTo("DE43999999990001000001");
        assertThat(second.creditorBic()).isNull();
        assertThat(second.amount()).isEqualTo("250.50");
    }

    @Test
    void eachPaymentInformationBlockHasItsOwnDebtor() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.03"><CstmrCdtTrfInitn>
                  <GrpHdr><MsgId>M1</MsgId></GrpHdr>
                  <PmtInf><DbtrAcct><Id><IBAN>DE43999999990001000001</IBAN></Id></DbtrAcct>
                    <CdtTrfTxInf><PmtId><EndToEndId>E1</EndToEndId></PmtId></CdtTrfTxInf></PmtInf>
                  <PmtInf><DbtrAcct><Id><IBAN>DE16999999990001000002</IBAN></Id></DbtrAcct>
                    <CdtTrfTxInf><PmtId><EndToEndId>E2</EndToEndId></PmtId></CdtTrfTxInf></PmtInf>
                </CstmrCdtTrfInitn></Document>""";
        Pain001 message = Pain001.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(message.instructions()).extracting(Pain001.Instruction::debtorIban)
                .containsExactly("DE43999999990001000001", "DE16999999990001000002");
    }

    @Test
    void readsPacs008() throws IOException {
        Pacs008 message = Pacs008.parse(sample("pacs008.xml"));

        assertThat(message.messageId()).isEqualTo("CLEARING-20261108-0007");
        assertThat(message.credits()).hasSize(2);
        Pacs008.Credit credit = message.credits().getFirst();
        assertThat(credit.endToEndId()).isEqualTo("FUNDING-ALICE-1");
        assertThat(credit.amount()).isEqualTo("50000.00");
        assertThat(credit.currency()).isEqualTo("EUR");
        assertThat(credit.debtorName()).isEqualTo("Alice Private Account");
        assertThat(credit.debtorIban()).isEqualTo("FR1420041010050500013M02606");
        assertThat(credit.creditorIban()).isEqualTo("DE43999999990001000001");
        assertThat(credit.remittance()).isEqualTo("Funding");
        assertThat(message.credits().get(1).remittance()).isNull();
    }

    @Test
    void readsCamt053() throws IOException {
        Camt053 statement = Camt053.parse(sample("camt053.xml"));

        assertThat(statement.statementId()).isEqualTo("STMT-20261108-01");
        assertThat(statement.accountIban()).isEqualTo("DE93999999990000000001");
        assertThat(statement.entries()).containsExactly(
                new Camt053.Entry("FUNDING-ALICE-1", 5_000_000, Camt053.Direction.CREDIT, LocalDate.of(2026, 11, 8)),
                new Camt053.Entry("INV-2026-0042", 100_000, Camt053.Direction.DEBIT, LocalDate.of(2026, 11, 8)));
    }

    @Test
    void refusesTheWrongMessageType() throws IOException {
        byte[] pacs = sample("pacs008.xml");
        assertThatThrownBy(() -> Pain001.parse(pacs))
                .isInstanceOf(XmlFormatException.class)
                .hasMessageContaining("expected a pain.001");
    }
}
