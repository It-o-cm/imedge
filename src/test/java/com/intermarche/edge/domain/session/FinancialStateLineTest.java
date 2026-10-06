package com.intermarche.edge.domain.session;

import com.intermarche.edge.domain.session.FinancialStateLine.StateSection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link FinancialStateLine}.
 *
 * <p>Plain POJO tests: the constructor, the defaults and the checksum are pure
 * in-memory logic, so no Panache and no Quarkus context are booted.
 */
class FinancialStateLineTest {

    /**
     * The convenience constructor sets the section, order, code, label and amount,
     * and leaves the section-specific columns null until a section fills them.
     */
    @Test
    void constructorSetsTheCoreFieldsAndLeavesTheRestNull() {
        FinancialStateLine line = new FinancialStateLine(
                StateSection.SETTLEMENT, 2, "CASH", "Espèces", new BigDecimal("12.3400"));
        assertEquals(StateSection.SETTLEMENT, line.section);
        assertEquals(2, line.rank);
        assertEquals("CASH", line.code);
        assertEquals("Espèces", line.label);
        assertEquals(new BigDecimal("12.3400"), line.amount);
        assertNull(line.quantity);
        assertNull(line.amountExcludingTax);
        assertNull(line.vatAmount);
        assertNull(line.share);
    }

    /**
     * The no-argument constructor leaves every field at its default, as JPA needs.
     */
    @Test
    void defaultConstructorLeavesEverythingNull() {
        FinancialStateLine line = new FinancialStateLine();
        assertNull(line.section);
        assertNull(line.code);
        assertNull(line.amount);
        assertEquals(0, line.rank);
    }

    /**
     * {@code getChecksum} is equal for two lines carrying the same identifying and
     * financial fields and differs when one of them changes.
     */
    @Test
    void getChecksumReflectsIdentifyingAndFinancialFields() {
        FinancialStateLine a = new FinancialStateLine(
                StateSection.VAT, 1, "20", "TVA 20%", new BigDecimal("2.0000"));
        FinancialStateLine b = new FinancialStateLine(
                StateSection.VAT, 1, "20", "TVA 20%", new BigDecimal("2.0000"));
        assertEquals(a.getChecksum(), b.getChecksum());
        b.amount = new BigDecimal("3.0000");
        assertNotEquals(a.getChecksum(), b.getChecksum());
    }

    /**
     * The section enumeration exposes exactly the seven sections the single table
     * carries.
     */
    @Test
    void sectionEnumerationExposesSevenSections() {
        assertEquals(7, StateSection.values().length);
        assertEquals(StateSection.BEST_SELLER, StateSection.valueOf("BEST_SELLER"));
    }
}
