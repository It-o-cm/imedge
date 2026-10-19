package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketFidelityLine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromotionCashierReport} ({@code BO-06-08-01}).
 *
 * <p>Branch enumeration (both arms): a ticket carrying advantages and one
 * carrying none — null list included; two tickets of the same promotion and
 * cashier, which add up; the same promotion under two cashiers, which do
 * not; a cashier selection that keeps and that drops; a ticket with no
 * cashier; a null amount and a null total, which weigh nothing; and the
 * zero-sales guard of the share.
 */
class PromotionCashierReportTest {

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
    private PromotionCashierReport reportOver(List<Ticket> tickets) {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Ticket> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t"), eq(Ticket.class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(tickets);
        PromotionCashierReport report = new PromotionCashierReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds an employee carrying a badge and a surname.
     *
     * @param badgeId the badge
     * @param lastName the surname
     * @return the employee
     */
    private Employee employee(String badgeId, String lastName) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        employee.lastName = lastName;
        employee.firstName = "";
        return employee;
    }

    /**
     * Builds an advantage line.
     *
     * @param ruleCode the promotion's rule code
     * @param label what the engine called it
     * @param amount what it gave back, or null
     * @return the advantage line
     */
    private TicketFidelityLine advantage(String ruleCode, String label, String amount) {
        TicketFidelityLine line = new TicketFidelityLine();
        line.ruleCode = ruleCode;
        line.label = label;
        line.amount = amount == null ? null : new BigDecimal(amount);
        return line;
    }

    /**
     * Builds a closed ticket.
     *
     * @param cashier the cashier, possibly null
     * @param total the ticket total including tax, or null
     * @param advantages the advantage lines, or null for none
     * @return the ticket
     */
    private Ticket ticket(Employee cashier, String total, List<TicketFidelityLine> advantages) {
        Ticket ticket = new Ticket();
        ticket.cashier = cashier;
        ticket.totalIncludingTax = total == null ? null : new BigDecimal(total);
        ticket.fidelityLines = advantages == null ? null : new ArrayList<>(advantages);
        return ticket;
    }

    /**
     * The report names itself, its requirement and its family.
     */
    @Test
    void theReportNamesItself() {
        PromotionCashierReport report = reportOver(List.of());
        assertEquals("promotion-cashiers", report.code());
        assertEquals("Ventes promotionnelles par caissière", report.label());
        assertEquals("BO-06-08-01", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(8, report.columns().size());
    }

    /**
     * BO-06-08-01: two tickets of the same promotion and cashier add up —
     * their firings, their net sales and their discount — and the share is
     * the discount over the sales.
     */
    @Test
    void thePromotionAndTheCashierAddUp() {
        Employee alice = employee("007", "Martin");
        PromotionCashierReport report = reportOver(List.of(
                ticket(alice, "100.00", List.of(advantage("P1", "Palier 50", "5.00"))),
                ticket(alice, "100.00", List.of(advantage("P1", "Palier 50", "5.00")))));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals("P1", rows.get(0).get(0));
        assertEquals("Palier 50", rows.get(0).get(1));
        assertEquals("007", rows.get(0).get(2));
        assertEquals(2L, rows.get(0).get(4));
        assertEquals(new BigDecimal("200.00"), rows.get(0).get(5));
        assertEquals(new BigDecimal("10.00"), rows.get(0).get(6));
        assertEquals(new BigDecimal("5.00"), rows.get(0).get(7));
    }

    /**
     * BO-06-08-01: the same promotion under two cashiers makes two rows, and
     * the rows come out promotion by promotion.
     */
    @Test
    void twoCashiersMakeTwoRowsOrderedByPromotion() {
        PromotionCashierReport report = reportOver(List.of(
                ticket(employee("008", "Durand"), "50.00",
                        List.of(advantage("P2", "Boost", "2.00"))),
                ticket(employee("007", "Martin"), "50.00",
                        List.of(advantage("P1", "Palier", "1.00")))));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(2, rows.size());
        assertEquals("P1", rows.get(0).get(0));
        assertEquals("P2", rows.get(1).get(0));
    }

    /**
     * A ticket carrying no advantage — an empty list or none at all — makes
     * no row, and a ticket with no cashier still counts its promotion.
     */
    @Test
    void aTicketWithoutAdvantagesMakesNoRow() {
        PromotionCashierReport report = reportOver(List.of(
                ticket(employee("007", "Martin"), "50.00", List.of()),
                ticket(employee("007", "Martin"), "50.00", null)));
        assertTrue(report.rows(new ReportCriteria()).isEmpty());
        PromotionCashierReport anonymous = reportOver(List.of(
                ticket(null, "50.00", List.of(advantage("P1", "Palier", "1.00")))));
        List<List<Object>> rows = anonymous.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).get(2));
        assertEquals("", rows.get(0).get(3));
    }

    /**
     * BO-06-08-01: a cashier selection keeps only those cashiers, and drops
     * a ticket carrying none.
     */
    @Test
    void aCashierSelectionKeepsOnlyThose() {
        PromotionCashierReport report = reportOver(List.of(
                ticket(employee("007", "Martin"), "50.00",
                        List.of(advantage("P1", "Palier", "1.00"))),
                ticket(employee("008", "Durand"), "50.00",
                        List.of(advantage("P1", "Palier", "1.00"))),
                ticket(null, "50.00", List.of(advantage("P1", "Palier", "1.00")))));
        ReportCriteria criteria = new ReportCriteria();
        criteria.cashiers.add("007");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        assertEquals("007", rows.get(0).get(2));
    }

    /**
     * A null amount and a null total weigh nothing, and sales of zero answer
     * a share of zero rather than a division by nothing.
     */
    @Test
    void nullAmountsWeighNothingAndZeroSalesShareNothing() {
        PromotionCashierReport report = reportOver(List.of(
                ticket(employee("007", "Martin"), null,
                        List.of(advantage(null, null, null)))));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).get(0));
        assertEquals("", rows.get(0).get(1));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(5));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(6));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(7));
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
        PromotionCashierReport report = new PromotionCashierReport();
        report.entityManager = entityManager;
        ReportCriteria filtered = new ReportCriteria();
        filtered.terminals.add("CAISSE1");
        assertTrue(report.rows(filtered).isEmpty());
        org.mockito.Mockito.verify(entityManager)
                .createQuery(contains("t.terminalId in :terminals"), eq(Ticket.class));
    }
}
