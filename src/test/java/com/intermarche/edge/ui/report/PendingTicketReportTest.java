package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PendingTicketReport}.
 * <p>
 * The report builds one JPQL query on a mocked {@link EntityManager}, optionally
 * appending a {@code terminalId in :terminals} clause, then maps each returned
 * row to a display line while defending every nullable column. Every
 * collaborator is a Mockito mock, result rows are fed as raw {@code Object[]}
 * and assertions bear absolute expected values. The tests cover both arms of the
 * two {@code terminals.isEmpty()} guards, the entered and not-entered arms of the
 * mapping loop and both arms of every column ternary (terminal, cashier badge,
 * cashier name, ticket number and amount).
 */
class PendingTicketReportTest {

    /**
     * Creates a fresh {@link TypedQuery} mock, confining the unchecked cast to a
     * single suppression point.
     *
     * @param <T> the query result type
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Builds a {@link PendingTicketReport} whose {@link EntityManager} field is
     * wired to the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return a report ready under test
     */
    private PendingTicketReport reportWith(EntityManager entityManager) {
        PendingTicketReport report = new PendingTicketReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Wires the pending-ticket query on the given entity-manager mock so that it
     * returns a query echoing itself from any {@code setParameter} call and
     * yielding the supplied rows.
     *
     * @param entityManager the mocked entity manager
     * @param rows the rows the query returns
     * @return the wired typed-query mock, for further verification
     */
    private TypedQuery<Object[]> stubQuery(EntityManager entityManager, List<Object[]> rows) {
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t"), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        return query;
    }

    /**
     * Builds a real {@link Employee} carrying the given badge and name, so the
     * public {@code badgeId} field and the {@code getFullName()} accessor both
     * resolve to concrete values.
     *
     * @param badgeId the badge identifier
     * @param firstName the first name
     * @param lastName the last name
     * @return the populated employee
     */
    private Employee employee(String badgeId, String firstName, String lastName) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        employee.firstName = firstName;
        employee.lastName = lastName;
        return employee;
    }

    /**
     * The report's code is the stable identifier of the pending-ticket state.
     */
    @Test
    void codeIsPendingTickets() {
        assertEquals("pending-tickets", reportWith(mock(EntityManager.class)).code());
    }

    /**
     * The report's label is its human title.
     */
    @Test
    void labelIsTitle() {
        assertEquals("État des tickets en attente", reportWith(mock(EntityManager.class)).label());
    }

    /**
     * The report answers the pending-ticket requirement.
     */
    @Test
    void requirementIsBo060601() {
        assertEquals("BO-06-06-01", reportWith(mock(EntityManager.class)).requirement());
    }

    /**
     * The report belongs to the cashiers family.
     */
    @Test
    void familyIsCashiers() {
        assertEquals(ReportFamily.CASHIERS, reportWith(mock(EntityManager.class)).family());
    }

    /**
     * The report exposes the seven columns the requirement enumerates, in order.
     */
    @Test
    void columnsEnumerateTheWaitingCart() {
        List<ReportColumn> columns = reportWith(mock(EntityManager.class)).columns();
        assertEquals(9, columns.size());
        assertEquals("when", columns.get(0).key());
        assertEquals("terminal", columns.get(1).key());
        assertEquals("badge", columns.get(2).key());
        assertEquals("name", columns.get(3).key());
        assertEquals("number", columns.get(4).key());
        assertEquals("items", columns.get(5).key());
        assertEquals("amount", columns.get(6).key());
        assertEquals("recalledAt", columns.get(7).key());
        assertEquals("recalledOn", columns.get(8).key());
    }

