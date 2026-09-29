package com.intermarche.pos.ui.report;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReportFamily}: verifies the constant set, their order,
 * their French labels and the values()/valueOf() round-trip.
 */
class ReportFamilyTest {

    /**
     * Verifies the full ordered set of enum constants.
     */
    @Test
    void valuesReturnsAllConstantsInDeclarationOrder() {
        ReportFamily[] values = ReportFamily.values();
        Assertions.assertEquals(5, values.length);
        Assertions.assertArrayEquals(
                new ReportFamily[] {
                        ReportFamily.SALES,
                        ReportFamily.CASHIERS,
                        ReportFamily.SETTLEMENTS,
                        ReportFamily.TAX,
                        ReportFamily.CUSTOMERS
                },
                values);
    }

    /**
     * Verifies the ordinal of every constant matches its declared position.
     */
    @Test
    void ordinalsMatchDeclarationOrder() {
        Assertions.assertEquals(0, ReportFamily.SALES.ordinal());
        Assertions.assertEquals(1, ReportFamily.CASHIERS.ordinal());
        Assertions.assertEquals(2, ReportFamily.SETTLEMENTS.ordinal());
        Assertions.assertEquals(3, ReportFamily.TAX.ordinal());
        Assertions.assertEquals(4, ReportFamily.CUSTOMERS.ordinal());
    }

    /**
     * Verifies the label of every constant.
     */
    @Test
    void getLabelReturnsFrenchHeading() {
        Assertions.assertEquals("Ventes", ReportFamily.SALES.getLabel());
        Assertions.assertEquals("États caissières", ReportFamily.CASHIERS.getLabel());
        Assertions.assertEquals("Règlements", ReportFamily.SETTLEMENTS.getLabel());
        Assertions.assertEquals("TVA", ReportFamily.TAX.getLabel());
        Assertions.assertEquals("Clients", ReportFamily.CUSTOMERS.getLabel());
    }

    /**
     * Verifies that valueOf() resolves each constant name back to its instance.
     */
    @Test
    void valueOfResolvesEachName() {
        for (ReportFamily family : ReportFamily.values()) {
            Assertions.assertSame(family, ReportFamily.valueOf(family.name()));
        }
    }

    /**
     * Verifies that valueOf() rejects an unknown name.
     */
    @Test
    void valueOfRejectsUnknownName() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ReportFamily.valueOf("UNKNOWN"));
    }

    /**
     * Verifies that the constant names match their declared identifiers.
     */
    @Test
    void namesMatchDeclaredIdentifiers() {
        Assertions.assertEquals("SALES", ReportFamily.SALES.name());
        Assertions.assertEquals("CASHIERS", ReportFamily.CASHIERS.name());
        Assertions.assertEquals("SETTLEMENTS", ReportFamily.SETTLEMENTS.name());
        Assertions.assertEquals("TAX", ReportFamily.TAX.name());
        Assertions.assertEquals("CUSTOMERS", ReportFamily.CUSTOMERS.name());
    }
}
