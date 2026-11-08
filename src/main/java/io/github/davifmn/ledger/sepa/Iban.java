package io.github.davifmn.ledger.sepa;

import java.util.Locale;
import java.util.Map;

/** IBAN validation (ISO 13616, mod-97 check digits) for the SEPA countries. */
public final class Iban {

    /** Total IBAN length per SEPA country. */
    private static final Map<String, Integer> LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AT", 20), Map.entry("BE", 16), Map.entry("BG", 22),
            Map.entry("CH", 21), Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22),
            Map.entry("DK", 18), Map.entry("EE", 20), Map.entry("ES", 24), Map.entry("FI", 18),
            Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GI", 23), Map.entry("GR", 27),
            Map.entry("HR", 21), Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IS", 26),
            Map.entry("IT", 27), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20),
            Map.entry("LV", 21), Map.entry("MC", 27), Map.entry("MT", 31), Map.entry("NL", 18),
            Map.entry("NO", 15), Map.entry("PL", 28), Map.entry("PT", 25), Map.entry("RO", 24),
            Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27),
            Map.entry("VA", 22));

    private Iban() {
    }

    /** Removes spaces and upper-cases: "de89 3704 ..." → "DE893704...". */
    public static String normalize(String iban) {
        return iban == null ? "" : iban.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String iban) {
        String s = normalize(iban);
        if (!s.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) {
            return false;
        }
        Integer length = LENGTHS.get(s.substring(0, 2));
        return length != null && s.length() == length && mod97(s.substring(4) + s.substring(0, 4)) == 1;
    }

    /** Builds a valid IBAN from a country code and a national account number (BBAN). */
    public static String of(String country, String bban) {
        int check = 98 - mod97(bban + country + "00");
        String iban = country + (check < 10 ? "0" : "") + check + bban;
        if (!isValid(iban)) {
            throw new IllegalArgumentException("cannot build a valid " + country + " IBAN from " + bban);
        }
        return iban;
    }

    /** Remainder of the number formed by the digits (letters become 10..35), digit by digit. */
    private static int mod97(String s) {
        int remainder = 0;
        for (int i = 0; i < s.length(); i++) {
            int value = Character.digit(s.charAt(i), 36);
            remainder = value < 10 ? (remainder * 10 + value) % 97 : (remainder * 100 + value) % 97;
        }
        return remainder;
    }
}
