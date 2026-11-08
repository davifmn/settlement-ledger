package io.github.davifmn.ledger.ledger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes journal entries. This is the only code that changes balances.
 *
 * <p>Concurrency (docs/adr/0002): the accounts of an entry are locked with
 * {@code SELECT ... ORDER BY id FOR UPDATE}. Every writer takes its locks in ascending id
 * order, so two entries touching the same accounts queue up instead of deadlocking.
 */
@Service
public class LedgerService {

    private final JdbcClient jdbc;

    public LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record LockedAccount(long id, String asset, long balance, boolean allowNegative) {
    }

    /**
     * Posts a balanced entry. Must run inside the caller's transaction, so that the entry
     * commits (or not) together with the caller's own rows: the settlement record, the
     * transfer status, the outbox event.
     */
    public PostingResult post(String kind, String reference, String description, List<Leg> legs) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LedgerService.post must run inside a transaction");
        }
        Legs.requireBalanced(legs);

        List<Long> ids = legs.stream().map(Leg::accountId).distinct().sorted().toList();
        Map<Long, LockedAccount> accounts = new HashMap<>();
        jdbc.sql("select id, asset, balance, allow_negative from account where id in (:ids) order by id for update")
                .param("ids", ids)
                .query((rs, n) -> new LockedAccount(
                        rs.getLong("id"), rs.getString("asset"), rs.getLong("balance"), rs.getBoolean("allow_negative")))
                .list()
                .forEach(a -> accounts.put(a.id(), a));

        Map<Long, Long> delta = new LinkedHashMap<>();
        for (Leg leg : legs) {
            LockedAccount account = accounts.get(leg.accountId());
            if (account == null) {
                throw new IllegalArgumentException("unknown account " + leg.accountId());
            }
            if (!account.asset().equals(leg.asset())) {
                throw new IllegalArgumentException(
                        "account " + account.id() + " holds " + account.asset() + ", not " + leg.asset());
            }
            delta.merge(leg.accountId(), leg.amount(), Math::addExact);
        }
        for (var change : delta.entrySet()) {
            LockedAccount account = accounts.get(change.getKey());
            long after = Math.addExact(account.balance(), change.getValue());
            if (after < 0 && !account.allowNegative()) {
                return new PostingResult.Rejected(account.id(), account.asset(), account.balance(), -change.getValue());
            }
        }

        long entryId = jdbc.sql("""
                        insert into journal_entry (kind, reference, description)
                        values (:kind, :reference, :description)
                        returning id""")
                .param("kind", kind)
                .param("reference", reference)
                .param("description", description)
                .query(Long.class)
                .single();

        Map<Long, Long> running = new HashMap<>();
        accounts.forEach((id, a) -> running.put(id, a.balance()));
        for (Leg leg : legs) {
            long after = running.merge(leg.accountId(), leg.amount(), Math::addExact);
            jdbc.sql("""
                            insert into posting (entry_id, account_id, asset, amount, balance_after)
                            values (:entry, :account, :asset, :amount, :after)""")
                    .param("entry", entryId)
                    .param("account", leg.accountId())
                    .param("asset", leg.asset())
                    .param("amount", leg.amount())
                    .param("after", after)
                    .update();
        }
        for (Long id : delta.keySet()) {
            jdbc.sql("update account set balance = :balance where id = :id")
                    .param("balance", running.get(id))
                    .param("id", id)
                    .update();
        }
        return new PostingResult.Posted(entryId);
    }
}
