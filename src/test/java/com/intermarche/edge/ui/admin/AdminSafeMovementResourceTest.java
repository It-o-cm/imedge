package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.edge.domain.safe.SafeMovementType;
import com.intermarche.edge.domain.safe.SafeVatLine;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminSafeMovementResource}.
 *
 * <p>The screen refuses before the service is ever asked: an unknown kind, a
 * kind that is not offered for entry, an unusable amount and a missing wording
 * are each asserted on their own leg, and the VAT breakdown is read on its
 * stated and its blank rows. The safe itself is a mock — what it does with a
 * movement is proved on {@code SafeServiceTest}.
 */
class AdminSafeMovementResourceTest {

    /**
     * Builds a resource with mocked template, safe and identity.
     *
     * @return the wired resource
     */
    private AdminSafeMovementResource newResource() {
        AdminSafeMovementResource resource = new AdminSafeMovementResource();
        resource.adminSafeMovements = mock(Template.class);
        resource.safeService = mock(SafeService.class);
        resource.currentUser = mock(CurrentUser.class);
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
     * Builds a posted movement form.
     *
     * @param type the kind name
     * @param amount the amount as typed
     * @param label the wording as typed
     * @return the form
     */
    private MultivaluedMap<String, String> form(String type, String amount, String label) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("type", type);
        form.add("amount", amount);
        form.add("label", label);
        return form;
    }

