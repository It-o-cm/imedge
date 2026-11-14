package com.intermarche.edge.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link MeasureUnit} (BO-02-03-17): the finders, the standard
 * list and the checksum.
 */
class MeasureUnitTest {

    /**
     * findByCode answers null for a null or blank code and looks the trimmed,
     * upper-cased code up otherwise.
     */
    @Test
    void findByCodeNormalizesTheCode() {
        assertNull(MeasureUnit.findByCode(null));
        assertNull(MeasureUnit.findByCode(" "));
        MeasureUnit unit = new MeasureUnit();
        @SuppressWarnings("unchecked")
        PanacheQuery<MeasureUnit> query = mock(PanacheQuery.class,
                invocation -> "firstResult".equals(invocation.getMethod().getName()) ? unit : null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> MeasureUnit.find("code", "B")).thenReturn(query);
            assertSame(unit, MeasureUnit.findByCode(" b "));
        }
    }

    /**
     * The listings read every unit, or the active ones, by code.
     */
    @Test
    void listingsAreOrderedByCode() {
        MeasureUnit unit = new MeasureUnit();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> MeasureUnit.list("order by code")).thenReturn(List.of(unit));
            panache.when(() -> MeasureUnit.list("active = true order by code")).thenReturn(List.of(unit, unit));
            assertEquals(1, MeasureUnit.listAllOrdered().size());
            assertEquals(2, MeasureUnit.listActive().size());
        }
    }

    /**
     * The standard list carries the ten units of the questionnaire, and the
     * checksum hashes every business field.
     */
    @Test
    void standardListAndChecksum() {
        assertEquals(10, MeasureUnit.STANDARD.size());
        assertEquals("kg", MeasureUnit.STANDARD.get(1)[2]);
        MeasureUnit unit = new MeasureUnit();
        unit.code = "2";
        unit.label = "Kilogramme";
        unit.symbol = "kg";
        unit.decimalAllowed = true;
        assertEquals(Objects.hash("2", "Kilogramme", "kg", true, true), unit.getChecksum());
        assertEquals(99, MeasureUnit.MAX_UNITS);
    }
}
