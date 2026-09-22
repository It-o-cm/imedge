package com.intermarche.pos.domain.catalog.attribute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declared catalog of product attributes (BO-02-03-18).
 * <p>
 * Two things coexist under "un article a des attributs":
 * <ul>
 *   <li>The <b>mechanism</b>: a product carries an open map of code&nbsp;→&nbsp;text
 *       value ({@link com.intermarche.pos.domain.catalog.Product#attributes}). The map
 *       is unbounded, so the "minimum 99 attributs possibles" the questionnaire
 *       asks for is a property of the storage, not of this list — an attribute
 *       code integrated from the Gestion Commerciale that this catalog does not
 *       name is still stored, distributed and readable.</li>
 *   <li>The <b>well-known attributes</b>: the subset below that the register
 *       actually acts upon (VAT exemption, discount ban, recall, …). Only these
 *       have a declared type, label and default; {@link ProductAttributes} turns
 *       them into sale behaviour.</li>
 * </ul>
 * This mirrors the typed-catalog doctrine of {@code PosSettingsService.CATALOG}:
 * adding a behavioural attribute is one entry here plus its consumer.
 * <p>
 * Non-instantiable — a pure static registry.
 */
public final class ProductAttributeCatalog {

    /** Whether discounts and rebates are forbidden on the article (BO-02-03-09). */
    public static final String DISCOUNT_FORBIDDEN = "DISCOUNT_FORBIDDEN";

    /** Whether the article is VAT-exempt: its line is ventilated at rate 0 (BO-02-03-26/27). */
    public static final String VAT_EXEMPT = "VAT_EXEMPT";

    /** Whether the article is under a product recall and must be refused at the register (BO-02-03-11). */
    public static final String RECALL = "RECALL";

    /** The lot numbers of the article that are under recall (LC-02-03-12/13). */
    public static final String RECALL_LOTS = "RECALL_LOTS";

    /** Whether the article is eligible for meal-voucher tender (BO-02-03-06). */
    public static final String MEAL_VOUCHER_ELIGIBLE = "MEAL_VOUCHER_ELIGIBLE";

    /** Whether the article is eligible for eco-voucher tender (LC-09-01-13/14). */
    public static final String ECO_VOUCHER_ELIGIBLE = "ECO_VOUCHER_ELIGIBLE";

    /** Whether the article is eligible for the social purchase card (LC-09-01-15/16). */
    public static final String SOCIAL_CARD_ELIGIBLE = "SOCIAL_CARD_ELIGIBLE";

    /** Whether the article is bulky (BO-02-03-25). */
    public static final String BULKY = "BULKY";

    /** Whether the price must be entered at the register rather than called from the referential (BO-02-03-21). */
    public static final String PRICE_TO_ENTER = "PRICE_TO_ENTER";

    /** Whether a quantity must be entered at the register for this article (BO-02-03-22). */
    public static final String QUANTITY_TO_ENTER = "QUANTITY_TO_ENTER";

    /** Whether the article is covered by the legal conformity guarantee, which forces the sale ticket to print (LC-08-03-09). */
    public static final String LEGAL_WARRANTY = "LEGAL_WARRANTY";

    /** The unit eco-tax borne by the article, carried into the invoice extractions (BO-10-04-14). */
    public static final String ECO_TAX = "ECO_TAX";

    /**
     * The declared well-known attributes, in admin-render order. Each entry is
     * the contract of a code the register understands.
     */
    public static final List<ProductAttributeDef> CATALOG = List.of(
            new ProductAttributeDef(DISCOUNT_FORBIDDEN, ProductAttributeType.BOOL,
                    "Remise / rabais interdit(e)", "false"),
            new ProductAttributeDef(VAT_EXEMPT, ProductAttributeType.BOOL,
                    "TVA exonérée", "false"),
            new ProductAttributeDef(RECALL, ProductAttributeType.BOOL,
                    "Article en retrait / rappel", "false"),
            new ProductAttributeDef(RECALL_LOTS, ProductAttributeType.TEXT,
                    "Numéros de lot en rappel", ""),
            new ProductAttributeDef(MEAL_VOUCHER_ELIGIBLE, ProductAttributeType.BOOL,
                    "Éligible titre-restaurant", "false"),
            new ProductAttributeDef(ECO_VOUCHER_ELIGIBLE, ProductAttributeType.BOOL,
                    "Éligible éco-chèque", "false"),
            new ProductAttributeDef(SOCIAL_CARD_ELIGIBLE, ProductAttributeType.BOOL,
                    "Éligible carte achat sociale", "false"),
            new ProductAttributeDef(BULKY, ProductAttributeType.BOOL,
                    "Article encombrant", "false"),
            new ProductAttributeDef(PRICE_TO_ENTER, ProductAttributeType.BOOL,
                    "Prix à saisir en caisse", "false"),
            new ProductAttributeDef(QUANTITY_TO_ENTER, ProductAttributeType.BOOL,
                    "Quantité à saisir en caisse", "false"),
            new ProductAttributeDef(LEGAL_WARRANTY, ProductAttributeType.BOOL,
                    "Soumis à la garantie légale de conformité", "false"),
            new ProductAttributeDef(ECO_TAX, ProductAttributeType.DECIMAL,
                    "Éco-taxe unitaire", "0"));

    /** Fast lookup of a definition by its code, preserving catalog order. */
    private static final Map<String, ProductAttributeDef> BY_CODE = index();

    /**
     * Non-instantiable static registry.
     */
    private ProductAttributeCatalog() {
    }

    /**
     * Builds the code-indexed view of the catalog once at class load.
     *
     * @return an ordered map from code to definition
     */
    private static Map<String, ProductAttributeDef> index() {
        Map<String, ProductAttributeDef> map = new LinkedHashMap<>();
        for (ProductAttributeDef def : CATALOG) {
            map.put(def.code(), def);
        }
        return map;
    }

    /**
     * Returns the definition of a well-known attribute.
     *
     * @param code the attribute code, or null
     * @return the definition, or null when the code is null or not well-known
     */
    public static ProductAttributeDef def(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * Tells whether a code names a well-known attribute the register acts upon.
     *
     * @param code the attribute code, or null
     * @return true when the code is declared in the catalog
     */
    public static boolean isKnown(String code) {
        return def(code) != null;
    }

    /**
     * The declared default value of a well-known attribute, or null when the
     * code is not well-known.
     *
     * @param code the attribute code, or null
     * @return the declared default, or null for an unknown code
     */
    public static String defaultValue(String code) {
        ProductAttributeDef def = def(code);
        return def != null ? def.defaultValue() : null;
    }
}
