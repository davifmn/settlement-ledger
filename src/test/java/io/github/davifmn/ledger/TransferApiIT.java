package io.github.davifmn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.davifmn.ledger.support.IntegrationTest;
import io.github.davifmn.ledger.transfer.TransferService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TransferApiIT extends IntegrationTest {

    @Autowired
    TransferService transfers;

    private static String transferJson(String participant, String amount, String iban, String e2e) {
        return """
                {"participantId":"%s","amount":"%s","creditorName":"Berlin Office Supplies",
                 "creditorIban":"%s","remittanceInformation":"Invoice 42"%s}"""
                .formatted(participant, amount, iban, e2e == null ? "" : ",\"endToEndId\":\"" + e2e + "\"");
    }

    private ResultActions createTransfer(String key, String json) throws Exception {
        return mvc.perform(post("/api/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void inboundCreditsFundTheParticipantOnce() throws Exception {
        String p = unique("p");
        String iban = createParticipant(p);
        byte[] message = pacs008(unique("E2E"), "1500.25", iban);

        String first = mvc.perform(post("/api/sepa/inbound").contentType(MediaType.APPLICATION_XML).content(message))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SETTLED"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/sepa/inbound").contentType(MediaType.APPLICATION_XML).content(message))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$[0].transferId")).isEqualTo(JsonPath.read(first, "$[0].transferId"));
        assertThat(balance(p, "EUR")).isEqualTo(150_025);
    }

    @Test
    void inboundForAnIbanOfNoParticipantIsRejected() throws Exception {
        mvc.perform(post("/api/sepa/inbound").contentType(MediaType.APPLICATION_XML)
                        .content(pacs008(unique("E2E"), "10.00", "DE89370400440532013000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].reason", startsWith("AC01")));
    }

    @Test
    void anOutboundTransferIsValidatedThenSettledInTheNextCycle() throws Exception {
        String p = unique("p");
        fund(createParticipant(p), "1000.00");

        String body = createTransfer(unique("key"), transferJson(p, "250.00", "DE89370400440532013000", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.endToEndId", startsWith("LDG")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        transfers.settleBatch();

        mvc.perform(get("/api/transfers/" + id))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.events[*].status").value(contains("RECEIVED", "VALIDATED", "SETTLED")));
        assertThat(balance(p, "EUR")).isEqualTo(75_000);
    }

    @Test
    void retryingWithTheSameKeyDoesNotPayTwice() throws Exception {
        String p = unique("p");
        fund(createParticipant(p), "1000.00");
        String key = unique("key");
        String json = transferJson(p, "100.00", "DE89370400440532013000", null);

        String first = createTransfer(key, json).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = createTransfer(key, json).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(jdbc.sql("select count(*) from transfer where participant_id = :p and direction = 'OUTBOUND'")
                .param("p", p).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() throws Exception {
        String p = unique("p");
        createParticipant(p);
        String key = unique("key");

        createTransfer(key, transferJson(p, "100.00", "DE89370400440532013000", null)).andExpect(status().isCreated());
        createTransfer(key, transferJson(p, "999.00", "DE89370400440532013000", null)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void theDatabaseStillDeduplicatesWhenRedisForgotTheKey() throws Exception {
        String p = unique("p");
        createParticipant(p);
        String key = unique("key");
        String json = transferJson(p, "5.00", "DE89370400440532013000", null);
        createTransfer(key, json).andExpect(status().isCreated());

        // as if Redis had been flushed or failed over
        context.getBean(StringRedisTemplate.class).delete("idem:" + p + ":" + key);

        createTransfer(key, json).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(jdbc.sql("select count(*) from transfer where participant_id = :p").param("p", p)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sepaRuleViolationsAreRecordedAsRejectedWithAReasonCode() throws Exception {
        String p = unique("p");
        createParticipant(p);

        createTransfer(unique("key"), transferJson(p, "10.00", "DE89370400440532013001", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason", startsWith("AC01")));
    }

    @Test
    void insufficientFundsAreDetectedAtSettlement() throws Exception {
        String p = unique("p");
        fund(createParticipant(p), "50.00");

        String body = createTransfer(unique("key"), transferJson(p, "80.00", "DE89370400440532013000", null))
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andReturn().getResponse().getContentAsString();
        transfers.settleBatch();

        mvc.perform(get("/api/transfers/" + JsonPath.read(body, "$.id")))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason", startsWith("AM04")));
        assertThat(balance(p, "EUR")).isEqualTo(5_000);
    }

    @Test
    void requestsWithoutAKeyOrWithBadFieldsAre400() throws Exception {
        String p = unique("p");
        createParticipant(p);

        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                .content(transferJson(p, "1.00", "DE89370400440532013000", null))).andExpect(status().isBadRequest());
        createTransfer(unique("key"), transferJson(p, "1,50", "DE89370400440532013000", null)).andExpect(status().isBadRequest());
        createTransfer(unique("key"), transferJson(p, "0.00", "DE89370400440532013000", null)).andExpect(status().isBadRequest());
        createTransfer(unique("key"), transferJson("nobody-here", "1.00", "DE89370400440532013000", null))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPain001FileCreatesOneTransferPerInstructionAndCanBeResent() throws Exception {
        String p = unique("p");
        String iban = createParticipant(p);
        byte[] file = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn>
                  <GrpHdr><MsgId>%s</MsgId></GrpHdr>
                  <PmtInf><DbtrAcct><Id><IBAN>%s</IBAN></Id></DbtrAcct>
                    <CdtTrfTxInf><PmtId><EndToEndId>A-1</EndToEndId></PmtId><Amt><InstdAmt Ccy="EUR">10.00</InstdAmt></Amt>
                      <Cdtr><Nm>Creditor One</Nm></Cdtr><CdtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></CdtrAcct></CdtTrfTxInf>
                    <CdtTrfTxInf><PmtId><EndToEndId>A-2</EndToEndId></PmtId><Amt><InstdAmt Ccy="EUR">20.00</InstdAmt></Amt>
                      <Cdtr><Nm>Creditor Two</Nm></Cdtr><CdtrAcct><Id><IBAN>NL91ABNA0417164301</IBAN></Id></CdtrAcct></CdtTrfTxInf>
                  </PmtInf></CstmrCdtTrfInitn></Document>"""
                .formatted(unique("MSG"), iban).getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/api/transfers/pain001").contentType(MediaType.APPLICATION_XML).content(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("VALIDATED"))
                .andExpect(jsonPath("$[1].status").value("REJECTED"))
                .andExpect(jsonPath("$[1].reason", startsWith("AC01")));
        mvc.perform(post("/api/transfers/pain001").contentType(MediaType.APPLICATION_XML).content(file))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("select count(*) from transfer where participant_id = :p").param("p", p)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void malformedXmlIs400() throws Exception {
        mvc.perform(post("/api/sepa/inbound").contentType(MediaType.APPLICATION_XML).content("<Document><oops>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("malformed")));
    }

    @Test
    void aParticipantSendingTooFastGets429WithRetryAfter() throws Exception {
        long second = Instant.now().getEpochSecond() % 60;
        if (second > 50) {
            Thread.sleep((61 - second) * 1000); // stay inside one rate-limit window
        }
        String p = unique("p");
        createParticipant(p);
        for (int i = 0; i < 20; i++) {
            createTransfer(unique("key"), transferJson(p, "1.00", "DE89370400440532013000", null))
                    .andExpect(status().isCreated());
        }
        createTransfer(unique("key"), transferJson(p, "1.00", "DE89370400440532013000", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void reconciliationReportsEveryKindOfBreak() throws Exception {
        String p = unique("p");
        String iban = createParticipant(p);
        String inbound = unique("IN");
        mvc.perform(post("/api/sepa/inbound").contentType(MediaType.APPLICATION_XML).content(pacs008(inbound, "100.00", iban)))
                .andExpect(status().isOk());
        String outbound = unique("OUT");
        createTransfer(unique("key"), transferJson(p, "40.00", "DE89370400440532013000", outbound)).andExpect(status().isCreated());
        transfers.settleBatch();
        String unknown = unique("GHOST");
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        byte[] statement = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt><Id>S1</Id>
                  %s %s %s
                </Stmt></BkToCstmrStmt></Document>"""
                .formatted(entry(inbound, "100.00", "CRDT", today), entry(outbound, "45.00", "DBIT", today),
                        entry(unknown, "10.00", "CRDT", today))
                .getBytes(StandardCharsets.UTF_8);

        String report = mvc.perform(post("/api/reconciliation/camt053").contentType(MediaType.APPLICATION_XML).content(statement))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> breaks = JsonPath.read(report, "$.breaks");
        assertThat(breaks).noneMatch(b -> inbound.equals(b.get("endToEndId")));
        assertThat(breaks).anyMatch(b -> outbound.equals(b.get("endToEndId")) && "AMOUNT_MISMATCH".equals(b.get("type")));
        assertThat(breaks).anyMatch(b -> unknown.equals(b.get("endToEndId")) && "MISSING_IN_LEDGER".equals(b.get("type")));
    }

    private static String entry(String e2e, String amount, String direction, String date) {
        return """
                <Ntry><Amt Ccy="EUR">%s</Amt><CdtDbtInd>%s</CdtDbtInd><BookgDt><Dt>%s</Dt></BookgDt>
                  <NtryDtls><TxDtls><Refs><EndToEndId>%s</EndToEndId></Refs></TxDtls></NtryDtls></Ntry>"""
                .formatted(amount, direction, date, e2e);
    }

    @Test
    void invariantsHoldAndMetricsAreExposed() throws Exception {
        String p = unique("p");
        fund(createParticipant(p), "10.00");

        mvc.perform(get("/api/admin/invariants")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ledger_transfers_total")))
                .andExpect(content().string(containsString("ledger_invariant_violations")));
    }
}
