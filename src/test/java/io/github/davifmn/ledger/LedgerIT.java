package io.github.davifmn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.davifmn.ledger.admin.InvariantService;
import io.github.davifmn.ledger.ledger.AccountService;
import io.github.davifmn.ledger.ledger.Leg;
import io.github.davifmn.ledger.ledger.LedgerService;
import io.github.davifmn.ledger.ledger.PostingResult;
import io.github.davifmn.ledger.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerIT extends IntegrationTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountService accounts;

    @Autowired
    InvariantService invariants;

    @Autowired
    PlatformTransactionManager txManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private long eurAccount(String participant) throws Exception {
        createParticipant(participant);
        return accounts.clientAccount(participant, "EUR");
    }

    private PostingResult move(long from, long to, long cents, String reference) {
        return tx.execute(s -> ledger.post("TEST", reference, null, List.of(
                new Leg(from, "EUR", -cents), new Leg(to, "EUR", cents))));
    }

    private void fundDirectly(long account, long cents) {
        long clearing = accounts.systemAccount(AccountService.SEPA_CLEARING);
        assertThat(move(clearing, account, cents, unique("fund"))).isInstanceOf(PostingResult.Posted.class);
    }

    @Test
    void postingMovesBalancesAndKeepsARunningHistory() throws Exception {
        String alice = unique("alice");
        long a = eurAccount(alice);
        long b = eurAccount(unique("bob"));
        fundDirectly(a, 10_000);

        assertThat(move(a, b, 3_000, unique("t"))).isInstanceOf(PostingResult.Posted.class);

        var statement = accounts.statement(a, 0, 10).orElseThrow();
        assertThat(statement.balance()).isEqualTo(7_000);
        assertThat(statement.participantId()).isEqualTo(alice);
        assertThat(statement.lines()).extracting(AccountService.Line::amount).containsExactly(10_000L, -3_000L);
        assertThat(statement.lines()).extracting(AccountService.Line::balanceAfter).containsExactly(10_000L, 7_000L);
        assertThat(accounts.statement(b, 0, 10).orElseThrow().balance()).isEqualTo(3_000);
    }

    @Test
    void refusesToOverdrawAndWritesNothing() throws Exception {
        long a = eurAccount(unique("alice"));
        long b = eurAccount(unique("bob"));
        fundDirectly(a, 1_000);
        String reference = unique("overdraw");

        PostingResult result = move(a, b, 2_000, reference);

        assertThat(result).isEqualTo(new PostingResult.Rejected(a, "EUR", 1_000, 2_000));
        assertThat(jdbc.sql("select count(*) from journal_entry where reference = :r").param("r", reference)
                .query(Long.class).single()).isZero();
        assertThat(accounts.statement(a, 0, 10).orElseThrow().balance()).isEqualTo(1_000);
    }

    @Test
    void theDatabaseRefusesAnUnbalancedEntryEvenWithoutTheService() throws Exception {
        long a = eurAccount(unique("alice"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long entry = jdbc.sql("insert into journal_entry (kind, reference) values ('HACK', :r) returning id")
                    .param("r", unique("hack")).query(Long.class).single();
            jdbc.sql("insert into posting (entry_id, account_id, asset, amount, balance_after) values (:e, :a, 'EUR', 500, 500)")
                    .param("e", entry).param("a", a).update();
        })).hasStackTraceContaining("does not balance");
    }

    @Test
    void theJournalIsAppendOnly() throws Exception {
        long a = eurAccount(unique("alice"));
        fundDirectly(a, 100);

        assertThatThrownBy(() -> jdbc.sql("update posting set amount = amount * 2 where account_id = :a")
                .param("a", a).update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from journal_entry where kind = 'TEST'").update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void concurrentTransfersInBothDirectionsNeitherDeadlockNorLoseMoney() throws Exception {
        long a = eurAccount(unique("alice"));
        long b = eurAccount(unique("bob"));
        fundDirectly(a, 100_000);
        fundDirectly(b, 100_000);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < 8; thread++) {
            boolean aToB = thread % 2 == 0;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    PostingResult r = aToB ? move(a, b, 7, unique("c")) : move(b, a, 5, unique("c"));
                    assertThat(r).isInstanceOf(PostingResult.Posted.class);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS); // rethrows a deadlock or assertion failure from the thread
        }
        pool.shutdown();

        long balanceA = accounts.statement(a, 0, 1).orElseThrow().balance();
        long balanceB = accounts.statement(b, 0, 1).orElseThrow().balance();
        assertThat(balanceA).isEqualTo(100_000 - 4 * 50 * 7 + 4 * 50 * 5);
        assertThat(balanceA + balanceB).isEqualTo(200_000);
        assertThat(invariants.check().ok()).isTrue();
    }
}
