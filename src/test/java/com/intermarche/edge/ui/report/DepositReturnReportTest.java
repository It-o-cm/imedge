package com.intermarche.edge.ui.report;

import com.intermarche.edge.service.PosSettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
 * Unit tests for {@link DepositReturnReport}, the consigne / déconsigne state
 * (BO-03-13-11).
 *
 * <p>The report reads, on a mocked {@link EntityManager}, the déconsigne type
 * codes of the referential and of the lists, the CLOSED refunds filed under
 * them and the ticket lines that move a deposit. Branch enumeration: no
 * déconsigne type (no refund query), both arms of the terminals filter on
 * both queries, no deposit article (the sentinel parameter), and every
 * classification of a line — deposit article sold, taken back, typed return,
 * deposit voucher, a typed return line of another type, a return line that is
 * nothing — with the null arms of every rendered field.
 */
class DepositReturnReportTest {

    /** The query answering the déconsigne types of the referential. */
    private TypedQuery<String> definitions;

    /** The query answering the déconsigne types of the lists. */
    private TypedQuery<String> listed;

    /** The refund query. */
    private TypedQuery<Object[]> refunds;

    /** The ticket-line query. */
    private TypedQuery<Object[]> lines;

    /**
     * Builds a report whose queries answer the given rows.
     *
     * @param definitionCodes the referential's déconsigne codes
     * @param listedCodes the lists' déconsigne codes
     * @param refundRows the refund rows
     * @param lineRows the ticket-line rows
     * @param eans the deposit articles administered
     * @return the report under test
     */
    @SuppressWarnings("unchecked")
    private DepositReturnReport report(List<String> definitionCodes, List<String> listedCodes,
                                       List<Object[]> refundRows, List<Object[]> lineRows, List<String> eans) {
        EntityManager entityManager = mock(EntityManager.class);
        definitions = mock(TypedQuery.class);
        listed = mock(TypedQuery.class);
        refunds = mock(TypedQuery.class);
        lines = mock(TypedQuery.class);
        when(entityManager.createQuery(contains("from ReturnTypeDefinition"), eq(String.class))).thenReturn(definitions);
        when(entityManager.createQuery(contains("from PosListItem"), eq(String.class))).thenReturn(listed);
        when(definitions.getResultList()).thenReturn(definitionCodes);
        when(listed.setParameter(anyString(), any())).thenReturn(listed);
        when(listed.getResultList()).thenReturn(listedCodes);
        when(entityManager.createQuery(contains("from Refund r"), eq(Object[].class))).thenReturn(refunds);
        when(refunds.setParameter(anyString(), any())).thenReturn(refunds);
        when(refunds.getResultList()).thenReturn(refundRows);
        when(entityManager.createQuery(contains("from Ticket t"), eq(Object[].class))).thenReturn(lines);
        when(lines.setParameter(anyString(), any())).thenReturn(lines);
        when(lines.getResultList()).thenReturn(lineRows);
        DepositReturnReport report = new DepositReturnReport();
        report.entityManager = entityManager;
        report.posSettingsService = mock(PosSettingsService.class);
        when(report.posSettingsService.depositArticleEans()).thenReturn(eans);
        return report;
    }

    /**
     * Builds one ticket-line row.
     *
     * @param when the ticket date
     * @param ean the line code
     * @param total the line total
     * @param returnLine whether it is a return line
     * @param deposit whether it is a deposit line
     * @param typeCode the return type code
     * @return the row
     */
    private Object[] line(LocalDateTime when, String ean, String total, boolean returnLine, boolean deposit,
                          String typeCode) {
        return new Object[] {when, "C04", "C04-1", ean, "LIB", total == null ? null : new BigDecimal(total),
                returnLine, deposit, typeCode, typeCode == null ? null : "Type " + typeCode};
    }

