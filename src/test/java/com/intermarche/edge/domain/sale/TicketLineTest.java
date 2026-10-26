package com.intermarche.edge.domain.sale;

import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.ui.PriceModType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Unit tests for {@link TicketLine}, targeting 100% branch coverage.
 * <p>
 * The class is a persisted snapshot with three formatting/checksum methods and
 * no collaborators reached through static finders or persist, so no Panache
 * mocking is required: a plain {@link Product} instance with its public fields
 * set is enough to exercise the {@code isWeight} short-circuit chain and the
 * {@code product != null} ternary. Each test is fully isolated and asserts
 * absolute expected values.
 */
class TicketLineTest {

    /**
     * A freshly constructed line leaves reference fields null and primitives at
     * their defaults (no defaults are set by the class).
     */
    @Test
    void defaults() {
        TicketLine line = new TicketLine();
        Assertions.assertEquals(0, line.lineNumber);
        Assertions.assertFalse(line.deposit);
        Assertions.assertNull(line.lineUid);
        Assertions.assertNull(line.product);
        Assertions.assertNull(line.ean);
        Assertions.assertNull(line.plu);
        Assertions.assertNull(line.productLabel);
        Assertions.assertNull(line.quantity);
        Assertions.assertNull(line.unitPrice);
        Assertions.assertNull(line.vatRate);
        Assertions.assertNull(line.modifierLabel);
        Assertions.assertNull(line.modifierType);
        Assertions.assertNull(line.modifierValue);
        Assertions.assertNull(line.originalUnitPrice);
        Assertions.assertNull(line.totalPrice);
        Assertions.assertNull(line.familyCode);
        Assertions.assertNull(line.familyLabel);
        Assertions.assertFalse(line.cancelled);
        Assertions.assertNull(line.cancellationDate);
        Assertions.assertNull(line.cancelledBy);
    }

    /**
     * All public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        TicketLine line = new TicketLine();
        Product product = new Product();
        line.lineNumber = 3;
        line.lineUid = "uid-1";
        line.product = product;
        line.ean = "3560070000000";
        line.plu = "1234";
        line.productLabel = "Bananas";
        line.quantity = new BigDecimal("1.500");
        line.unitPrice = new BigDecimal("1.9900");
        line.vatRate = new BigDecimal("0.0550");
        line.modifierLabel = "REMISE -10%";
        line.modifierType = PriceModType.REMISE;
        line.modifierValue = new BigDecimal("0.1000");
        line.originalUnitPrice = new BigDecimal("2.2100");
        line.totalPrice = new BigDecimal("2.9850");
        line.deposit = true;
        line.familyCode = "FRUITS";
        line.familyLabel = "Rayon Fruits";
        line.cancelled = true;
        line.cancellationDate = java.time.LocalDateTime.of(2026, 9, 1, 15, 42);
        line.cancelledBy = "12341234";
        Assertions.assertEquals(3, line.lineNumber);
        Assertions.assertEquals("uid-1", line.lineUid);
        Assertions.assertSame(product, line.product);
        Assertions.assertEquals("3560070000000", line.ean);
        Assertions.assertEquals("1234", line.plu);
        Assertions.assertEquals("Bananas", line.productLabel);
        Assertions.assertEquals(new BigDecimal("1.500"), line.quantity);
        Assertions.assertEquals(new BigDecimal("1.9900"), line.unitPrice);
        Assertions.assertEquals(new BigDecimal("0.0550"), line.vatRate);
        Assertions.assertEquals("REMISE -10%", line.modifierLabel);
        Assertions.assertEquals(PriceModType.REMISE, line.modifierType);
        Assertions.assertEquals(new BigDecimal("0.1000"), line.modifierValue);
        Assertions.assertEquals(new BigDecimal("2.2100"), line.originalUnitPrice);
        Assertions.assertEquals(new BigDecimal("2.9850"), line.totalPrice);
        Assertions.assertTrue(line.deposit);
        Assertions.assertEquals("FRUITS", line.familyCode);
        Assertions.assertEquals("Rayon Fruits", line.familyLabel);
        Assertions.assertTrue(line.cancelled);
        Assertions.assertEquals(java.time.LocalDateTime.of(2026, 9, 1, 15, 42), line.cancellationDate);
        Assertions.assertEquals("12341234", line.cancelledBy);
    }

    /**
     * getTotalFormatted returns the zero placeholder when the total is null.
     */
    @Test
    void totalFormattedNull() {
        TicketLine line = new TicketLine();
        line.totalPrice = null;
        Assertions.assertEquals("0,00", line.getTotalFormatted());
    }

