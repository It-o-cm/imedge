package com.intermarche.pos.domain.sale;

import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.domain.catalog.Product;
import java.math.BigDecimal;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
        Assertions.assertEquals("x1.5", line.getFormattedQuantity());
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
}
