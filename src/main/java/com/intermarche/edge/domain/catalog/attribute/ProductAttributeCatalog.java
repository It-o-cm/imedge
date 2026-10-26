package com.intermarche.edge.domain.catalog.attribute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declared catalog of product attributes (BO-02-03-18).
 * <p>
 * Two things coexist under "un article a des attributs":
 * <ul>
 *   <li>The <b>mechanism</b>: a product carries an open map of code&nbsp;→&nbsp;text
 *       value ({@link com.intermarche.edge.domain.catalog.Product#attributes}). The map
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

    /** Whether the article message refuses registration after acknowledgement (LC-02-03-11). */
    public static final String INFO_MESSAGE_BLOCKING = "INFO_MESSAGE_BLOCKING";

    /** EAN of the automatically registered companion article (LC-02-03-04). */
    public static final String LINKED_ARTICLE_EAN = "LINKED_ARTICLE_EAN";

    /** Whether discounts and rebates are forbidden on the article (BO-02-03-09). */
    public static final String DISCOUNT_FORBIDDEN = "DISCOUNT_FORBIDDEN";

    /** Whether the article is VAT-exempt: its line is ventilated at rate 0 (BO-02-03-26/27). */
    public static final String VAT_EXEMPT = "VAT_EXEMPT";

    /** Whether the article is under a product recall and must be refused at the register (BO-02-03-11). */
    public static final String RECALL = "RECALL";

    /** The lot numbers of the article that are under recall (LC-02-03-12/13). */
    public static final String RECALL_LOTS = "RECALL_LOTS";

    /** The message the register shows when it refuses the article for a forbidden sale or a recall (BO-10-07-20). */
    public static final String FORBIDDEN_SALE_MESSAGE = "FORBIDDEN_SALE_MESSAGE";

    /** Whether the article code accompanies the label on the screens and the ticket (BO-10-02-34/35). */
    public static final String CODE_ON_SCREEN = "CODE_ARTICLE_ON_SCREEN";

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

    /** The software tare in kilograms sent to the scale when this article is weighed, replacing the default tare (LC-14-01-07). */
    public static final String TARE = "TARE";

    /** Whether the article is a dematerialised product whose activation code must be scanned after its EAN (BO-03-06-44). */
    public static final String ACTIVATION_REQUIRED = "ACTIVATION_REQUIRED";

    /** The provider that owes the activation of the dematerialised article (BO-03-06-44). */
    public static final String ACTIVATION_PROVIDER = "ACTIVATION_PROVIDER";

    /** The islands the article is NOT sold on, semicolon-separated (BO-03-01-18). */
    public static final String DISABLED_ISLANDS = "DISABLED_ISLANDS";

    /** The registers the article is NOT sold on, semicolon-separated (BO-03-01-18). */
    public static final String DISABLED_TERMINALS = "DISABLED_TERMINALS";

    /** The article's label in other languages, as {@code lang=libellé} pairs (BO-03-01-20). */
    public static final String TRANSLATED_LABELS = "TRANSLATED_LABELS";

    /**
     * The checkout mode (mode de passage en caisse) integrated from the Gestion
     * Commerciale (BO-02-03-05, -21 to -24): one of the {@code MODE_*} values.
     */
    public static final String CHECKOUT_MODE = "CHECKOUT_MODE";

    /** Checkout mode: the price is called from the referential and nothing else (BO-02-03-05). */
    public static final String MODE_PRICE_CALL = "PRICE_CALL";

    /** Checkout mode: the price is keyed by the cashier (BO-02-03-21). */
    public static final String MODE_PRICE_TO_ENTER = "PRICE_TO_ENTER";

    /** Checkout mode: a whole positive quantity is keyed by the cashier (BO-02-03-22). */
    public static final String MODE_QUANTITY_TO_ENTER = "QUANTITY_TO_ENTER";

    /** Checkout mode: a decimal quantity is keyed by the cashier (BO-02-03-23). */
    public static final String MODE_DECIMAL_QUANTITY_TO_ENTER = "DECIMAL_QUANTITY_TO_ENTER";

    /** Checkout mode: the article must be weighed (BO-02-03-24). */
    public static final String MODE_WEIGHED = "WEIGHED";

    /** The checkout modes the register understands, in display order. */
    public static final List<String> CHECKOUT_MODES = List.of(MODE_PRICE_CALL, MODE_PRICE_TO_ENTER,
            MODE_QUANTITY_TO_ENTER, MODE_DECIMAL_QUANTITY_TO_ENTER, MODE_WEIGHED);

    /** Whether a decimal quantity must be entered at the register for this article (BO-02-03-23). */
    public static final String DECIMAL_QUANTITY_TO_ENTER = "DECIMAL_QUANTITY_TO_ENTER";

    /** Whether the article must be weighed when it is scanned or keyed (BO-02-03-24). */
    public static final String WEIGHED = "WEIGHED";

    /** How many companion articles are added per unit of the principal (BO-02-03-15). */
    public static final String LINKED_ARTICLE_QUANTITY = "LINKED_ARTICLE_QUANTITY";

    /** The EANs of the complementary articles suggested when this one is sold, semicolon-separated (BO-02-03-16). */
    public static final String COMPLEMENTARY_EANS = "COMPLEMENTARY_EANS";

    /** The code of the article's unit of measure, as declared in the back office (BO-02-03-17). */
    public static final String UNIT_CODE = "UNIT_CODE";

    /** The number of the article's VAT regime, integrated from the Gestion Commerciale (BO-02-03-27). */
    public static final String VAT_NUMBER = "VAT_NUMBER";

    /** Whether the article is sold on behalf of a third party, the "TVA vente prestation" (BO-02-03-41 to -43). */
    public static final String THIRD_PARTY_SALE = "THIRD_PARTY_SALE";

    /**
     * Whether the article is a SERVICE (prestation de services) rather than
     * goods: its collected amounts are the payment data of the e-reporting
     * (BO-05-01-03).
     */
    public static final String SERVICE = "SERVICE";

    /** The code of the article's supplier (BO-02-03-30). */
    public static final String SUPPLIER_CODE = "SUPPLIER_CODE";

    /** The name of the article's supplier (BO-02-03-30). */
    public static final String SUPPLIER_NAME = "SUPPLIER_NAME";

    /** The supplier's own code of the article (BO-02-03-30). */
    public static final String SUPPLIER_ARTICLE_CODE = "SUPPLIER_ARTICLE_CODE";

    /** The supplier's own name of the article (BO-02-03-30). */
    public static final String SUPPLIER_ARTICLE_NAME = "SUPPLIER_ARTICLE_NAME";

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
                    "Éco-taxe unitaire", "0"),
            new ProductAttributeDef(TARE, ProductAttributeType.DECIMAL,
                    "Tare logicielle article (kg)", "0"),
            new ProductAttributeDef(ACTIVATION_REQUIRED, ProductAttributeType.BOOL,
                    "Produit dématérialisé : code d'activation à scanner", "false"),
            new ProductAttributeDef(ACTIVATION_PROVIDER, ProductAttributeType.TEXT,
                    "Fournisseur du produit dématérialisé", ""),
            new ProductAttributeDef(DISABLED_ISLANDS, ProductAttributeType.TEXT,
                    "Îlots où l'article n'est pas vendu", ""),
            new ProductAttributeDef(DISABLED_TERMINALS, ProductAttributeType.TEXT,
                    "Caisses où l'article n'est pas vendu", ""),
            new ProductAttributeDef(TRANSLATED_LABELS, ProductAttributeType.TEXT,
                    "Libellés traduits (langue=libellé)", ""),
            new ProductAttributeDef(INFO_MESSAGE_BLOCKING, ProductAttributeType.BOOL,
                    "Message article bloquant (article non enregistré)", "false"),
            new ProductAttributeDef(LINKED_ARTICLE_EAN, ProductAttributeType.TEXT,
                    "Article lié automatiquement (EAN)", ""),
            new ProductAttributeDef(FORBIDDEN_SALE_MESSAGE, ProductAttributeType.TEXT,
                    "Message de vente interdite / rappel", ""),
            new ProductAttributeDef(CHECKOUT_MODE, ProductAttributeType.TEXT,
                    "Mode de passage en caisse", ""),
            new ProductAttributeDef(DECIMAL_QUANTITY_TO_ENTER, ProductAttributeType.BOOL,
                    "Quantité décimale à saisir en caisse", "false"),
            new ProductAttributeDef(WEIGHED, ProductAttributeType.BOOL,
                    "Article à peser obligatoirement", "false"),
            new ProductAttributeDef(LINKED_ARTICLE_QUANTITY, ProductAttributeType.DECIMAL,
                    "Quantité d'article lié par unité", "1"),
            new ProductAttributeDef(COMPLEMENTARY_EANS, ProductAttributeType.TEXT,
                    "Articles complémentaires (EAN)", ""),
            new ProductAttributeDef(UNIT_CODE, ProductAttributeType.TEXT,
                    "Code de l'unité de mesure", ""),
            new ProductAttributeDef(VAT_NUMBER, ProductAttributeType.TEXT,
                    "Code TVA de l'article", ""),
            new ProductAttributeDef(THIRD_PARTY_SALE, ProductAttributeType.BOOL,
                    "TVA vente prestation (vente pour compte de tiers)", "false"),
            new ProductAttributeDef(SUPPLIER_CODE, ProductAttributeType.TEXT,
                    "Code fournisseur", ""),
            new ProductAttributeDef(SUPPLIER_NAME, ProductAttributeType.TEXT,
                    "Nom fournisseur", ""),
            new ProductAttributeDef(SUPPLIER_ARTICLE_CODE, ProductAttributeType.TEXT,
                    "Code article fournisseur", ""),
            new ProductAttributeDef(SUPPLIER_ARTICLE_NAME, ProductAttributeType.TEXT,
                    "Nom article fournisseur", ""),
            new ProductAttributeDef(SERVICE, ProductAttributeType.BOOL,
                    "Prestation de services (e-reporting des paiements)", "false"));

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
