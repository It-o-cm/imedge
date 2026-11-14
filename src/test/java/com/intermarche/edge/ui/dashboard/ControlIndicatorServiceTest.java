package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.service.PosSettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ControlIndicatorService} (BO-04-03-01).
 */
class ControlIndicatorServiceTest {

    /**
     * A typed query mock returning itself on every setter.
     *
     * @param <T> the result type
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typed() {
        TypedQuery<T> query = mock(TypedQuery.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        return query;
    }

    /**
     * Builds a service whose day reads the given figures.
     *
     * @param revenue the day's revenue
     * @param customers the day's tickets
     * @param items the day's articles
     * @param lastYear the revenue a year before
     * @param level the administered level rank
     * @param lengths the code lengths declared for that rank
     * @param rows the revenue detail rows
     * @param labels the labels of the detail codes
     * @return the service
     */
    private ControlIndicatorService service(BigDecimal revenue, long customers, BigDecimal items, BigDecimal lastYear,
                                            int level, List<Integer> lengths, List<Object[]> rows, List<Object[]> labels) {
        ControlIndicatorService service = new ControlIndicatorService();
        service.entityManager = mock(EntityManager.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.controlIndicatorsBar()).thenReturn(true);
        when(service.posSettingsService.controlRevenueLevel()).thenReturn(level);
        when(service.posSettingsService.controlRevenueTop()).thenReturn(5);
        Query day = mock(Query.class);
        when(day.setParameter(anyString(), any())).thenReturn(day);
        when(day.getSingleResult()).thenReturn(new Object[]{revenue, customers});
        when(service.entityManager.createQuery(startsWith("select coalesce(sum(t.totalIncludingTax), 0), count(t)")))
                .thenReturn(day);
        TypedQuery<BigDecimal> itemQuery = typed();
        when(itemQuery.getSingleResult()).thenReturn(items);
        when(service.entityManager.createQuery(startsWith("select coalesce(sum(l.quantity), 0)"), eq(BigDecimal.class)))
                .thenReturn(itemQuery);
        TypedQuery<BigDecimal> yearQuery = typed();
        when(yearQuery.getSingleResult()).thenReturn(lastYear);
        when(service.entityManager.createQuery(startsWith("select coalesce(sum(t.totalIncludingTax), 0) from"),
                eq(BigDecimal.class))).thenReturn(yearQuery);
        TypedQuery<Integer> lengthQuery = typed();
        when(lengthQuery.getResultList()).thenReturn(lengths);
        when(service.entityManager.createQuery(startsWith("select n.codeLength"), eq(Integer.class))).thenReturn(lengthQuery);
        TypedQuery<Object[]> detailQuery = typed();
        when(detailQuery.getResultList()).thenReturn(rows);
        when(service.entityManager.createQuery(contains("sum(l.totalPrice) from Ticket t"), eq(Object[].class)))
                .thenReturn(detailQuery);
        TypedQuery<Object[]> labelQuery = typed();
        when(labelQuery.getResultList()).thenReturn(labels);
        when(service.entityManager.createQuery(startsWith("select f.code, f.description"), eq(Object[].class)))
                .thenReturn(labelQuery);
        return service;
    }

    /**
     * The day's figures, the comparison with last year and the detail at the
     * administered level, labelled where the nomenclature names the code.
     */
    @Test
    void computesTheDayAndItsDetail() {
        ControlIndicatorService service = service(new BigDecimal("150"), 4L, new BigDecimal("9"), new BigDecimal("100"),
                1, List.of(2), List.of(new Object[]{"10", new BigDecimal("90")}, new Object[]{"20", new BigDecimal("60")}),
                List.of(new Object[]{"10", "EPICERIE"}, new Object[]{"20", null}));
        Map<String, Object> data = service.data();
        assertEquals(true, data.get("enabled"));
        assertEquals("150,00", data.get("revenue"));
        assertEquals(4L, data.get("customers"));
        assertEquals("2,25", data.get("basketQuantity"));
        assertEquals("100,00", data.get("lastYearRevenue"));
        assertEquals("50,00", data.get("difference"));
        assertEquals("50,0", data.get("differencePercent"));
        List<?> detail = (List<?>) data.get("detail");
        assertEquals("EPICERIE", ((Map<?, ?>) detail.get(0)).get("label"));
        assertEquals("20", ((Map<?, ?>) detail.get(1)).get("label"));
        assertEquals("90,00", ((Map<?, ?>) detail.get(0)).get("revenue"));
        verify(service.entityManager).createQuery(contains("group by l.familyCode"), eq(Object[].class));
    }

