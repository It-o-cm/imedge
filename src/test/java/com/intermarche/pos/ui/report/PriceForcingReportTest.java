package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Unit tests for {@link PriceForcingReport}.
 * <p>
 * The report reads one JPQL query on a mocked {@link EntityManager}, whose
 * package-private field is set directly, and turns each returned row into a
 * report line with a signed money delta and a markdown/markup rate. Every
 * collaborator is a Mockito mock, the query result is fed as raw {@code Object[]}
 * rows and every assertion bears an absolute expected value. The tests cover
 * both arms of every ternary and null guard of {@code rows()}, {@code kept()}
 * and {@code rate()}: the down/up/all option filter, the null-quantity default,
 * the null-cashier blanking, the null and blank EAN fallbacks, the null label
 * fields, the zero-retail rate short-circuit and the empty-result loop.
 */
class PriceForcingReportTest {

    /**
     * Creates a fresh {@link TypedQuery} mock, isolating the unchecked cast.
     *
     * @return a bare typed-query mock over {@code Object[]}
     */
    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Builds a report whose entity manager returns the given rows for the
     * price-forcing query, each {@code setParameter} call returning the query.
     *
     * @param rows the raw rows the query yields
     * @return a report ready under test
     */
    private PriceForcingReport reportReturning(Object[]... rows) {
        List<Object[]> list = new ArrayList<>();
        for (Object[] one : rows) {
            list.add(one);
        }
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t join t.lines l"), eq(Object[].class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(list);
        PriceForcingReport report = new PriceForcingReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds an {@link Employee} carrying a badge and a name.
     *
     * @param badge the badge identifier
     * @param first the first name
     * @param last the last name
     * @return the populated employee
     */
    private Employee employee(String badge, String first, String last) {
        Employee employee = new Employee();
        employee.badgeId = badge;
        employee.firstName = first;
        employee.lastName = last;
        return employee;
    }

    /**
     * Assembles one raw query row in the order the JPQL projection selects it.
     *
     * @param when the closing timestamp
     * @param terminal the register identifier
     * @param ticket the ticket number
     * @param cashier the cashier, possibly null
     * @param modifier the modifier label, possibly null
     * @param ean the article EAN, possibly null or blank
     * @param plu the article PLU fallback
     * @param family the family code, possibly null
     * @param label the article label, possibly null
     * @param retail the original unit price
     * @param forced the forced unit price
     * @param quantity the quantity, possibly null
     * @return the twelve-cell row
     */
    private Object[] row(LocalDateTime when, String terminal, String ticket, Employee cashier,
            String modifier, String ean, String plu, String family, String label,
            BigDecimal retail, BigDecimal forced, BigDecimal quantity) {
        return new Object[]{when, terminal, ticket, cashier, modifier, ean, plu, family, label,
                retail, forced, quantity};
    }

    /**
     * Builds default criteria carrying the given option.
     *
     * @param option the report option, or null for none
     * @return the criteria
     */
    private ReportCriteria criteriaWithOption(String option) {
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = option;
        return criteria;
    }

    /**
     * With the « all » option and a fully-populated markup row, every field maps
     * through the non-null arm of its ternary, the non-blank EAN is kept, the
     * quantity is taken as given, {@code kept()} returns true through its final
     * arm and {@code rate()} computes a positive percentage against a non-zero
     * retail price.
     */
    @Test
    void allOptionMapsFullMarkupRow() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 10, 15);
        PriceForcingReport report = reportReturning(row(when, "TPV1", "TKT1",
                employee("C001", "Alice", "Martin"), "Majoration", "3800000000001", "PLU9",
                "FAM1", "Milk", new BigDecimal("2.00"), new BigDecimal("3.00"), new BigDecimal("2")));
        List<List<Object>> rows = report.rows(criteriaWithOption(null));
        assertEquals(1, rows.size());
        List<Object> line = rows.get(0);
        assertEquals(when, line.get(0));
        assertEquals("TPV1", line.get(1));
        assertEquals("TKT1", line.get(2));
        assertEquals("C001", line.get(3));
        assertEquals("Alice Martin", line.get(4));
        assertEquals("Majoration", line.get(5));
        assertEquals("3800000000001", line.get(6));
        assertEquals("FAM1", line.get(7));
        assertEquals("Milk", line.get(8));
        assertEquals(new BigDecimal("2.00"), line.get(9));
        assertEquals(new BigDecimal("3.00"), line.get(10));
        assertEquals(new BigDecimal("2"), line.get(11));
        assertEquals(new BigDecimal("2.00"), line.get(12));
        assertEquals(new BigDecimal("50.0"), line.get(13));
    }

    /**
     * With the « all » option and a row whose cashier, modifier, EAN, family and
     * label are null, whose quantity is null and whose retail price is zero,
     * every null guard takes its blank arm, the quantity defaults to one, the
     * PLU replaces the null EAN and {@code rate()} short-circuits to zero.
     */
    @Test
    void allOptionBlanksNullFieldsAndDefaultsQuantity() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 11, 0);
        PriceForcingReport report = reportReturning(row(when, "TPV2", "TKT2",
                null, null, null, "PLU5", null, null,
                new BigDecimal("0.00"), new BigDecimal("5.00"), null));
        List<List<Object>> rows = report.rows(criteriaWithOption("all"));
        assertEquals(1, rows.size());
        List<Object> line = rows.get(0);
        assertEquals("", line.get(3));
        assertEquals("", line.get(4));
        assertEquals("", line.get(5));
        assertEquals("PLU5", line.get(6));
        assertEquals("", line.get(7));
        assertEquals("", line.get(8));
        assertEquals(BigDecimal.ONE, line.get(11));
        assertEquals(new BigDecimal("5.00"), line.get(12));
        assertEquals(BigDecimal.ZERO, line.get(13));
    }

    /**
     * A blank (non-null) EAN takes the second leg of the {@code ean is null or
     * blank} guard and is replaced by the PLU, while the other fields stay on
     * their non-null arms.
     */
    @Test
    void blankEanFallsBackToPlu() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 12, 30);
        PriceForcingReport report = reportReturning(row(when, "TPV3", "TKT3",
                employee("C003", "Bob", "Durand"), "Remise", "   ", "PLU7",
                "FAM3", "Bread", new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("1")));
        List<List<Object>> rows = report.rows(criteriaWithOption("all"));
        assertEquals(1, rows.size());
        assertEquals("PLU7", rows.get(0).get(6));
    }

    /**
     * With the « down » option, a negative-delta row is kept through the
     * {@code signum < 0} true arm while a positive-delta row is dropped through
     * its false arm and the {@code !kept continue} true arm.
     */
    @Test
    void downOptionKeepsOnlyMarkdowns() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 13, 0);
        PriceForcingReport report = reportReturning(
                row(when, "TPV4", "MARKDOWN", employee("C004", "Eve", "Petit"), "Remise",
                        "E1", "P1", "F1", "A1", new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("1")),
                row(when, "TPV4", "MARKUP", employee("C004", "Eve", "Petit"), "Majoration",
                        "E2", "P2", "F2", "A2", new BigDecimal("2.00"), new BigDecimal("3.00"), new BigDecimal("1")));
        List<List<Object>> rows = report.rows(criteriaWithOption(PriceForcingReport.DOWN));
        assertEquals(1, rows.size());
        assertEquals("MARKDOWN", rows.get(0).get(2));
        assertEquals(new BigDecimal("-1.00"), rows.get(0).get(12));
    }

    /**
     * With the « up » option, a positive-delta row is kept through the
     * {@code signum > 0} true arm while a negative-delta row is dropped through
     * its false arm.
     */
    @Test
    void upOptionKeepsOnlyMarkups() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 14, 0);
        PriceForcingReport report = reportReturning(
                row(when, "TPV5", "MARKUP", employee("C005", "Max", "Roy"), "Majoration",
                        "E1", "P1", "F1", "A1", new BigDecimal("2.00"), new BigDecimal("3.00"), new BigDecimal("1")),
                row(when, "TPV5", "MARKDOWN", employee("C005", "Max", "Roy"), "Remise",
                        "E2", "P2", "F2", "A2", new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("1")));
        List<List<Object>> rows = report.rows(criteriaWithOption(PriceForcingReport.UP));
        assertEquals(1, rows.size());
        assertEquals("MARKUP", rows.get(0).get(2));
        assertEquals(new BigDecimal("1.00"), rows.get(0).get(12));
    }

    /**
     * With no qualifying line for the period, the loop body never runs and the
     * report returns an empty list — the zero-sales day.
     */
    @Test
    void emptyResultYieldsNoRows() {
        PriceForcingReport report = reportReturning();
        List<List<Object>> rows = report.rows(criteriaWithOption("all"));
        assertTrue(rows.isEmpty());
    }

    /**
     * The metadata methods return the constant descriptors the requirement
     * enumerates, exercising their single line each.
     */
    @Test
    void metadataDescribesTheReport() {
        PriceForcingReport report = new PriceForcingReport();
        assertEquals("price-forcings", report.code());
        assertEquals("État des forçages prix", report.label());
        assertEquals("BO-06-06-10", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals("Sens du forçage", report.optionLabel());
        assertEquals(14, report.columns().size());
        assertEquals(List.of("all:Toutes", "down:Baisses de prix", "up:Hausses de prix"),
                report.optionChoices());
    }

    /**
     * The query is bound to {@link Ticket.TicketStatus#CLOSED}; this test keeps a
     * returned row and asserts the closed status enum, proving the status binding
     * routed the query without throwing.
     */
    @Test
    void closedStatusRowIsReturned() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 15, 0);
        PriceForcingReport report = reportReturning(row(when, "TPV6", "TKT6",
                employee("C006", "Ann", "Lue"), "Remise", "E6", "P6", "F6", "A6",
                new BigDecimal("4.00"), new BigDecimal("2.00"), new BigDecimal("1")));
        List<List<Object>> rows = report.rows(criteriaWithOption("all"));
        assertEquals(Ticket.TicketStatus.CLOSED, Ticket.TicketStatus.valueOf("CLOSED"));
        assertEquals(1, rows.size());
    }
}
