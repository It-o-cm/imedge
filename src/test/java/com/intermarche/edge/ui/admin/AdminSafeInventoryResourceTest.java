package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.SafeDenomination;
import com.intermarche.edge.domain.safe.SafeInventory;
import com.intermarche.edge.domain.safe.SafeInventoryLine;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminSafeInventoryResource}.
 *
 * <p>The count sheet. What is proved here is the reader that turns it into
 * lines — a declared zero IS a line, a blank field is not — and the two arms of
 * the outcome message, balanced and not. Writing the discrepancy off reports
 * what the safe answered on both of its arms.
 */
class AdminSafeInventoryResourceTest {

    /**
     * Builds a resource with mocked template, safe and identity.
     *
     * @return the wired resource
     */
    private AdminSafeInventoryResource newResource() {
        AdminSafeInventoryResource resource = new AdminSafeInventoryResource();
        resource.adminSafeInventories = mock(Template.class);
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
     * The page carries the counts, the two denomination lists and the
     * theoretical of the moment.
     */
    @Test
    void thepageCarriesTheCountsAndTheTheoretical() {
        AdminSafeInventoryResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeInventories);
        SafeInventory inventory = new SafeInventory();
        when(resource.safeService.balance()).thenReturn(new BigDecimal("540"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<SafeInventory> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(inventory));
            panache.when(() -> SafeInventory.find("order by countedAt desc, id desc"))
                    .thenReturn(query);
            assertSame(view, resource.list(null, true));
        }
        verify(resource.adminSafeInventories).data("inventories", List.of(inventory));
        verify(view).data("notes", SafeDenomination.notes());
        verify(view).data("coins", SafeDenomination.coins());
        verify(view).data("theoretical", "540.00");
    }

    /**
     * A declared ZERO is a counted line; a field left blank is not — the two
     * arms of that reader.
     */
    @Test
    void adeclaredZeroIsAlineAndAblankFieldIsNot() {
        AdminSafeInventoryResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("qtyN50", "10");
        form.add("qtyN20", "0");
        form.add("qtyN10", "   ");
        List<SafeInventoryLine> lines = resource.countedLines(form);
        assertEquals(2, lines.size());
        assertEquals(10, lines.get(0).quantity);
        assertEquals(0, lines.get(1).quantity);
        assertTrue(resource.countedLines(new MultivaluedHashMap<>()).isEmpty());
    }

    /**
     * An unreadable or negative quantity counts as none rather than as a
     * negative pile of notes — the two clamping legs.
     */
    @Test
    void anUnreadableOrNegativeQuantityCountsAsNone() {
        AdminSafeInventoryResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("qtyN50", "beaucoup");
        form.add("qtyN20", "-3");
        List<SafeInventoryLine> lines = resource.countedLines(form);
        assertEquals(0, lines.get(0).quantity);
        assertEquals(0, lines.get(1).quantity);
    }

    /**
     * A sheet counting nothing at all is refused.
     */
    @Test
    void asheetCountingNothingIsRefused() {
        AdminSafeInventoryResource resource = newResource();
        Response response = resource.count(new MultivaluedHashMap<>());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.safeService, never()).takeInventory(any(), any());
    }

    /**
     * A count reaches the safe with its lines and its comment, and the message
     * says whether it found a discrepancy — the two arms.
     */
    @Test
    void acountReachesTheSafeAndReportsItsOutcome() {
        AdminSafeInventoryResource resource = newResource();
        Employee counter = new Employee();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("qtyN50", "10");
        form.add("comment", "Contrôle hebdomadaire");
        when(resource.currentUser.getName()).thenReturn("alice");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeInventory> created =
                        mockConstruction(SafeInventory.class, (built, context) -> {
                            when(built.isBalanced()).thenReturn(true);
                        })) {
            PanacheQuery<Employee> who = mock(PanacheQuery.class);
            when(who.firstResult()).thenReturn(counter);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(who);
            Response response = resource.count(form);
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            SafeInventory written = created.constructed().get(0);
            assertEquals(1, written.lines.size());
            assertEquals("Contrôle hebdomadaire", written.comment);
            verify(resource.safeService).takeInventory(written, counter);
        }
    }

    /**
     * A count that found a discrepancy says so, and says how much.
     */
    @Test
    void acountThatFoundAdiscrepancySaysHowMuch() {
        AdminSafeInventoryResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("qtyN50", "10");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SafeInventory> created =
                        mockConstruction(SafeInventory.class, (built, context) -> {
                            when(built.isBalanced()).thenReturn(false);
                            when(built.getVarianceFormatted()).thenReturn("-15.00");
                        })) {
            String location = resource.count(form).getLocation().toString();
            assertTrue(location.contains("noticeOk=false"));
            assertTrue(location.contains("-15%2C00"));
        }
    }

    /**
     * Writing off reports what the safe answered, on both of its arms, and an
     * unknown count is refused on both legs of its guard.
     */
    @Test
    void writingOffReportsWhatTheSafeAnswered() {
        AdminSafeInventoryResource resource = newResource();
        SafeInventory inventory = new SafeInventory();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeInventory.findById(3L)).thenReturn(inventory);
            when(resource.safeService.adjustFromInventory(any(), any()))
                    .thenReturn(null, "Refusé.");
            assertTrue(resource.adjust(form).getLocation().toString().contains("noticeOk=true"));
            assertTrue(resource.adjust(form).getLocation().toString().contains("noticeOk=false"));
        }
        when(resource.safeService.adjustFromInventory(any(), any())).thenReturn("Refusé.");
        assertTrue(resource.adjust(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
    }

    /**
     * The signed-in employee is resolved by login, and is nobody when the
     * session names nobody — the three legs of that guard.
     */
    @Test
    void thesignedInEmployeeIsResolvedByLogin() {
        AdminSafeInventoryResource resource = newResource();
        Employee counter = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice", "", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(counter);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            assertSame(counter, resource.signedIn());
            assertNull(resource.signedIn());
            assertNull(resource.signedIn());
        }
        AdminSafeInventoryResource anonymous = newResource();
        anonymous.currentUser = null;
        assertNull(anonymous.signedIn());
    }

    /**
     * A null theoretical balance is shown as zero: the null arm of the amount
     * format.
     */
    @Test
    void aNullTheoreticalIsShownAsZero() {
        AdminSafeInventoryResource resource = newResource();
        TemplateInstance view = stub(resource.adminSafeInventories);
        when(resource.safeService.balance()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<SafeInventory> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of());
            panache.when(() -> SafeInventory.find("order by countedAt desc, id desc"))
                    .thenReturn(query);
            assertSame(view, resource.list(null, true));
        }
        verify(view).data("theoretical", "0.00");
    }
}
