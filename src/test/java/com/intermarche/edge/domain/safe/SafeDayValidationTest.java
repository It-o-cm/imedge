package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link SafeDayValidation} (BO-05-02-09, BO-05-02-10).
 */
class SafeDayValidationTest {

    /**
     * The validation of a day is found by its day; no day finds none.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theValidationOfADayIsFoundByItsDay() {
        SafeDayValidation validation = new SafeDayValidation();
        assertEquals(BigDecimal.ZERO, validation.netRevenue);
        PanacheQuery<SafeDayValidation> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(validation);
        LocalDate day = LocalDate.of(2026, 9, 15);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeDayValidation.find("accountingDay", day)).thenReturn(query);
            assertSame(validation, SafeDayValidation.forDay(day));
        }
        assertNull(SafeDayValidation.forDay(null));
    }
}
