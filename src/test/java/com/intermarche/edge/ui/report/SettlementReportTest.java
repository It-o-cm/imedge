package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.payment.ChequePayment;
import com.intermarche.edge.domain.payment.TenderDefinition;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SettlementReport}.
 * <p>
 * The report aggregates CLOSED tickets' settlements through a single JPQL query
 * on a mocked {@link EntityManager}, resolves each method's administered label
 * through the Panache static finder {@code TenderDefinition.find} — which under
 * plain {@code mvn test} resolves to {@link PanacheEntityBase} and is
 * intercepted with {@link org.mockito.Mockito#mockStatic} — and then sorts the
 * rows by net value descending. Every collaborator is a Mockito mock, result
 * rows are fed as raw {@code Object[]} and assertions bear absolute expected
 * values. The tests cover both arms of the two {@code terminals.isEmpty()}
 * guards, the entered/empty arms of the row loop, every arm of the
 * functional-id and label ternaries, the null/non-null amount arms, the
 * descending sort with two rows, and the three arms of {@code keyOf} (a known
 * class, an unknown class and a non-class discriminator).
 */
class SettlementReportTest {

    /**
     * Builds a {@link SettlementReport} whose package-private
     * {@link EntityManager} field is wired to the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return a report ready under test
     */
    private SettlementReport reportWith(EntityManager entityManager) {
        SettlementReport report = new SettlementReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock, erasing the unchecked cast to a
     * single suppression point.
     *
     * @return a bare typed-query mock over {@code Object[]}
     */
    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Wires the aggregate query on the given entity-manager mock so that it
     * returns the supplied rows and returns itself from any {@code setParameter}
     * call.
     *
     * @param entityManager the mocked entity manager
     * @param query the typed-query mock to return
     * @param rows the pre-baked result rows
     */
    private void stubQuery(EntityManager entityManager, TypedQuery<Object[]> query, List<Object[]> rows) {
        when(entityManager.createQuery(contains("from Ticket t join t.payments p"), eq(Object[].class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
    }

    /**
     * Builds a {@link PanacheQuery} mock whose {@code firstResult} yields the
     * given tender, mirroring the caller-side shape of {@code find(...)}. Built
     * OUTSIDE any {@code mockStatic} block by every caller, because stubbing this
     * inner mock inside an in-flight {@code mockStatic} stubbing would leave the
     * outer stub unfinished.
     *
     * @param result the tender the finder resolves to, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<TenderDefinition> queryOf(TenderDefinition result) {
        PanacheQuery<TenderDefinition> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds an administered tender with the given functional identifier and
     * label.
     *
     * @param functionalId the functional identifier, possibly null
     * @param label the administered label, possibly null or blank
     * @return the populated tender
     */
    private TenderDefinition tender(String functionalId, String label) {
        TenderDefinition tender = new TenderDefinition();
        tender.functionalId = functionalId;
        tender.label = label;
        return tender;
    }

    /**
     * With no terminals filter and two fully-administered methods, {@code rows()}
     * omits the {@code in :terminals} clause, maps each column through the
     * non-null arm of its ternary and sorts the rows by net value descending.
     */
    @Test
    void rowsPopulatedAndSortedDescending() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, List.of(
                new Object[]{CardPayment.class, 3L, new BigDecimal("50.00")},
                new Object[]{CashPayment.class, 5L, new BigDecimal("120.00")}));
        PanacheQuery<TenderDefinition> cardQuery = queryOf(tender("002", "Carte bancaire"));
        PanacheQuery<TenderDefinition> cashQuery = queryOf(tender("001", "Espèces"));
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "CARD")).thenReturn(cardQuery);
            ms.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            rows = reportWith(entityManager).rows(new ReportCriteria());
        }
        assertEquals(2, rows.size());
        assertEquals(List.of("Carte bancaire", "CARD", "002", "Carte bancaire", 3L,
                new BigDecimal("50.00")), rows.get(0));
        assertEquals(List.of("Espèces", "CASH", "001", "Espèces", 5L,
                new BigDecimal("120.00")), rows.get(1));
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture(), eq(Object[].class));
        assertFalse(jpql.getValue().contains("in :terminals"));
        verify(query, never()).setParameter(eq("terminals"), any());
    }

    /**
     * With a terminals filter, {@code rows()} appends the {@code in :terminals}
     * clause and binds the terminals parameter, taking the true arm of both
     * {@code terminals.isEmpty()} guards.
     */
    @Test
    void rowsWithTerminalsFilter() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, Collections.singletonList(
                new Object[]{CashPayment.class, 2L, new BigDecimal("10.00")}));
        ReportCriteria criteria = new ReportCriteria();
        criteria.terminals.add("CAISSE1");
        PanacheQuery<TenderDefinition> cashQuery = queryOf(tender("001", "Espèces"));
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            rows = reportWith(entityManager).rows(criteria);
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("Espèces", "CASH", "001", "Espèces", 2L, new BigDecimal("10.00")), rows.get(0));
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture(), eq(Object[].class));
        assertTrue(jpql.getValue().contains("and t.terminalId in :terminals"));
        verify(query).setParameter("terminals", criteria.terminals);
    }

    /**
     * A row whose summed amount is null takes the null arm of the amount
     * ternary, defaulting the net value to {@link BigDecimal#ZERO} while the
     * count still resolves.
     */
    @Test
    void rowsNullAmountBecomesZero() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, Collections.singletonList(
                new Object[]{CashPayment.class, 4L, null}));
        PanacheQuery<TenderDefinition> cashQuery = queryOf(tender("001", "Espèces"));
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            rows = reportWith(entityManager).rows(new ReportCriteria());
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("Espèces", "CASH", "001", "Espèces", 4L, BigDecimal.ZERO), rows.get(0));
    }

    /**
     * When no row administers the method, the finder resolves to null, so
     * {@code rows()} takes the {@code tender == null} arm of the group, of the
     * functional-id and of the label ternaries (BO-06-06-09: a method with no
     * administered group stands in a group of its own, named by the method):
     * the identifier blanks and both the group and the label fall back to the
     * technical key.
     */
    @Test
    void rowsUnadministeredMethodFallsBackToKey() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, Collections.singletonList(
                new Object[]{CashPayment.class, 1L, new BigDecimal("5.00")}));
        PanacheQuery<TenderDefinition> cashQuery = queryOf(null);
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            rows = reportWith(entityManager).rows(new ReportCriteria());
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("Espèces", "CASH", "", "Espèces", 1L, new BigDecimal("5.00")), rows.get(0));
    }

    /**
     * When the administered tender exists but leaves both its functional
     * identifier and its label unset, {@code rows()} takes the
     * {@code functionalId == null} and {@code label == null} arms: the
     * identifier blanks, and both the group and the label fall back to the
     * technical key (BO-06-06-09).
     */
    @Test
    void rowsTenderWithoutFunctionalIdAndLabel() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, Collections.singletonList(
                new Object[]{CashPayment.class, 1L, new BigDecimal("5.00")}));
        PanacheQuery<TenderDefinition> cashQuery = queryOf(tender(null, null));
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            rows = reportWith(entityManager).rows(new ReportCriteria());
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("Espèces", "CASH", "", "Espèces", 1L, new BigDecimal("5.00")), rows.get(0));
    }

    /**
     * When the administered tender carries a blank label but a present
     * functional identifier, {@code rows()} takes the {@code label.isBlank()}
     * arm — the label falls back to the technical key — while the identifier is
     * kept through the non-null arm of its ternary.
     */
    @Test
    void rowsTenderWithBlankLabel() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, Collections.singletonList(
                new Object[]{CashPayment.class, 1L, new BigDecimal("5.00")}));
        PanacheQuery<TenderDefinition> cashQuery = queryOf(tender("007", "   "));
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            rows = reportWith(entityManager).rows(new ReportCriteria());
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("Espèces", "CASH", "007", "Espèces", 1L, new BigDecimal("5.00")), rows.get(0));
    }

    /**
     * A period with no closed settlement yields an empty result list, so the row
     * loop is never entered and {@code rows()} returns an empty list.
     */
    @Test
    void rowsEmptyResult() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, List.of());
        List<List<Object>> rows = reportWith(entityManager).rows(new ReportCriteria());
        assertTrue(rows.isEmpty());
    }

    /**
     * When the query returns a payment class the registry does not know,
     * {@code keyOf} exhausts its loop and takes its {@code instanceof Class} arm,
     * naming the row by the class's simple name.
     */
    @Test
    void rowsUnknownPaymentClassUsesSimpleName() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, Collections.singletonList(
                new Object[]{String.class, 1L, new BigDecimal("5.00")}));
        PanacheQuery<TenderDefinition> unknownQuery = queryOf(null);
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "String")).thenReturn(unknownQuery);
            rows = reportWith(entityManager).rows(new ReportCriteria());
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("String", "String", "", "String", 1L, new BigDecimal("5.00")), rows.get(0));
    }

    /**
     * When the discriminator the query returns is not a {@link Class} at all,
     * {@code keyOf} exhausts its loop and takes its non-class arm, naming the row
     * by {@code String.valueOf} of the value.
     */
    @Test
    void rowsNonClassDiscriminatorUsesStringValue() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, Collections.singletonList(
                new Object[]{"mystery", 1L, new BigDecimal("5.00")}));
        PanacheQuery<TenderDefinition> mysteryQuery = queryOf(null);
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> TenderDefinition.find("code", "mystery")).thenReturn(mysteryQuery);
            rows = reportWith(entityManager).rows(new ReportCriteria());
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("mystery", "mystery", "", "mystery", 1L, new BigDecimal("5.00")), rows.get(0));
    }

    /**
     * The report's code is its stable technical identifier.
     */
    @Test
    void codeIsStable() {
        assertEquals("settlements", new SettlementReport().code());
    }

    /**
     * The report's label is its French title.
     */
    @Test
    void labelIsFrenchTitle() {
        assertEquals("État des règlements", new SettlementReport().label());
    }

    /**
     * The report answers its questionnaire requirement.
     */
    @Test
    void requirementIsBoCode() {
        assertEquals("BO-06-06-09", new SettlementReport().requirement());
    }

    /**
     * The report belongs to the settlements family.
     */
    @Test
    void familyIsSettlements() {
        assertEquals(ReportFamily.SETTLEMENTS, new SettlementReport().family());
    }

    /**
     * BO-06-06-09: two methods administered under the SAME group are totalled
     * by a sub-total line under them, and a group holding one method gets
     * none — it would repeat the line above it word for word.
     */
    @Test
    void groupsCarryTheirSubTotal() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, List.of(
                new Object[]{CashPayment.class, 5L, new BigDecimal("120.00")},
                new Object[]{CardPayment.class, 3L, new BigDecimal("50.00")},
                new Object[]{ChequePayment.class, 2L, new BigDecimal("30.00")}));
        TenderDefinition cash = tender("001", "Espèces");
        TenderDefinition card = tender("002", "Carte bancaire");
        card.groupCode = "CB";
        card.groupLabel = "Cartes";
        TenderDefinition cheque = tender("003", "Chèque");
        cheque.groupCode = "CB";
        cheque.groupLabel = "Cartes";
        PanacheQuery<TenderDefinition> cashQuery = queryOf(cash);
        PanacheQuery<TenderDefinition> cardQuery = queryOf(card);
        PanacheQuery<TenderDefinition> chequeQuery = queryOf(cheque);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            panache.when(() -> TenderDefinition.find("code", "CARD")).thenReturn(cardQuery);
            panache.when(() -> TenderDefinition.find("code", "CHEQUE")).thenReturn(chequeQuery);
            List<List<Object>> rows = reportWith(entityManager).rows(new ReportCriteria());
            assertEquals(4, rows.size());
            assertEquals("Cartes", rows.get(0).get(0));
            assertEquals("Cartes", rows.get(1).get(0));
            assertEquals("Total Cartes", rows.get(2).get(3));
            assertEquals(5L, rows.get(2).get(4));
            assertEquals(new BigDecimal("80.00"), rows.get(2).get(5));
            assertTrue(ReportData.isSubtotal(rows.get(2)));
            assertEquals("Espèces", rows.get(3).get(0));
            assertFalse(ReportData.isSubtotal(rows.get(3)));
        }
    }

    /**
     * The report exposes the six columns the requirement enumerates, in order
     * and with their kinds — the accounting group first (BO-06-06-09).
     */
    @Test
    void columnsMatchRequirement() {
        List<ReportColumn> columns = new SettlementReport().columns();
        assertEquals(6, columns.size());
        assertEquals("group", columns.get(0).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(0).kind());
        assertEquals("key", columns.get(1).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(1).kind());
        assertEquals("id", columns.get(2).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(2).kind());
        assertEquals("label", columns.get(3).key());
        assertEquals(ReportColumn.Kind.TEXT, columns.get(3).kind());
        assertEquals("count", columns.get(4).key());
        assertEquals(ReportColumn.Kind.INTEGER, columns.get(4).kind());
        assertEquals("amount", columns.get(5).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(5).kind());
    }

    /**
     * The chart is drawn on the net-value column, the fifth.
     */
    @Test
    void chartColumnIsAmount() {
        assertEquals(5, new SettlementReport().chartColumn());
    }

    /**
     * Each chart bar is named by the label column, the third.
     */
    @Test
    void chartLabelColumnIsLabel() {
        assertEquals(3, new SettlementReport().chartLabelColumn());
    }

    /**
     * BO-06-06-09: the state closes on the frame's TOTAL row — the counts and
     * the amounts of every method above it, the group sub-totals counted once
     * — and the report writes no total of its own, which the frame would add
     * a second time.
     */
    @Test
    void theStateClosesOnItsTotal() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, List.of(
                new Object[]{CashPayment.class, 5L, new BigDecimal("120.00")},
                new Object[]{CardPayment.class, 3L, new BigDecimal("50.00")}));
        TenderDefinition cash = tender("001", "Espèces");
        cash.groupLabel = "Monnaie";
        TenderDefinition card = tender("002", "Carte bancaire");
        card.groupLabel = "Monnaie";
        PanacheQuery<TenderDefinition> cashQuery = queryOf(cash);
        PanacheQuery<TenderDefinition> cardQuery = queryOf(card);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> TenderDefinition.find("code", "CASH")).thenReturn(cashQuery);
            panache.when(() -> TenderDefinition.find("code", "CARD")).thenReturn(cardQuery);
            SettlementReport report = reportWith(entityManager);
            List<List<Object>> rows = report.rows(new ReportCriteria());
            assertEquals(3, rows.size());
            assertEquals("Total Monnaie", rows.get(2).get(3));
            ReportData data = new ReportData(report, rows);
            assertEquals(8L, data.totals.get(4));
            assertEquals(new BigDecimal("170.00"), data.totals.get(5));
        }
    }

    /** BO-06-06-09: the final multi-method group receives its subtotal before the overall total. */
    @Test
    void finalPaymentGroupReceivesItsSubtotal() throws Exception {
        var report = new SettlementReport();
        var method = SettlementReport.class.getDeclaredMethod("withTotals", List.class);
        method.setAccessible(true);
        List<List<Object>> input = List.of(List.of("CARDS", "CARD", "1", "CARD", 1L, BigDecimal.TEN), List.of("CARDS", "TRD", "2", "TRD", 2L, BigDecimal.ONE));
        var rows = (List<List<Object>>) method.invoke(report, input);
        assertEquals(3, rows.size());
        assertEquals("Total CARDS", rows.get(2).get(3));
        assertEquals(3L, rows.get(2).get(4));
        assertEquals(new BigDecimal("11"), rows.get(2).get(5));
    }

    /**
     * A state without any settlement gets no sub-total row at all.
     *
     * @throws Exception when the private method cannot be reached
     */
    @Test
    void emptyStateGetsNoSubtotal() throws Exception {
        var method = SettlementReport.class.getDeclaredMethod("withTotals", List.class);
        method.setAccessible(true);
        assertTrue(((List<?>) method.invoke(new SettlementReport(), List.of())).isEmpty());
    }

    /** BO-06-06-09: a blank group label uses its own tender's wording rather than an empty shared group. */
    @Test
    void blankGroupLabelFallsBackToTheTenderLabel() {
        var manager = mock(EntityManager.class);
        var query = typedQueryMock();
        stubQuery(manager, query, java.util.Collections.singletonList(new Object[]{CashPayment.class, 1L, BigDecimal.TEN}));
        var tender = tender("001", "CASH WORDING");
        tender.groupLabel = " ";
        var lookup = queryOf(tender);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("code", "CASH")).thenReturn(lookup);
            assertEquals("CASH WORDING", reportWith(manager).rows(new ReportCriteria()).get(0).get(0));
        }
    }

}
