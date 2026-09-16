package com.intermarche.pos.ui.dashboard;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardService}.
 * <p>
 * The service aggregates the supervisor dashboard through five JPQL queries on
 * a mocked {@link EntityManager} plus one Panache {@code list} static call on
 * {@link CashSession}; the latter resolves to {@link PanacheEntityBase} under
 * plain {@code mvn test} and is intercepted with {@link org.mockito.Mockito#mockStatic}.
 * Every collaborator is a Mockito mock, query results are fed as raw rows and
 * assertions bear absolute expected values. The three tests cover both arms of
 * the {@code ticketCount > 0}, {@code finished > 0} and {@code openingCashier != null}
 * ternaries and the entered/not-entered arms of the per-register, top-sales and
 * active-sessions loops.
 */
class DashboardServiceTest {

    /**
     * Builds a {@link DashboardService} whose {@link EntityManager} field is
     * wired to the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return a service ready under test
     */
    private DashboardService serviceWith(EntityManager entityManager) {
        DashboardService service = new DashboardService();
        service.entityManager = entityManager;
        return service;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock, erasing the unchecked cast to a
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
     * Wires the five aggregation queries of {@code buildData()} on the given
     * entity-manager mock, each returning the supplied pre-baked result and each
     * returning itself from any {@code setParameter}/{@code setMaxResults} call.
     *
     * @param entityManager the mocked entity manager
     * @param revenueRow the {sum, count} row of the revenue query
     * @param registerRows the per-register rows
     * @param cancelled the cancelled-ticket count
     * @param refundRow the {sum, count} row of the refund query
     * @param topRows the top-sales rows
     */
    private void stubQueries(EntityManager entityManager, Object[] revenueRow,
            List<Object[]> registerRows, long cancelled, Object[] refundRow,
            List<Object[]> topRows) {
        Query revenueQuery = mock(Query.class);
        when(entityManager.createQuery(contains("count(t) from Ticket t where t.status"))).thenReturn(revenueQuery);
        when(revenueQuery.setParameter(anyString(), any())).thenReturn(revenueQuery);
        when(revenueQuery.getSingleResult()).thenReturn(revenueRow);
        TypedQuery<Object[]> registerQuery = typedQueryMock();
        when(entityManager.createQuery(contains("group by t.terminalId"), eq(Object[].class))).thenReturn(registerQuery);
        when(registerQuery.setParameter(anyString(), any())).thenReturn(registerQuery);
        when(registerQuery.getResultList()).thenReturn(registerRows);
        TypedQuery<Long> cancelledQuery = typedQueryMock();
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(cancelledQuery);
        when(cancelledQuery.setParameter(anyString(), any())).thenReturn(cancelledQuery);
        when(cancelledQuery.getSingleResult()).thenReturn(cancelled);
        Query refundQuery = mock(Query.class);
        when(entityManager.createQuery(contains("from Refund r"))).thenReturn(refundQuery);
        when(refundQuery.setParameter(anyString(), any())).thenReturn(refundQuery);
        when(refundQuery.getSingleResult()).thenReturn(refundRow);
        TypedQuery<Object[]> topQuery = typedQueryMock();
        when(entityManager.createQuery(contains("join t.lines"), eq(Object[].class))).thenReturn(topQuery);
        when(topQuery.setParameter(anyString(), any())).thenReturn(topQuery);
        when(topQuery.setMaxResults(anyInt())).thenReturn(topQuery);
        when(topQuery.getResultList()).thenReturn(topRows);
    }

    /**
     * Builds a {@link CashSession} with the given fields.
     *
     * @param terminalId the register identifier
     * @param sessionNumber the session number
     * @param openingCashier the opening cashier, possibly null
     * @param openingDate the opening timestamp
     * @return the populated session
     */
    private CashSession session(String terminalId, String sessionNumber,
            Employee openingCashier, LocalDateTime openingDate) {
        CashSession session = new CashSession();
        session.terminalId = terminalId;
        session.sessionNumber = sessionNumber;
        session.openingCashier = openingCashier;
        session.openingDate = openingDate;
        return session;
    }

    /**
     * With non-empty rows for every query and one open session with a cashier,
     * {@code buildData()} formats every indicator, enters all three loops and
     * takes the true arm of each ternary.
     */
    @Test
    void buildDataPopulated() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQueries(entityManager,
                new Object[]{new BigDecimal("100.00"), 4L},
                Collections.singletonList(new Object[]{"CAISSE1", new BigDecimal("100.00"), 4L}),
                1L,
                new Object[]{new BigDecimal("10.00"), 2L},
                Collections.singletonList(new Object[]{"Milk", new BigDecimal("3.5")}));
        Employee cashier = mock(Employee.class);
        when(cashier.getFullName()).thenReturn("Alice Martin");
        DashboardService service = serviceWith(entityManager);
        Map<String, Object> data;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.list("status", CashSession.SessionStatus.OPEN))
                    .thenReturn(List.of(session("CAISSE1", "C01-S00001", cashier,
                            LocalDateTime.of(2026, 8, 3, 9, 5))));
            data = service.buildData();
        }
        assertEquals("100,00", data.get("revenue"));
        assertEquals(4L, data.get("ticketCount"));
        assertEquals("25,00", data.get("averageBasket"));
        assertEquals("20,0", data.get("cancellationRate"));
        assertEquals("10,00", data.get("refundTotal"));
        assertEquals(2L, data.get("refundCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perRegister = (List<Map<String, Object>>) data.get("perRegister");
        assertEquals(1, perRegister.size());
        assertEquals("CAISSE1", perRegister.get(0).get("terminal"));
        assertEquals("100,00", perRegister.get(0).get("revenue"));
        assertEquals(4L, perRegister.get(0).get("tickets"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topSales = (List<Map<String, Object>>) data.get("topSales");
        assertEquals(1, topSales.size());
        assertEquals("Milk", topSales.get(0).get("label"));
        assertEquals("3,5", topSales.get(0).get("quantity"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) data.get("sessions");
        assertEquals(1, sessions.size());
        assertEquals("CAISSE1", sessions.get(0).get("terminal"));
        assertEquals("C01-S00001", sessions.get(0).get("number"));
        assertEquals("Alice Martin", sessions.get(0).get("operator"));
        assertEquals("09:05", sessions.get(0).get("since"));
    }

    /**
     * With zero tickets, empty register/top rows and no open session,
     * {@code buildData()} skips all three loops and takes the false arm of the
     * {@code ticketCount > 0} and {@code finished > 0} ternaries.
     */
    @Test
    void buildDataEmpty() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQueries(entityManager,
                new Object[]{new BigDecimal("0"), 0L},
                List.of(),
                0L,
                new Object[]{new BigDecimal("0"), 0L},
                List.of());
        DashboardService service = serviceWith(entityManager);
        Map<String, Object> data;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.list("status", CashSession.SessionStatus.OPEN))
                    .thenReturn(List.of());
            data = service.buildData();
        }
        assertEquals("0,00", data.get("revenue"));
        assertEquals(0L, data.get("ticketCount"));
        assertEquals("0,00", data.get("averageBasket"));
        assertEquals("0,0", data.get("cancellationRate"));
        assertEquals("0,00", data.get("refundTotal"));
        assertEquals(0L, data.get("refundCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perRegister = (List<Map<String, Object>>) data.get("perRegister");
        assertTrue(perRegister.isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topSales = (List<Map<String, Object>>) data.get("topSales");
        assertTrue(topSales.isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) data.get("sessions");
        assertTrue(sessions.isEmpty());
    }

    /**
     * With one open session whose opening cashier is null, {@code buildData()}
     * enters the session loop and takes the false arm of the
     * {@code openingCashier != null} ternary, exposing a blank operator.
     */
    @Test
    void buildDataSessionWithoutCashier() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQueries(entityManager,
                new Object[]{new BigDecimal("0"), 0L},
                List.of(),
                0L,
                new Object[]{new BigDecimal("0"), 0L},
                List.of());
        DashboardService service = serviceWith(entityManager);
        Map<String, Object> data;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.list("status", CashSession.SessionStatus.OPEN))
                    .thenReturn(List.of(session("CAISSE2", "C02-S00002", null,
                            LocalDateTime.of(2026, 8, 3, 14, 30))));
            data = service.buildData();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) data.get("sessions");
        assertEquals(1, sessions.size());
        assertEquals("CAISSE2", sessions.get(0).get("terminal"));
        assertEquals("C02-S00002", sessions.get(0).get("number"));
        assertEquals("", sessions.get(0).get("operator"));
        assertEquals("14:30", sessions.get(0).get("since"));
    }
}
