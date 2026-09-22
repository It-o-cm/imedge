package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.safe.SafeMovement;
import com.intermarche.pos.domain.safe.SafeMovementType;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminEmployeeResource}.
 *
 * <p>The payroll file, beside the user file and never instead of it. What is
 * proved here is that the screen writes the three payroll columns and NOTHING
 * else — a screen that could rewrite a credential would be a way around the
 * rights model — and that the search matches on each of its three fields.
 */
class AdminEmployeeResourceTest {

    /**
     * Builds a resource with a mocked template.
     *
     * @return the wired resource
     */
    private AdminEmployeeResource newResource() {
        AdminEmployeeResource resource = new AdminEmployeeResource();
        resource.adminEmployees = mock(Template.class);
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
     * Builds a payroll file.
     *
     * @param last the surname or business name
     * @param first the first name, null on a legal entity
     * @param login the login name
     * @return the file
     */
    private Employee employee(String last, String first, String login) {
        Employee employee = new Employee();
        employee.lastName = last;
        employee.firstName = first;
        employee.loginName = login;
        employee.role = Employee.EmployeeRole.CASHIER;
        return employee;
    }

    /**
     * Stubs the payroll listing.
     *
     * @param panache the active PanacheEntityBase mock
     * @param employees the files to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubAll(MockedStatic<PanacheEntityBase> panache, List<Employee> employees) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(employees);
        panache.when(() -> Employee.find("order by lastName, firstName")).thenReturn(query);
    }

    /**
     * Without a criterion, every file is listed.
     */
    @Test
    void withoutAcriterionEveryFileIsListed() {
        AdminEmployeeResource resource = newResource();
        TemplateInstance view = stub(resource.adminEmployees);
        Employee first = employee("Doe", "Jane", "jdoe");
        Employee second = employee("NETTOYAGE SARL", null, "netto");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(first, second));
            assertSame(view, resource.list(null, 1, null, true));
        }
        org.mockito.ArgumentCaptor<Object> captor =
                org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(resource.adminEmployees)
                .data(org.mockito.ArgumentMatchers.eq("page"), captor.capture());
        com.intermarche.pos.ui.journal.JournalPage<?> window =
                (com.intermarche.pos.ui.journal.JournalPage<?>) captor.getValue();
        assertEquals(List.of(first, second), window.rows);
        assertEquals(2L, window.total);
        verify(view).data("text", "");
    }

    /**
     * The search matches on the surname, the first name and the login — the
     * three legs of its compound guard, each alone.
     */
    @Test
    void thesearchMatchesOnEachOfItsThreeFields() {
        AdminEmployeeResource resource = newResource();
        Employee doe = employee("Doe", "Jane", "jdoe");
        Employee netto = employee("NETTOYAGE SARL", null, "netto");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(doe, netto));
            assertEquals(List.of(doe), resource.search("doe"));
            assertEquals(List.of(doe), resource.search("jane"));
            assertEquals(List.of(netto), resource.search("netto"));
            assertEquals(List.of(), resource.search("introuvable"));
            assertEquals(List.of(doe, netto), resource.search("   "));
        }
    }

    /**
     * A file carrying no name at all matches nothing rather than exploding — the
     * null arms of the three legs.
     */
    @Test
    void afileWithoutAnameMatchesNothing() {
        AdminEmployeeResource resource = newResource();
        Employee bare = new Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubAll(panache, List.of(bare));
            assertEquals(List.of(), resource.search("doe"));
        }
    }

    /**
     * Saving an unknown file is refused, whether the identifier is absent or
     * names nothing — the two legs of that guard.
     */
    @Test
    void savingAnUnknownFileIsRefused() {
        AdminEmployeeResource resource = newResource();
        assertTrue(resource.save(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(9L)).thenReturn(null);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * A negative advance ceiling is refused: a ceiling below zero authorizes
     * nothing and reads as a typing slip.
     */
    @Test
    void anegativeCeilingIsRefused() {
        AdminEmployeeResource resource = newResource();
        Employee employee = mock(Employee.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "5");
        form.add("advanceLimit", "-10,00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(5L)).thenReturn(employee);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=false"));
            verify(employee, never()).persist();
        }
    }

    /**
     * A save writes the three payroll columns and leaves the credential columns
     * exactly as they were.
     */
    @Test
    void asaveWritesThePayrollColumnsAndNothingElse() {
        AdminEmployeeResource resource = newResource();
        Employee employee = mock(Employee.class);
        employee.loginName = "jdoe";
        employee.password = "hash";
        employee.role = Employee.EmployeeRole.CASHIER;
        employee.badgeId = "B01";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "5");
        form.add("legalEntity", "on");
        form.add("forcingLevel", "3");
        form.add("advanceLimit", "150,00");
        form.add("loginName", "pirate");
        form.add("role", "ADMIN");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(5L)).thenReturn(employee);
            assertTrue(resource.save(form).getLocation().toString().contains("noticeOk=true"));
        }
        assertTrue(employee.legalEntity);
        assertEquals(3, employee.forcingLevel);
        assertEquals(new BigDecimal("150.00"), employee.advanceLimit);
        assertEquals("jdoe", employee.loginName);
        assertEquals("hash", employee.password);
        assertEquals(Employee.EmployeeRole.CASHIER, employee.role);
        assertEquals("B01", employee.badgeId);
        verify(employee).persist();
    }

    /**
     * A save with the legal-entity box absent, no ceiling and an unreadable rank
     * clears them rather than keeping what was there — the false and null arms
     * of the three columns.
     */
    @Test
    void asaveWithoutTheColumnsClearsThem() {
        AdminEmployeeResource resource = newResource();
        Employee employee = mock(Employee.class);
        employee.legalEntity = true;
        employee.forcingLevel = 3;
        employee.advanceLimit = new BigDecimal("150.00");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "5");
        form.add("forcingLevel", "moyen");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(5L)).thenReturn(employee);
            resource.save(form);
        }
        assertFalse(employee.legalEntity);
        assertEquals(0, employee.forcingLevel);
        assertNull(employee.advanceLimit);
    }

    /**
     * A negative rank is clamped to none: a level below zero orders nothing.
     */
    @Test
    void anegativeRankIsClampedToNone() {
        AdminEmployeeResource resource = newResource();
        Employee employee = mock(Employee.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "5");
        form.add("forcingLevel", "-2");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(5L)).thenReturn(employee);
            resource.save(form);
        }
        assertEquals(0, employee.forcingLevel);
    }

    /**
     * What the safe already paid a file is the opposite of what its movements
     * add to the safe: an expense made out to somebody is money they received.
     */
    @Test
    @SuppressWarnings("unchecked")
    void whatThesafePaidAfileIsTheOppositeOfItsMovements() {
        AdminEmployeeResource resource = newResource();
        Employee employee = new Employee();
        employee.id = 5L;
        SafeMovement advance = new SafeMovement();
        advance.type = SafeMovementType.EXPENSE;
        advance.amount = new BigDecimal("150.00");
        SafeMovement repaid = new SafeMovement();
        repaid.type = SafeMovementType.RECEIPT;
        repaid.amount = new BigDecimal("50.00");
        PanacheQuery<SafeMovement> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(advance, repaid));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeMovement.find("employee.id = ?1", 5L)).thenReturn(query);
            assertEquals(new BigDecimal("100.00"), resource.paidTo(employee));
        }
    }

    /**
     * A null file, and one never written, were paid nothing — the two legs of
     * that guard.
     */
    @Test
    void anAbsentFileWasPaidNothing() {
        AdminEmployeeResource resource = newResource();
        assertEquals(BigDecimal.ZERO, resource.paidTo(null));
        assertEquals(BigDecimal.ZERO, resource.paidTo(new Employee()));
    }
}
