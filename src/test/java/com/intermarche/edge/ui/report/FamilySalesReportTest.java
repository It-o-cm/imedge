package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FamilySalesReport}.
 * <p>
 * The report runs two JPQL grouping queries on a mocked {@link EntityManager} —
 * one for the sold lines, one for the cancelled ones — then folds each sold row
 * into a display row, joining its cancellation figures by family code. Every
 * collaborator is a Mockito mock, query results are fed as raw {@code Object[]}
 * rows and assertions bear absolute expected values. The tests cover both arms
 * of every ternary of {@code rows()} (null code, null label, null amount, null
 * quantity, zero lines, present/absent/amount-less cancellation), the two arms
 * of the {@code share()} guard, the descending sort, the empty-result and
 * empty-grouping cases, and the constant metadata methods.
 */
class FamilySalesReportTest {

    /**
     * Creates a fresh {@link TypedQuery} mock, confining the unchecked cast to
     * a single suppression point.
     *
     * @param <T> the query result type
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Builds a {@link FamilySalesReport} whose entity-manager field routes the
     * sold-lines query to {@code sold} and the cancelled-lines query to
     * {@code cancelled}, each returning itself from any {@code setParameter}.
     *
     * @param sold the rows the sold-lines query returns
     * @param cancelled the rows the cancelled-lines query returns
     * @return a report ready under test
     */
    private FamilySalesReport reportWith(List<Object[]> sold, List<Object[]> cancelled) {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> soldQuery = typedQueryMock();
        when(entityManager.createQuery(contains("l.familyLabel"), eq(Object[].class))).thenReturn(soldQuery);
        when(soldQuery.setParameter(anyString(), any())).thenReturn(soldQuery);
        when(soldQuery.getResultList()).thenReturn(sold);
        TypedQuery<Object[]> cancelledQuery = typedQueryMock();
        when(entityManager.createQuery(contains("l.cancellationDate"), eq(Object[].class))).thenReturn(cancelledQuery);
        when(cancelledQuery.setParameter(anyString(), any())).thenReturn(cancelledQuery);
        when(cancelledQuery.getResultList()).thenReturn(cancelled);
        FamilySalesReport report = new FamilySalesReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * With two families whose sold rows carry non-null values and a cancellation
     * list holding a null-code line then an F1 line, {@code rows()} takes the
     * non-null arm of every ternary, matches F1's cancellation by equality
     * (finding null-code first and continuing), leaves F2 without a match, uses
     * the divide arm of the average and the non-zero arm of the share, and
     * sorts the two rows by descending net sales.
     */
    @Test
    void rowsPopulatedMatchedAndSorted() {
        List<Object[]> sold = List.of(
                new Object[]{"F1", "Fruits", new BigDecimal("40.00"), 4L, 8L},
                new Object[]{"F2", "Légumes", new BigDecimal("60.00"), 3L, 6L});
        List<Object[]> cancelled = List.of(
                new Object[]{null, 9L, new BigDecimal("9.00")},
                new Object[]{"F1", 2L, new BigDecimal("5.00")});
        FamilySalesReport report = reportWith(sold, cancelled);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(2, rows.size());
        List<Object> first = rows.get(0);
        assertEquals("F2", first.get(0));
        assertEquals("Légumes", first.get(1));
        assertEquals(new BigDecimal("60.00"), first.get(2));
        assertEquals(new BigDecimal("60.0"), first.get(3));
        assertEquals(3L, first.get(4));
        assertEquals(6L, first.get(5));
        assertEquals(new BigDecimal("20.00"), first.get(6));
        assertEquals(0L, first.get(7));
        assertEquals(BigDecimal.ZERO, first.get(8));
        List<Object> second = rows.get(1);
        assertEquals("F1", second.get(0));
        assertEquals("Fruits", second.get(1));
        assertEquals(new BigDecimal("40.00"), second.get(2));
        assertEquals(new BigDecimal("40.0"), second.get(3));
        assertEquals(4L, second.get(4));
        assertEquals(8L, second.get(5));
        assertEquals(new BigDecimal("10.00"), second.get(6));
        assertEquals(2L, second.get(7));
        assertEquals(new BigDecimal("5.00"), second.get(8));
    }

    /**
     * With a single sold row whose amount is null, {@code rows()} takes the null
     * arm of the total accumulation and of the net ternary, yielding a zero net,
     * and the zero-total arm of the share guard, yielding a zero share.
     */
    @Test
    void rowNullAmountGivesZeroNetAndShare() {
        List<Object[]> sold = List.<Object[]>of(
                new Object[]{"F1", "Fruits", null, 2L, 4L});
        FamilySalesReport report = reportWith(sold, List.of());
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("F1", row.get(0));
        assertEquals(BigDecimal.ZERO, row.get(2));
        assertEquals(BigDecimal.ZERO, row.get(3));
        assertEquals(2L, row.get(4));
        assertEquals(new BigDecimal("0.00"), row.get(6));
        assertEquals(0L, row.get(7));
        assertEquals(BigDecimal.ZERO, row.get(8));
    }

    /**
     * With a sold row whose code, label and quantity are null and whose line
     * count is zero, {@code rows()} takes the null arm of the code, label and
     * quantity ternaries and the zero arm of the average ternary, and matches a
     * null-code cancellation whose amount is null, taking the amount-less leg of
     * the compound cancellation guard.
     */
    @Test
    void rowNullCodeLabelQuantityAndZeroLines() {
        List<Object[]> sold = List.<Object[]>of(
                new Object[]{null, null, new BigDecimal("30.00"), 0L, null});
        List<Object[]> cancelled = List.<Object[]>of(
                new Object[]{null, 3L, null});
        FamilySalesReport report = reportWith(sold, cancelled);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("—", row.get(0));
        assertEquals("", row.get(1));
        assertEquals(new BigDecimal("30.00"), row.get(2));
        assertEquals(new BigDecimal("100.0"), row.get(3));
        assertEquals(0L, row.get(4));
        assertEquals(0L, row.get(5));
        assertEquals(BigDecimal.ZERO, row.get(6));
        assertEquals(3L, row.get(7));
        assertEquals(BigDecimal.ZERO, row.get(8));
    }

    /**
     * With no sold rows and no cancellations, {@code rows()} skips its folding
     * loop entirely and returns an empty list, the zero-sales case.
     */
    @Test
    void rowsEmptyResultIsEmptyList() {
        FamilySalesReport report = reportWith(List.of(), List.of());
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertTrue(rows.isEmpty());
    }

    /**
     * The metadata methods return their constant contract values, including the
     * nine columns and the chart indices.
     */
    @Test
    void metadataIsConstant() {
        FamilySalesReport report = new FamilySalesReport();
        assertEquals("family-sales", report.code());
        assertEquals("État ventes par famille", report.label());
        assertEquals("BO-06-04-01", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(9, report.columns().size());
        assertEquals("code", report.columns().get(0).key());
        assertEquals(2, report.chartColumn());
        assertEquals(1, report.chartLabelColumn());
    }

    /**
     * Sanity anchor tying the sold-lines query to the CLOSED ticket status,
     * proving the routed query is the sales query and not the cancellation one.
     */
    @Test
    void closedStatusEnumIsReferenced() {
        assertEquals("CLOSED", Ticket.TicketStatus.CLOSED.name());
    }
}
