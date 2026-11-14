package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.SalesChannel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelRevenueReport} (BO-07-01-02): the revenue is
 * split by sales channel, the tickets carrying none counted as the store's,
 * the channels in their order, an empty sum read as zero.
 */
class ChannelRevenueReportTest {

    /**
     * A report over a mocked aggregate answering the given rows.
     *
     * @param rows the aggregate rows
     * @return the report
     */
    @SuppressWarnings("unchecked")
    private ChannelRevenueReport report(List<Object[]> rows) {
        ChannelRevenueReport report = new ChannelRevenueReport();
        report.entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(report.entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        return report;
    }

    /**
     * The report's identity, family and columns.
     */
    @Test
    void theReportIsIdentified() {
        ChannelRevenueReport report = new ChannelRevenueReport();
        assertEquals("channel-revenue", report.code());
        assertEquals("Chiffre d'affaires par canal", report.label());
        assertEquals("BO-07-01-02", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(5, report.columns().size());
        assertEquals(2, report.chartColumn());
    }

    /**
     * One row per channel in the channels' order: the tickets without channel
     * merged with the store's own, the e-commerce one apart, null sums zero.
     */
    @Test
    void theRevenueIsSplitByChannel() {
        ChannelRevenueReport report = report(List.of(
                new Object[]{SalesChannel.ECOMMERCE, 2L, new BigDecimal("30.00"), new BigDecimal("25.00"), new BigDecimal("5.00")},
                new Object[]{null, 3L, new BigDecimal("12.00"), new BigDecimal("10.00"), new BigDecimal("2.00")},
                new Object[]{SalesChannel.STORE, 1L, null, null, null}));
        ReportCriteria criteria = new ReportCriteria();
        criteria.terminals.add("C04");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(List.of("Magasin", 4L, new BigDecimal("12.00"), new BigDecimal("10.00"), new BigDecimal("2.00")),
                rows.get(0));
        assertEquals(List.of("E-commerce", 2L, new BigDecimal("30.00"), new BigDecimal("25.00"), new BigDecimal("5.00")),
                rows.get(1));
        verify(report.entityManager).createQuery(contains("group by t.salesChannel"), eq(Object[].class));
        assertTrue(report(List.of()).rows(new ReportCriteria()).isEmpty());
    }
}
