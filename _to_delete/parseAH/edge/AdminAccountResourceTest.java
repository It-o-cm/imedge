package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAccountResource} (BO-01-01-01, -02, -06).
 *
 * <p>The screen is a Panache consumer over {@link Employee}: the lookups
 * resolve to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. No database and no Quarkus context
 * is booted; the redirect's query string carries the outcome, which is what
 * each case asserts.
 *
 * <p>Branch enumeration: the three mandatory fields (each absent in turn), a
 * login already taken by ANOTHER account and by the SAME one, a new account
 * without an initial secret, a secret shorter than the floor, an unknown id on
 * save and on delete, a holder the history names, and the role fallback.
 */
class AdminAccountResourceTest {

    /**
     * Builds a form carrying everything a creation needs.
     *
     * @return the valid form
     */
    private MultivaluedMap<String, String> validForm() {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("loginName", "amartin");
        form.putSingle("lastName", "Martin");
        form.putSingle("firstName", "Alice");
        form.putSingle("email", "a.martin@magasin.fr");
        form.putSingle("secret", "4821");
        form.putSingle("role", "MANAGER");
        form.putSingle("hierarchyLevel", "2");
        form.putSingle("active", "on");
        return form;
    }

    /**
     * Reads the notice the redirect carries.
     *
     * @param response the redirect
     * @return its query string, which carries the notice
     */
    private String notice(Response response) {
        return response.getLocation().getQuery();
    }

    /**
     * BO-01-01-01: identifier, surname and electronic address are the three a
     * creation cannot do without — each one absent refuses on its own.
     */
    @Test
    void theThreeMandatoryFieldsAreMandatory() {
        for (String missing : new String[]{"loginName", "lastName", "email"}) {
            MultivaluedMap<String, String> form = validForm();
            form.remove(missing);
            Response response = new AdminAccountResource().save(form);
            assertTrue(notice(response).contains("obligatoires"), missing);
        }
    }

    /**
     * BO-01-01-02: a login already taken by ANOTHER account is refused, and the
     * same login carried by the account being edited is not.
     */
    @Test
    void aLoginIsTakenOnlyByAnotherAccount() {
        // A MOCK and not a real row: the valid branch persists, and a persist
        // outside a transaction would be the only thing this case proves.
        Employee other = mock(Employee.class);
        other.id = 7L;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> query = query(other);
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(query);
            MultivaluedMap<String, String> creation = validForm();
            assertTrue(notice(new AdminAccountResource().save(creation)).contains("déjà"));

            MultivaluedMap<String, String> itself = validForm();
            itself.putSingle("id", "7");
            ms.when(() -> Employee.findById(7L)).thenReturn(other);
            assertFalse(notice(new AdminAccountResource().save(itself)).contains("déjà"));
        }
    }

    /**
     * BO-01-01-01: a NEW account demands an initial secret, and any secret must
     * reach the floor — four characters, the same floor the register's PIN has.
     */
    @Test
    void anInitialSecretIsDemandedAndMustReachTheFloor() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(query(null));
            MultivaluedMap<String, String> without = validForm();
            without.remove("secret");
            assertTrue(notice(new AdminAccountResource().save(without)).contains("code initial"));

            MultivaluedMap<String, String> tooShort = validForm();
            tooShort.putSingle("secret", "12");
            assertTrue(notice(new AdminAccountResource().save(tooShort)).contains("moins"));
        }
    }

    /**
     * An unknown id is an unknown account, on the save as on the delete.
     */
    @Test
    void anUnknownAccountIsRefused() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.find("loginName", "amartin")).thenReturn(query(null));
            ms.when(() -> Employee.findById(42L)).thenReturn(null);
            MultivaluedMap<String, String> form = validForm();
            form.putSingle("id", "42");
            assertTrue(notice(new AdminAccountResource().save(form)).contains("inconnu"));

            MultivaluedMap<String, String> deletion = new MultivaluedHashMap<>();
            deletion.putSingle("id", "42");
            assertTrue(notice(new AdminAccountResource().delete(deletion)).contains("inconnu"));
            assertTrue(notice(new AdminAccountResource().delete(new MultivaluedHashMap<>()))
                    .contains("inconnu"));
        }
    }

    /**
     * BO-01-01-02: an account the HISTORY names is not erased — it is
     * deactivated, because erasing it would orphan the journal that names it.
     */
    @Test
    void aReferencedAccountIsNotErased() {
        AdminAccountResource resource = new AdminAccountResource() {
            @Override
            boolean referenced(Employee employee) {
                return true;
            }
        };
        Employee account = new Employee();
        account.id = 7L;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(account);
            MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
            form.putSingle("id", "7");
            assertTrue(notice(resource.delete(form)).contains("historique"));
        }
    }

    /**
     * An absent or unknown role answers the CASHIER, which is the least any
     * account can do — never the ADMIN a typo would otherwise grant.
     */
    @Test
    void anUnknownRoleFallsBackToTheCashier() {
        AdminAccountResource resource = new AdminAccountResource();
        assertEquals(Employee.EmployeeRole.CASHIER, resource.role(null));
        assertEquals(Employee.EmployeeRole.CASHIER, resource.role("   "));
        assertEquals(Employee.EmployeeRole.CASHIER, resource.role("SORCIER"));
        assertEquals(Employee.EmployeeRole.ADMIN, resource.role("admin"));
        assertEquals(Employee.EmployeeRole.MANAGER, resource.role(" MANAGER "));
    }

    /**
     * Builds a Panache query whose {@code firstResult} answers the given row.
     *
     * @param result the row to answer, or null for none
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> query(Employee result) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }
}
