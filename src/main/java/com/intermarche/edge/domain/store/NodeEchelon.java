package com.intermarche.edge.domain.store;

/**
 * Where a node, or a point of sale it speaks for, sits in the organisation:
 * its point of sale, the enseigne of that point of sale and the country of
 * that enseigne (BO-01-01-03).
 *
 * <p>A value: each part is null when it is not known, a node never
 * commissioned into the organisation tree knowing none of them.
 */
public final class NodeEchelon {

    /** The point-of-sale number, or null. */
    public final String pdv;

    /** The enseigne code, or null. */
    public final String enseigne;

    /** The country code, or null. */
    public final String country;

    /**
     * Builds a place in the organisation.
     *
     * @param pdv the point-of-sale number, or null
     * @param enseigne the enseigne code, or null
     * @param country the country code, or null
     */
    public NodeEchelon(String pdv, String enseigne, String country) {
        this.pdv = pdv;
        this.enseigne = enseigne;
        this.country = country;
    }

    /**
     * Resolves the enseigne and the country of a point of sale from the
     * organisation tree this node holds.
     *
     * @param pdvNumber the point-of-sale number, possibly null or blank
     * @return its place, every part null when the number is null or blank,
     *         the upper parts null when the tree does not know them
     */
    public static NodeEchelon of(String pdvNumber) {
        if (pdvNumber == null || pdvNumber.isBlank()) {
            return new NodeEchelon(null, null, null);
        }
        String number = pdvNumber.trim();
        Pdv pdv = Pdv.findByNumber(number);
        String enseigneCode = pdv == null ? null : pdv.enseigneCode;
        Enseigne enseigne = enseigneCode == null ? null : Enseigne.findByCode(enseigneCode);
        return new NodeEchelon(number, enseigneCode, enseigne == null ? null : enseigne.countryCode);
    }

    /**
     * Renders this place for a log line.
     *
     * @return the three parts
     */
    @Override
    public String toString() {
        return "PDV " + pdv + " / enseigne " + enseigne + " / pays " + country;
    }
}
