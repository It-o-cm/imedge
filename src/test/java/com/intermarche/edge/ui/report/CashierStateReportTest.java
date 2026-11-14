package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.payment.TenderDefinition;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashierStateReport}: the summary and the detail, an
 * empty period, every block of a cashier — activity, sales, tenders, VAT recap
 * under each regime naming, cancellations, statistics — the shares of a
 * session, the zero divisors, and the metadata.
 */
class CashierStateReportTest {

    /**
     * Builds the figures of one cashier.
     *
     * @return the figures
     */
    private CashierActivity.Figures figures() {
        CashierActivity.Figures f = new CashierActivity.Figures();
        f.badge = "111";
        f.firstName = "Jean";
        f.lastName = "Dupont";
        CashierActivity.Basket basket = new CashierActivity.Basket();
        basket.items = 4;
        basket.customerSeconds = 600;
        basket.scanSeconds = 240;
        basket.paySeconds = 60;
        f.baskets.add(basket);
        f.takings = new BigDecimal("40.00");
        f.tenders.put("CARD", new BigDecimal("40.00"));
        f.withdrawals.put("CASH", new BigDecimal("100.00"));
        f.floats = new BigDecimal("150.00");
        f.sessionSeconds = 3600;
        f.activeSeconds = 1800;
        f.cashOperationSeconds = 120;
        f.vat.add(new BigDecimal("0.2"), 1, new BigDecimal("12.00"));
        f.vat.add(new BigDecimal("0.055"), null, new BigDecimal("10.55"));
        f.vat.add(new BigDecimal("0.1"), null, new BigDecimal("11.00"));
        return f;
    }

