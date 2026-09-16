package com.intermarche.pos.domain.setting;

/**
 * The three administrable echelons of the organisation, from the least to the
 * most specific (BO-02-05-04 route A: Pays &rarr; Enseigne &rarr; Point De
 * Vente). The îlot and the point of contact live BELOW the PDV, inside a
 * store node, and are not settings-bearing echelons here — the settings
 * inheritance the AO asks for stops at the PDV.
 * <p>
 * The declaration order is the INHERITANCE order read from the bottom up: a
 * value resolved for a PDV wins over its {@link #ENSEIGNE} default, which wins
 * over the {@link #COUNTRY} default. {@link EchelonSetting} and the resolution
 * in {@link com.intermarche.pos.service.EchelonSettingService} rely on this
 * ranking; changing it changes who overrides whom.
 */
public enum EchelonLevel {

    /** The country echelon (Pays), the least specific default. */
    COUNTRY,

    /** The store-group echelon (Enseigne), between country and PDV. */
    ENSEIGNE,

    /** The point-of-sale echelon (Point De Vente), the most specific. */
    PDV
}
