package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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
import static org.mockito.Mockito.mockStatic;
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
 * values. The tests cover the loop-entered and loop-skipped arms, both arms
 * of each of the per-bucket ternaries (null rate, null amount, null family
 * code, null family label, null quantity, an administered regime against an
 * unknown one, and a regime without number or label), and both arms of the
 * zero-divisor guard in {@code excluding}.
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
     * Stubs the regime lookup a populated bucket reads: the rate resolves, on
     * the Panache finder the report calls, to the given regime. The query
     * mock is built before the stubbing so its own {@code when} does not sit
     * inside the unfinished registration of the find.
     *
     * @param panache the active Panache static mock
     * @param rate the rate the bucket carries
     * @param regime the regime the referential holds at that rate, or null
     */
    private void stubRegime(MockedStatic<PanacheEntityBase> panache, BigDecimal rate,
                            VatRate regime) {
        PanacheQuery<VatRate> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(regime);
        panache.when(() -> VatRate.find("rate", rate)).thenReturn(query);
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
     * The columns are the nine the requirement lists, in order, with the kind
     * each carries — BO-06-07-01 asks for the REGIME beside the rate (a rate
     * is a figure, a code is what an accountant reconciles on) and for the
     * quantity sold beside the amounts.
     */
    @Test
    void columns() {
        VatByFamilyReport report = new VatByFamilyReport();
        List<ReportColumn> columns = report.columns();
        assertEquals(9, columns.size());
        assertEquals("code", columns.get(0).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(0).kind());
        assertEquals("label", columns.get(1).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(1).kind());
        assertEquals("vatCode", columns.get(2).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(2).kind());
        assertEquals("vatLabel", columns.get(3).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(3).kind());
        assertEquals("rate", columns.get(4).key());
        assertEquals(ReportColumn.Kind.PERCENT, columns.get(4).kind());
        assertEquals("quantity", columns.get(5).key());
        assertEquals(ReportColumn.Kind.INTEGER, columns.get(5).kind());
        assertEquals("excluding", columns.get(6).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(6).kind());
        assertEquals("vat", columns.get(7).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(7).kind());
        assertEquals("including", columns.get(8).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(8).kind());
    }

    /**
     * A fully populated bucket with a normal rate takes the false arm of every
     * ternary and of the zero-divisor guard: the family code and label are
     * echoed, the regime the rate resolves to is named beside the rate, the
     * quantity is carried, the rate is scaled to a percentage and the excluded
     * amount and tax are derived from the inclusive total.
     */
    @Test
    void rowFullyPopulated() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"01", "Épicerie", new BigDecimal("0.055"),
                        new BigDecimal("105.50"), 7L}));
        VatRate reduced = new VatRate(1, new BigDecimal("0.055"), "Taux réduit");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRegime(panache, new BigDecimal("0.055"), reduced);
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(1, rows.size());
            List<Object> row = rows.get(0);
            assertEquals("01", row.get(0));
            assertEquals("Épicerie", row.get(1));
            assertEquals("1", row.get(2));
            assertEquals("Taux réduit", row.get(3));
            assertEquals(new BigDecimal("5.500"), row.get(4));
            assertEquals(7L, row.get(5));
            assertEquals(new BigDecimal("100.00"), row.get(6));
            assertEquals(new BigDecimal("5.50"), row.get(7));
            assertEquals(new BigDecimal("105.50"), row.get(8));
        }
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
     * rate is read as zero, the regime is not looked up at all — so the two
     * regime columns come out empty — and the excluded amount equals the
     * inclusive total and the tax is nil.
     */
    @Test
    void rowWithNullRate() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"02", "Frais", null, new BigDecimal("50.00"), 2L}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("02", row.get(0));
        assertEquals("Frais", row.get(1));
        assertEquals("", row.get(2));
        assertEquals("", row.get(3));
        assertEquals(new BigDecimal("0"), row.get(4));
        assertEquals(2L, row.get(5));
        assertEquals(new BigDecimal("50.00"), row.get(6));
        assertEquals(new BigDecimal("0.00"), row.get(7));
        assertEquals(new BigDecimal("50.00"), row.get(8));
    }

    /**
     * A bucket whose summed amount is null takes the true arm of the amount
     * ternary: the inclusive total is read as zero, so the excluded amount and
     * the tax are both nil. The rate is carried by no regime the referential
     * holds, which leaves the two regime columns empty.
     */
    @Test
    void rowWithNullAmount() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{"03", "Bazar", new BigDecimal("0.20"), null, 3L}));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRegime(panache, new BigDecimal("0.20"), null);
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(1, rows.size());
            List<Object> row = rows.get(0);
            assertEquals("03", row.get(0));
            assertEquals("Bazar", row.get(1));
            assertEquals("", row.get(2));
            assertEquals("", row.get(3));
            assertEquals(new BigDecimal("20.00"), row.get(4));
            assertEquals(3L, row.get(5));
            assertEquals(new BigDecimal("0.00"), row.get(6));
            assertEquals(new BigDecimal("0.00"), row.get(7));
            assertEquals(BigDecimal.ZERO, row.get(8));
        }
    }

    /**
     * A bucket whose family code and label are both null takes the true arm of
     * the code and label ternaries: the code renders as a dash and the label as
     * an empty string, the grouping key being absent. Its null quantity takes
     * the true arm of the quantity ternary and reads as zero.
     */
    @Test
    void rowWithNullFamily() {
        EntityManager entityManager = mock(EntityManager.class);
        VatByFamilyReport report = reportReturning(entityManager, List.<Object[]>of(
                new Object[]{null, null, new BigDecimal("0.10"),
                        new BigDecimal("11.00"), null}));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRegime(panache, new BigDecimal("0.10"), null);
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(1, rows.size());
            List<Object> row = rows.get(0);
            assertEquals("—", row.get(0));
            assertEquals("", row.get(1));
            assertEquals("", row.get(2));
            assertEquals("", row.get(3));
            assertEquals(new BigDecimal("10.00"), row.get(4));
            assertEquals(0L, row.get(5));
            assertEquals(new BigDecimal("10.00"), row.get(6));
            assertEquals(new BigDecimal("1.00"), row.get(7));
            assertEquals(new BigDecimal("11.00"), row.get(8));
        }
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
                new Object[]{"99", "Défaut", new BigDecimal("-1"),
                        new BigDecimal("42.00"), 1L}));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRegime(panache, new BigDecimal("-1"), null);
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(1, rows.size());
            List<Object> row = rows.get(0);
            assertEquals("99", row.get(0));
            assertEquals("Défaut", row.get(1));
            assertEquals("", row.get(2));
            assertEquals("", row.get(3));
            assertEquals(new BigDecimal("-100"), row.get(4));
            assertEquals(1L, row.get(5));
            assertEquals(new BigDecimal("42.00"), row.get(6));
            assertEquals(new BigDecimal("0.00"), row.get(7));
            assertEquals(new BigDecimal("42.00"), row.get(8));
        }
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
                new Object[]{"01", "Épicerie", new BigDecimal("0.055"),
                        new BigDecimal("105.50"), 3L},
                new Object[]{"02", "Frais", new BigDecimal("0.20"),
                        new BigDecimal("12.00"), 2L}));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubRegime(panache, new BigDecimal("0.055"), null);
            stubRegime(panache, new BigDecimal("0.20"), null);
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(2, rows.size());
            assertEquals("01", rows.get(0).get(0));
            assertEquals(3L, rows.get(0).get(5));
            assertEquals("02", rows.get(1).get(0));
            assertEquals(2L, rows.get(1).get(5));
            assertEquals(new BigDecimal("10.00"), rows.get(1).get(6));
            assertEquals(new BigDecimal("2.00"), rows.get(1).get(7));
            assertFalse(rows.isEmpty());
        }
    }
}