    /**
     * Stubs the payroll listing the screen offers.
     *
     * @param panache the active PanacheEntityBase mock
     * @param employees the files to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubEmployees(MockedStatic<PanacheEntityBase> panache, List<Employee> employees) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(employees);
        panache.when(() -> Employee.find("active = true order by lastName, firstName"))
                .thenReturn(query);
    }

    /**
     * The list page carries the period, the balance, what is left to point and
     * the kinds offered.
     */
    @Test
    void thelistPageCarriesThePeriodAndItsMovements() {
        AdminSafeMovementResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeMovements);
        AccountingPeriod period = new AccountingPeriod();
        SafeMovement movement = new SafeMovement();
        when(resource.safeService.openPeriod()).thenReturn(period);
        when(resource.safeService.movementsOf(period)).thenReturn(List.of(movement));
        when(resource.safeService.balance()).thenReturn(new BigDecimal("460"));
        when(resource.safeService.countPending(period)).thenReturn(2L);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployees(panache, List.of());
            assertSame(view, resource.list(null, true));
        }
        verify(resource.adminSafeMovements).data("movements", List.of(movement));
        verify(view).data("period", period);
        verify(view).data("balance", "460.00");
        verify(view).data("pending", 2L);
    }

    /**
     * Only the two kinds offered for entry reach the screen's picker.
     */
    @Test
    void onlyTheKindsOfferedForEntryReachThePicker() {
        AdminSafeMovementResource resource = newResource();
        assertEquals(List.of(SafeMovementType.EXPENSE, SafeMovementType.RECEIPT),
                resource.manualTypes());
    }

    /**
     * An unknown kind, and a kind that is not offered for entry, are refused
     * before the safe is ever asked — the two legs of that guard.
     */
    @Test
    void anUnknownOrUnofferedKindIsRefused() {
        AdminSafeMovementResource resource = newResource();
        Response unknown = resource.record(form("DONATION", "40,00", "Fournitures"));
        assertEquals(303, unknown.getStatus());
        assertTrue(unknown.getLocation().toString().contains("noticeOk=false"));
        Response unoffered = resource.record(form("POUCH", "40,00", "Fournitures"));
        assertTrue(unoffered.getLocation().toString().contains("noticeOk=false"));
        verify(resource.safeService, never()).record(any(), any(), anyString(), any(), any(),
                any(), any(), any());
    }

    /**
     * An absent, unreadable, zero or negative amount is refused — the four legs
     * of that guard.
     */
    @Test
    void anUnusableAmountIsRefused() {
        AdminSafeMovementResource resource = newResource();
        for (String amount : new String[] {null, "quarante", "0", "-40,00"}) {
            Response response = resource.record(form("EXPENSE", amount, "Fournitures"));
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
        verify(resource.safeService, never()).record(any(), any(), anyString(), any(), any(),
                any(), any(), any());
    }

    /**
     * A missing wording is refused: the journal is read by people.
     */
    @Test
    void amissingWordingIsRefused() {
        AdminSafeMovementResource resource = newResource();
        Response response = resource.record(form("EXPENSE", "40,00", "   "));
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * A complete entry reaches the safe with everything the form carried.
     */
    @Test
    void acompleteEntryReachesTheSafe() {
        AdminSafeMovementResource resource = newResource();
        MultivaluedMap<String, String> form = form("EXPENSE", "40,00", "Fournitures");
        form.add("movementDate", "2026-09-15");
        form.add("reference", "FACT-12");
        form.add("employeeId", "5");
        form.add("vatRate0", "20,00");
        form.add("vatBase0", "33,33");
        form.add("vatAmount0", "6,67");
        Employee concerned = new Employee();
        Employee author = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice");
        when(resource.safeService.record(any(), any(), anyString(), any(), any(), any(), any(),
                any())).thenReturn(new SafeMovement());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(5L)).thenReturn(concerned);
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(author);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            Response response = resource.record(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        verify(resource.safeService).record(eq(SafeMovementType.EXPENSE),
                eq(new BigDecimal("40.00")), eq("Fournitures"), eq(LocalDate.of(2026, 9, 15)),
                eq(author), eq(concerned), eq("FACT-12"), any());
    }

    /**
     * An entry the safe refuses — no period open — is announced as refused.
     */
    @Test
    void anEntryTheSafeRefusesIsAnnouncedAsRefused() {
        AdminSafeMovementResource resource = newResource();
        when(resource.safeService.record(any(), any(), anyString(), any(), any(), any(), any(),
                any())).thenReturn(null);
        Response response = resource.record(form("RECEIPT", "40,00", "Parking"));
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * The VAT breakdown keeps the rows that state something and drops the ones
     * left blank — both arms, over the three rows the screen offers.
     */
    @Test
    void thevatBreakdownKeepsOnlyTheStatedRows() {
        AdminSafeMovementResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("vatRate0", "20,00");
        form.add("vatBase0", "100,00");
        form.add("vatAmount0", "20,00");
        form.add("vatRate1", "5,50");
        form.add("vatRate2", "");
        List<SafeVatLine> lines = resource.vatLinesOf(form);
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("100.00"), lines.get(0).base);
        assertTrue(resource.vatLinesOf(new MultivaluedHashMap<>()).isEmpty());
    }

    /**
     * Validating one movement asks the safe, and says so when there was nothing
     * to validate — both arms.
     */
    @Test
    void validatingOneMovementAsksTheSafe() {
        AdminSafeMovementResource resource = newResource();
        SafeMovement movement = new SafeMovement();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.findById(3L)).thenReturn(movement);
            when(resource.safeService.validate(eq(movement), isNull())).thenReturn(true, false);
            assertTrue(resource.validate(form).getLocation().toString().contains("noticeOk=true"));
            assertTrue(resource.validate(form).getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * Validating an unknown movement is refused, whether the identifier is
     * absent or names nothing — the two legs of that guard.
     */
    @Test
    void validatingAnUnknownMovementIsRefused() {
        AdminSafeMovementResource resource = newResource();
        assertTrue(resource.validate(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.findById(9L)).thenReturn(null);
            assertTrue(resource.validate(form).getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * Validating the whole period counts what actually moved.
     */
    @Test
    void validatingThePeriodCountsWhatMoved() {
        AdminSafeMovementResource resource = newResource();
        SafeMovement first = new SafeMovement();
        SafeMovement second = new SafeMovement();
        AccountingPeriod period = new AccountingPeriod();
        when(resource.safeService.openPeriod()).thenReturn(period);
        when(resource.safeService.movementsOf(period)).thenReturn(List.of(first, second));
        when(resource.safeService.validate(eq(first), isNull())).thenReturn(true);
        when(resource.safeService.validate(eq(second), isNull())).thenReturn(false);
        Response response = resource.validateAll();
        assertTrue(response.getLocation().toString().contains("1+mouvement"));
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * A correction hands the safe what was typed and reports its refusal —
     * both arms.
     */
    @Test
    void acorrectionHandsTheSafeWhatWasTyped() {
        AdminSafeMovementResource resource = newResource();
        SafeMovement movement = new SafeMovement();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        form.add("amount", "55,00");
        form.add("label", "Papeterie");
        form.add("movementDate", "2026-09-16");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.findById(3L)).thenReturn(movement);
            when(resource.safeService.correct(movement, new BigDecimal("55.00"), "Papeterie",
                    LocalDate.of(2026, 9, 16))).thenReturn(null, SafeService.FROZEN_MOVEMENT);
            assertTrue(resource.correct(form).getLocation().toString().contains("noticeOk=true"));
            assertTrue(resource.correct(form).getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * The signed-in employee is resolved by login, and is nobody when the
     * session names nobody — the three legs of that guard.
     */
    @Test
    void thesignedInEmployeeIsResolvedByLogin() {
        AdminSafeMovementResource resource = newResource();
        Employee author = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice", "", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(author);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            assertSame(author, resource.signedIn());
            assertNull(resource.signedIn());
            assertNull(resource.signedIn());
        }
        AdminSafeMovementResource anonymous = newResource();
        anonymous.currentUser = null;
        assertNull(anonymous.signedIn());
    }

    /**
     * A null balance is shown as zero: the null arm of the amount format.
     */
    @Test
    void aNullBalanceIsShownAsZero() {
        AdminSafeMovementResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeMovements);
        AccountingPeriod period = new AccountingPeriod();
        when(resource.safeService.openPeriod()).thenReturn(period);
        when(resource.safeService.movementsOf(period)).thenReturn(List.of());
        when(resource.safeService.balance()).thenReturn(null);
        when(resource.safeService.countPending(period)).thenReturn(0L);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployees(panache, List.of());
            assertSame(view, resource.list(null, true));
        }
        verify(view).data("balance", "0.00");
    }
}
