package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VatByFamilyReport}: the family sold, the levels, the
 * synthesis, the rayon break, the station exclusion, the hours, the regime's
 * code and label, the null cells and the degenerate rate.
 */
class VatByFamilyReportTest {

    /** A moment of the period. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 10, 0);

    /**
     * Builds a regime.
     *
     * @param number the number, possibly null
     * @param label the label, possibly null
     * @return the regime
     */
    private VatRate regime(Integer number, String label) {
        VatRate rate = new VatRate();
        rate.number = number;
        rate.label = label;
        return rate;
    }

    /**
     * Runs the report over sold lines.
     *
     * @param lines the lines: family, label, rate, total, quantity, number, moment
     * @param option the option, or null
     * @param stations the station families
     * @return the rows
     */
    private List<List<Object>> run(List<Object[]> lines, String option, List<String> stations) {
        QueryStub stub = new QueryStub().answer("from Ticket t join t.lines l", lines);
        VatByFamilyReport report = new VatByFamilyReport();
        report.entityManager = stub.entityManager;
        report.posSettingsService = mock(PosSettingsService.class);
        when(report.posSettingsService.reportStationFamilies()).thenReturn(stations);
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = option;
        criteria.hourTo = LocalTime.of(20, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            NomenclatureFixture.scheme(panache, Map.of("1002", "BOUCHERIE", "10", "FRAIS", "10020200", "BOVIN"));
            rates.when(() -> VatRate.resolve(new BigDecimal("0.2000"), 1)).thenReturn(regime(1, "Normal"));
            rates.when(() -> VatRate.resolve(new BigDecimal("0.0550"), null)).thenReturn(regime(null, null));
            return report.rows(criteria);
        }
    }

    /**
     * The sold lines of the period.
     *
     * @return the lines
     */
    private List<Object[]> lines() {
        return rows(
                row("10020200", "Bovin", new BigDecimal("0.2000"), new BigDecimal("12.00"), new BigDecimal("2"), 1, AT),
                row("10020201", null, new BigDecimal("0.2000"), new BigDecimal("6.00"), new BigDecimal("1"), 1, AT),
                row("90000000", "Carburant", new BigDecimal("0.2000"), new BigDecimal("50.00"), BigDecimal.ONE, 1, AT),
                row(null, null, new BigDecimal("0.0550"), null, null, null, AT),
                row("10020200", "Bovin", new BigDecimal("0.2000"), new BigDecimal("9.00"), BigDecimal.ONE, 1,
                        AT.withHour(21)));
    }

    /**
     * By family sold: one row per family and rate, broken by rayon, the
     * station left out, the late line out of the hours.
     */
    @Test
    void byFamilySold() {
        List<List<Object>> rows = run(lines(), null, List.of("9000"));
        assertEquals(3, rows.size());
        List<Object> bovin = rows.get(0);
        assertEquals("1002", bovin.get(0));
        assertEquals("BOUCHERIE", bovin.get(1));
        assertEquals("10020200", bovin.get(2));
        assertEquals("Bovin", bovin.get(3));
        assertEquals("1", bovin.get(4));
        assertEquals("Normal", bovin.get(5));
        assertEquals(new BigDecimal("20.0000"), bovin.get(6));
        assertEquals(new BigDecimal("12.00"), bovin.get(7));
        assertEquals(2L, bovin.get(8));
        assertEquals(new BigDecimal("10.00"), bovin.get(9));
        assertEquals(new BigDecimal("2.00"), bovin.get(10));
        assertEquals("", rows.get(1).get(3));
        List<Object> unknown = rows.get(2);
        assertEquals("—", unknown.get(0));
        assertEquals("—", unknown.get(2));
        assertEquals("", unknown.get(4));
        assertEquals("", unknown.get(5));
        assertEquals(BigDecimal.ZERO, unknown.get(7));
    }

