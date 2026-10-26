package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.CashPouch;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.SafeService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminCashPouchResource}.
 *
 * <p>The step the register cannot take. What is proved here is the day's
 * proposal — the withdrawals of every session closed that day, the registers
 * named once each — and the four refusals of the declaration: no number, a
 * number already used, an unusable amount, and a safe with no period open.
 */
class AdminCashPouchResourceTest {

    /**
     * Builds a resource with mocked template, safe and identity.
     *
     * @return the wired resource
     */
    private AdminCashPouchResource newResource() {
        AdminCashPouchResource resource = new AdminCashPouchResource();
        resource.adminSafePouches = mock(Template.class);
        resource.safeService = mock(SafeService.class);
        resource.currentUser = mock(CurrentUser.class);
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
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
        stubValidation(panache, day, null);
    }

    /**
     * Stubs the validation of one accounting day (BO-05-02-10).
     *
     * @param panache the active PanacheEntityBase mock
     * @param day the accounting day
     * @param validation the validation to resolve, null for a day not validated
     */
    @SuppressWarnings("unchecked")
    private void stubValidation(MockedStatic<PanacheEntityBase> panache, LocalDate day,
            com.intermarche.edge.domain.safe.SafeDayValidation validation) {
        PanacheQuery<com.intermarche.edge.domain.safe.SafeDayValidation> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(validation);
        panache.when(() -> com.intermarche.edge.domain.safe.SafeDayValidation.find("accountingDay", day))
                .thenReturn(query);
    }