    /**
     * The report states what it is, what it answers and where it is filed,
     * with its two totalled sides.
     */
    @Test
    void theReportDescribesItself() {
        DepositReturnReport report = new DepositReturnReport();
        assertEquals("deposit-returns", report.code());
        assertEquals("État consigne / déconsigne", report.label());
        assertEquals("BO-03-13-11", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(8, report.columns().size());
        assertTrue(report.columns().get(6).totalled());
        assertTrue(report.columns().get(7).totalled());
    }

    /**
     * Both sides of the period, oldest first: a deposit article sold and
     * taken back, a typed return line, a deposit voucher, a refund under a
     * déconsigne type, the rest left out; the terminals filter on both
     * queries.
     */
    @Test
    void bothSidesAreListedAndClassified() {
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 25, 9, 0);
        List<Object[]> refundRows = List.<Object[]>of(
                new Object[] {t0.plusHours(5), null, null, "DEC", null, null},
                new Object[] {t0.plusHours(6), "C05", "R-2", "DEC", "Déconsigne", new BigDecimal("-4.00")});
        List<Object[]> lineRows = Arrays.asList(
                line(t0.plusHours(1), "BOUT", "0.50", false, false, null),
                line(t0.plusHours(2), "BOUT", "-0.50", true, true, null),
                line(t0.plusHours(3), "3560", "-3.00", true, true, "DEC"),
                line(t0.plusHours(4), "2980001", "-1.20", false, true, null),
                line(t0.plusHours(7), "3560", "-3.00", true, true, "OTHER"),
                line(t0.plusHours(8), "3560", null, true, false, null),
                new Object[] {t0, null, null, "BOUT", null, null, false, false, null, null});
        DepositReturnReport report = report(List.of("DEC"), List.of("LISTED"), refundRows, lineRows, List.of("BOUT"));
        ReportCriteria criteria = new ReportCriteria();
        criteria.terminals.add("C04");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(7, rows.size());
        assertEquals(Arrays.asList(t0, "", "", "Consigne", "BOUT", "", BigDecimal.ZERO, BigDecimal.ZERO), rows.get(0));
        assertEquals(Arrays.asList(t0.plusHours(1), "C04", "C04-1", "Consigne", "BOUT", "LIB",
                new BigDecimal("0.50"), BigDecimal.ZERO), rows.get(1));
        assertEquals("Reprise article consigné", rows.get(2).get(3));
        assertEquals(new BigDecimal("0.50"), rows.get(2).get(7));
        assertEquals(Arrays.asList(t0.plusHours(3), "C04", "C04-1", "Retour déconsigne", "DEC", "Type DEC",
                BigDecimal.ZERO, new BigDecimal("3.00")), rows.get(3));
        assertEquals("Bon de déconsigne", rows.get(4).get(3));
        assertEquals(new BigDecimal("1.20"), rows.get(4).get(7));
        assertEquals(Arrays.asList(t0.plusHours(5), "", "", "Retour déconsigne", "DEC", "",
                BigDecimal.ZERO, BigDecimal.ZERO), rows.get(5));
        assertEquals(new BigDecimal("4.00"), rows.get(6).get(7));
        verify(refunds).setParameter("codes", Set.of("DEC", "LISTED"));
        verify(refunds).setParameter("terminals", criteria.terminals);
        verify(lines).setParameter("terminals", criteria.terminals);
        verify(lines).setParameter("eans", Set.of("BOUT"));
    }

    /**
     * With no déconsigne type the refunds are not queried, with no deposit
     * article the line query is given a sentinel, and without a terminals
     * filter neither query gets one.
     */
    @Test
    void nothingAdministeredQueriesTheLinesOnly() {
        DepositReturnReport report = report(List.of(), List.of(), List.of(), List.of(), List.of());
        assertTrue(report.rows(new ReportCriteria()).isEmpty());
        verify(refunds, never()).getResultList();
        verify(lines).setParameter("eans", Set.of(""));
        verify(lines, never()).setParameter(eq("terminals"), any());
    }

    /**
     * With déconsigne types and no terminals filter, the refund query takes
     * no terminal clause.
     */
    @Test
    void refundsWithoutTerminalsFilter() {
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 25, 9, 0);
        DepositReturnReport report = report(List.of("DEC"), List.of(), List.<Object[]>of(
                new Object[] {t0, "C04", "R-1", "DEC", "Déconsigne", new BigDecimal("2.00")}), List.of(), List.of());
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(new BigDecimal("2.00"), rows.get(0).get(7));
        verify(refunds, never()).setParameter(eq("terminals"), any());
    }
}
