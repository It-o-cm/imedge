package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.service.CashSessionService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminDayHistoryResource}.
 *
 * <p>The day consolidated over every register. What is proved here is that the
 * breakdown by tender SUMS the sessions' own reports rather than recomputing
 * them, that a null amount inside one counts as zero, and that the endorsement
 * of a cashier's movement is refused without a name — the requirement asks for a
 * validation, and an unsigned one validates nothing.
 */
class AdminDayHistoryResourceTest {

    /**
     * Builds a resource with mocked template and consolidation.
     *
     * @return the wired resource
     */
    private AdminDayHistoryResource newResource() {
        AdminDayHistoryResource resource = new AdminDayHistoryResource();
        resource.adminSafeDays = mock(Template.class);
        resource.cashSessionService = mock(CashSessionService.class);
        return resource;
    }

    /**
     * Stubs a template's fluent chain to a recognizable view.
     *
     * @param template the template to stub
     * @return the view every chain call returns
     */
    private TemplateInstance stub(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a closed session of a register.
     *
     * @param terminal the register identifier
     * @param withdrawn what was taken out, as a plain decimal string, null for none
     * @return the session
     */
    private CashSession session(String terminal, String withdrawn) {
        CashSession session = new CashSession();
        session.terminalId = terminal;
        session.sessionNumber = terminal + "-1";
        session.withdrawnAmount = withdrawn == null ? null : new BigDecimal(withdrawn);
        return session;
    }

    /**
     * Builds the consolidation of one session.
     *
     * @param totals the totals by tender key
     * @return the report
     */
    private CashSessionService.SessionReport report(Map<String, BigDecimal> totals) {
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.totalsByMethod = new LinkedHashMap<>(totals);
        return report;
    }

    /**
     * Stubs the sessions of one day.
     *
     * @param panache the active PanacheEntityBase mock
     * @param day the day examined
     * @param sessions the sessions to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubSessions(MockedStatic<PanacheEntityBase> panache, LocalDate day,
            List<CashSession> sessions) {
        PanacheQuery<CashSession> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(sessions);
        panache.when(() -> CashSession.find(
                "status = ?1 and closingDate >= ?2 and closingDate < ?3 order by terminalId",
                CashSession.SessionStatus.CLOSED, day.atStartOfDay(),
                day.plusDays(1).atStartOfDay())).thenReturn(query);
    }

    /**
     * Stubs the drawer movements of one day.
     *
     * @param panache the active PanacheEntityBase mock
     * @param day the day examined
     * @param movements the movements to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubMovements(MockedStatic<PanacheEntityBase> panache, LocalDate day,
            List<CashMovement> movements) {
        PanacheQuery<CashMovement> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(movements);
        panache.when(() -> CashMovement.find(
                "movementDate >= ?1 and movementDate < ?2 order by movementDate",
                day.atStartOfDay(), day.plusDays(1).atStartOfDay())).thenReturn(query);
    }

    /**
     * The day's takings are the sum of the sessions' own reports, tender by
     * tender, with the totals and the withdrawals beside them.
     */
    @Test
    void thedaysTakingsSumTheSessionsReports() {
        AdminDayHistoryResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeDays);
        CashSession first = session("C01", "500.00");
        CashSession second = session("C02", "750.00");
        when(resource.cashSessionService.buildReport(first)).thenReturn(report(
                Map.of("CASH", new BigDecimal("300.00"))));
        when(resource.cashSessionService.buildReport(second)).thenReturn(report(
                new LinkedHashMap<>(Map.of("CASH", new BigDecimal("200.00")))));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.of(2026, 9, 15), List.of(first, second));
            stubMovements(panache, LocalDate.of(2026, 9, 15), List.of());
            assertSame(view, resource.day("2026-09-15", null, true));
        }
        verify(view).data("total", "500.00");
        verify(view).data("withdrawn", "1250.00");
        verify(view).data("day", "2026-09-15");
    }

    /**
     * A report carrying a null amount counts it as zero — the null arm of that
     * guard — and a day with no session totals nothing.
     */
    @Test
    void anUnsetTotalCountsAsZero() {
        AdminDayHistoryResource resource = newResource();
        CashSession session = session("C01", null);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("CASH", null);
        when(resource.cashSessionService.buildReport(session)).thenReturn(report(totals));
        assertEquals(BigDecimal.ZERO, resource.sum(resource.totalsByTender(
                List.of(session)).values()));
        assertTrue(resource.totalsByTender(List.of()).isEmpty());
        assertEquals(BigDecimal.ZERO, resource.withdrawnOf(List.of(session)));
    }

    /**
     * The same tender seen on two registers is summed once, not listed twice.
     */
    @Test
    void thesameTenderOnTwoRegistersIsSummedOnce() {
        AdminDayHistoryResource resource = newResource();
        CashSession first = session("C01", "0.00");
        CashSession second = session("C02", "0.00");
        when(resource.cashSessionService.buildReport(first))
                .thenReturn(report(Map.of("CASH", new BigDecimal("300.00"))));
        when(resource.cashSessionService.buildReport(second))
                .thenReturn(report(Map.of("CASH", new BigDecimal("200.00"))));
        List<AdminDayHistoryResource.TenderRow> rows =
                resource.rowsOf(resource.totalsByTender(List.of(first, second)));
        assertEquals(1, rows.size());
        assertEquals("CASH", rows.get(0).getMethod());
        assertEquals("500.00", rows.get(0).getAmount());
    }

    /**
     * Without a day, the page examines today — the null arm of that guard.
     */
    @Test
    void withoutAdayThePageExaminesToday() {
        AdminDayHistoryResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeDays);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.now(), List.of());
            stubMovements(panache, LocalDate.now(), List.of());
            resource.day(null, null, true);
        }
        verify(view).data("day", LocalDate.now().toString());
    }

    /**
     * Validating a cashier's movement writes the name of whoever checked it.
     */
    @Test
    void validatingAmovementWritesWhoCheckedIt() {
        AdminDayHistoryResource resource = newResource();
        CashMovement movement = mock(CashMovement.class);
        movement.movementUid = "MV-1";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        form.add("day", "2026-09-15");
        form.add("endorsedBy", " B12 ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMovement.findById(3L)).thenReturn(movement);
            Response response = resource.validate(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            assertTrue(response.getLocation().toString().contains("day=2026-09-15"));
            assertEquals("B12", movement.endorsedBy);
            verify(movement).persist();
        }
    }

    /**
     * Validating an unknown movement is refused, whether the identifier is
     * absent or names nothing — the two legs of that guard.
     */
    @Test
    void validatingAnUnknownMovementIsRefused() {
        AdminDayHistoryResource resource = newResource();
        assertTrue(resource.validate(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMovement.findById(9L)).thenReturn(null);
            assertTrue(resource.validate(form).getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * An UNSIGNED validation is refused: a validation nobody signed validates
     * nothing.
     */
    @Test
    void anUnsignedValidationIsRefused() {
        AdminDayHistoryResource resource = newResource();
        CashMovement movement = mock(CashMovement.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        form.add("endorsedBy", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashMovement.findById(3L)).thenReturn(movement);
            Response response = resource.validate(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertNull(movement.endorsedBy);
        }
    }

    /**
     * The extraction states one row per tender and names the file after the day.
     */
    @Test
    void theextractionStatesOneRowPerTender() {
        AdminDayHistoryResource resource = newResource();
        CashSession session = session("C01", "0.00");
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("CASH;CB", new BigDecimal("300.00"));
        totals.put(null, new BigDecimal("12.00"));
        when(resource.cashSessionService.buildReport(session)).thenReturn(report(totals));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.of(2026, 9, 15), List.of(session));
            Response response = resource.export("2026-09-15");
            assertEquals(200, response.getStatus());
            String csv = String.valueOf(response.getEntity());
            assertTrue(csv.startsWith("JOURNEE;MOYEN;MONTANT\n"));
            assertTrue(csv.contains("2026-09-15;CASH,CB;300.00"));
            assertTrue(csv.contains("2026-09-15;;12.00"));
            assertTrue(response.getHeaderString("Content-Disposition")
                    .contains("journee-2026-09-15.csv"));
        }
    }

    /**
     * The extraction of an unnamed day extracts today — the null arm of that
     * guard.
     */
    @Test
    void theextractionOfAnUnnamedDayExtractsToday() {
        AdminDayHistoryResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.now(), List.of());
            assertTrue(resource.export(null).getHeaderString("Content-Disposition")
                    .contains("journee-" + LocalDate.now() + ".csv"));
        }
    }

    /**
     * An employee is re-read by identifier, and an absent one names nobody —
     * both arms of that guard.
     */
    @Test
    void anEmployeeIsReReadByIdentifier() {
        AdminDayHistoryResource resource = newResource();
        Employee employee = new Employee();
        assertNull(resource.employeeOf(null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(5L)).thenReturn(employee);
            assertSame(employee, resource.employeeOf(5L));
        }
    }

    /**
     * A null amount inside the sum counts as zero — the null arm of that guard —
     * and a printable row of a null total shows it as zero rather than exploding.
     */
    @Test
    void thesumAndTheRowsTolerateNullAmounts() {
        AdminDayHistoryResource resource = newResource();
        assertEquals(BigDecimal.ZERO,
                resource.sum(java.util.Arrays.asList((BigDecimal) null)));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("CASH", null);
        List<AdminDayHistoryResource.TenderRow> rows = resource.rowsOf(totals);
        assertEquals("0.00", rows.get(0).getAmount());
    }
}
