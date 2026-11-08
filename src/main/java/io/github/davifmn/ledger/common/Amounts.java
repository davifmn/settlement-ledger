package io.github.davifmn.ledger.common;

import java.math.BigDecimal;

/** Converts decimal strings from the outside world into integer minor units, exactly. */
public final class Amounts {

    private Amounts() {
    }

    /**
     * Parses a positive decimal with at most two fractional digits ("34.15", "100") into
     * cents. Anything else ("1e3", "0.001", "-5", "abc") is rejected rather than rounded.
     */
    public static long parseCents(String decimal) {
        if (decimal == null || !decimal.matches("\\d{1,15}(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("not a positive amount with at most 2 decimals: " + decimal);
        }
        long cents = new BigDecimal(decimal).movePointRight(2).longValueExact();
        if (cents <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + decimal);
        }
        return cents;
    }

    /** Parses a positive whole number of units (shares). */
    public static long parseUnits(String number) {
        if (number == null || !number.matches("\\d{1,15}")) {
            throw new IllegalArgumentException("not a positive whole number: " + number);
        }
        long units = Long.parseLong(number);
        if (units <= 0) {
            throw new IllegalArgumentException("must be positive: " + number);
        }
        return units;
    }

    /** 123456 → "1234.56". */
    public static String formatCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
