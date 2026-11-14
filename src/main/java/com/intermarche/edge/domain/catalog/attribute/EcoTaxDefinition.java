package com.intermarche.edge.domain.catalog.attribute;

import com.intermarche.edge.domain.catalog.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One ECO-TAX the article attributes carry (BO-10-06-05, BO-10-06-06).
 *
 * <p>An eco-tax is an article attribute whose value is a unit amount: the
 * built-in {@link ProductAttributeCatalog#ECO_TAX} and any dynamic attribute
 * the back office declares as one, as many as the country needs (DEEE,
 * furniture, batteries, tyres, packaging…). Each states whether it prints on
 * the sale receipt; all of them are totalled on the invoices, the delivery
 * notes and their extractions, which is the Belgian rule the requirement
 * quotes — on the invoice, never on the ticket.
 *
 * @param code    the article attribute code holding the unit amount
 * @param label   the printed wording, or null for the register's own
 * @param printed whether this eco-tax prints on the sale receipt
 */
public record EcoTaxDefinition(String code, String label, boolean printed) {

    /** The suffix that keeps an eco-tax off the receipt. */
    private static final String NO_PRINT = ">NOPRINT";

    /**
     * Parses the administered list ({@code ecotax.attributes}): entries
     * separated by semicolons, each {@code CODE[=Label][>NOPRINT]}.
     *
     * <p>The built-in {@code ECO_TAX} attribute is always an eco-tax: listed,
     * it takes the administered label and print choice; not listed, it comes
     * first, printed, under the register's own wording. A blank entry or a
     * code listed twice is ignored, the first occurrence standing.
     *
     * @param raw the administered list, possibly null or blank
     * @return the definitions, never empty
     */
    public static List<EcoTaxDefinition> parse(String raw) {
        List<EcoTaxDefinition> declared = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        String[] entries = raw == null ? new String[0] : raw.split(";");
        for (String entry : entries) {
            String text = entry.trim();
            boolean printed = true;
            if (text.toUpperCase(java.util.Locale.ROOT).endsWith(NO_PRINT)) {
                printed = false;
                text = text.substring(0, text.length() - NO_PRINT.length()).trim();
            }
            String label = null;
            int equals = text.indexOf('=');
            if (equals >= 0) {
                String named = text.substring(equals + 1).trim();
                label = named.isEmpty() ? null : named;
                text = text.substring(0, equals).trim();
            }
            if (text.isEmpty() || codes.contains(text)) {
                continue;
            }
            codes.add(text);
            declared.add(new EcoTaxDefinition(text, label, printed));
        }
        if (!codes.contains(ProductAttributeCatalog.ECO_TAX)) {
            declared.add(0, new EcoTaxDefinition(ProductAttributeCatalog.ECO_TAX, null, true));
        }
        return declared;
    }

    /**
     * The definitions to read when none were handed over — a collaborator
     * built without its parameters — which is the built-in eco-tax alone.
     *
     * @param definitions the administered definitions, possibly null or empty
     * @return the definitions, never empty
     */
    public static List<EcoTaxDefinition> orBuiltIn(List<EcoTaxDefinition> definitions) {
        return definitions == null || definitions.isEmpty() ? parse(null) : definitions;
    }

    /**
     * The unit amount this eco-tax bears on an article, zero when the article
     * carries none or an unreadable value.
     *
     * @param product the article, or null
     * @return the unit amount, never null
     */
    public BigDecimal unitAmount(Product product) {
        return ProductAttributes.decimalAttribute(product, code);
    }

    /**
     * The total unit amount of every eco-tax of a list an article bears.
     *
     * @param product the article, or null
     * @param definitions the eco-taxes
     * @return the summed unit amount, never null
     */
    public static BigDecimal totalUnitAmount(Product product, List<EcoTaxDefinition> definitions) {
        BigDecimal total = BigDecimal.ZERO;
        for (EcoTaxDefinition definition : definitions) {
            total = total.add(definition.unitAmount(product));
        }
        return total;
    }
}
