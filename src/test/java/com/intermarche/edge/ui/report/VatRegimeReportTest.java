package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VatRegimeReport} ({@code BO-02-02-03}).
 *
 * <p>The regime list is a custom static of {@link VatRate} and is
 * intercepted with {@code mockStatic}; the price count runs on a mocked
 * {@link EntityManager}. Branch enumeration (both arms): a regime with and
 * without a number, a label, a country and a legal mention; a zero rate,
 * which exempts, and a non-zero one, which does not; a null rate; and a
 * regime never persisted, whose prices are not counted at all.
 */
class VatRegimeReportTest {

    /**
     * Creates a fresh typed-query mock, confining the unchecked cast.
     *
     * @param <T> the query result type
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Builds a report whose price count always answers the given figure.
     *
     * @param prices what the count returns
     * @return the report under test
     */
    private VatRegimeReport reportCounting(long prices) {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Long> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Price p"), eq(Long.class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(prices);
        VatRegimeReport report = new VatRegimeReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds a regime.
     *
     * @param id the database id, or null for one never persisted
     * @param number the regime number, or null
     * @param rate the rate as a fraction, or null
     * @param label the label, or null
     * @param country the ISO country, or null
     * @param mention the legal mention, or null
     * @return the regime
     */
    private VatRate regime(Long id, Integer number, String rate, String label,
                           String country, String mention) {
        VatRate regime = new VatRate();
        regime.id = id;
        regime.number = number;
        regime.rate = rate == null ? null : new BigDecimal(rate);
        regime.label = label;
        regime.country = country;
        regime.legalMention = mention;
        return regime;
    }

    /**
     * The report names itself, its requirement and its family.
     */
    @Test
    void theReportNamesItself() {
        VatRegimeReport report = reportCounting(0L);
        assertEquals("vat-regimes", report.code());
        assertEquals("État des régimes de TVA", report.label());
        assertEquals("BO-02-02-03", report.requirement());
        assertEquals(ReportFamily.TAX, report.family());
        assertEquals(7, report.columns().size());
    }

    /**
     * BO-02-02-03: every regime comes out with its code, its rate, its
     * country and the mention it obliges; a zero rate is named exempt.
     */
    @Test
    void everyRegimeComesOutWithItsConformityColumns() {
        VatRegimeReport report = reportCounting(42L);
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(VatRate::listAllOrdered).thenReturn(List.of(
                    regime(1L, 1, "0.2000", "Taux normal", "FR", "TVA sur les débits"),
                    regime(2L, 4, "0.0000", "Exonéré", "PT", null)));
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(2, rows.size());
            assertEquals("1", rows.get(0).get(0));
            assertEquals("Taux normal", rows.get(0).get(2));
            assertEquals("FR", rows.get(0).get(3));
            assertEquals("TVA sur les débits", rows.get(0).get(4));
            assertEquals(42L, rows.get(0).get(5));
            assertEquals("Non", rows.get(0).get(6));
            assertEquals("", rows.get(1).get(4));
            assertEquals("Oui", rows.get(1).get(6));
        }
    }

    /**
     * A regime carrying neither number, label, country nor mention prints
     * empty cells rather than the word null, and one never persisted is not
     * counted at all.
     */
    @Test
    void anEmptyRegimePrintsEmptyCells() {
        VatRegimeReport report = reportCounting(7L);
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(VatRate::listAllOrdered)
                    .thenReturn(List.of(regime(null, null, null, null, null, null)));
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(1, rows.size());
            assertEquals("", rows.get(0).get(0));
            assertEquals("", rows.get(0).get(2));
            assertEquals("", rows.get(0).get(3));
            assertEquals("", rows.get(0).get(4));
            assertEquals(0L, rows.get(0).get(5));
            assertEquals("Non", rows.get(0).get(6));
        }
    }

    /**
     * An empty referential produces an empty report rather than a header
     * with nothing under it.
     */
    @Test
    void anEmptyReferentialProducesNoRow() {
        VatRegimeReport report = reportCounting(0L);
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(VatRate::listAllOrdered).thenReturn(List.of());
            assertTrue(report.rows(new ReportCriteria()).isEmpty());
        }
    }
}
