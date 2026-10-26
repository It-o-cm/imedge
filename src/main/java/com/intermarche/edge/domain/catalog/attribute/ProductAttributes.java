package com.intermarche.edge.domain.catalog.attribute;

import com.intermarche.edge.domain.catalog.Product;

/**
 * The typed reader of a product's declared attributes (BO-02-03-18) — the
 * single façade the sale surface consults so no scan handler parses the raw
 * attribute map itself.
 * <p>
 * Reading rules, applied uniformly:
 * <ul>
 *   <li>a null product or a product with no attribute map yields the catalog
 *       default (or false / null when the code is not well-known);</li>
 *   <li>a value explicitly carried by the product wins over the default;</li>
 *   <li>a well-known code with no product value falls back to its declared
 *       default in {@link ProductAttributeCatalog}.</li>
 * </ul>
 * The named accessors ({@link #vatExempt}, {@link #discountForbidden}, …) are
 * the vocabulary the register speaks; {@link #raw} is the generic escape hatch
 * for attributes integrated from the Gestion Commerciale that the register does
 * not act upon. Pure and static: no CDI, no database — everything reads the
 * in-memory {@link Product#attributes} map, which the scan chain has already
 * loaded with the product.
 * <p>
 * Non-instantiable.
 */
public final class ProductAttributes {

    /**
     * Non-instantiable utility.
     */
    private ProductAttributes() {
    }

    /**
     * Returns the raw text value of an attribute: the product's own value when
     * present, otherwise the catalog default of a well-known code, otherwise
     * null.
     *
     * @param product the product, or null
     * @param code the attribute code
     * @return the resolved text value, or null when neither the product nor the
     *         catalog supplies one
     */
    public static String raw(Product product, String code) {
        if (product != null && product.attributes != null) {
            String value = product.attributes.get(code);
            if (value != null) {
                return value;
            }
        }
        return ProductAttributeCatalog.defaultValue(code);
    }

    /**
     * Reads an attribute as a boolean flag. A missing value resolves through
     * {@link #raw} (catalog default or null); a null resolved value is false.
     *
     * @param product the product, or null
     * @param code the attribute code
     * @return true when the resolved value is {@code "true"} (case-insensitive)
     */
    public static boolean flag(Product product, String code) {
        String value = raw(product, code);
        return Boolean.parseBoolean(value);
    }

    /**
     * Whether discounts and rebates are forbidden on the article (BO-02-03-09).
     *
     * @param product the product, or null
     * @return true when the article bans price reductions
     */
    public static boolean discountForbidden(Product product) {
        return flag(product, ProductAttributeCatalog.DISCOUNT_FORBIDDEN);
    }

    /**
     * Whether the article is VAT-exempt — its ticket line is ventilated at rate
     * 0 (BO-02-03-26/27).
     *
     * @param product the product, or null
     * @return true when the article is VAT-exempt
     */
    public static boolean vatExempt(Product product) {
        return flag(product, ProductAttributeCatalog.VAT_EXEMPT);
    }

    /**
     * Whether the article is under a product recall and must be refused at the
     * register (BO-02-03-11).
     *
     * @param product the product, or null
     * @return true when the article is recalled
     */
    public static boolean recall(Product product) {
        return flag(product, ProductAttributeCatalog.RECALL);
    }

    /**
     * The lot numbers of the article that are under recall ({@code LC-02-03-12/13}).
     *
     * <p>A RECALL IS RARELY THE WHOLE ARTICLE. A batch of yoghurts is withdrawn, not
     * the reference; refusing the reference would stop the sale of the pots that are
     * fine, and refusing nothing would sell the pots that are not. The referential
     * therefore carries the recalled LOTS beside the blanket {@link #recall} flag, and
     * what the register does depends on whether the barcode in the cashier's hand says
     * which lot this item belongs to.
     *
     * @param product the product, or null
     * @return the recalled lot numbers, in referential order, empty when the article
     *         carries no lot recall
     */
    public static java.util.List<String> recalledLots(Product product) {
        String value = raw(product, ProductAttributeCatalog.RECALL_LOTS);
        java.util.List<String> lots = new java.util.ArrayList<>();
        for (String entry : value.split("[;,]")) {
            String lot = entry.trim();
            if (!lot.isEmpty() && !lots.contains(lot)) {
                lots.add(lot);
            }
        }
        return lots;
    }

