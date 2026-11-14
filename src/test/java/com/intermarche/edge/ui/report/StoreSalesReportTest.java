package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link StoreSalesReport}: the store's indicators over the
 * cashiers' figures, the training sales and the promotions granted, read or
 * zero, the tenders named by the referential with the cash discrepancy beside
 * the cash, and the metadata.
 */
class StoreSalesReportTest {

    /**
     * Runs the report.
     *
     * @param stub the stub
     * @return the rows
     */
    private List<List<Object>> run(QueryStub stub) {
        StoreSalesReport report = new StoreSalesReport();
        report.entityManager = stub.entityManager;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            CashierStateReportTest.tenders(panache);
            return report.rows(new ReportCriteria());
        }
    }

    /**
     * Finds an indicator.
     *
     * @param rows the rows
     * @param indicator the indicator
     * @return its row
     */
    private List<Object> find(List<List<Object>> rows, String indicator) {
        for (List<Object> row : rows) {
            if (indicator.equals(row.get(1))) {
                return row;
            }
        }
        throw new AssertionError(indicator);
    }

    /**
     * The indicators of a period with a sale, training sales and promotions;
     * the tenders, the card named by the referential and the cash with its
     * discrepancy.
     */
    @Test
    void theIndicatorsOfAPeriod() {
        Ticket ticket = CashierPerformanceReportTest.ticket("111", "30.00", 6);
        ticket.payments = List.<TicketPayment>of(new CardPayment(new BigDecimal("30.00")));
        QueryStub stub = new QueryStub()
                .answer("from TicketLineValuation v", rows(row(3, new BigDecimal("-4.00"))))
                .answer("t.status = :closed", List.of(ticket))
                .answer("from TrainingTicket t", rows(row(2L, new BigDecimal("15.00"))));
        List<List<Object>> rows = run(stub);
        assertEquals(26, rows.size());
        assertEquals(new BigDecimal("30.00"), find(rows, "Ventes nettes").get(3));
        assertEquals(2L, find(rows, "Ventes mode école").get(2));
        assertEquals(new BigDecimal("15.00"), find(rows, "Ventes mode école").get(3));
        assertEquals(3L, find(rows, "Promotions remboursées").get(2));
        assertEquals(1L, find(rows, "Comptage client").get(2));
        List<Object> basket = find(rows, "Panier moyen");
        assertEquals(new BigDecimal("30.00"), basket.get(3));
        assertEquals(new BigDecimal("6.00"), basket.get(4));
        assertEquals(new BigDecimal("6.00"), find(rows, "Articles par minute").get(4));
        List<Object> card = find(rows, "Carte bancaire");
        assertEquals("Détail par moyen de paiement", card.get(0));
        assertEquals(1L, card.get(2));
        assertEquals(BigDecimal.ZERO, card.get(5));
        List<Object> cash = find(rows, "Espèces");
        assertEquals(0L, cash.get(2));
        assertEquals(BigDecimal.ZERO, cash.get(3));
        assertEquals(BigDecimal.ZERO, cash.get(5));
    }

    /**
     * An empty period: the aggregates answer nothing or null sums, every
     * indicator reads zero.
     */
    @Test
    void anEmptyPeriod() {
        QueryStub stub = new QueryStub().answer("from TrainingTicket t", rows(row(null, null)));
        List<List<Object>> rows = run(stub);
        assertEquals(25, rows.size());
        assertEquals(0L, find(rows, "Ventes mode école").get(2));
        assertEquals(BigDecimal.ZERO, find(rows, "Ventes mode école").get(3));
        assertEquals(0L, find(rows, "Promotions remboursées").get(2));
        assertEquals(BigDecimal.ZERO, find(rows, "Panier moyen").get(3));
        assertEquals(BigDecimal.ZERO, find(rows, "Montant des prélèvements").get(3));
    }

    /**
     * Withdrawals are summed over every tender.
     */
    @Test
    void theWithdrawalsAreSummed() {
        com.intermarche.edge.domain.session.CashMovement cash = new com.intermarche.edge.domain.session.CashMovement();
        cash.type = com.intermarche.edge.domain.session.CashMovement.MovementType.WITHDRAWAL;
        cash.amount = new BigDecimal("50.00");
        com.intermarche.edge.domain.session.CashMovement cheque = new com.intermarche.edge.domain.session.CashMovement();
        cheque.type = com.intermarche.edge.domain.session.CashMovement.MovementType.WITHDRAWAL;
        cheque.amount = new BigDecimal("20.00");
        cheque.paymentMethod = "CHEQUE";
        List<List<Object>> rows = run(new QueryStub().answer("from CashMovement m", List.of(cash, cheque)));
        assertEquals(2L, find(rows, "Montant des prélèvements").get(2));
        assertEquals(new BigDecimal("70.00"), find(rows, "Montant des prélèvements").get(3));
    }

    /**
     * The metadata: identity, columns, chart and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        StoreSalesReport report = new StoreSalesReport();
        assertEquals("store-sales", report.code());
        assertEquals("État ventes magasin", report.label());
        assertEquals("BO-06-04-05", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(6, report.columns().size());
        assertEquals(3, report.chartColumn());
        assertEquals(1, report.chartLabelColumn());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND), report.filters());
    }
}
