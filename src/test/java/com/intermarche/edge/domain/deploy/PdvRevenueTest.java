package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PdvRevenue} (BO-02-05-05): its finders resolve to
 * {@link PanacheEntityBase} and are intercepted by a static mock.
 */
class PdvRevenueTest {

    /**
     * One day of one point of sale is found by its pair, and a period is listed
     * by point of sale then day.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theDaysAreFoundAndListed() {
        PdvRevenue day = new PdvRevenue();
        LocalDate first = LocalDate.of(2026, 10, 1);
        LocalDate last = LocalDate.of(2026, 10, 31);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PdvRevenue> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(day);
            panache.when(() -> PdvRevenue.find("pdvNumber = ?1 and day = ?2", "07039", first)).thenReturn(query);
            panache.when(() -> PdvRevenue.list("day >= ?1 and day <= ?2 order by pdvNumber, day", first, last))
                    .thenReturn(List.of(day));
            assertSame(day, PdvRevenue.findDay("07039", first));
            assertEquals(List.of(day), PdvRevenue.listBetween(first, last));
        }
    }
}
