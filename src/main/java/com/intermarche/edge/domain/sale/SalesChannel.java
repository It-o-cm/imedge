package com.intermarche.edge.domain.sale;

/**
 * The SALES CHANNEL a transaction comes from (BO-07-01-01, BO-07-01-02): the
 * store's own lanes, or a third-party system whose sales the point of sale
 * integrates — the e-commerce platform, a mobility tool.
 *
 * <p>Carried by the ticket so the revenue of the point of sale can tell the
 * sales of its lanes from the ones it integrated, and so every state built on
 * the tickets — the cash book, the revenue by channel, the SAF-T file — can
 * isolate them. A ticket carrying no channel is a ticket of the store's own
 * lanes, which is every ticket a register ever produced.
 */
public enum SalesChannel {

    /** A sale of the store's own lanes. */
    STORE("Magasin"),

    /** An order of the e-commerce platform (BO-07-01-01). */
    ECOMMERCE("E-commerce"),

    /** A sale of a mobility tool (BO-07-01-01). */
    MOBILITY("Mobilité");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a channel with its operator-facing label.
     *
     * @param label the French label
     */
    SalesChannel(String label) {
        this.label = label;
    }

    /**
     * Returns the operator-facing label.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Whether the channel is a third-party system's rather than the store's.
     *
     * @return true for the e-commerce platform and the mobility tools
     */
    public boolean isThirdParty() {
        return this != STORE;
    }

    /**
     * Resolves a channel name, tolerating case, blanks and unknown values.
     *
     * @param raw the name, or null
     * @return the channel, or null when unrecognised
     */
    public static SalesChannel fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (SalesChannel channel : values()) {
            if (channel.name().equals(trimmed)) {
                return channel;
            }
        }
        return null;
    }

    /**
     * The channel of a ticket: the one it carries, the store's when none.
     *
     * @param channel the channel the ticket carries, or null
     * @return the channel, never null
     */
    public static SalesChannel of(SalesChannel channel) {
        return channel == null ? STORE : channel;
    }
}
