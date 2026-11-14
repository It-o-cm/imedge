package com.intermarche.edge.domain.catalog;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How the register WRITES a quantity (BO-10-08-28, BO-10-08-29): the decimals
 * of a quantity sold by a unit of measure, the decimals of a weight and the
 * symbol a weight carries, on the screens and on the receipts alike.
 *
 * <p>A value and not a service: the screens, the customer display and the
 * printer each read the administered parameters once and hand this to the
 * formatting, so a quantity is written the same way on the three surfaces.
 * {@link #DEFAULT} writes exactly what the register wrote before the
 * parameters existed — two decimals, three for a weight, {@code kg}.
 *
 * @param measureDecimals the decimals of a non-whole unit-of-measure quantity
 * @param weightDecimals the decimals of a weight
 * @param weightSymbol the symbol written beside a weight
 */
public record UnitFormat(int measureDecimals, int weightDecimals, String weightSymbol)
        implements Serializable {

    /** The register's historical writing. */
    public static final UnitFormat DEFAULT = new UnitFormat(2, 3, "kg");

    /**
     * The writing to use when none was handed over — a collaborator built
     * without its parameters — which is the register's historical one.
     *
     * @param format the administered writing, possibly null
     * @return the writing, never null
     */
    public static UnitFormat orDefault(UnitFormat format) {
        return format == null ? DEFAULT : format;
    }

    /**
     * Whether the QUANTITY of a line is a weight in kilograms: a line of an
     * article sold by its PLU that was weighed — at the scale, on a weighed-EAN
     * label, keyed, or from a GS1 net weight. A price-embedded line counts one
     * sticker, and a line whose weight is stated beside its quantity (a deduced
     * weight, a counter line) counts one article: their quantity is not a weight.
     *
     * @param plu the PLU code the line carries, or null
     * @param priceEmbedded whether the line was rung at a read total
     * @param statedWeight the weight stated beside the quantity, or null
     * @return true when the quantity is the weight
     */
    public static boolean weighedQuantity(String plu, boolean priceEmbedded, BigDecimal statedWeight) {
        return plu != null && !plu.isEmpty() && !priceEmbedded && statedWeight == null;
    }

    /**
     * Writes a weight with its decimals, French comma, without its symbol.
     *
     * @param kilograms the weight
     * @return the written number
     */
    public String weightNumber(BigDecimal kilograms) {
        return kilograms.setScale(weightDecimals, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Writes a weight with its decimals and its symbol: {@code 0,188 kg}.
     *
     * @param kilograms the weight
     * @return the written weight
     */
    public String weight(BigDecimal kilograms) {
        return weightNumber(kilograms) + " " + weightSymbol;
    }

    /**
     * Writes a quantity sold by a unit of measure: a whole quantity without
     * decimals, any other with the administered decimals.
     *
     * @param quantity the quantity
     * @return the written quantity, French comma
     */
    public String measure(BigDecimal quantity) {
        if (quantity.stripTrailingZeros().scale() <= 0) {
            return quantity.stripTrailingZeros().toPlainString();
        }
        return quantity.setScale(measureDecimals, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Writes a unit price, two decimals and French comma.
     *
     * @param price the price
     * @return the written price
     */
    private static String price(BigDecimal price) {
        return price.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Builds the ONE decomposition line written under a label (P3-5,
     * LC-02-05-04) with this writing of quantities: a price-embedded line's
     * deduced weight, a weighed line, a unit-of-measure line, several pieces.
     *
     * @param quantity the line quantity (a weight for a weighed line)
     * @param unitPrice the unit price (per kilo for a weighed line)
     * @param unitName the referential unit of measure, or null
     * @param weighed whether the line is weighed
     * @param embeddedWeight the deduced weight of a price-embedded line, or null
     * @param embeddedUnitPrice the referential price per kilo, or null
     * @param euro the currency glyph to write
     * @return the decomposition line, or an empty string when none applies
     */
    public String detailLine(BigDecimal quantity, BigDecimal unitPrice, String unitName,
            boolean weighed, BigDecimal embeddedWeight, BigDecimal embeddedUnitPrice, String euro) {
        if (embeddedWeight != null && embeddedUnitPrice != null) {
            return weight(embeddedWeight) + " x " + price(embeddedUnitPrice) + " " + euro + "/" + weightSymbol;
        }
        if (weighed) {
            return weight(quantity) + " x " + price(unitPrice) + " " + euro + "/" + weightSymbol;
        }
        if (unitName != null && !unitName.isBlank()) {
            return measure(quantity) + " " + unitName + " x " + price(unitPrice) + " " + euro + "/" + unitName;
        }
        if (quantity.compareTo(BigDecimal.ONE) > 0) {
            return measure(quantity) + " x " + price(unitPrice) + " " + euro;
        }
        return "";
    }
}
