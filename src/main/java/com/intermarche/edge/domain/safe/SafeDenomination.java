package com.intermarche.edge.domain.safe;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A denomination of cash the SAFE counts in ({@code BO-05-02-04},
 * {@code BO-05-02-11}).
 *
 * <p>Wider than the drawer's list on purpose: a register never keeps a note
 * above fifty euros, but a coin order comes back from the bank in whatever the
 * bank delivers, and an inventory that could not name a two-hundred euro note
 * would count the safe wrong by the amount of the note it cannot write down.
 *
 * <p>An enum and not a table: a denomination is the currency of the euro area,
 * not a decision a shop makes. The {@link #name()} is the token stored in a
 * counted line, so renaming a constant would rewrite history — the constants
 * are therefore named after the value they carry, which cannot change.
 */
public enum SafeDenomination {

    /** The five-hundred euro note. */
    N500("Billet 500 €", "500.00", true),

    /** The two-hundred euro note. */
    N200("Billet 200 €", "200.00", true),

    /** The one-hundred euro note. */
    N100("Billet 100 €", "100.00", true),

    /** The fifty euro note. */
    N50("Billet 50 €", "50.00", true),

    /** The twenty euro note. */
    N20("Billet 20 €", "20.00", true),

    /** The ten euro note. */
    N10("Billet 10 €", "10.00", true),

    /** The five euro note. */
    N5("Billet 5 €", "5.00", true),

    /** The two euro coin. */
    C2("Pièce 2 €", "2.00", false),

    /** The one euro coin. */
    C1("Pièce 1 €", "1.00", false),

    /** The fifty cent coin. */
    C050("Pièce 0,50 €", "0.50", false),

    /** The twenty cent coin. */
    C020("Pièce 0,20 €", "0.20", false),

    /** The ten cent coin. */
    C010("Pièce 0,10 €", "0.10", false),

    /** The five cent coin. */
    C005("Pièce 0,05 €", "0.05", false),

    /** The two cent coin. */
    C002("Pièce 0,02 €", "0.02", false),

    /** The one cent coin. */
    C001("Pièce 0,01 €", "0.01", false);

    /** The operator-facing label (French). */
    private final String label;

    /** The unit value in euros. */
    private final BigDecimal value;

    /** Whether this denomination is a banknote rather than a coin. */
    private final boolean note;

    /**
     * Builds a denomination.
     *
     * @param label the operator-facing label
     * @param value the unit value in euros, as a plain decimal string
     * @param note true when the denomination is a banknote
     */
    SafeDenomination(String label, String value, boolean note) {
        this.label = label;
        this.value = new BigDecimal(value);
        this.note = note;
    }

    /**
     * Returns the operator-facing label of this denomination.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the unit value of this denomination.
     *
     * @return the value in euros, never null
     */
    public BigDecimal getValue() {
        return value;
    }

    /**
     * Tells whether this denomination is a banknote.
     *
     * @return true for a note, false for a coin
     */
    public boolean isNote() {
        return note;
    }

    /**
     * The amount a given count of this denomination is worth.
     *
     * @param quantity how many of them, null or negative counting as none
     * @return the amount, never null, never negative
     */
    public BigDecimal amountOf(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * The banknote denominations, largest first.
     *
     * @return the notes, in declaration order
     */
    public static List<SafeDenomination> notes() {
        List<SafeDenomination> notes = new ArrayList<>();
        for (SafeDenomination denomination : values()) {
            if (denomination.note) {
                notes.add(denomination);
            }
        }
        return notes;
    }

    /**
     * The coin denominations, largest first.
     *
     * @return the coins, in declaration order
     */
    public static List<SafeDenomination> coins() {
        List<SafeDenomination> coins = new ArrayList<>();
        for (SafeDenomination denomination : values()) {
            if (!denomination.note) {
                coins.add(denomination);
            }
        }
        return coins;
    }

    /**
     * Resolves a denomination from its stored name.
     *
     * @param name the stored name, possibly null or unknown
     * @return the matching denomination, or null when the name names none
     */
    public static SafeDenomination fromName(String name) {
        if (name == null) {
            return null;
        }
        for (SafeDenomination denomination : values()) {
            if (denomination.name().equals(name.trim())) {
                return denomination;
            }
        }
        return null;
    }
}