    /**
     * Whether one named lot of the article is under recall ({@code LC-02-03-12}).
     *
     * <p>The comparison ignores case and surrounding blanks, because a lot number read
     * from a GS1 element string and one typed into the referential are the same lot
     * written by two different hands.
     *
     * @param product the product, or null
     * @param lot the lot number the barcode carried, or null when it carried none
     * @return true when that exact lot is recalled
     */
    public static boolean lotRecalled(Product product, String lot) {
        if (lot == null || lot.isBlank()) {
            return false;
        }
        String wanted = lot.trim();
        for (String recalled : recalledLots(product)) {
            if (recalled.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The recalled lot numbers of the article, joined for display, when the
     * article carries a lot recall but the code that was scanned or keyed
     * does not say which lot is in the customer's basket
     * ({@code LC-02-03-13}).
     *
     * <p>The lot numbers are referential values and never change language;
     * the wording around them is localised by the caller that shows them.
     *
     * @param product the product, or null
     * @return the joined lot numbers, or null when the article carries no lot recall
     */
    public static String recalledLotsJoined(Product product) {
        java.util.List<String> lots = recalledLots(product);
        if (lots.isEmpty()) {
            return null;
        }
        return String.join(", ", lots);
    }

    /**
     * The message a register shows when an article carries a lot recall but the code
     * that was scanned or keyed does not say which lot is in the customer's basket
     * ({@code LC-02-03-13}).
     *
     * <p>It LISTS THE LOTS rather than refusing the sale: the register cannot know
     * whether this particular item is one of them, and only the cashier, holding the
     * pack, can read the lot printed on it. The lots are those of
     * {@link #recalledLotsJoined}; this French wording is the untranslated one,
     * a localised caller composes its own around the joined lots.
     *
     * @param product the product, or null
     * @return the message, or null when the article carries no lot recall
     */
    public static String recalledLotsMessage(Product product) {
        String lots = recalledLotsJoined(product);
        if (lots == null) {
            return null;
        }
        return "PRODUIT EN RAPPEL - LOTS : " + lots;
    }

    /**
     * The message a register shows when it refuses the article for a forbidden
     * sale or a recall (BO-10-07-20): the article's own, else the shop's
     * default, else null for the register's sentence.
     *
     * <p>The article's message wins because it is the more precise one — the
     * recall of THIS reference, with its reason — and the shop's default is
     * what a withdrawal posted in a hurry, with no message typed, still shows.
     *
     * @param product the product, or null
     * @param shopDefault the administered default message, possibly null or blank
     * @return the message to show, trimmed, or null when neither carries one
     */
    public static String forbiddenSaleMessage(Product product, String shopDefault) {
        // Never null: the code is declared in the catalog, whose default is empty.
        String own = raw(product, ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE);
        if (!own.isBlank()) {
            return own.trim();
        }
        return shopDefault == null || shopDefault.isBlank() ? null : shopDefault.trim();
    }

    /**
     * Whether the article code accompanies the label on the register screen,
     * the customer display and the ticket (BO-10-02-34/35).
     *
     * <p>The article's own flag decides when it carries one; an article that
     * has never been given one takes the shop's parameter, which is how the
     * parameter "initialises" the flag of every article without a fiche
     * decision of its own.
     *
     * @param product the product, or null
     * @param shopDefault the shop's parameter (display.show-ean)
     * @return true when the code is shown
     */
    public static boolean codeShown(Product product, boolean shopDefault) {
        String value = product == null || product.attributes == null
                ? null : product.attributes.get(ProductAttributeCatalog.CODE_ON_SCREEN);
        return value == null || value.isBlank() ? shopDefault : Boolean.parseBoolean(value.trim());
    }

    /**
     * Whether the article is eligible for meal-voucher tender (BO-02-03-06).
     *
     * @param product the product, or null
     * @return true when the article is meal-voucher eligible
     */
    public static boolean mealVoucherEligible(Product product) {
        return flag(product, ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE);
    }




    /**
     * The checkout mode the Gestion Commerciale declared for the article
     * (BO-02-03-05, -21 to -24), or null when it declares none the register
     * understands.
     *
     * <p>ONE column carries the mode, and the flags beside it keep working: an
     * article that states no mode is rung by its flags and, failing them, at its
     * referential price, exactly as every article was before the mode existed.
     * The comparison ignores case and blanks, because the mode is integrated as
     * text and {@code weighed} and {@code WEIGHED } are the same decision.
     *
     * @param product the product, or null
     * @return one of the {@code ProductAttributeCatalog.MODE_*} values, or null
     */
    public static String checkoutMode(Product product) {
        String value = raw(product, ProductAttributeCatalog.CHECKOUT_MODE).trim()
                .toUpperCase(java.util.Locale.ROOT);
        return ProductAttributeCatalog.CHECKOUT_MODES.contains(value) ? value : null;
    }

    /**
     * Whether the article is in the "appel prix" checkout mode (BO-02-03-05): it
     * is rung at the price of its fiche and at no other — no price keyed, no
     * price forced, and no sale at all when the referential carries no price.
     *
     * @param product the product, or null
     * @return true when the article's mode is the price call
     */
    public static boolean priceCallOnly(Product product) {
        return ProductAttributeCatalog.MODE_PRICE_CALL.equals(checkoutMode(product));
    }

    /**
     * Whether the price must be entered at the register (BO-02-03-21): the flag,
     * or the "prix à saisir" checkout mode.
     *
     * @param product the product, or null
     * @return true when the price is to be entered
     */
    public static boolean priceToEnter(Product product) {
        return flag(product, ProductAttributeCatalog.PRICE_TO_ENTER)
                || ProductAttributeCatalog.MODE_PRICE_TO_ENTER.equals(checkoutMode(product));
    }

    /**
     * Whether a WHOLE quantity must be entered at the register (BO-02-03-22):
     * the flag, or the "quantité à saisir" checkout mode.
     *
     * @param product the product, or null
     * @return true when a whole quantity is to be entered
     */
    public static boolean quantityToEnter(Product product) {
        return flag(product, ProductAttributeCatalog.QUANTITY_TO_ENTER)
                || ProductAttributeCatalog.MODE_QUANTITY_TO_ENTER.equals(checkoutMode(product));
    }

    /**
     * Whether a DECIMAL quantity must be entered at the register (BO-02-03-23):
     * the flag, or the "quantité décimale à saisir" checkout mode.
     *
     * @param product the product, or null
     * @return true when a decimal quantity is to be entered
     */
    public static boolean decimalQuantityToEnter(Product product) {
        return flag(product, ProductAttributeCatalog.DECIMAL_QUANTITY_TO_ENTER)
                || ProductAttributeCatalog.MODE_DECIMAL_QUANTITY_TO_ENTER.equals(checkoutMode(product));
    }

    /**
     * Whether the article must be weighed when it is scanned or keyed
     * (BO-02-03-24): the flag, or the "article pesé" checkout mode.
     *
     * @param product the product, or null
     * @return true when the register must weigh the article
     */
    public static boolean weighed(Product product) {
        return flag(product, ProductAttributeCatalog.WEIGHED)
                || ProductAttributeCatalog.MODE_WEIGHED.equals(checkoutMode(product));
    }

    /**
     * Whether the article is bulky (BO-02-03-25): a self-service lane does not
     * check it on the security scale of the bagging area.
     *
     * @param product the product, or null
     * @return true when the article is bulky
     */
    public static boolean bulky(Product product) {
        return flag(product, ProductAttributeCatalog.BULKY);
    }

    /**
     * Whether the article is sold on behalf of a third party (BO-02-03-41 to
     * -43): its VAT is the third party's, and the store's VAT report and
     * turnover leave it out.
     *
     * @param product the product, or null
     * @return true when the article is a third-party sale
     */
    public static boolean thirdPartySale(Product product) {
        return flag(product, ProductAttributeCatalog.THIRD_PARTY_SALE);
    }

    /**
     * Whether the article is a service rather than goods (BO-05-01-03): its
     * collected amounts are the payment data of the e-reporting.
     *
     * @param product the article, possibly null
     * @return true when the article carries the service flag
     */
    public static boolean service(Product product) {
        return flag(product, ProductAttributeCatalog.SERVICE);
    }

    /**
     * How many companion articles are added per unit of the principal
     * (BO-02-03-15): one by default, the pack size for a pack of bottles each
     * carrying its own deposit. A value that is absent, unreadable, zero or
     * negative reads as one, so a malformed cell never removes the deposit.
     *
     * @param product the product, or null
     * @return the multiplier, strictly positive
     */
    public static java.math.BigDecimal linkedArticleQuantity(Product product) {
        java.math.BigDecimal value = decimalAttribute(product, ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY);
        return value.signum() > 0 ? value.stripTrailingZeros() : java.math.BigDecimal.ONE;
    }

    /**
     * The EANs of the complementary articles suggested when this one is sold
     * (BO-02-03-16), in referential order, without blanks or repeats.
     *
     * @param product the product, or null
     * @return the EANs, empty when the article suggests nothing
     */
    public static java.util.List<String> complementaryEans(Product product) {
        java.util.List<String> eans = new java.util.ArrayList<>();
        for (String entry : raw(product, ProductAttributeCatalog.COMPLEMENTARY_EANS).split("[;,]")) {
            String ean = entry.trim();
            if (!ean.isEmpty() && !eans.contains(ean)) {
                eans.add(ean);
            }
        }
        return eans;
    }

    /**
     * The number of the VAT regime the article itself names (BO-02-03-27), or
     * null when it names none or names it unreadably — the price's regime then
     * answers, as it always did.
     *
     * @param product the product, or null
     * @return the regime number, or null
     */
    public static Integer vatNumber(Product product) {
        String value = raw(product, ProductAttributeCatalog.VAT_NUMBER).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Merges {@code code=value} pairs separated by semicolons into an attribute
     * map (BO-02-03-18) — the grammar of the feed's ATTRIBUTES cell, offered to
     * the API surface so a GraphQL mutation and a CSV line say the same thing
     * the same way. A blank text merges nothing; a pair without {@code =} or
     * with a blank code is skipped; a pair with an empty value REMOVES the code,
     * which is how an API caller withdraws an attribute.
     *
     * @param target the attribute map, mutated in place
     * @param pairs the pairs, or null
     * @return the number of codes set or removed
     */
    public static int mergePairs(java.util.Map<String, String> target, String pairs) {
        if (pairs == null || pairs.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String pair : pairs.split(";")) {
            int eq = pair.indexOf('=');
            String code = eq < 0 ? "" : pair.substring(0, eq).trim();
            if (code.isEmpty()) {
                continue;
            }
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty()) {
                target.remove(code);
            } else {
                target.put(code, value);
            }
            count++;
        }
        return count;
    }

    /**
     * Whether the article is a DEMATERIALISED product whose activation code
     * must be scanned right after its EAN (BO-03-06-44).
     *
     * @param product the product, or null
     * @return true when a second scan is required on this article
     */
    public static boolean activationRequired(Product product) {
        return flag(product, ProductAttributeCatalog.ACTIVATION_REQUIRED);
    }

    /**
     * The article's label in a given language (BO-03-01-20), or null when the
     * referential carries none for it.
     *
     * <p>The pairs are administered as {@code lang=libellé}, separated by
     * semicolons — {@code nl=Melk;pt=Leite} — on the SAME vehicle every other
     * per-article decision travels on: the attribute map, which is already
     * distributed with the product. A language tag nobody administered, a pair
     * with no language, a pair with no wording and an empty wording all answer
     * null, so the caller falls back to the catalog's own label rather than
     * showing a blank touch.
     *
     * @param product the product, or null
     * @param language the language tag (fr, nl, pt), or null
     * @return the translated label, or null when none is administered
     */
    public static String translatedLabel(Product product, String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String raw = raw(product, ProductAttributeCatalog.TRANSLATED_LABELS);
        if (raw.isBlank()) {
            return null;
        }
        String wanted = language.trim().toLowerCase();
        for (String entry : raw.split(";")) {
            int equals = entry.indexOf('=');
            if (equals <= 0 || equals == entry.length() - 1) {
                continue;
            }
            String tag = entry.substring(0, equals).trim().toLowerCase();
            String label = entry.substring(equals + 1).trim();
            if (tag.equals(wanted) && !label.isEmpty()) {
                return label;
            }
        }
        return null;
    }

    /**
     * The article's label in the given language, falling back to the catalog's
     * own (BO-03-01-20).
     *
     * <p>ONE accessor for every caller and every entry path, so a screen or a
     * till set to Dutch shows the Dutch wording wherever the article is named,
     * whether it arrived by EAN, by PLU, by scale label or by GS1 payload. The
     * fallback is {@link Product#saleLabel()}, which is what every caller and
     * every path showed before translations existed.
     *
     * @param product the product, or null
     * @param language the language tag the screen or till is set to, or null
     * @return the label to show, or null when there is no product
     */
    public static String saleLabel(Product product, String language) {
        if (product == null) {
            return null;
        }
        String translated = translatedLabel(product, language);
        return translated == null ? product.saleLabel() : translated;
    }

    /**
     * Whether the article is sold on the register described by its terminal and
     * its island (BO-03-01-18).
     *
     * <p>A store may take an article out of ONE island or ONE register without
     * taking it out of the shop: the fruit stand's scale labels have no
     * business on the self-service lane, and a bulky article no checkout can
     * bag is not withdrawn from the aisle. The two exclusion lists are
     * independent and cumulative, and both empty — the default — sell
     * everywhere.
     *
     * @param product the product, or null
     * @param terminalId the register's identifier, or null when unknown
     * @param islandCode the code of the island it stands on, or null
     * @return true when the article may be rung there
     */
    public static boolean soldOn(Product product, String terminalId, String islandCode) {
        return !excluded(raw(product, ProductAttributeCatalog.DISABLED_TERMINALS), terminalId)
                && !excluded(raw(product, ProductAttributeCatalog.DISABLED_ISLANDS), islandCode);
    }

    /**
     * Tells whether a semicolon-separated exclusion list names a value.
     *
     * <p>An empty list excludes NOTHING, which is the whole safety of the rule:
     * an article nobody scoped is sold everywhere, as every article was before
     * the scope existed. A list that names something and a value nobody can
     * name (an unknown terminal, a register on no island) do not match, so a
     * register the back office cannot describe keeps selling.
     *
     * @param list the administered exclusion list, possibly null or blank
     * @param value the register's or island's code, possibly null
     * @return true when the list names the value
     */
    private static boolean excluded(String list, String value) {
        if (list.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        for (String entry : list.split(";")) {
            if (entry.trim().equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The provider that owes the activation of a dematerialised article
     * (BO-03-06-44), or null when the referential names none.
     *
     * <p>Kept beside the flag and not derived from it: a store can declare the
     * second scan without yet knowing which provider will settle it, and the
     * register must capture the code either way rather than refuse the sale.
     *
     * @param product the product, or null
     * @return the provider name, or null when none is declared
     */
    public static String activationProvider(Product product) {
        String value = raw(product, ProductAttributeCatalog.ACTIVATION_PROVIDER);
        return value.isBlank() ? null : value.trim();
    }

    /**
     * Whether the article is covered by the legal conformity guarantee (GLC):
     * a ticket carrying one is printed whatever the cashier chose, when the
     * back office forces it (LC-08-03-09).
     *
     * @param product the product, or null
     * @return true when the article is under the legal conformity guarantee
     */
    public static boolean legalWarranty(Product product) {
        return flag(product, ProductAttributeCatalog.LEGAL_WARRANTY);
    }

    /**
     * The unit eco-tax borne by the article (BO-10-04-14), zero when it carries
     * none.
     *
     * <p>An unreadable value is zero and not an error: the eco-tax is integrated
     * from the commercial management as text, and one malformed article must not
     * stop an invoice from being drawn.
     *
     * @param product the product, or null
     * @return the unit eco-tax, never null, never negative-by-accident
     */
    public static java.math.BigDecimal ecoTax(Product product) {
        return decimalAttribute(product, ProductAttributeCatalog.ECO_TAX);
    }

    /**
     * Reads an attribute as a decimal amount — the value of an eco-tax,
     * built-in or declared (BO-10-06-05) — zero when it is absent or
     * unreadable, a comma being accepted as the decimal separator.
     *
     * @param product the product, or null
     * @param code the attribute code
     * @return the amount, never null
     */
    public static java.math.BigDecimal decimalAttribute(Product product, String code) {
        String value = raw(product, code);
        if (value == null || value.isBlank()) {
            return java.math.BigDecimal.ZERO;
        }
        try {
            return new java.math.BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    /**
     * The software tare in kilograms declared for the article (LC-14-01-07),
     * zero when it declares none. Sent to the scale in place of the default
     * tare when strictly positive.
     *
     * <p>An unreadable value is zero and not an error, exactly like {@link
     * #ecoTax}: the tare is integrated from the commercial management as text,
     * and one malformed article must not stop a weighing.
     *
     * @param product the product, or null
     * @return the article tare in kilograms, never null, never negative-by-accident
     */
    public static java.math.BigDecimal tare(Product product) {
        String value = raw(product, ProductAttributeCatalog.TARE);
        if (value.isBlank()) {
            return java.math.BigDecimal.ZERO;
        }
        try {
            java.math.BigDecimal parsed = new java.math.BigDecimal(value.trim().replace(',', '.'));
            return parsed.signum() < 0 ? java.math.BigDecimal.ZERO : parsed;
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }
}
