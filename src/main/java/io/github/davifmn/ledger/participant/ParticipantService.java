package io.github.davifmn.ledger.participant;

import io.github.davifmn.ledger.common.ApiException;
import io.github.davifmn.ledger.config.LedgerProperties;
import io.github.davifmn.ledger.ledger.AccountService;
import io.github.davifmn.ledger.sepa.Iban;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Participants (the clients of the ledger) and their virtual IBANs. */
@Service
public class ParticipantService {

    public static final String ID_PATTERN = "[A-Za-z0-9_-]{1,32}";

    public record Participant(String id, String name, String iban) {
    }

    public record Balance(long accountId, String asset, long balance) {
    }

    public record ParticipantView(String id, String name, String iban, List<Balance> balances) {
    }

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final LedgerProperties props;

    public ParticipantService(JdbcClient jdbc, AccountService accounts, LedgerProperties props) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.props = props;
    }

    /** Opens a participant with a EUR account and a virtual IBAN for incoming transfers. */
    @Transactional
    public Participant create(String id, String name) {
        long number = jdbc.sql("select nextval('participant_iban_seq')").query(Long.class).single();
        String iban = Iban.of("DE", props.sepa().virtualIbanBankCode() + String.format("%010d", number));
        try {
            jdbc.sql("insert into participant (id, name, iban) values (:id, :name, :iban)")
                    .param("id", id)
                    .param("name", name)
                    .param("iban", iban)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("participant " + id + " already exists");
        }
        accounts.clientAccount(id, AccountService.EUR);
        return new Participant(id, name, iban);
    }

    public Optional<Participant> find(String id) {
        return jdbc.sql("select id, name, iban from participant where id = :id")
                .param("id", id)
                .query((rs, n) -> new Participant(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    public Optional<Participant> findByIban(String iban) {
        return jdbc.sql("select id, name, iban from participant where iban = :iban")
                .param("iban", Iban.normalize(iban))
                .query((rs, n) -> new Participant(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    public Participant require(String id) {
        return find(id).orElseThrow(() -> ApiException.notFound("participant " + id + " does not exist"));
    }

    public List<ParticipantView> list() {
        return views(null);
    }

    public Optional<ParticipantView> view(String id) {
        return views(id).stream().findFirst();
    }

    private List<ParticipantView> views(String onlyId) {
        record Row(String id, String name, String iban, Long accountId, String asset, long balance) {
        }
        List<Row> rows = jdbc.sql("""
                        select p.id, p.name, p.iban, a.id, a.asset, a.balance
                        from participant p left join account a on a.participant_id = p.id
                        where cast(:id as text) is null or p.id = :id
                        order by p.id, a.asset""")
                .param("id", onlyId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getString(5), rs.getLong(6)))
                .list();
        Map<String, ParticipantView> views = new LinkedHashMap<>();
        for (Row row : rows) {
            ParticipantView view = views.computeIfAbsent(row.id(),
                    k -> new ParticipantView(k, row.name(), row.iban(), new ArrayList<>()));
            if (row.accountId() != null) {
                view.balances().add(new Balance(row.accountId(), row.asset(), row.balance()));
            }
        }
        return List.copyOf(views.values());
    }
}