    /**
     * At the rayon level the families of a rayon are gathered, labelled by the
     * nomenclature, and the station kept when no station is administered.
     */
    @Test
    void atALevel() {
        List<List<Object>> rows = run(lines(), "1", List.of());
        assertEquals(3, rows.size());
        assertEquals("1002", rows.get(0).get(2));
        assertEquals("BOUCHERIE", rows.get(0).get(3));
        assertEquals(new BigDecimal("18.00"), rows.get(0).get(7));
        assertEquals("9000", rows.get(1).get(2));
        assertEquals("—", rows.get(2).get(2));
    }

    /**
     * The synthesis edits one row per rate, without the nomenclature.
     */
    @Test
    void theSynthesis() {
        List<List<Object>> rows = run(lines(), "SYNTHESIS", List.of("9000"));
        assertEquals(2, rows.size());
        assertEquals("Synthèse", rows.get(0).get(0));
        assertEquals("", rows.get(0).get(1));
        assertEquals("", rows.get(0).get(2));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(7));
        assertEquals(new BigDecimal("18.00"), rows.get(1).get(7));
    }

    /**
     * The level option reads the four ranks and nothing else; the station
     * test is a code prefix; the preamble states the station exclusion.
     */
    @Test
    void levelsStationsAndPreamble() {
        assertEquals(0, VatByFamilyReport.level("0"));
        assertEquals(3, VatByFamilyReport.level("3"));
        assertEquals(-1, VatByFamilyReport.level("SOLD"));
        assertTrue(VatByFamilyReport.station("90001234", List.of("9000")));
        assertFalse(VatByFamilyReport.station("10001234", List.of("9000")));
        VatByFamilyReport report = new VatByFamilyReport();
        report.posSettingsService = mock(PosSettingsService.class);
        when(report.posSettingsService.reportStationFamilies()).thenReturn(List.of("9000", "9100"), List.of());
        assertEquals(List.of("Chiffre d'affaires de la station exclu : 9000, 9100"),
                report.preamble(new ReportCriteria()));
        assertTrue(report.preamble(new ReportCriteria()).isEmpty());
    }

    /**
     * A rate of minus one cannot divide: the amount stays whole.
     */
    @Test
    void aDegenerateRateKeepsTheAmount() {
        List<Object[]> lines = rows(row("10020200", "Bovin", new BigDecimal("-1"), new BigDecimal("5.00"),
                BigDecimal.ONE, 7, AT));
        List<List<Object>> rows = run(lines, null, List.of());
        assertEquals(new BigDecimal("5.00"), rows.get(0).get(9));
        assertEquals(new BigDecimal("0.00"), rows.get(0).get(10));
    }

    /**
     * A line without rate is read at zero: the amount stays whole.
     */
    @Test
    void aLineWithoutRateIsReadAtZero() {
        List<Object[]> lines = rows(row("10020200", "Bovin", null, new BigDecimal("5.00"), BigDecimal.ONE, null, AT));
        List<List<Object>> rows = run(lines, null, List.of());
        assertEquals(new BigDecimal("5.00"), rows.get(0).get(9));
        assertEquals(new BigDecimal("0.00"), rows.get(0).get(10));
    }

    /**
     * The metadata: identity, columns, chart, break, options and filters.
     */
    @Test
    void columns() {
        VatByFamilyReport report = new VatByFamilyReport();
        assertEquals("vat-by-family", report.code());
        assertEquals("État TVA par nomenclature", report.label());
        assertEquals("BO-06-07-01", report.requirement());
        assertEquals(ReportFamily.TAX, report.family());
        assertEquals(11, report.columns().size());
        assertEquals(7, report.chartColumn());
        assertEquals(3, report.chartLabelColumn());
        assertEquals("rayon", report.breakKey());
        assertEquals("Niveau", report.optionLabel());
        assertEquals(6, report.optionChoices().size());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.ARTICLES),
                report.filters());
    }
}
