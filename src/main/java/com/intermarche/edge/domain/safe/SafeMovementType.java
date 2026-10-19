package com.intermarche.edge.domain.safe;

/**
 * The kind of movement that debits or credits the SAFE ({@code BO-05-02}).
 *
 * <p>The DIRECTION belongs to the kind and not to the row: an expense always
 * takes money out and a receipt always brings money in, so a row carrying its
 * own sign would let a mis-keyed minus turn one into the other. An amount is
 * therefore always stored positive, and what it does to the balance is read
 * from here.
 *
 * <p>The safe's balance is the sum of these movements and nothing else — there
 * is no stored balance to drift from them. That is why a movement is never
 * deleted once validated: it is reversed by another movement, which is what an
 * accounting period can be closed on.
 */
public enum SafeMovementType {

    /** Cash taken out of the safe to buy, refund or advance ({@code BO-05-02-01}). */
    EXPENSE("Dépense", false, true, true),

    /** A settlement received outside the registers, entered by hand ({@code BO-05-02-02}). */
    RECEIPT("Recette", true, true, true),

    /** A settlement received outside the registers, integrated automatically ({@code BO-05-02-03}). */
    AUTOMATIC_RECEIPT("Recette intégrée", true, true, false),

    /** Coin delivered by the bank against an order ({@code BO-05-02-05}). */
    COIN_DELIVERY("Réception de monnaie", true, false, false),

    /** A register's takings brought to the safe in a pouch ({@code BO-05-02-10}). */
    POUCH("Remise au coffre", true, false, false),

    /** Cash sent to the bank ({@code BO-05-02-07}). */
    BANK_DEPOSIT("Remise en banque", false, false, false),

    /** The correction an inventory calls for ({@code BO-05-02-11}). */
    INVENTORY_ADJUSTMENT("Écart d'inventaire", true, false, false),

    /** The balance the safe opens an accounting period with ({@code BO-05-02-12}). */
    OPENING_BALANCE("Solde d'ouverture", true, false, false);

    /** The operator-facing label (French). */
    private final String label;

    /** Whether the movement brings money INTO the safe, as {@code C} or {@code D}. */
    private final String credit;

    /** Whether the movement may carry a VAT breakdown. */
    private final boolean vatBearing;

    /** Whether the movement is one an operator enters by hand. */
    private final boolean manual;

    /**
     * Builds a movement kind.
     *
     * @param label the operator-facing label
     * @param credit true when the movement brings money into the safe
     * @param vatBearing true when the movement may carry a VAT breakdown
     * @param manual true when the movement screen offers it for entry
     */
    SafeMovementType(String label, boolean credit, boolean vatBearing, boolean manual) {
        this.label = label;
        this.credit = credit ? "C" : "D";
        this.vatBearing = vatBearing;
        this.manual = manual;
    }

    /**
     * Returns the operator-facing label of this kind.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Tells whether this kind brings money into the safe.
     *
     * @return true for a credit, false for a debit
     */
    public boolean isCredit() {
        return "C".equals(credit);
    }

    /**
     * Tells whether this kind takes money out of the safe.
     *
     * @return true for a debit, false for a credit
     */
    public boolean isDebit() {
        return !isCredit();
    }

    /**
     * Tells whether this kind may carry a VAT breakdown ({@code BO-05-02-01},
     * {@code BO-05-02-02}).
     *
     * <p>Only the movements that state a PURCHASE or a SALE do: a pouch coming
     * up from a register carries takings whose VAT the tickets already state,
     * and declaring it a second time here would count it twice.
     *
     * @return true when a VAT breakdown is accepted
     */
    public boolean isVatBearing() {
        return vatBearing;
    }

    /**
     * Tells whether this kind is entered by an operator rather than produced by
     * another gesture of the safe.
     *
     * @return true when the movement screen offers it
     */
    public boolean isManual() {
        return manual;
    }

    /**
     * Resolves a kind from its stored name.
     *
     * @param name the stored or posted name, possibly null or unknown
     * @return the matching kind, or null when the name names none
     */
    public static SafeMovementType fromName(String name) {
        if (name == null) {
            return null;
        }
        for (SafeMovementType type : values()) {
            if (type.name().equals(name.trim())) {
                return type;
            }
        }
        return null;
    }
}