    /**
     * getTotalFormatted rounds to two decimals (HALF_UP) with a French comma.
     */
    @Test
    void totalFormattedRoundsAndUsesComma() {
        TicketLine line = new TicketLine();
        line.totalPrice = new BigDecimal("12.345");
        Assertions.assertEquals("12,35", line.getTotalFormatted());
    }

    /**
     * getFormattedQuantity returns an empty string when the quantity is null.
     */
    @Test
    void formattedQuantityNull() {
        TicketLine line = new TicketLine();
        line.quantity = null;
        Assertions.assertEquals("", line.getFormattedQuantity());
    }

    /**
     * getFormattedQuantity renders kilograms when the product carries a non-empty
     * PLU (weighed line): all three isWeight conditions are true.
     */
    @Test
    void formattedQuantityWeighed() {
        TicketLine line = new TicketLine();
        Product product = new Product();
        product.plu = "1234";
        line.product = product;
        line.quantity = new BigDecimal("1.234");
        Assertions.assertEquals("1,234 kg", line.getFormattedQuantity());
    }

    /**
     * getFormattedQuantity treats a product whose PLU is the empty string as a
     * unit line: the isEmpty guard is the false arm of isWeight.
     */
    @Test
    void formattedQuantityEmptyPluIsUnit() {
        TicketLine line = new TicketLine();
        Product product = new Product();
        product.plu = "";
        line.product = product;
        line.quantity = new BigDecimal("2");
        Assertions.assertEquals("x2", line.getFormattedQuantity());
    }

    /**
     * getFormattedQuantity treats a product with a null PLU as a unit line: the
     * plu != null guard is the false arm of isWeight.
     */
    @Test
    void formattedQuantityNullPluIsUnit() {
        TicketLine line = new TicketLine();
        Product product = new Product();
        product.plu = null;
        line.product = product;
        line.quantity = new BigDecimal("5");
        Assertions.assertEquals("x5", line.getFormattedQuantity());
    }

    /**
     * getFormattedQuantity renders an integer unit count when there is no product
     * (product != null is the false arm) and the quantity is whole.
     */
    @Test
    void formattedQuantityIntegerNoProduct() {
        TicketLine line = new TicketLine();
        line.product = null;
        line.quantity = new BigDecimal("3");
        Assertions.assertEquals("x3", line.getFormattedQuantity());
    }

    /**
     * getFormattedQuantity strips trailing zeros for a non-integer unit quantity
     * (the compareTo != 0 arm of the remainder test).
     */
    @Test
    void formattedQuantityFractionalUnit() {
        TicketLine line = new TicketLine();
        line.product = null;
        line.quantity = new BigDecimal("1.500");
        Assertions.assertEquals("x1,5", line.getFormattedQuantity());
    }

    /**
     * getChecksum hashes the line number, the product id, the quantity and the
     * total when a product is present (the true arm of the ternary).
     */
    @Test
    void checksumWithProduct() {
        TicketLine line = new TicketLine();
        Product product = new Product();
        product.id = 42L;
        line.product = product;
        line.lineNumber = 3;
        line.quantity = new BigDecimal("2.000");
        line.totalPrice = new BigDecimal("3.9800");
        int expected = Objects.hash(3, 42L, new BigDecimal("2.000"), new BigDecimal("3.9800"));
        Assertions.assertEquals(expected, line.getChecksum());
    }

    /**
     * getChecksum tolerates the absence of a product, hashing a null id (the
     * false arm of the ternary).
     */
    @Test
    void checksumWithoutProduct() {
        TicketLine line = new TicketLine();
        line.product = null;
        line.lineNumber = 7;
        line.quantity = new BigDecimal("1.000");
        line.totalPrice = new BigDecimal("9.9900");
        int expected = Objects.hash(7, null, new BigDecimal("1.000"), new BigDecimal("9.9900"));
        Assertions.assertEquals(expected, line.getChecksum());
    }

    /**
     * A change in a hashed field changes the checksum (change-detection contract).
     */
    @Test
    void checksumChangesWhenTotalChanges() {
        TicketLine line = new TicketLine();
        line.totalPrice = new BigDecimal("3.9800");
        int before = line.getChecksum();
        line.totalPrice = new BigDecimal("4.9800");
        Assertions.assertNotEquals(before, line.getChecksum());
    }

    /**
     * The return-line flag defaults false and is read/write (EXP-38): a mixed
     * ticket's return line is persisted as such.
     */
    @Test
    void returnLineFlagIsReadWriteAndDefaultsFalse() {
        TicketLine line = new TicketLine();
        Assertions.assertFalse(line.returnLine);
        line.returnLine = true;
        Assertions.assertTrue(line.returnLine);
    }

