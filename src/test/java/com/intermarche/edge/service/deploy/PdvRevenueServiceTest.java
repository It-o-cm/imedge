package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.PdvRevenue;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PdvRevenueService} (BO-02-05-05).
 * <p>
 * The sums run on a mocked {@link EntityManager}; the filing reads through
 * {@link PdvRevenue}'s finder, intercepted by a static mock, its construction
 * neutralised. Branches: the seven days summed; the null point of sale and the
 * null list; an unreadable and a null day skipped; a day created and a day
 * restated; a null revenue filed as zero.
 */
class PdvRevenueServiceTest {

    /**
     * The store sums its seven last days, oldest first, today included.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLastSevenDaysAreSummed() {
        PdvRevenueService service = new PdvRevenueService();
        service.entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(service.entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new Object[]{new BigDecimal("120.50"), 3L});
        List<DeployPayloads.DayRevenue> days = service.dayRevenues(LocalDate.of(2026, 10, 25));
        assertEquals(7, days.size());
        assertEquals("2026-10-19", days.get(0).day);
        assertEquals("2026-10-25", days.get(6).day);
        assertEquals(new BigDecimal("120.50"), days.get(6).revenue);
        assertEquals(3L, days.get(6).tickets);
        verify(query).setParameter("from", LocalDate.of(2026, 10, 25).atStartOfDay());
    }

    /**
     * Nothing is filed without a point of sale or without days.
     */
    @Test
    void nothingIsFiledWithoutAPointOfSaleOrDays() {
        PdvRevenueService service = new PdvRevenueService();
        assertEquals(0, service.record(null, List.of(new DeployPayloads.DayRevenue())));
        assertEquals(0, service.record("07039", null));
    }

    /**
     * A new day is created, a known day restated, an unreadable or missing day
     * skipped, and a missing revenue filed as zero.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theDaysAreFiledOrRestated() {
        PdvRevenueService service = new PdvRevenueService();
        DeployPayloads.DayRevenue fresh = day("2026-10-24", null, 0L);
        DeployPayloads.DayRevenue again = day("2026-10-25", new BigDecimal("80.00"), 2L);
        DeployPayloads.DayRevenue unreadable = day("hier", BigDecimal.ONE, 1L);
        DeployPayloads.DayRevenue missing = day(null, BigDecimal.ONE, 1L);
        PdvRevenue held = mock(PdvRevenue.class);
        List<PdvRevenue> created = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<PdvRevenue> rows = mockConstruction(PdvRevenue.class)) {
            PanacheQuery<PdvRevenue> none = mock(PanacheQuery.class);
            PanacheQuery<PdvRevenue> found = mock(PanacheQuery.class);
            when(found.firstResult()).thenReturn(held);
            panache.when(() -> PdvRevenue.find("pdvNumber = ?1 and day = ?2", "07039",
                    LocalDate.of(2026, 10, 24))).thenReturn(none);
            panache.when(() -> PdvRevenue.find("pdvNumber = ?1 and day = ?2", "07039",
                    LocalDate.of(2026, 10, 25))).thenReturn(found);
            assertEquals(2, service.record("07039", List.of(fresh, again, unreadable, missing)));
            created.addAll(rows.constructed());
        }
        assertEquals(1, created.size());
        PdvRevenue row = created.get(0);
        assertEquals("07039", row.pdvNumber);
        assertEquals(LocalDate.of(2026, 10, 24), row.day);
        assertEquals(BigDecimal.ZERO, row.revenue);
        assertNotNull(row.receivedAt);
        verify(row).persist();
        assertEquals(new BigDecimal("80.00"), held.revenue);
        assertEquals(2L, held.tickets);
        verify(held, never()).persist();
    }

    /**
     * Builds one reported day.
     *
     * @param date the ISO day, possibly null or unreadable
     * @param revenue the revenue, possibly null
     * @param tickets the ticket count
     * @return the day
     */
    private DeployPayloads.DayRevenue day(String date, BigDecimal revenue, long tickets) {
        DeployPayloads.DayRevenue day = new DeployPayloads.DayRevenue();
        day.day = date;
        day.revenue = revenue;
        day.tickets = tickets;
        return day;
    }
}
