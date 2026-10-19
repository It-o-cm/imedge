package com.intermarche.pos.domain.store;

/**
 * The PROTOCOL a scale attached to a checkout point speaks (BO-03-09-15, and
 * the CD-06 the register already drove).
 *
 * <p>The protocol only. The address and the port that go with it are
 * administered beside it on the checkout point, and the DRIVER that speaks it
 * lives in the hardware module — exactly as {@link DisplayType} names a display
 * without implementing one. Naming it here is what lets a store be equipped
 * without a release: the register reads which protocol it was given and asks
 * the bridge for it.
 */
public enum ScaleProtocol {

    /** No scale attached, which is the default of a register that weighs nothing. */
    NONE("Aucune"),

    /** The CD-06 protocol, the one the register already drove. */
    CD06("CD-06"),

    /** The DIGI protocol (BO-03-09-15). */
    DIGI("DIGI");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a scale protocol with its operator-facing label.
     *
     * @param label the French label
     */
    ScaleProtocol(String label) {
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
    public static ScaleProtocol fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (ScaleProtocol protocol : values()) {
            if (protocol.name().equals(trimmed)) {
                return protocol;
            }
        }
        return null;
    }
}