    /**
     * The embedded-weight fields (EXP-26, LC-02-05-04) default null and are
     * read/write, so a price-embedded weighed line persists its deduced weight
     * and price per kilo for the fiscal ticket.
     */
    @Test
    void embeddedWeightFieldsAreReadWriteAndDefaultNull() {
        TicketLine line = new TicketLine();
        Assertions.assertNull(line.embeddedWeight);
        Assertions.assertNull(line.embeddedUnitPrice);
        line.embeddedWeight = new BigDecimal("0.750");
        line.embeddedUnitPrice = new BigDecimal("4.0000");
        Assertions.assertEquals(new BigDecimal("0.750"), line.embeddedWeight);
        Assertions.assertEquals(new BigDecimal("4.0000"), line.embeddedUnitPrice);
    }

    /**
     * Builds a line carrying a quantity, a unit price and a line total, the
     * three fields {@code getEffectiveUnitPrice} reads (LC-05-02-12).
     *
     * @param qty the sold quantity, or null
     * @param unitPrice the catalog unit price including tax
     * @param totalPrice the line total including tax, or null
     * @return the line
     */
    private TicketLine priced(String qty, String unitPrice, String totalPrice) {
        TicketLine line = new TicketLine();
        line.quantity = qty == null ? null : new BigDecimal(qty);
        line.unitPrice = new BigDecimal(unitPrice);
        line.totalPrice = totalPrice == null ? null : new BigDecimal(totalPrice);
        return line;
    }

    /**
     * {@code getEffectiveUnitPrice} shares the line total out over the quantity
     * when the line carries no advantage: the result is the unit price itself
     * (LC-05-02-12, nominal case).
     */
    @Test
    void effectiveUnitPriceEqualsTheUnitPriceWithoutAnyAdvantage() {
        Assertions.assertEquals(new BigDecimal("10.0000"),
                priced("3", "10.00", "30.00").getEffectiveUnitPrice());
    }

    /**
     * An advantage carried by the line total — an engine promotion or the share
     * of a global ticket discount, neither of which touches the unit price —
     * lowers the refunded unit price (LC-05-02-12, the defect this closes).
     */
    @Test
    void effectiveUnitPriceCarriesTheAdvantageOfTheSale() {
        TicketLine line = priced("3", "10.00", "24.00");
        Assertions.assertEquals(new BigDecimal("8.0000"), line.getEffectiveUnitPrice());
        Assertions.assertTrue(line.getEffectiveUnitPrice().compareTo(line.unitPrice) < 0);
    }

    /**
     * A division that does not terminate is rounded to four decimals, and a
     * return of the WHOLE quantity still adds up to the line total at the
     * centime (LC-05-02-12, rounding contract).
     */
    @Test
    void effectiveUnitPriceRoundsToFourDecimalsAndStillSumsToTheLineTotal() {
        TicketLine line = priced("3", "1.00", "2.00");
        Assertions.assertEquals(new BigDecimal("0.6667"), line.getEffectiveUnitPrice());
        Assertions.assertEquals(new BigDecimal("2.00"),
                line.getEffectiveUnitPrice().multiply(line.quantity)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * With no line total there is nothing to share out, so the unit price is
     * handed back (first leg of the fallback guard).
     */
    @Test
    void effectiveUnitPriceFallsBackToTheUnitPriceWithoutALineTotal() {
        Assertions.assertEquals(new BigDecimal("10.00"),
                priced("3", "10.00", null).getEffectiveUnitPrice());
    }

    /**
     * With no quantity there is nothing to divide by (second leg of the
     * fallback guard).
     */
    @Test
    void effectiveUnitPriceFallsBackToTheUnitPriceWithoutAQuantity() {
        Assertions.assertEquals(new BigDecimal("10.00"),
                priced(null, "10.00", "30.00").getEffectiveUnitPrice());
    }

    /**
     * A zero quantity would be a division by zero (third leg of the fallback
     * guard).
     */
    @Test
    void effectiveUnitPriceFallsBackToTheUnitPriceOnAZeroQuantity() {
        Assertions.assertEquals(new BigDecimal("10.00"),
                priced("0", "10.00", "0.00").getEffectiveUnitPrice());
    }

    /**
     * A RETURN line inside a mixed ticket (EXP-38) carries a negative quantity
     * and a negative total: the quotient is the positive price paid, and the
     * zero guard does not catch it.
     */
    @Test
    void effectiveUnitPriceOfAReturnLineIsThePositivePricePaid() {
        Assertions.assertEquals(new BigDecimal("2.0000"),
                priced("-2", "2.00", "-4.00").getEffectiveUnitPrice());
    }
}
