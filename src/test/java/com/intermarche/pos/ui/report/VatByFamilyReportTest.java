package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.sale.Ticket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VatByFamilyReport}.
 * <p>
 * The report runs one grouping JPQL query on a mocked {@link EntityManager},
 * whose typed query returns pre-baked {@code Object[]} buckets, and turns each
 * bucket into a display row while deriving the tax excluded amount from the
 * inclusive total and the line rate. Every collaborator is a Mockito mock,
 * result rows are fed as raw arrays and assertions bear absolute expected
 * values. The tests cover the loop-entered and loop-skipped arms, both arms of
 * each of the four per-bucket ternaries (null rate, null amount, null family
 * code, null family label) and both arms of the zero-divisor guard in
 * {@code excluding}.
 */
class VatByFamilyReportTest {

    /**
     * Creates a fresh {@link TypedQuery} mock over {@code Object[]}, confining
     * the unchecked cast to a single suppression point.
     *
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Builds a report whose entity manager runs the grouping query and returns
     * the supplied buckets, the query returning itself from any
     * {@code setParameter} call.
     *
     * @param entityManager the mocked entity manager
     * @param buckets the rows the query yields
     * @return a report ready under test
     */
    private VatByFamilyReport reportReturning(EntityManager entityManager, List<Object[]> buckets) {
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("group by l.familyCode"), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(buckets);
        VatByFamilyReport report = new VatByFamilyReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * The report's static descriptors are the fixed strings and family the
     * requirement enumerates.
     */
    @Test
    void descriptors() {
        VatByFamilyReport report = new VatByFamilyReport();
        assertEquals("vat-by-family", report.code());
        assertEquals("État TVA par nomenclature", report.label());
        assertEquals("BO-06-07-01", report.requirement());
        assertEquals(ReportFamily.TAX, report.family());
    }

    /**
     * The columns are the six the requirement lists, in order, with the kind
     * each carries.
     */
    @Test
    void columns() {
        VatByFamilyReport report = new VatByFamilyReport();
        List<ReportColumn> columns = report.columns();
        assertEquals(6, columns.size());
        assertEquals("code", columns.get(0).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(0).kind());
        assertEquals("label", columns.get(1).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(1).kind());
        assertEquals("rate", columns.get(2).key());
        assertEquals(ReportColumn.Kind.PERCENT, columns.get(2).kind());
        assertEquals("excluding", columns.get(3).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(3).kind());
        assertEquals("vat", columns.get(4).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(4).kind());
        assertEquals("including", columns.get(5).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(5).kind());
    }

    /**
     * A fully populated bucket with a normal rate takes the false arm of every
     * ternary and of the zero-divisor guard: the family code and label are
     * echoed, the rate is scaled to a percentage and the excluded amount and
     * tax are derived from the inclusive total.
     */
    @Test
    void rowFullyPopulated() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"01", "Épicerie", new BigDecimal("0.055"), new BigDecimal("105.50")}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("01", row.get(0));
        assertEquals("Épicerie", row.get(1));
        assertEquals(new BigDecimal("5.500"), row.get(2));
        assertEquals(new BigDecimal("100.00"), row.get(3));
        assertEquals(new BigDecimal("5.50"), row.get(4));
        assertEquals(new BigDecimal("105.50"), row.get(5));
    }

    /**
     * The report passes the closed status and the criteria bounds to the query,
     * proving the three parameters are the ones the JPQL names.
     */
    @Test
    void queryParameters() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("group by l.familyCode"), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        VatByFamilyReport report = new VatByFamilyReport();
        report.entityManager = entityManager;
        ReportCriteria criteria = new ReportCriteria();
        report.rows(criteria);
        verify(query).setParameter("closed", Ticket.TicketStatus.CLOSED);
        verify(query).setParameter("from", criteria.from());
        verify(query).setParameter("to", criteria.to());
    }

    /**
     * A bucket whose rate is null takes the true arm of the rate ternary: the
     * rate is read as zero, so the excluded amount equals the inclusive total
     * and the tax is nil.
     */
    @Test
    void rowWithNullRate() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"02", "Frais", null, new BigDecimal("50.00")}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("02", row.get(0));
        assertEquals("Frais", row.get(1));
        assertEquals(new BigDecimal("0"), row.get(2));
        assertEquals(new BigDecimal("50.00"), row.get(3));
        assertEquals(new BigDecimal("0.00"), row.get(4));
        assertEquals(new BigDecimal("50.00"), row.get(5));
    }

    /**
     * A bucket whose summed amount is null takes the true arm of the amount
     * ternary: the inclusive total is read as zero, so the excluded amount and
     * the tax are both nil.
     */
    @Test
    void rowWithNullAmount() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"03", "Bazar", new BigDecimal("0.20"), null}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("03", row.get(0));
        assertEquals("Bazar", row.get(1));
        assertEquals(new BigDecimal("20.00"), row.get(2));
        assertEquals(new BigDecimal("0.00"), row.get(3));
        assertEquals(new BigDecimal("0.00"), row.get(4));
        assertEquals(BigDecimal.ZERO, row.get(5));
    }

    /**
     * A bucket whose family code and label are both null takes the true arm of
     * the code and label ternaries: the code renders as a dash and the label as
     * an empty string, the grouping key being absent.
     */
    @Test
    void rowWithNullFamily() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{null, null, new BigDecimal("0.10"), new BigDecimal("11.00")}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("—", row.get(0));
        assertEquals("", row.get(1));
        assertEquals(new BigDecimal("10.00"), row.get(2));
        assertEquals(new BigDecimal("10.00"), row.get(3));
        assertEquals(new BigDecimal("1.00"), row.get(4));
        assertEquals(new BigDecimal("11.00"), row.get(5));
    }

    /**
     * A bucket carrying the impossible rate of minus one takes the true arm of
     * the zero-divisor guard in {@code excluding}: the inclusive total is
     * returned untouched as the excluded amount, so the tax is nil.
     */
    @Test
    void rowWithMinusOneRate() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"99", "Défaut", new BigDecimal("-1"), new BigDecimal("42.00")}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("99", row.get(0));
        assertEquals("Défaut", row.get(1));
        assertEquals(new BigDecimal("-100"), row.get(2));
        assertEquals(new BigDecimal("42.00"), row.get(3));
        assertEquals(new BigDecimal("0.00"), row.get(4));
        assertEquals(new BigDecimal("42.00"), row.get(5));
    }

    /**
     * An empty result list — a day with no closed sales — skips the loop
     * entirely, yielding no rows.
     */
    @Test
    void rowsWhenNoSales() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of());
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertTrue(rows.isEmpty());
    }

    /**
     * Two buckets are both turned into rows, proving the loop iterates rather
     * than emitting only the first bucket, and preserving the query's family
     * then rate order.
     */
    @Test
    void rowsMultipleBuckets() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"01", "Épicerie", new BigDecimal("0.055"), new BigDecimal("105.50")},
                new Object[]{"02", "Frais", new BigDecimal("0.20"), new BigDecimal("12.00")}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(2, rows.size());
        assertEquals("01", rows.get(0).get(0));
        assertEquals("02", rows.get(1).get(0));
        assertEquals(new BigDecimal("10.00"), rows.get(1).get(3));
        assertEquals(new BigDecimal("2.00"), rows.get(1).get(4));
        assertFalse(rows.isEmpty());
    }
}
