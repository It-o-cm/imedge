package com.intermarche.pos.domain.sale;

import java.math.BigDecimal;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RefundLine}, targeting 100% branch coverage.
 * <p>
 * The class carries no conditional logic: it is a snapshot of an original
 * ticket line whose only method, {@link RefundLine#getChecksum()}, hashes the
 * identifying and financial fields, so there are no ternaries or null guards to
 * split. Coverage is therefore complete once the field defaults, the read/write
 * of every public field, the checksum over populated fields, the checksum over
 * null fields (the pre-lot-4 row) and the change-detection contract are
 * asserted. No static finder or persist is reached, so no Panache mocking is
 * required. Each test is fully isolated and asserts absolute expected values.
 */
class RefundLineTest {

    /**
     * A freshly constructed line leaves every field null (no defaults set).
     */
    @Test
    void defaultsAreNull() {
        RefundLine line = new RefundLine();
        Assertions.assertNull(line.originalLineId);
        Assertions.assertNull(line.productLabel);
        Assertions.assertNull(line.quantity);
        Assertions.assertNull(line.price);
        Assertions.assertNull(line.vatRate);
    }

    /**
     * All public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        RefundLine line = new RefundLine();
        line.originalLineId = 7L;
        line.productLabel = "Milk 1L";
        line.quantity = new BigDecimal("2.000");
        line.price = new BigDecimal("1.2500");
        line.vatRate = new BigDecimal("0.0550");
        Assertions.assertEquals(7L, line.originalLineId);
        Assertions.assertEquals("Milk 1L", line.productLabel);
        Assertions.assertEquals(new BigDecimal("2.000"), line.quantity);
        Assertions.assertEquals(new BigDecimal("1.2500"), line.price);
        Assertions.assertEquals(new BigDecimal("0.0550"), line.vatRate);
    }

    /**
     * getChecksum hashes the original line id, quantity, price and VAT rate.
     */
    @Test
    void checksumHashesSalientFields() {
        RefundLine line = new RefundLine();
        line.originalLineId = 7L;
        line.quantity = new BigDecimal("2.000");
        line.price = new BigDecimal("1.2500");
        line.vatRate = new BigDecimal("0.0550");
        int expected = Objects.hash(7L, new BigDecimal("2.000"),
                new BigDecimal("1.2500"), new BigDecimal("0.0550"));
        Assertions.assertEquals(expected, line.getChecksum());
    }

    /**
     * getChecksum tolerates the null-valued fields (a pre-lot-4 row with no VAT rate).
     */
    @Test
    void checksumToleratesNulls() {
        RefundLine line = new RefundLine();
        int expected = Objects.hash(null, null, null, null);
        Assertions.assertEquals(expected, line.getChecksum());
    }

    /**
     * The product label is not part of the checksum: changing it leaves the hash intact.
     */
    @Test
    void checksumIgnoresProductLabel() {
        RefundLine line = new RefundLine();
        line.originalLineId = 7L;
        int before = line.getChecksum();
        line.productLabel = "Renamed";
        Assertions.assertEquals(before, line.getChecksum());
    }

    /**
     * A change in a hashed field changes the checksum (change detection contract).
     */
    @Test
    void checksumChangesWhenFieldChanges() {
        RefundLine line = new RefundLine();
        line.price = new BigDecimal("1.2500");
        int before = line.getChecksum();
        line.price = new BigDecimal("9.9900");
        Assertions.assertNotEquals(before, line.getChecksum());
    }
}
