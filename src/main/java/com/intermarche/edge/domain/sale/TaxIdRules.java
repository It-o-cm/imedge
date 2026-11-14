package com.intermarche.edge.domain.sale;

import java.util.List;

/**
 * The legal construction of a Portuguese NIF (LC-08-06-04), as the store node
 * applies it to the NIF an e-commerce order states (BO-07-01-07) — the very
 * rule the register applies to the keyed and the scanned sale NIF.
 *
 * <p>NINE DIGITS, A LEGAL PREFIX, A CHECK DIGIT. The first eight digits are
 * weighted 9 down to 2; the remainder of their sum by 11 gives the check digit,
 * {@code 11 - remainder}, or 0 when the remainder is 0 or 1. The first digit
 * names the kind of taxpayer — 1, 2 and 3 for individuals, 5 for companies, 6
 * for public bodies, 8 for sole traders — and the two-digit prefixes 45, 70 to
 * 79 and 90, 91, 98, 99 the special cases, 999999990 (the "consumidor final")
 * among them.
 *
 * <p>ONLY WHAT LOOKS PORTUGUESE IS CHECKED. An entry made of digits, prefixed
 * or not by {@code PT}, is a Portuguese NIF and must be valid. An entry with
 * other letters is a foreign identifier, which the till keeps without control
 * (LC-08-06-09): refusing it here would block a foreign customer's sale.
 */
public final class TaxIdRules {

    /** The single first digits a Portuguese NIF may start with. */
    private static final String FIRST_DIGITS = "123568";

    /** The two-digit prefixes a Portuguese NIF may also start with. */
    private static final List<String> PREFIXES =
            List.of("45", "70", "71", "72", "74", "75", "77", "79", "90", "91", "98", "99");

    /**
     * Not instantiable: a holder of static rules.
     */
    private TaxIdRules() {
    }

    /**
     * Returns the digits of an entry that is a Portuguese NIF: the whole entry
     * when it is made of digits, or what follows a {@code PT} country prefix.
     *
     * @param entry the trimmed, upper-cased entry, possibly null
     * @return the digits to check, or null when the entry is empty or foreign
     */
    public static String portugueseDigits(String entry) {
        if (entry == null) {
            return null;
        }
        String digits = entry.startsWith("PT") ? entry.substring(2) : entry;
        return digits.matches("\\d+") ? digits : null;
    }

    /**
     * Tells whether a string of digits is a well-formed Portuguese NIF: nine
     * digits, a legal prefix and the right check digit.
     *
     * @param digits the digits, possibly null
     * @return true when the NIF is legally constructed
     */
    public static boolean isValidPortuguese(String digits) {
        if (digits == null || !digits.matches("\\d{9}")) {
            return false;
        }
        if (FIRST_DIGITS.indexOf(digits.charAt(0)) < 0 && !PREFIXES.contains(digits.substring(0, 2))) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (digits.charAt(i) - '0') * (9 - i);
        }
        int remainder = sum % 11;
        int check = remainder < 2 ? 0 : 11 - remainder;
        return check == digits.charAt(8) - '0';
    }

    /**
     * Tells whether an entry may be recorded as the sale NIF: always when the
     * store does not check, and otherwise when it is foreign, empty, or a
     * valid Portuguese NIF.
     *
     * @param entry the trimmed, upper-cased entry
     * @param check whether the store checks Portuguese NIFs (LC-08-06-04)
     * @return true when the entry may be recorded
     */
    public static boolean acceptable(String entry, boolean check) {
        if (!check) {
            return true;
        }
        String digits = portugueseDigits(entry);
        return digits == null || isValidPortuguese(digits);
    }
}