    /**
     * Stubs the pouch history.
     *
     * @param panache the active PanacheEntityBase mock
     * @param pouches the pouches to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubPouches(MockedStatic<PanacheEntityBase> panache, List<CashPouch> pouches) {
        PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(pouches);
        panache.when(() -> CashPouch.find("order by accountingDay desc, id desc"))
                .thenReturn(query);
    }

    /**
     * The page proposes the day's withdrawals and names its registers.
     */
    @Test
    void thepageProposesTheDaysWithdrawals() {
        AdminCashPouchResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafePouches);
        List<CashSession> sessions = List.of(session("C01", "500.00"), session("C02", "750.00"),
                session("C01", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.of(2026, 9, 15), sessions);
            stubPouches(panache, List.of());
            assertSame(view, resource.list("2026-09-15", null, true));
        }
        verify(view).data("withdrawn", "1250.00");
        verify(view).data("terminals", "C01,C02");
        verify(view).data("validationRequired", false);
        verify(view).data("day", "2026-09-15");
    }

    /**
     * Without a day, the page examines today — the null arm of that guard.
     */
    @Test
    void withoutAdayThePageExaminesToday() {
        AdminCashPouchResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafePouches);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.now(), List.of());
            stubPouches(panache, List.of());
            resource.list(null, null, true);
        }
        verify(view).data("day", LocalDate.now().toString());
        verify(view).data("withdrawn", "0.00");
        verify(view).data("terminals", "");
    }

    /**
     * A session naming no register is not named twice, and not named at all —
     * the null arm of the register guard.
     */
    @Test
    void asessionNamingNoRegisterIsNotNamed() {
        AdminCashPouchResource resource = newResource();
        assertEquals("", resource.terminalsOf(List.of(session(null, "10.00"))));
        assertEquals(BigDecimal.ZERO, resource.withdrawnOf(List.of()));
    }

    /**
     * A pouch without a number, and one whose number is already used, are
     * refused — the two legs of that guard.
     */
    @Test
    void anUnnamedOrDuplicatePouchIsRefused() {
        AdminCashPouchResource resource = newResource();
        MultivaluedMap<String, String> unnamed = new MultivaluedHashMap<>();
        unnamed.add("pouchNumber", "  ");
        assertTrue(resource.deposit(unnamed).getLocation().toString().contains("noticeOk=false"));
        CashPouch existing = new CashPouch();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("pouchNumber", "POCH-1");
        form.add("amount", "1250,00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashPouch> created = mockConstruction(CashPouch.class)) {
            PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> CashPouch.find("pouchNumber", "POCH-1")).thenReturn(query);
            assertTrue(resource.deposit(form).getLocation().toString().contains("noticeOk=false"));
            assertTrue(created.constructed().isEmpty());
        }
        verify(resource.safeService, never()).depositPouch(any(), any());
    }

    /**
     * An absent, unreadable, zero or negative amount is refused — the four legs
     * of that guard.
     */
    @Test
    void anUnusableAmountIsRefused() {
        AdminCashPouchResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashPouch> created = mockConstruction(CashPouch.class)) {
            PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> CashPouch.find("pouchNumber", "POCH-1")).thenReturn(query);
            for (String amount : new String[] {null, "beaucoup", "0", "-10,00"}) {
                MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
                form.add("pouchNumber", "POCH-1");
                if (amount != null) {
                    form.add("amount", amount);
                }
                assertTrue(resource.deposit(form).getLocation().toString()
                        .contains("noticeOk=false"));
            }
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A complete pouch reaches the safe carrying everything the form held.
     */
    @Test
    void acompletePouchReachesTheSafe() {
        AdminCashPouchResource resource = newResource();
        Employee carrier = new Employee();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("pouchNumber", "POCH-1");
        form.add("label", "Recettes du jour");
        form.add("accountingDay", "2026-09-15");
        form.add("amount", "1250,00");
        form.add("terminals", "C01,C02");
        when(resource.currentUser.getName()).thenReturn("alice");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashPouch> created = mockConstruction(CashPouch.class)) {
            PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> CashPouch.find("pouchNumber", "POCH-1")).thenReturn(query);
            PanacheQuery<Employee> who = mock(PanacheQuery.class);
            when(who.firstResult()).thenReturn(carrier);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(who);
            assertTrue(resource.deposit(form).getLocation().toString().contains("noticeOk=true"));
            CashPouch pouch = created.constructed().get(0);
            assertEquals("POCH-1", pouch.pouchNumber);
            assertEquals("Recettes du jour", pouch.label);
            assertEquals(LocalDate.of(2026, 9, 15), pouch.accountingDay);
            assertEquals(new BigDecimal("1250.00"), pouch.amount);
            assertEquals("C01,C02", pouch.terminals);
            verify(resource.safeService).depositPouch(pouch, carrier);
        }
    }

    /**
     * A pouch with no day counts for today, and a safe with no period open
     * refuses it — the null arm of the day guard and the refusing arm of the
     * safe's answer.
     */
    @Test
    void apouchWithoutAdayCountsForTodayAndMayStillBeRefused() {
        AdminCashPouchResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("pouchNumber", "POCH-2");
        form.add("amount", "10,00");
        when(resource.safeService.depositPouch(any(), any()))
                .thenReturn(SafeService.NO_OPEN_PERIOD);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashPouch> created = mockConstruction(CashPouch.class)) {
            PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> CashPouch.find("pouchNumber", "POCH-2")).thenReturn(query);
            Response response = resource.deposit(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
            assertEquals(LocalDate.now(), created.constructed().get(0).accountingDay);
        }
    }

    /**
     * The signed-in employee is resolved by login, and is nobody when the
     * session names nobody — the three legs of that guard.
     */
    @Test
    void thesignedInEmployeeIsResolvedByLogin() {
        AdminCashPouchResource resource = newResource();
        Employee carrier = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice", "", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(carrier);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            assertSame(carrier, resource.signedIn());
            assertNull(resource.signedIn());
            assertNull(resource.signedIn());
        }
        AdminCashPouchResource anonymous = newResource();
        anonymous.currentUser = null;
        assertNull(anonymous.signedIn());
    }

    /**
     * A null withdrawn total is shown as zero: the null arm of the amount
     * format, forced by stubbing the day total to null on a spy since the real
     * accumulator never yields null.
     */
    @Test
    void aNullWithdrawnTotalIsShownAsZero() {
        AdminCashPouchResource resource = spy(newResource());
        TemplateInstance view = stub(resource.adminSafePouches);
        doReturn(null).when(resource).withdrawnOf(any());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubSessions(panache, LocalDate.now(), List.of());
            stubPouches(panache, List.of());
            assertSame(view, resource.list(null, null, true));
        }
        verify(view).data("withdrawn", "0.00");
    }
    /**
     * A pouch waits for the validation of its day when the back office asks
     * for it, and is accepted once the day is validated (BO-05-02-10).
     */
    @Test
    void apouchWaitsForTheValidationOfItsDay() {
        AdminCashPouchResource resource = newResource();
        when(resource.posSettingsService.pouchRequiresDayValidation()).thenReturn(true);
        LocalDate day = LocalDate.of(2026, 9, 15);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("pouchNumber", "POCH-3");
        form.add("accountingDay", "2026-09-15");
        form.add("amount", "10,00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashPouch> created = mockConstruction(CashPouch.class)) {
            PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
            panache.when(() -> CashPouch.find("pouchNumber", "POCH-3")).thenReturn(query);
            stubValidation(panache, day, null);
            String location = java.net.URLDecoder.decode(resource.deposit(form).getLocation().toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(location.contains("La journée comptable n'est pas validée"));
            assertTrue(created.constructed().isEmpty());
            stubValidation(panache, day, new com.intermarche.edge.domain.safe.SafeDayValidation());
            assertNull(resource.sequenceRefusal(day));
        }
    }

    /**
     * An accounting day takes at most the administered number of pouches; no
     * limit at zero (BO-05-02-10).
     */
    @Test
    void anAccountingDayTakesAtMostItsPouches() {
        AdminCashPouchResource resource = newResource();
        LocalDate day = LocalDate.of(2026, 9, 15);
        when(resource.posSettingsService.pouchesPerDay()).thenReturn(2, 2, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashPouch.count("accountingDay", day)).thenReturn(2L, 1L);
            assertEquals("Cette journée comptable compte déjà 2 pochette(s) remise(s) au coffre.",
                    resource.sequenceRefusal(day));
            assertNull(resource.sequenceRefusal(day));
            assertNull(resource.sequenceRefusal(day));
        }
    }
}
