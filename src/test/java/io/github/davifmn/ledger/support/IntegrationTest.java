package io.github.davifmn.ledger.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class of the integration tests. All of them share one application context and
 * one set of containers; each test uses its own participants, so tests do not interfere
 * and the global invariants keep holding between them.
 */
@SpringBootTest(properties = {
        "ledger.simulator.enabled=true",
        "ledger.sepa.initial-delay-ms=3600000",
        "ledger.sepa.settlement-interval-ms=3600000",
        "ledger.rate-limit.requests-per-minute=20",
        "ledger.fee-bps=2"})
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    protected JdbcClient jdbc;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates a participant and returns its virtual IBAN. */
    protected String createParticipant(String id) throws Exception {
        String body = mvc.perform(post("/api/participants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"%s\",\"name\":\"Test %s\"}".formatted(id, id)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.iban");
    }

    /** Funds a participant the way real money arrives: a pacs.008 from the clearing bank. */
    protected void fund(String iban, String amount) throws Exception {
        mvc.perform(post("/api/sepa/inbound")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(pacs008(unique("FUND"), amount, iban)))
                .andExpect(status().isOk());
    }

    protected void depositShares(String participantId, String symbol, long quantity) throws Exception {
        mvc.perform(post("/api/admin/securities-deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantId\":\"%s\",\"symbol\":\"%s\",\"quantity\":%d}"
                                .formatted(participantId, symbol, quantity)))
                .andExpect(status().isCreated());
    }

    protected long balance(String participantId, String asset) {
        return jdbc.sql("select coalesce(max(balance), 0) from account where participant_id = :p and asset = :a")
                .param("p", participantId)
                .param("a", asset)
                .query(Long.class)
                .single();
    }

    protected static byte[] pacs008(String endToEndId, String amount, String creditorIban) {
        return """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"><FIToFICstmrCdtTrf>
                  <GrpHdr><MsgId>%s</MsgId></GrpHdr>
                  <CdtTrfTxInf>
                    <PmtId><EndToEndId>%s</EndToEndId></PmtId>
                    <IntrBkSttlmAmt Ccy="EUR">%s</IntrBkSttlmAmt>
                    <Dbtr><Nm>External Payer</Nm></Dbtr>
                    <DbtrAcct><Id><IBAN>FR1420041010050500013M02606</IBAN></Id></DbtrAcct>
                    <CdtrAcct><Id><IBAN>%s</IBAN></Id></CdtrAcct>
                  </CdtTrfTxInf>
                </FIToFICstmrCdtTrf></Document>"""
                .formatted(unique("MSG"), endToEndId, amount, creditorIban)
                .getBytes(StandardCharsets.UTF_8);
    }
}
