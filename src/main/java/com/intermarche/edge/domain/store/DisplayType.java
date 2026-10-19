package com.intermarche.edge.domain.store;

/**
 * The KIND of an operator or customer display attached to a checkout point
 * (BO-03-09-12 afficheur caissière, BO-03-09-13 afficheur client) — the type
 * only; its pixel resolution is administered separately as free text on the
 * checkout point, and no display DRIVER is implemented here.
 */
public enum DisplayType {

    /** No display attached (BO-03-09-12/13). */
    NONE("Aucun"),

    /** A text-only LCD line display (BO-03-09-12/13). */
    LCD_TEXT("Afficheur LCD texte"),

    /** A graphic display (BO-03-09-12/13). */
    GRAPHIC("Afficheur graphique"),

    /** A touch screen used as a display (BO-03-09-12/13). */
    TOUCH("Écran tactile");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a display type with its operator-facing label.
     *
     * @param label the French label
     */
    DisplayType(String label) {
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
     * Resolves a display-type name, tolerating an unknown or blank value.
     *
     * @param raw the posted name, or null
     * @return the type, or null when unrecognised
     */
    public static DisplayType fromName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (DisplayType type : values()) {
            if (type.name().equals(trimmed)) {
                return type;
            }
        }
        return null;
    }
}
