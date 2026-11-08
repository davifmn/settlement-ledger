package io.github.davifmn.ledger.sepa;

import java.util.regex.Pattern;

/** Field rules from the EPC SEPA Credit Transfer implementation guidelines. */
public final class SepaRules {

    /** The SEPA "Latin character set" every bank must accept. */
    private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9/\\-?:().,'+ ]*");
    private static final Pattern BIC = Pattern.compile("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");

    public static final int MAX_NAME = 70;
    public static final int MAX_REMITTANCE = 140;
    public static final int MAX_REFERENCE = 35;

    private SepaRules() {
    }

    public static boolean isValidText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength && TEXT.matcher(value).matches();
    }

    /** Identifiers such as the end-to-end id: SEPA charset, no spaces, no leading/trailing or double '/'. */
    public static boolean isValidReference(String value) {
        return isValidText(value, MAX_REFERENCE)
                && !value.contains(" ")
                && !value.startsWith("/")
                && !value.endsWith("/")
                && !value.contains("//");
    }

    public static boolean isValidBic(String bic) {
        return bic != null && BIC.matcher(bic).matches();
    }
}