    /**
     * With a terminals filter and a fully populated row, {@code rows()} takes the
     * true arm of both {@code terminals.isEmpty()} guards — appending the
     * {@code in :terminals} clause and binding it — enters the loop and takes the
     * false arm of every column ternary, mapping each concrete value through.
     */
    @Test
    void rowsWithTerminalsFilterAndFullRow() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 8, 3, 9, 5);
        Employee cashier = employee("B01", "Alice", "Martin");
        TypedQuery<Object[]> query = stubQuery(entityManager, List.<Object[]>of(
                new Object[]{when, "CAISSE1", cashier, "T-42", 3, new BigDecimal("12.50"),
                        null, null, null}));
        PendingTicketReport report = reportWith(entityManager);
        ReportCriteria criteria = new ReportCriteria();
        criteria.terminals.add("CAISSE1");
        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        List<List<Object>> rows = report.rows(criteria);
        verify(entityManager).createQuery(jpqlCaptor.capture(), eq(Object[].class));
        assertTrue(jpqlCaptor.getValue().contains("and t.terminalId in :terminals"));
        verify(query).setParameter("terminals", criteria.terminals);
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals(when, row.get(0));
        assertEquals("CAISSE1", row.get(1));
        assertEquals("B01", row.get(2));
        assertEquals("Alice Martin", row.get(3));
        assertEquals("T-42", row.get(4));
        assertEquals(3L, row.get(5));
        assertEquals(new BigDecimal("12.50"), row.get(6));
        assertEquals("", row.get(7));
        assertEquals("", row.get(8));
    }

    /**
     * BO-06-06-01: the RECALLED option edits the carts that came back, with
     * the date and the lane of their recall, and the moment shown is the
     * PARKING and no longer the creation.
     */
    @Test
    void rowsWithTheRecalledOption() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime created = LocalDateTime.of(2026, 10, 15, 8, 0);
        LocalDateTime parked = LocalDateTime.of(2026, 10, 15, 8, 30);
        LocalDateTime recalled = LocalDateTime.of(2026, 10, 15, 9, 10);
        Employee cashier = employee("B10", "Eve", "Blanc");
        stubQuery(entityManager, List.<Object[]>of(
                new Object[]{created, "CAISSE1", cashier, "T-55", 2, new BigDecimal("9.90"),
                        parked, recalled, "CAISSE4"}));
        PendingTicketReport report = reportWith(entityManager);
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = "RECALLED";
        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        List<List<Object>> rows = report.rows(criteria);
        verify(entityManager).createQuery(jpqlCaptor.capture(), eq(Object[].class));
        assertTrue(jpqlCaptor.getValue().contains("and t.resumedAt is not null"));
        assertEquals(parked, rows.get(0).get(0));
        assertEquals(recalled, rows.get(0).get(7));
        assertEquals("CAISSE4", rows.get(0).get(8));
    }

    /**
     * BO-06-06-01: the default edits the carts still waiting, and BOTH edits
     * them all — the three arms of the option.
     */
    @Test
    void rowsFilterOnTheRecallOption() {
        EntityManager pending = mock(EntityManager.class);
        stubQuery(pending, List.of());
        ArgumentCaptor<String> waiting = ArgumentCaptor.forClass(String.class);
        reportWith(pending).rows(new ReportCriteria());
        verify(pending).createQuery(waiting.capture(), eq(Object[].class));
        assertTrue(waiting.getValue().contains("and t.resumedAt is null"));

        EntityManager all = mock(EntityManager.class);
        stubQuery(all, List.of());
        ReportCriteria both = new ReportCriteria();
        both.option = "ALL";
        ArgumentCaptor<String> everything = ArgumentCaptor.forClass(String.class);
        reportWith(all).rows(both);
        verify(all).createQuery(everything.capture(), eq(Object[].class));
        // The BOTH arm appends no recall filter — but the base clause
        // "(t.status = :parked or t.resumedAt is not null)" IS the both-halves
        // filter of BO-06-06-01, so what must be absent is the APPENDED one.
        assertTrue(!everything.getValue().contains("and t.resumedAt is null"));
        assertTrue(!everything.getValue().contains("and t.resumedAt is not null"));
    }

    /**
     * BO-06-06-01: the option names the three halves and the screen labels it.
     */
    @Test
    void optionNamesTheThreeHalves() {
        PendingTicketReport report = new PendingTicketReport();
        assertEquals("Tickets édités", report.optionLabel());
        assertEquals(List.of("WAITING", "RECALLED", "ALL"), report.optionChoices());
    }

    /**
     * With no terminals filter and an empty result list — a day with zero parked
     * carts — {@code rows()} takes the false arm of both {@code terminals.isEmpty()}
     * guards, never binds the terminals parameter and never enters the loop,
     * returning an empty list.
     */
    @Test
    void rowsWithoutTerminalsFilterAndEmptyResult() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = stubQuery(entityManager, List.of());
        PendingTicketReport report = reportWith(entityManager);
        ReportCriteria criteria = new ReportCriteria();
        ArgumentCaptor<String> jpqlCaptor = ArgumentCaptor.forClass(String.class);
        List<List<Object>> rows = report.rows(criteria);
        verify(entityManager).createQuery(jpqlCaptor.capture(), eq(Object[].class));
        assertTrue(rows.isEmpty());
        assertTrue(!jpqlCaptor.getValue().contains("in :terminals"));
        verify(query, never()).setParameter(eq("terminals"), any());
    }

    /**
     * A row whose amount column is null takes the true arm of the amount ternary,
     * exposing {@link BigDecimal#ZERO} instead of the missing total, while the
     * other populated columns keep their values.
     */
    @Test
    void rowsWithNullAmount() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 8, 3, 10, 0);
        Employee cashier = employee("B02", "Bob", "Durand");
        stubQuery(entityManager, List.<Object[]>of(
                new Object[]{when, "CAISSE2", cashier, "T-7", 1, null, null, null, null}));
        PendingTicketReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("CAISSE2", row.get(1));
        assertEquals("B02", row.get(2));
        assertEquals("Bob Durand", row.get(3));
        assertEquals(1L, row.get(5));
        assertEquals(BigDecimal.ZERO, row.get(6));
    }

    /**
     * A row whose cashier is null takes the true arm of both cashier ternaries,
     * exposing a blank badge and a blank name while the amount is mapped through.
     */
    @Test
    void rowsWithNullCashier() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 8, 3, 11, 15);
        stubQuery(entityManager, List.<Object[]>of(
                new Object[]{when, "CAISSE3", null, "T-9", 2, new BigDecimal("5.00"), null, null, null}));
        PendingTicketReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("CAISSE3", row.get(1));
        assertEquals("", row.get(2));
        assertEquals("", row.get(3));
        assertEquals("T-9", row.get(4));
        assertEquals(2L, row.get(5));
        assertEquals(new BigDecimal("5.00"), row.get(6));
    }

    /**
     * A row whose terminal and ticket-number columns are null takes the true arm
     * of the terminal and ticket-number ternaries, exposing a blank in each,
     * while a present cashier keeps the false arm of both cashier ternaries.
     */
    @Test
    void rowsWithNullTerminalAndNullTicketNumber() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 8, 3, 12, 30);
        Employee cashier = employee("B03", "Carol", "Petit");
        stubQuery(entityManager, List.<Object[]>of(
                new Object[]{when, null, cashier, null, 4, new BigDecimal("8.00"), null, null, null}));
        PendingTicketReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals(when, row.get(0));
        assertEquals("", row.get(1));
        assertEquals("B03", row.get(2));
        assertEquals("Carol Petit", row.get(3));
        assertEquals("", row.get(4));
        assertEquals(4L, row.get(5));
        assertEquals(new BigDecimal("8.00"), row.get(6));
    }

    /**
     * The query is always bound with the PARKED status and the resolved period
     * bounds, whichever criteria are supplied.
     */
    @Test
    void rowsBindStatusAndPeriodBounds() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = stubQuery(entityManager, List.of());
        PendingTicketReport report = reportWith(entityManager);
        ReportCriteria criteria = new ReportCriteria();
        report.rows(criteria);
        verify(query).setParameter("parked", Ticket.TicketStatus.PARKED);
        verify(query).setParameter("from", criteria.from());
        verify(query).setParameter("to", criteria.to());
    }
}
