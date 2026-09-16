package com.intermarche.pos.domain.people;

/**
 * The unit of use a profile may be granted on a {@link Feature}
 * (BO-01-04-02): the four options are visualisation, creation, modification
 * and deletion.
 * <p>
 * A grant is always the pair (feature, option): a profile that holds
 * {@code VIEW} on a feature may open its screen; {@code CREATE},
 * {@code UPDATE} and {@code DELETE} unlock the corresponding write gestures,
 * each addressable on its own. This is the axis that replaces the four fixed
 * roles with an administered, per-functionality authorization.
 */
public enum CrudOption {

    /** May open and read the feature. */
    VIEW("Visualisation"),

    /** May create through the feature. */
    CREATE("Création"),

    /** May modify through the feature. */
    UPDATE("Modification"),

    /** May delete through the feature. */
    DELETE("Suppression");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds an option with its operator-facing label.
     *
     * @param label the French label shown in the profile grid
     */
    CrudOption(String label) {
        this.label = label;
    }

    /**
     * Returns the operator-facing label of this option.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }
}
