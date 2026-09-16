package com.intermarche.pos.ui.journal;

/**
 * The whitelisted sort columns of the transactional journal list
 * (BO-04-01-51: the result list must be sortable ascending or descending on a
 * closed set of columns). Modelled as an enum so an unknown or absent sort key
 * can only ever fall back to the {@link #DATE} default — a user-supplied sort
 * string never reaches the JPQL {@code order by} verbatim.
 */
public enum JournalSort {

    /** Sort by register (TPV) identifier. */
    TERMINAL("terminal", "t.terminalId"),
    /** Sort by transaction (ticket) number. */
    TRANSACTION("transaction", "t.ticketNumber"),
    /** Sort by cashier badge. */
    CASHIER("cashier", "t.cashier.badgeId"),
    /** Sort by transaction datetime. */
    DATE("date", "t.creationDate"),
    /** Sort by ticket amount. */
    AMOUNT("amount", "t.totalIncludingTax"),
    /** Sort by article count. */
    ITEMS("items", "t.itemCount");

    /** The form key that selects this column. */
    public final String key;

    /** The JPQL path this column orders on. */
    public final String path;

    /**
     * Builds a sort column.
     *
     * @param key the form key
     * @param path the JPQL path
     */
    JournalSort(String key, String path) {
        this.key = key;
        this.path = path;
    }

    /**
     * Resolves a form key to its column, falling back to {@link #DATE} for an
     * unknown or null key.
     *
     * @param key the form key, possibly null or unknown
     * @return the matching column, or {@link #DATE} by default
     */
    public static JournalSort fromKey(String key) {
        if (key == null) {
            return DATE;
        }
        for (JournalSort sort : values()) {
            if (sort.key.equals(key)) {
                return sort;
            }
        }
        return DATE;
    }
}