    /**
     * A code is cut to the level where the cut names a node, two codes of one
     * node summing up; a family coded outside the nomenclature keeps its own
     * code and label; a code nobody names is still cut; a null sum counts
     * zero; only the administered number of rows is kept.
     */
    @Test
    void codesAreCutOnlyWhereTheCutNamesANode() {
        ControlIndicatorService service = service(BigDecimal.TEN, 1L, BigDecimal.ONE, BigDecimal.ZERO,
                2, List.of(4), List.of(new Object[]{"20200200", new BigDecimal("6")},
                        new Object[]{"EPICERIE", new BigDecimal("8")}, new Object[]{"20200300", new BigDecimal("5")},
                        new Object[]{"99990000", new BigDecimal("1")}, new Object[]{"ABC", null},
                        new Object[]{"77770000", new BigDecimal("0.5")}, new Object[]{"66660000", new BigDecimal("0.1")}),
                List.of(new Object[]{"2020", "CREMERIE LS"}, new Object[]{"EPICERIE", "Épicerie"}));
        List<?> detail = (List<?>) service.data().get("detail");
        assertEquals(5, detail.size());
        assertEquals("2020", ((Map<?, ?>) detail.get(0)).get("code"));
        assertEquals("CREMERIE LS", ((Map<?, ?>) detail.get(0)).get("label"));
        assertEquals("11,00", ((Map<?, ?>) detail.get(0)).get("revenue"));
        assertEquals("EPICERIE", ((Map<?, ?>) detail.get(1)).get("code"));
        assertEquals("Épicerie", ((Map<?, ?>) detail.get(1)).get("label"));
        assertEquals("9999", ((Map<?, ?>) detail.get(2)).get("code"));
        assertEquals("7777", ((Map<?, ?>) detail.get(3)).get("code"));
        assertEquals("6666", ((Map<?, ?>) detail.get(4)).get("code"));
    }

    /**
     * A day without sale and without last year's: zero basket, no percentage,
     * the family as sold when the level is not declared, no label read.
     */
    @Test
    void anEmptyDay() {
        ControlIndicatorService service = service(BigDecimal.ZERO, 0L, BigDecimal.ZERO, BigDecimal.ZERO,
                3, List.of(), List.of(), List.of());
        Map<String, Object> data = service.data(LocalDateTime.of(2026, 10, 25, 12, 0));
        assertEquals("0,00", data.get("basketQuantity"));
        assertEquals("—", data.get("differencePercent"));
        assertTrue(((List<?>) data.get("detail")).isEmpty());
        verify(service.entityManager).createQuery(contains("group by l.familyCode"), eq(Object[].class));
    }

    /**
     * A level rank of zero reads the family as sold without asking the nomenclature.
     */
    @Test
    void levelZeroIsTheFamilyAsSold() {
        ControlIndicatorService service = service(BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ZERO,
                0, List.of(4), List.<Object[]>of(new Object[]{"20200200", BigDecimal.ONE}), List.of());
        assertEquals(0, service.codeLength(0));
        assertEquals(0, service.codeLength(-1));
        assertEquals("20200200", ((Map<?, ?>) ((List<?>) service.data().get("detail")).get(0)).get("code"));
    }
}
