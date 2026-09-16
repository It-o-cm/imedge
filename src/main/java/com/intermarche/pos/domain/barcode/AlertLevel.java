package com.intermarche.pos.domain.barcode;

import java.util.Locale;

/**
 * What the register does when an administered control finds something wrong —
 * an expired date, a code meant for another shop, a ticket below the minimum,
 * a code already used ({@code LC-11-03-13}, {@code BO-03-06-45/46/47/48/49/67}).
 *
 * <p>THE SHOP DECIDES, NOT THE REGISTER. The same expired best-before is a
 * reason to stop the sale in one store and a reason to warn the cashier in
 * another — the rule depends on the article's nature, on what the shop does
 * with its short-dated stock and on who is standing at the till. So the level
 * is administered, and the register only applies it.
 *
 * <p>The default is the one that cannot break a lane: {@link #NONE}. A register
 * that suddenly started refusing codes because a supplier began encoding an
 * expiry date would be a checkout outage, and a shop that wants the refusal
 * will ask for it.
 *
 * <p>This enum is SHARED. It started life inside the GS1 parsing as
 * {@code Gs1AlertLevel} — the single warn-or-block mechanism of the whole
 * register — and every other control that had to warn or block was written
 * without one. It now serves both the GS1 dates and the administered barcode
 * ranges.
 */
public enum AlertLevel {

    /** Say nothing: the finding is decoded, journalled, and acted on by nobody. */
    NONE,

    /** Tell the cashier and carry on. */
    INFO,

    /** Refuse: nothing is registered. */
    BLOCK;

    /**
     * Reads an administered level, tolerating case and padding.
     *
     * @param administered the level as the back office holds it, possibly null
     * @return the level, {@link #NONE} when nothing readable was administered
     */
    public static AlertLevel of(String administered) {
        if (administered == null || administered.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(administered.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // An unknown level is a mistyped parameter. Falling back to silence keeps
            // the lane running, where falling back to a refusal would stop it.
            return NONE;
        }
    }

    /**
     * Tells whether this level says anything at all to the cashier.
     *
     * @return true for an informative or a blocking level
     */
    public boolean speaks() {
        return this != NONE;
    }

    /**
     * Tells whether this level refuses what it was asked about.
     *
     * @return true for the blocking level only
     */
    public boolean blocks() {
        return this == BLOCK;
    }

    /**
     * Returns the sterner of two levels, so a set of findings can be reduced
     * to the single decision the register acts on.
     *
     * @param other the level to compare with, or null
     * @return the level that speaks loudest
     */
    public AlertLevel max(AlertLevel other) {
        if (other == null) {
            return this;
        }
        return other.ordinal() > ordinal() ? other : this;
    }
}
