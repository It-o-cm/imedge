package com.intermarche.edge.domain.setting;

/**
 * The five administrable echelons of the organisation, from the least to the
 * most specific (BO-02-05-04 route A: Pays &rarr; Enseigne &rarr; Point De
 * Vente, extended INSIDE the store by BO-03-09/03-10: Îlot &rarr; Point de
 * contact d'encaissement).
 * <p>
 * The first three levels are CENTRAL, distributed by the ECHELON_SETTINGS pull;
 * the last two live BELOW the PDV, inside a store node, and are administered
 * locally there. What they share is the resolution: a setting posed at the more
 * specific echelon wins over the less specific one, all the way down to a value
 * posed on a single terminal — which is what the fifteen partial lines mean when
 * they ask for a key to be « réglable par TPV ».
 * <p>
 * The declaration order is the INHERITANCE order read from the bottom up: a
 * value resolved for a {@link #CHECKOUT_POINT} wins over its {@link #ISLAND}
 * default, which wins over the {@link #PDV}, which wins over the
 * {@link #ENSEIGNE}, which wins over the {@link #COUNTRY} default.
 * {@link EchelonSetting} and the resolution in
 * {@link com.intermarche.edge.service.EchelonSettingService} rely on this
 * ranking; changing it changes who overrides whom.
 */
public enum EchelonLevel {

    /** The country echelon (Pays), the least specific default. */
    COUNTRY,

    /** The store-group echelon (Enseigne), between country and PDV. */
    ENSEIGNE,

    /** The point-of-sale echelon (Point De Vente). */
    PDV,

    /**
     * The checkout-island echelon (Îlot), below the PDV and inside the store —
     * a value posed here reaches every register of the island (BO-03-10).
     */
    ISLAND,

    /**
     * The checkout-point echelon (point de contact d'encaissement, keyed by
     * terminal identifier), the most specific — a value posed here binds one
     * single register (BO-03-09).
     */
    CHECKOUT_POINT
}
