package com.intermarche.pos.domain.store;

/**
 * The PROTOCOL a coin machine attached to a checkout point speaks
 * (BO-03-09-24 Gunnebo, -25 Glory, -26 Toshiba).
 *
 * <p>The three lines of the questionnaire ask for the same three things —
 * protocol, address, port — and differ only by the name of the protocol. So
 * they are one administered choice and not three screens: a store that changes
 * supplier changes a value, and the machine itself is driven by the hardware
 * module, which is where a protocol is spoken.
 */
public enum CoinMachineProtocol {

    /** No coin machine attached, the default of a register with a drawer. */
    NONE("Aucun"),

    /** The Gunnebo protocol (BO-03-09-24). */
    GUNNEBO("Gunnebo"),

    /** The Glory protocol (BO-03-09-25). */
    GLORY("Glory"),

    /** The Toshiba protocol (BO-03-09-26). */
    TOSHIBA("Toshiba");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a coin-machine protocol with its operator-facing label.
     *
     * @param label the French label
     */
    CoinMachineProtocol(String label) {
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
     * Resolves a protocol name, tolerating an unknown or blank value.
     *
     * @param raw the posted name, or null
     * @return the protocol, or null when unrecognised
     */
    public static CoinMachineProtocol fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (CoinMachineProtocol protocol : values()) {
            if (protocol.name().equals(trimmed)) {
                return protocol;
            }
        }
        return null;
    }
}
