package io.github.davifmn.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.davifmn.ledger.ledger.Leg;
import io.github.davifmn.ledger.ledger.Legs;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementMathTest {

    private static final SettlementMath.Accounts ACCOUNTS = new SettlementMath.Accounts(1, 2, 3, 4, 5);

    @Test
    void buyerPaysNotionalPlusFeeAndSellerReceivesNotionalMinusFee() {
        // 300 shares at 34.15 EUR = 10245.00 EUR; 2 bps = 2.049 EUR, rounded to 2.05
        SettlementMath.Cash cash = SettlementMath.cash(3415, 300, 2);

        assertThat(cash.notional()).isEqualTo(1_024_500);
        assertThat(cash.feePerSide()).isEqualTo(205);
        assertThat(cash.buyerPays()).isEqualTo(1_024_705);
        assertThat(cash.sellerReceives()).isEqualTo(1_024_295);
        assertThat(cash.totalFees()).isEqualTo(410);
    }

    @Test
    void feesRoundHalfUpToTheCent() {
        assertThat(SettlementMath.fee(25_000, 2)).isEqualTo(5);  // 5.0 cents
        assertThat(SettlementMath.fee(12_500, 2)).isEqualTo(3);  // 2.5 → 3
        assertThat(SettlementMath.fee(12_499, 2)).isEqualTo(2);  // 2.4998 → 2
        assertThat(SettlementMath.fee(100, 0)).isZero();
    }

    @Test
    void legsAreDeliveryVersusPaymentAndBalance() {
        SettlementMath.Cash cash = SettlementMath.cash(3415, 300, 2);
        List<Leg> legs = SettlementMath.legs("PETR4", 300, cash, ACCOUNTS);

        assertThat(legs).containsExactly(
                new Leg(1, "EUR", -1_024_705),
                new Leg(2, "EUR", 1_024_295),
                new Leg(5, "EUR", 410),
                new Leg(4, "PETR4", -300),
                new Leg(3, "PETR4", 300));
        Legs.requireBalanced(legs);
    }

    @Test
    void noFeeLegWithoutFees() {
        List<Leg> legs = SettlementMath.legs("PETR4", 10, SettlementMath.cash(100, 10, 0), ACCOUNTS);

        assertThat(legs).hasSize(4).noneMatch(leg -> leg.accountId() == 5);
        Legs.requireBalanced(legs);
    }

    @Test
    void overflowIsAnErrorNotAWrongNumber() {
        assertThatThrownBy(() -> SettlementMath.cash(Long.MAX_VALUE / 2, 3, 2)).isInstanceOf(ArithmeticException.class);
    }
}
