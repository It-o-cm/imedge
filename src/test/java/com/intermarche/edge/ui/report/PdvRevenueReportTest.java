package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.deploy.PdvRevenue;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PdvRevenueReport} (BO-02-05-05).
 * <p>
 * The revenue rows and the organisation tree resolve to
 * {@link PanacheEntityBase} and are intercepted by a static mock. Branches: the
 * adhérent filter absent, matching and not; a point of sale with an adhérent
 * and an enseigne of a known country, one with neither, one whose enseigne the
 * tree does not hold or holds without country; a former number named after
 * its holder; a number nobody holds; the line per adhérent of the {@code *}
 * option.
 */
class PdvRevenueReportTest {

    /** The first day of the period the tests edit. */
    private static final LocalDate FIRST = LocalDate.of(2026, 10, 1);

    /** The last day of the period the tests edit. */
    private static final LocalDate LAST = LocalDate.of(2026, 10, 31);

    /**
     * Builds the criteria of the October range, with an adhérent option.
     *
     * @param option the adhérent asked for, or null
     * @return the criteria
     */
    private ReportCriteria october(String option) {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.RANGE;
        criteria.rangeFrom = FIRST;
        criteria.rangeTo = LAST;
        criteria.option = option;
        return criteria;
    }

    /**
     * Builds one reported day.
     *
     * @param pdv the point of sale
     * @param revenue the revenue
     * @param tickets the ticket count
     * @return the day
     */
    private PdvRevenue day(String pdv, String revenue, long tickets) {
        PdvRevenue day = new PdvRevenue();
        day.pdvNumber = pdv;
        day.revenue = new BigDecimal(revenue);
        day.tickets = tickets;
        return day;
    }

    /**
     * Builds a point of sale.
     *
     * @param id its id
     * @param number its number
     * @param name its name
     * @param enseigne its enseigne, or null
     * @param adherent its adhérent, or null
     * @return the point of sale
     */
    private Pdv pdv(Long id, String number, String name, String enseigne, String adherent) {
        Pdv pdv = new Pdv();
        pdv.id = id;
        pdv.pdvNumber = number;
        pdv.name = name;
        pdv.enseigneCode = enseigne;
        pdv.adherentCode = adherent;
        return pdv;
    }

    /**
     * Stubs a finder answering one row.
     *
     * @param panache the active static mock
     * @param row the row, or null
     * @param query the query text
     * @param params its parameters
     */
    @SuppressWarnings("unchecked")
    private void stub(MockedStatic<PanacheEntityBase> panache, Object row, String query, Object... params) {
        PanacheQuery<Object> answer = mock(PanacheQuery.class);
        when(answer.firstResult()).thenReturn(row);
        panache.when(() -> PanacheEntityBase.find(query, params)).thenReturn(answer);
    }

    /**
     * Stubs the whole fixture: five numbers reported over October.
     *
     * @param panache the active static mock
     */
    private void fixture(MockedStatic<PanacheEntityBase> panache) {
        panache.when(() -> PdvRevenue.list("day >= ?1 and day <= ?2 order by pdvNumber, day", FIRST, LAST))
                .thenReturn(List.of(day("10001", "100.00", 2), day("10001", "50.00", 1),
                        day("10002", "30.00", 1), day("01011", "7.00", 1), day("10003", "5.00", 1),
                        day("99999", "1.00", 1), day("10004", "2.00", 1)));
        Enseigne portuguese = new Enseigne();
        portuguese.code = "IP";
        portuguese.countryCode = "PT";
        Enseigne stateless = new Enseigne();
        stateless.code = "IX";
        stub(panache, pdv(1L, "10001", "Porto", "IP", "AD1"), "pdvNumber", "10001");
        stub(panache, pdv(2L, "10002", "Braga", "IZ", "AD1"), "pdvNumber", "10002");
        stub(panache, pdv(3L, "10003", "Lisboa", null, null), "pdvNumber", "10003");
        stub(panache, pdv(4L, "10004", "Faro", "IX", "AD2"), "pdvNumber", "10004");
        stub(panache, null, "pdvNumber", "01011");
        stub(panache, null, "pdvNumber", "99999");
        stub(panache, portuguese, "code", "IP");
        stub(panache, null, "code", "IZ");
        stub(panache, stateless, "code", "IX");
        PdvHistory left = new PdvHistory();
        left.pdvId = 2L;
        stub(panache, left, "kind = ?1 and oldValue = ?2", PdvHistory.Kind.NUMBER, "01011");
        stub(panache, null, "kind = ?1 and oldValue = ?2", PdvHistory.Kind.NUMBER, "99999");
        panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(pdv(2L, "10002", "Braga", "IZ", "AD1"));
    }

    /**
     * The definition names its code, its title, its requirement, its family,
     * its seven columns, its chart and its option.
     */
    @Test
    void theDefinitionIsComplete() {
        PdvRevenueReport report = new PdvRevenueReport();
        assertEquals("pdv-revenue", report.code());
        assertEquals("Chiffre d'affaires par point de vente et par adhérent", report.label());
        assertEquals("BO-02-05-05", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(7, report.columns().size());
        assertEquals(6, report.chartColumn());
        assertEquals(1, report.chartLabelColumn());
        assertEquals("Adhérent (code), * : un total par adhérent — vide : tous", report.optionLabel());
    }

    /**
     * Without option every point of sale comes out, sorted by adhérent, a
     * former number named after its holder.
     */
    @Test
    void everyPointOfSaleIsSortedByAdherent() {
        PdvRevenueReport report = new PdvRevenueReport();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            fixture(panache);
            List<List<Object>> rows = report.rows(october(null));
            assertEquals(List.of(
                    Arrays.asList("AD1", "10001", "Porto", "IP", "PT", 3L, new BigDecimal("150.00")),
                    Arrays.asList("AD1", "10002", "Braga", "IZ", "", 1L, new BigDecimal("30.00")),
                    Arrays.asList("AD1", "01011", "Braga (ancien n°, actuel 10002)", "IZ", "", 1L,
                            new BigDecimal("7.00")),
                    Arrays.asList("AD2", "10004", "Faro", "IX", "", 1L, new BigDecimal("2.00")),
                    Arrays.asList("—", "10003", "Lisboa", "", "", 1L, new BigDecimal("5.00")),
                    Arrays.asList("—", "99999", "", "", "", 1L, new BigDecimal("1.00"))), rows);
        }
    }

    /**
     * The option {@code *} folds the report into one line per adhérent, with
     * its points of sale, its tickets and its revenue.
     */
    @Test
    void theStarFoldsOneLinePerAdherent() {
        PdvRevenueReport report = new PdvRevenueReport();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            fixture(panache);
            List<List<Object>> rows = report.rows(october(" * "));
            assertEquals(List.of(
                    Arrays.asList("AD1", "10001, 10002, 01011", "", "", "", 5L, new BigDecimal("187.00")),
                    Arrays.asList("AD2", "10004", "", "", "", 1L, new BigDecimal("2.00")),
                    Arrays.asList("—", "10003, 99999", "", "", "", 2L, new BigDecimal("6.00"))), rows);
        }
    }

    /**
     * The option narrows the report to one adhérent, whatever its case.
     */
    @Test
    void theOptionKeepsOneAdherent() {
        PdvRevenueReport report = new PdvRevenueReport();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            fixture(panache);
            List<List<Object>> rows = report.rows(october(" ad2 "));
            assertEquals(1, rows.size());
            assertEquals("10004", rows.get(0).get(1));
        }
    }
}
