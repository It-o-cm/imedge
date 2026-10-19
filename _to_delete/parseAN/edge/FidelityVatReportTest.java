package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.payment.FidelityPayment;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
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
 * Unit tests for {@link FidelityVatReport} ({@code BO-06-07-02}).
 *
 * <p>The regime finder is a custom static of {@link VatRate} and is
 * intercepted with {@code mockStatic}; the query runs on a mocked
 * {@link EntityManager}. Branch enumeration (both arms): a ticket settled by
 * a purse and one settled without; a cancelled line, which weighs nothing; a
 * rate no regime carries, which is named by its percentage; the empty report,
 * which produces no total row; and the register filter.
 */
class FidelityVatReportTest {

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
     * Builds a report wired to a mocked entity manager returning tickets.
     *
     * @param tickets the tickets the query yields
     * @return the report under test
     */
    private FidelityVatReport reportOver(List<Ticket> tickets) {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Ticket> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t"), eq(Ticket.class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(tickets);
        FidelityVatReport report = new FidelityVatReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds a line taxed at a rate for a total including tax.
     *
     * @param rate the VAT rate as a fraction
     * @param total the line total including tax
     * @param cancelled whether the cashier cancelled it
     * @return the line
     */
    private TicketLine line(String rate, String total, boolean cancelled) {
        TicketLine line = new TicketLine();
        line.vatRate = new BigDecimal(rate);
        line.totalPrice = new BigDecimal(total);
        line.cancelled = cancelled;
        return line;
    }

    /**
     * Builds a closed ticket over lines, settled by a loyalty purse or not.
     *
     * @param fidelity what the purse settled, or null for none
     * @param lines the sold lines
     * @return the ticket
     */
    private Ticket ticket(String fidelity, TicketLine... lines) {
        Ticket ticket = new Ticket();
        ticket.lines = new ArrayList<>(List.of(lines));
        ticket.payments = new ArrayList<>();
        ticket.payments.add(new CashPayment(new BigDecimal("1.00"), new BigDecimal("1.00")));
        if (fidelity != null) {
            ticket.payments.add(new FidelityPayment(new BigDecimal(fidelity)));
        }
        return ticket;
    }

    /**
     * Builds a regime carrying a number and a label.
     *
     * @param number the regime number
     * @param label the regime label
     * @return the regime
     */
    private VatRate regime(int number, String label) {
        VatRate rate = new VatRate();
        rate.number = number;
        rate.label = label;
        return rate;
    }

    /**
     * The report names itself, its requirement and its family.
     */
    @Test
    void theReportNamesItself() {
        FidelityVatReport report = reportOver(List.of());
        assertEquals("fidelity-vat", report.code());
        assertEquals("TVA du règlement fidélité", report.label());
        assertEquals("BO-06-07-02", report.requirement());
        assertEquals(ReportFamily.TAX, report.family());
        assertEquals(8, report.columns().size());
    }

    /**
     * BO-06-07-02: the loyalty settlement is apportioned pro rata of what
     * each VAT code weighs in the ticket, and the cancelled line weighs
     * nothing. A total row closes the table.
     */
    @Test
    void theSettlementIsApportionedAcrossTheCodes() {
        FidelityVatReport report = reportOver(List.of(
                ticket("10.00", line("0.2000", "60.00", false),
                        line("0.0550", "40.00", false),
                        line("0.2000", "99.00", true))));
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.2000")))
                    .thenReturn(regime(1, "Taux normal"));
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.0550")))
                    .thenReturn(regime(2, "Taux réduit"));
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(3, rows.size());
            assertEquals("1", rows.get(0).get(0));
            assertEquals("Taux normal", rows.get(0).get(1));
            assertEquals(new BigDecimal("60.00"), rows.get(0).get(2));
            assertEquals(new BigDecimal("6.00"), rows.get(0).get(6));
            assertEquals(new BigDecimal("4.00"), rows.get(1).get(6));
            assertEquals("Total", rows.get(2).get(1));
            assertEquals(new BigDecimal("10.00"), rows.get(2).get(6));
        }
    }

    /**
     * BO-06-07-02: a ticket no purse settled fills the sales columns and
     * leaves the loyalty ones at zero.
     */
    @Test
    void aTicketWithoutAPurseSettlesNothing() {
        FidelityVatReport report = reportOver(List.of(
                ticket(null, line("0.2000", "60.00", false))));
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(() -> VatRate.findByRate(new BigDecimal("0.2000")))
                    .thenReturn(regime(1, "Taux normal"));
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(2, rows.size());
            assertEquals(BigDecimal.ZERO, rows.get(0).get(6));
            assertEquals(BigDecimal.ZERO, rows.get(0).get(5));
        }
    }

    /**
     * A rate no regime carries is named by its own percentage rather than
     * left blank, and a report with no ticket has no total row.
     */
    @Test
    void anUnknownRateIsNamedByItsPercentage() {
        FidelityVatReport report = reportOver(List.of(
                ticket(null, line("0.1300", "20.00", false))));
        try (MockedStatic<VatRate> rates = mockStatic(VatRate.class)) {
            rates.when(() -> VatRate.findByRate(any())).thenReturn(null);
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(2, rows.size());
            assertEquals("", rows.get(0).get(0));
            assertEquals("13.00 %", rows.get(0).get(1));
        }
        assertTrue(reportOver(List.of()).rows(new ReportCriteria()).isEmpty());
    }

    /**
     * An edition naming registers adds the clause; one naming none does not.
     */
    @Test
    void theRegisterFilterIsAddedOnlyWhenAsked() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Ticket> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t"), eq(Ticket.class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        FidelityVatReport report = new FidelityVatReport();
        report.entityManager = entityManager;
        ReportCriteria filtered = new ReportCriteria();
        filtered.terminals.add("CAISSE1");
        assertTrue(report.rows(filtered).isEmpty());
        org.mockito.Mockito.verify(entityManager)
                .createQuery(contains("t.terminalId in :terminals"), eq(Ticket.class));
    }
}
