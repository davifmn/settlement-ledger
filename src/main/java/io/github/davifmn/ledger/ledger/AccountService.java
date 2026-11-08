package io.github.davifmn.ledger.ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Finds and opens accounts. Client accounts are opened on first use, per asset. */
@Service
public class AccountService {

    public static final String EUR = "EUR";
    public static final String SEPA_CLEARING = "SEPA_CLEARING";
    public static final String FEES = "FEES";

    private final JdbcClient jdbc;

    public AccountService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The participant's account for an asset, opened if it does not exist yet. */
    public long clientAccount(String participantId, String asset) {
        jdbc.sql("""
                        insert into account (participant_id, asset) values (:participant, :asset)
                        on conflict (participant_id, asset) do nothing""")
                .param("participant", participantId)
                .param("asset", asset)
                .update();
        return jdbc.sql("select id from account where participant_id = :participant and asset = :asset")
                .param("participant", participantId)
                .param("asset", asset)
                .query(Long.class)
                .single();
    }

    public Optional<Long> findClientAccount(String participantId, String asset) {
        return jdbc.sql("select id from account where participant_id = :participant and asset = :asset")
                .param("participant", participantId)
                .param("asset", asset)
                .query(Long.class)
                .optional();
    }

    /** A system account seeded by the migrations (SEPA_CLEARING, FEES). */
    public long systemAccount(String code) {
        return jdbc.sql("select id from account where code = :code")
                .param("code", code)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("missing system account " + code));
    }

    /**
     * The depository account of an instrument: where securities come from when they are
     * deposited into the ledger. It mirrors the holdings, so it may go negative.
     */
    public long depositoryAccount(String symbol) {
        String code = "CSD:" + symbol;
        jdbc.sql("""
                        insert into account (code, asset, allow_negative) values (:code, :asset, true)
                        on conflict (code) do nothing""")
                .param("code", code)
                .param("asset", symbol)
                .update();
        return systemAccount(code);
    }

    public record Statement(long accountId, String participantId, String asset, long balance,
                            List<Line> lines, Long nextAfterId) {
    }

    public record Line(long postingId, long entryId, String kind, String reference, long amount,
                       long balanceAfter, Instant at) {
    }

    /** Postings of an account, oldest first, with keyset pagination on the posting id. */
    public Optional<Statement> statement(long accountId, long afterId, int limit) {
        record Header(String participantId, String asset, long balance) {
        }
        Optional<Header> header = jdbc.sql("select participant_id, asset, balance from account where id = :id")
                .param("id", accountId)
                .query((rs, n) -> new Header(rs.getString(1), rs.getString(2), rs.getLong(3)))
                .optional();
        return header.map(h -> {
            var lines = jdbc.sql("""
                            select p.id, p.entry_id, e.kind, e.reference, p.amount, p.balance_after, e.created_at
                            from posting p join journal_entry e on e.id = p.entry_id
                            where p.account_id = :account and p.id > :after
                            order by p.id
                            limit :limit""")
                    .param("account", accountId)
                    .param("after", afterId)
                    .param("limit", limit)
                    .query((rs, n) -> new Line(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6), rs.getObject(7, OffsetDateTime.class).toInstant()))
                    .list();
            Long next = lines.size() == limit ? lines.getLast().postingId() : null;
            return new Statement(accountId, h.participantId(), h.asset(), h.balance(), lines, next);
        });
    }
}
