package com.intermarche.edge.domain.store;

/**
 * The promotion CHANNEL an island declares (BO-03-10-08): the origin a checkout
 * island tags its transactions with, so a promotion engine can tell a
 * point-of-sale purchase from a Web pickup.
 *
 * <p>This lot makes the channel administrable per island and resolvable for a
 * terminal (through {@link CheckoutIsland#findByTerminal(String)}). Carrying the
 * resolved value all the way into a fidelity event (FidEvent) belongs to the
 * fidelity-event mechanism, which does not exist in this store-node repository
 * yet — see the campaign report.
 */
public enum PromotionChannel {

    /** A purchase made at the point of sale (BO-03-10-08). */
    POINT_OF_SALE("Point de vente"),

    /** A Web order handled through the island (BO-03-10-08). */
    WEB("Web"),

    /** Any other channel (BO-03-10-08). */
    OTHER("Autres");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a channel with its operator-facing label.
     *
     * @param label the French label
     */
    PromotionChannel(String label) {
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
     * Resolves a channel name, tolerating an unknown or blank value.
     *
     * @param raw the posted name, or null
     * @return the channel, or null when unrecognised
     */
    public static PromotionChannel fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (PromotionChannel channel : values()) {
            if (channel.name().equals(trimmed)) {
                return channel;
            }
        }
        return null;
    }
}
