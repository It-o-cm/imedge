package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.safe.AccountingPeriod;
import com.intermarche.pos.security.CurrentUser;
import com.intermarche.pos.service.SafeService;
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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminSafePeriodResource}.
 *
 * <p>The screen that opens and closes the safe. What is proved here is that a
 * period without a wording never reaches the safe, that a second opening is
 * announced as refused, and that the closure's refusal — pending movements —
 * travels back to the operator rather than being swallowed.
 */
class AdminSafePeriodResourceTest {

    /**
     * Builds a resource with mocked template, safe and identity.
     *
     * @return the wired resource
     */
    private AdminSafePeriodResource newResource() {
        AdminSafePeriodResource resource = new AdminSafePeriodResource();
        resource.adminSafePeriods = mock(Template.class);
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
     * The list page carries the open period, the balance and what still blocks
     * the closure.
     */
    @Test
    void thelistPageCarriesWhatBlocksTheClosure() {
        AdminSafePeriodResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafePeriods);
        AccountingPeriod open = new AccountingPeriod();
        when(resource.safeService.openPeriod()).thenReturn(open);
        when(resource.safeService.balance()).thenReturn(new BigDecimal("460"));
        when(resource.safeService.countPending(open)).thenReturn(2L);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(open));
            panache.when(() -> AccountingPeriod.find("order by startDate desc")).thenReturn(query);
            assertSame(view, resource.list(null, true));
        }
        verify(resource.adminSafePeriods).data("periods", List.of(open));
        verify(view).data("open", open);
        verify(view).data("balance", "460.00");
        verify(view).data("pending", 2L);
    }

    /**
     * A period without a wording never reaches the safe.
     */
    @Test
    void aperiodWithoutAwordingNeverReachesTheSafe() {
        AdminSafePeriodResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("label", "   ");
        Response response = resource.open(form);
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.safeService, never()).open(anyString(), any());
    }

    /**
     * A named period reaches the safe with its first day, and the absent day is
     * passed on as such — both arms of that guard.
     */
    @Test
    void anamedPeriodReachesTheSafe() {
        AdminSafePeriodResource resource = newResource();
        AccountingPeriod opened = new AccountingPeriod();
        opened.label = "2026-09";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("label", "2026-09");
        form.add("startDate", "2026-09-01");
        when(resource.safeService.open("2026-09", LocalDate.of(2026, 9, 1))).thenReturn(opened);
        when(resource.safeService.open("2026-09", null)).thenReturn(opened);
        assertTrue(resource.open(form).getLocation().toString().contains("noticeOk=true"));
        MultivaluedMap<String, String> undated = new MultivaluedHashMap<>();
        undated.add("label", "2026-09");
        assertTrue(resource.open(undated).getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * A second opening is announced as refused.
     */
    @Test
    void asecondOpeningIsAnnouncedAsRefused() {
        AdminSafePeriodResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("label", "2026-10");
        when(resource.safeService.open(anyString(), any())).thenReturn(null);
        assertTrue(resource.open(form).getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * The closure's refusal travels back to the operator, and its success too —
     * both arms.
     */
    @Test
    void theclosureRefusalTravelsBack() {
        AdminSafePeriodResource resource = newResource();
        when(resource.safeService.close(isNull()))
                .thenReturn(SafeService.PENDING_MOVEMENTS, (String) null);
        assertTrue(resource.close().getLocation().toString().contains("noticeOk=false"));
        assertTrue(resource.close().getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * The closure names the signed-in employee, and nobody when the session
     * names nobody — the three legs of that guard.
     */
    @Test
    void theclosureNamesTheSignedInEmployee() {
        AdminSafePeriodResource resource = newResource();
        Employee closer = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice", "", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(closer);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            assertSame(closer, resource.signedIn());
            assertNull(resource.signedIn());
            assertNull(resource.signedIn());
        }
        AdminSafePeriodResource anonymous = newResource();
        anonymous.currentUser = null;
        assertNull(anonymous.signedIn());
    }

    /**
     * A null balance is shown as zero: the null arm of the amount format.
     */
    @Test
    void aNullBalanceIsShownAsZero() {
        AdminSafePeriodResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafePeriods);
        AccountingPeriod open = new AccountingPeriod();
        when(resource.safeService.openPeriod()).thenReturn(open);
        when(resource.safeService.balance()).thenReturn(null);
        when(resource.safeService.countPending(open)).thenReturn(0L);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<AccountingPeriod> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(open));
            panache.when(() -> AccountingPeriod.find("order by startDate desc")).thenReturn(query);
            assertSame(view, resource.list(null, true));
        }
        verify(view).data("balance", "0.00");
    }
}
