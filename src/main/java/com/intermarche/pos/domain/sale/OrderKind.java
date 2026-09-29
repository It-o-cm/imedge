package com.intermarche.pos.domain.sale;

/**
 * The KIND of a customer order recalled at the till by its code (onglet G): the
 * four documents the store hands a customer as a code, all built on the one
 * recall-by-code mechanism (EXP-90/91/92).
 *
 * <p>They differ only in whether a partial payment (a deposit) is allowed when
 * the code is recalled: a quote or an order may be settled by a deposit and the
 * rest later; a sale voucher and a click-and-collect basket are paid in full.
 */
public enum OrderKind {

    /** A quote (devis): entered at the back office, deposit allowed (EXP-90). */
    QUOTE("Devis", true),

    /** A firm order (commande): deposit allowed, the rest on collection (EXP-90). */
    ORDER("Commande", true),

    /** A sale voucher (bon de vente): paid in full at the till (EXP-91). */
    SALE_VOUCHER("Bon de vente", false),

    /** A click-and-collect basket from a mobility tool, paid in full (EXP-92). */
    CLICK_COLLECT("Click and collect", false);

    /** The operator-facing label (French). */
    private final String label;

    /** Whether a partial payment (deposit) is allowed on recall. */
    private final boolean depositAllowed;

    /**
     * Builds a kind with its label and deposit rule.
     *
     * @param label the French label
     * @param depositAllowed whether a deposit may settle part of it
     */
    OrderKind(String label, boolean depositAllowed) {
        this.label = label;
        this.depositAllowed = depositAllowed;
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
     * Whether a partial payment (deposit) may settle part of this kind on recall
     * (EXP-85): true for a quote or an order, false for a voucher or a
     * click-and-collect basket.
     *
     * @return true when a deposit is allowed
     */
    public boolean isDepositAllowed() {
        return depositAllowed;
    }

    /**
     * Resolves a kind name, tolerating an unknown or blank value.
     *
     * @param raw the posted name, or null
     * @return the kind, or null when unrecognised
     */
    public static OrderKind fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (OrderKind kind : values()) {
            if (kind.name().equals(trimmed)) {
                return kind;
            }
        }
        return null;
    }
}
