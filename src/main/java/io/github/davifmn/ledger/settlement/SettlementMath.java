package io.github.davifmn.ledger.settlement;

import io.github.davifmn.ledger.ledger.AccountService;
import io.github.davifmn.ledger.ledger.Leg;
import java.util.ArrayList;
import java.util.List;

/** The cash and securities movements of a trade. Pure functions, so they are easy to test. */
public final class SettlementMath {

    private SettlementMath() {
    }

    /** What each side pays or receives, in cents. Each side pays the fee on the notional. */
    public record Cash(long notional, long feePerSide, long buyerPays, long sellerReceives, long totalFees) {
    }

    public record Accounts(long buyerCash, long sellerCash, long buyerSecurities, long sellerSecurities, long fees) {
    }

    public static Cash cash(long priceCents, long quantity, int feeBps) {
        long notional = Math.multiplyExact(priceCents, quantity);
        long fee = fee(notional, feeBps);
        return new Cash(notional, fee, Math.addExact(notional, fee), notional - fee, 2 * fee);
    }

    /** Fee in basis points, rounded half up to the cent. */
    public static long fee(long notionalCents, int bps) {
        return Math.addExact(Math.multiplyExact(notionalCents, bps), 5_000) / 10_000;
    }

    /**
     * Delivery versus payment in one entry: the buyer's cash and the seller's securities
     * move together, or not at all.
     */
    public static List<Leg> legs(String symbol, long quantity, Cash cash, Accounts a) {
        List<Leg> legs = new ArrayList<>(5);
        legs.add(new Leg(a.buyerCash(), AccountService.EUR, -cash.buyerPays()));
        legs.add(new Leg(a.sellerCash(), AccountService.EUR, cash.sellerReceives()));
        if (cash.totalFees() > 0) {
            legs.add(new Leg(a.fees(), AccountService.EUR, cash.totalFees()));
        }
        legs.add(new Leg(a.sellerSecurities(), symbol, -quantity));
        legs.add(new Leg(a.buyerSecurities(), symbol, quantity));
        return legs;
    }
}