    /**
     * Stubs the tender referential: the card administered as « Carte
     * bancaire », every other tender unknown.
     *
     * @param panache the static mock of the entities
     */
    @SuppressWarnings("unchecked")
    static void tenders(MockedStatic<PanacheEntityBase> panache) {
        TenderDefinition card = new TenderDefinition();
        card.label = "Carte bancaire";
        panache.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class))).thenAnswer(call -> {
            PanacheQuery<Object> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn("CARD".equals(call.getArguments()[1]) ? card : null);
            return query;
        });
    }

    /**
     * Writes the blocks of the figures, the regimes resolved three ways.
     *
     * @param f the figures
     * @return the rows
     */
    private List<List<Object>> block(CashierActivity.Figures f) {
        VatRate normal = new VatRate();
        normal.number = 1;
        normal.label = "Normal";
        VatRate bare = new VatRate();
        List<List<Object>> rows = new ArrayList<>();
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            tenders(panache);
            rates.when(() -> VatRate.resolve(new BigDecimal("0.2"), 1)).thenReturn(normal);
            rates.when(() -> VatRate.resolve(new BigDecimal("0.055"), null)).thenReturn(bare);
            new CashierStateReport().block(rows, f);
        }
        return rows;
    }

    /**
     * Finds the row of an item in a block.
     *
     * @param rows the rows
     * @param section the block
     * @param item the item
     * @return the row
     */
    private List<Object> find(List<List<Object>> rows, String section, String item) {
        for (List<Object> row : rows) {
            if (section.equals(row.get(2)) && item.equals(row.get(3))) {
                return row;
            }
        }
        throw new AssertionError(section + " / " + item);
    }

    /**
     * Every block of a cashier: activity, sales, the tenders and the cash
     * drawer's float, contributions and outflows, the tenders named by their
     * administered label or their French name, the VAT recap, the
     * cancellations and the statistics with their shares.
     */
    @Test
    void everyBlockIsWritten() {
        List<List<Object>> rows = block(figures());
        assertEquals(33, rows.size());
        assertEquals("Jean Dupont", rows.get(0).get(1));
        assertEquals(1L, find(rows, "Activité", "Nombre de clients").get(4));
        assertEquals(new BigDecimal("40.00"), find(rows, "Carte bancaire", "Ventes").get(5));
        assertEquals(BigDecimal.ZERO, find(rows, "Carte bancaire", "Prélèvement").get(5));
        assertEquals(new BigDecimal("100.00"), find(rows, "Espèces", "Prélèvement").get(5));
        assertEquals(new BigDecimal("150.00"), find(rows, "Espèces", "Fond de caisse").get(5));
        List<Object> normal = find(rows, "Récapitulatif TVA", "1 Normal");
        assertEquals(new BigDecimal("12.00"), normal.get(5));
        assertEquals(new BigDecimal("10.00"), normal.get(6));
        assertEquals(new BigDecimal("2.00"), normal.get(7));
        assertEquals(new BigDecimal("10.55"), find(rows, "Récapitulatif TVA", "5.50 %").get(5));
        assertEquals(new BigDecimal("11.00"), find(rows, "Récapitulatif TVA", "10.00 %").get(5));
        assertEquals(new BigDecimal("4.00"), find(rows, "Statistiques", "Nombre moyen d'articles par client").get(8));
        List<Object> active = find(rows, "Statistiques", "Session active");
        assertEquals("00:30:00", active.get(9));
        assertEquals(new BigDecimal("50.0"), active.get(10));
        assertEquals(new BigDecimal("33.3"), find(rows, "Statistiques", "Inactivité").get(10));
        assertEquals(new BigDecimal("2.00"), find(rows, "Statistiques", "Clients par heure").get(8));
        assertEquals(new BigDecimal("80.00"), find(rows, "Statistiques", "Ventes par heure").get(5));
        assertEquals(new BigDecimal("1.00"), find(rows, "Statistiques", "Articles par minute").get(8));
    }

    /**
     * A cashier unlocked longer than the session lasted reads its shares
     * against the active time: none exceeds the whole.
     */
    @Test
    void theSharesNeverExceedTheWhole() {
        CashierActivity.Figures f = figures();
        f.sessionSeconds = 900;
        List<List<Object>> rows = block(f);
        assertEquals(new BigDecimal("50.0"), find(rows, "Statistiques", "Durée d'ouverture de la session").get(10));
        assertEquals(new BigDecimal("100.0"), find(rows, "Statistiques", "Session active").get(10));
    }

    /**
     * A cashier without customer, session or active time reads zero ratios
     * and shares.
     */
    @Test
    void zeroDivisorsReadZero() {
        CashierActivity.Figures f = new CashierActivity.Figures();
        List<List<Object>> rows = block(f);
        assertEquals(BigDecimal.ZERO, find(rows, "Statistiques", "Nombre moyen d'articles par client").get(8));
        assertEquals(BigDecimal.ZERO, find(rows, "Statistiques", "Session active").get(10));
        assertEquals(BigDecimal.ZERO, find(rows, "Statistiques", "Ventes par heure").get(5));
        assertEquals(BigDecimal.ZERO, CashierStateReport.ratio(5, 0));
        assertEquals(new BigDecimal("2.50"), CashierStateReport.ratio(5, 2));
    }

    /**
     * The summary writes the recap only, the detail each cashier then the
     * recap, an empty period nothing.
     */
    @Test
    void summaryDetailAndEmpty() {
        Ticket ticket = new Ticket();
        Employee cashier = new Employee();
        cashier.badgeId = "111";
        cashier.lastName = "Dupont";
        ticket.cashier = cashier;
        ticket.closingDate = java.time.LocalDateTime.now();
        CashierStateReport report = new CashierStateReport();
        report.entityManager = new QueryStub().answer("t.status = :closed", List.of(ticket)).entityManager;
        List<List<Object>> summary;
        List<List<Object>> detail;
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            tenders(panache);
            rates.when(() -> VatRate.resolve(any(), any())).thenReturn(null);
            summary = report.rows(new ReportCriteria());
            ReportCriteria detailed = new ReportCriteria();
            detailed.option = "DETAIL";
            detail = report.rows(detailed);
        }
        assertEquals("Toutes les caissières", summary.get(0).get(1));
        assertEquals(summary.size() * 2, detail.size());
        assertEquals("111", detail.get(0).get(0));
        report.entityManager = new QueryStub().entityManager;
        assertTrue(report.rows(new ReportCriteria()).isEmpty());
    }

    /**
     * The metadata: identity, columns, chart, options and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        CashierStateReport report = new CashierStateReport();
        assertEquals("cashier-state", report.code());
        assertEquals("État caissière", report.label());
        assertEquals("BO-06-06-03", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(11, report.columns().size());
        assertEquals(5, report.chartColumn());
        assertEquals(3, report.chartLabelColumn());
        assertEquals("Édition", report.optionLabel());
        assertEquals(2, report.optionChoices().size());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS),
                report.filters());
    }
}
