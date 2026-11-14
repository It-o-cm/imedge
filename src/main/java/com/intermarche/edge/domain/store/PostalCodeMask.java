package com.intermarche.edge.domain.store;

/**
 * Puts a postal code into the SHAPE its country writes it (BO-06-03-03): the
 * administered mask, {@code C} standing for a digit and every other character
 * for itself — {@code CCCCC} in France, {@code CCCC} in Belgium,
 * {@code CCCC-CCC} in Portugal.
 *
 * <p>Used on both sides of the survey: the store node normalises the list of
 * codes a survey is based on when it is imported from a spreadsheet, and the
 * register normalises the code the cashier keys, so a Portuguese code keyed or
 * read as {@code 4100300} becomes {@code 4100-300} — the dash inserted rather
 * than demanded — and matches the list.
 */
public final class PostalCodeMask {

    /**
     * Not instantiable.
     */
    private PostalCodeMask() {
    }

    /**
     * Normalises a postal code to a mask.
     *
     * @param code the code as keyed or read, possibly null
     * @param mask the administered mask, possibly null
     * @return the code in the mask's shape when its digits fill the mask's
     *         slots exactly, else the code trimmed and upper-cased; an empty
     *         text for null
     */
    public static String normalize(String code, String mask) {
        if (code == null) {
            return "";
        }
        String trimmed = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (mask == null || mask.isBlank()) {
            return trimmed;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        long slots = mask.chars().filter(c -> c == 'C').count();
        if (digits.length() != slots || digits.length() != trimmed.replaceAll("[^0-9A-Z]", "").length()) {
            return trimmed;
        }
        StringBuilder shaped = new StringBuilder();
        int next = 0;
        for (char slot : mask.toCharArray()) {
            if (slot == 'C') {
                shaped.append(digits.charAt(next++));
            } else {
                shaped.append(slot);
            }
        }
        return shaped.toString();
    }
}
