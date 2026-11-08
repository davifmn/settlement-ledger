package io.github.davifmn.ledger.sepa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IsoXmlTest {

    private static IsoXml.Document parse(String xml, String record) {
        return IsoXml.parse(xml.getBytes(StandardCharsets.UTF_8), record);
    }

    @Test
    void ignoresNamespacesAndReadsAttributes() {
        IsoXml.Document doc = parse("""
                <ns:Document xmlns:ns="urn:x"><ns:Msg><ns:Hdr><ns:Id>H1</ns:Id></ns:Hdr>
                  <ns:Rec><ns:Amt Ccy="EUR">1.50</ns:Amt></ns:Rec>
                  <ns:Rec><ns:Amt Ccy="CHF">2.00</ns:Amt></ns:Rec>
                </ns:Msg></ns:Document>""", "Rec");

        assertThat(doc.messageType()).isEqualTo("Msg");
        assertThat(doc.header("Hdr/Id")).isEqualTo("H1");
        assertThat(doc.records()).hasSize(2);
        assertThat(doc.records().get(1).get("Amt")).isEqualTo("2.00");
        assertThat(doc.records().get(1).get("Amt@Ccy")).isEqualTo("CHF");
        assertThat(doc.records().get(1).context("Hdr/Id")).isEqualTo("H1");
    }

    @Test
    void refusesExternalEntities() {
        // The classic XXE payload: if entities were resolved, /etc/passwd would end up in the record.
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Document><Msg><Rec><Nm>&xxe;</Nm></Rec></Msg></Document>""";

        assertThatThrownBy(() -> parse(xxe, "Rec")).isInstanceOf(XmlFormatException.class);
    }

    @Test
    void refusesEntityExpansionBombs() {
        String bomb = """
                <?xml version="1.0"?>
                <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>
                <Document><Msg><Rec><Nm>&lol2;</Nm></Rec></Msg></Document>""";

        assertThatThrownBy(() -> parse(bomb, "Rec")).isInstanceOf(XmlFormatException.class);
    }

    @Test
    void refusesMalformedAndOversizedMessages() {
        assertThatThrownBy(() -> parse("<Document><Msg>", "Rec")).isInstanceOf(XmlFormatException.class);
        assertThatThrownBy(() -> IsoXml.parse(new byte[0], "Rec")).isInstanceOf(XmlFormatException.class);
        byte[] big = new byte[IsoXml.MAX_BYTES + 1];
        assertThatThrownBy(() -> IsoXml.parse(big, "Rec"))
                .isInstanceOf(XmlFormatException.class)
                .hasMessageContaining("1 MB");
    }
}
