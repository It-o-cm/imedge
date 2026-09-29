package com.intermarche.pos.domain.store;

/**
 * The KIND of a checkout point (point de contact d'encaissement), BO-03-09-07 —
 * the ATTRIBUTE alone, never a protocol.
 *
 * <p>This lot poses the type so a register can be administered as a manned till
 * or one of the self-service families; it does NOT implement any of the
 * self-service protocols (TGCS, DN, NCR are named here as the attribute the back
 * office records, not as drivers). Whether a point needs a technical hostess
 * (BO-03-09-08) follows from {@link #isSelfService()}.
 */
public enum TpvType {

    /** A manned checkout, operated by a cashier (BO-03-09-07). */
    CASH_REGISTER("Caisse"),

    /** A self-service checkout of the TGCS family (BO-03-09-07). */
    SELF_CHECKOUT_TGCS("Libre-service TGCS"),

    /** A self-service checkout of the DN family (BO-03-09-07). */
    SELF_CHECKOUT_DN("Libre-service DN"),

    /** A self-service checkout of the NCR family (BO-03-09-07). */
    SELF_CHECKOUT_NCR("Libre-service NCR");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a type with its operator-facing label.
     *
     * @param label the French label
     */
    TpvType(String label) {
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
     * Whether this is a self-service point, for which a technical hostess is
     * relevant (BO-03-09-08).
     *
     * @return true for every self-service family, false for a manned till
     */
    public boolean isSelfService() {
        return this != CASH_REGISTER;
    }

    /**
     * Resolves a type name, tolerating an unknown or blank value.
     *
     * @param raw the posted name, or null
     * @return the type, or null when unrecognised
     */
    public static TpvType fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (TpvType type : values()) {
            if (type.name().equals(trimmed)) {
                return type;
            }
        }
        return null;
    }
}
