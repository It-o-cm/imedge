package com.intermarche.pos.service;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.people.EmployeeProfile;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.Profile;
import com.intermarche.pos.domain.people.ProfileGrant;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PermissionService}.
 * <p>
 * The database reads resolve to {@link PanacheEntityBase} static finders under
 * plain {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; {@code decideInTransaction} is
 * exercised directly (it is the whole logic) and the thin {@code decide}
 * wrapper is covered once through the {@link QuarkusTransaction} deep-stub
 * pattern, executing the callable it receives.
 * <p>
 * Branch enumeration (every arm exercised): unknown employee (deny), ADMIN
 * superuser (allow before any profile lookup), a granting profile on both a
 * coded node and a store-less node (allow, validation terms propagated), and
 * the three fall-through continues — binding scoped elsewhere, missing
 * profile, missing grant — ending in a final deny.
 */
class PermissionServiceTest {

    /** The exact finder query {@code Employee.findActiveLogin} issues. */
    private static final String ACTIVE_LOGIN_QUERY =
            "(badgeId = ?1 or loginName = ?1) and active = true";

    /** The service under test. */
    private final PermissionService service = new PermissionService();

    /**
     * Builds an employee with an id and a role.
     *
     * @param id the id
     * @param role the role
     * @return the employee
     */
    private Employee employee(long id, Employee.EmployeeRole role) {
        Employee employee = new Employee();
        employee.id = id;
        employee.loginName = "mcurie";
        employee.role = role;
        return employee;
    }

    /**
     * Builds a store row with a code.
     *
     * @param code the point-of-sale code
     * @return the store
     */
    private Store store(String code) {
        Store store = new Store();
        store.code = code;
        return store;
    }

    /**
     * Builds a global binding to a profile.
     *
     * @param profileId the bound profile id
     * @param scope the echelon scope
     * @return the binding
     */
    private EmployeeProfile binding(long profileId, String scope) {
        EmployeeProfile binding = new EmployeeProfile();
        binding.employeeId = 7L;
        binding.profileId = profileId;
        binding.echelonScope = scope;
        return binding;
    }

    /**
     * Builds a profile holding the given grants.
     *
     * @param grants the grants
     * @return the profile
     */
    private Profile profile(ProfileGrant... grants) {
        Profile profile = new Profile();
        for (ProfileGrant grant : grants) {
            profile.grants.add(grant);
        }
        return profile;
    }

    /**
     * Stubs {@code Employee.findActiveLogin("mcurie")} to resolve the given
     * employee within the active static mock.
     *
     * @param panache the active PanacheEntityBase mock
     * @param employee the employee to resolve, possibly null
     */
    @SuppressWarnings("unchecked")
    private void stubEmployee(MockedStatic<PanacheEntityBase> panache, Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        panache.when(() -> Employee.find(ACTIVE_LOGIN_QUERY, "mcurie")).thenReturn(query);
    }

    /**
     * Stubs {@code Store.findAll().firstResult()} to resolve the given store.
     *
     * @param panache the active PanacheEntityBase mock
     * @param store the store to resolve, possibly null
     */
    @SuppressWarnings("unchecked")
    private void stubStore(MockedStatic<PanacheEntityBase> panache, Store store) {
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(store);
        panache.when(PanacheEntityBase::findAll).thenReturn(query);
    }

    /**
     * An unknown login name is denied (employee-null arm).
     */
    @Test
    void deniesUnknownEmployee() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, null);
            assertFalse(service.decideInTransaction("mcurie", Feature.SETTINGS, CrudOption.VIEW).isAllowed());
        }
    }

    /**
     * An ADMIN is allowed as a superuser before any profile lookup (ADMIN arm).
     */
    @Test
    void allowsAdminAsSuperuser() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee(1L, Employee.EmployeeRole.ADMIN));
            PermissionService.Decision decision =
                    service.decideInTransaction("mcurie", Feature.PROFILE, CrudOption.DELETE);
            assertTrue(decision.isAllowed());
            assertFalse(decision.requiresValidation);
            assertNull(decision.validatingProfileId);
        }
    }

    /**
     * A bound profile that holds the grant allows the request and propagates
     * the grant's validation terms (grant-hit arm, store-present).
     */
    @Test
    void allowsWhenProfileGrantsOnCodedNode() {
        Profile granting = profile(new ProfileGrant("settings", CrudOption.VIEW, true, 9L));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee(7L, Employee.EmployeeRole.MANAGER));
            stubStore(panache, store("0101"));
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(7L)))
                    .thenReturn(List.of(binding(3L, "0101")));
            panache.when(() -> Profile.findById(3L)).thenReturn(granting);
            PermissionService.Decision decision =
                    service.decideInTransaction("mcurie", Feature.SETTINGS, CrudOption.VIEW);
            assertTrue(decision.isAllowed());
            assertTrue(decision.requiresValidation);
            assertEquals(9L, decision.validatingProfileId);
        }
    }

    /**
     * A global binding still applies on a store-less node (store-null arm).
     */
    @Test
    void allowsGlobalBindingOnStorelessNode() {
        Profile granting = profile(new ProfileGrant("settings", CrudOption.UPDATE, false, null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee(7L, Employee.EmployeeRole.MANAGER));
            stubStore(panache, null);
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(7L)))
                    .thenReturn(List.of(binding(3L, EmployeeProfile.GLOBAL)));
            panache.when(() -> Profile.findById(3L)).thenReturn(granting);
            assertTrue(service.decideInTransaction("mcurie", Feature.SETTINGS, CrudOption.UPDATE).isAllowed());
        }
    }

    /**
     * A binding scoped to another node is skipped (appliesTo-false continue).
     */
    @Test
    void deniesWhenBindingScopedElsewhere() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee(7L, Employee.EmployeeRole.MANAGER));
            stubStore(panache, store("0101"));
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(7L)))
                    .thenReturn(List.of(binding(3L, "0202")));
            assertFalse(service.decideInTransaction("mcurie", Feature.SETTINGS, CrudOption.VIEW).isAllowed());
        }
    }

    /**
     * A binding whose profile has vanished is skipped (profile-null continue).
     */
    @Test
    void deniesWhenProfileMissing() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee(7L, Employee.EmployeeRole.MANAGER));
            stubStore(panache, store("0101"));
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(7L)))
                    .thenReturn(List.of(binding(3L, EmployeeProfile.GLOBAL)));
            panache.when(() -> Profile.findById(3L)).thenReturn(null);
            assertFalse(service.decideInTransaction("mcurie", Feature.SETTINGS, CrudOption.VIEW).isAllowed());
        }
    }

    /**
     * A bound profile that does not hold the grant is skipped, ending in the
     * final deny (grant-null continue then fall-through).
     */
    @Test
    void deniesWhenGrantMissing() {
        Profile other = profile(new ProfileGrant("store", CrudOption.VIEW, false, null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee(7L, Employee.EmployeeRole.MANAGER));
            stubStore(panache, store("0101"));
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(7L)))
                    .thenReturn(List.of(binding(3L, EmployeeProfile.GLOBAL)));
            panache.when(() -> Profile.findById(3L)).thenReturn(other);
            assertFalse(service.decideInTransaction("mcurie", Feature.SETTINGS, CrudOption.VIEW).isAllowed());
        }
    }

    /**
     * {@code decide} and {@code isAllowed} run the resolution inside the
     * transaction and return its verdict.
     */
    @Test
    @SuppressWarnings("unchecked")
    void decideWrapsInTransaction() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<QuarkusTransaction> tx =
                     mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)) {
            stubEmployee(panache, employee(1L, Employee.EmployeeRole.ADMIN));
            tx.when(() -> QuarkusTransaction.requiringNew().call(any(Callable.class)))
                    .thenAnswer(invocation -> {
                        Callable<?> callable = invocation.getArgument(0);
                        try {
                            return callable.call();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            assertTrue(service.decide("mcurie", Feature.SETTINGS, CrudOption.VIEW).isAllowed());
            assertTrue(service.isAllowed("mcurie", Feature.SETTINGS, CrudOption.VIEW));
        }
    }
}
