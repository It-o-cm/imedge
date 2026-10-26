package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.people.ProfileGrant;
import com.intermarche.edge.domain.store.Store;
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

    /**
     * Stubs {@code Employee.findActiveLogin(login)} for any login.
     *
     * @param panache the active PanacheEntityBase mock
     * @param login the login
     * @param employee the employee to resolve, possibly null
     */
    @SuppressWarnings("unchecked")
    private void stubLogin(MockedStatic<PanacheEntityBase> panache, String login, Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        panache.when(() -> Employee.find(ACTIVE_LOGIN_QUERY, login)).thenReturn(query);
    }

    /**
     * Builds an account with a back-office password.
     *
     * @param id the id
     * @param login the login
     * @param role the role
     * @param password the back-office password
     * @return the account
     */
    private Employee account(long id, String login, Employee.EmployeeRole role, String password) {
        Employee account = employee(id, role);
        account.loginName = login;
        account.backOfficePassword = Employee.hashPassword(password);
        return account;
    }

    /**
     * BO-01-01-03: an account attached to other echelons reaches no screen of
     * this node, whatever its profiles; the node's place comes from the
     * echelon service when there is one.
     */
    @Test
    void deniesAnAccountOutsideItsEchelons() {
        PermissionService scoped = new PermissionService();
        scoped.nodeEchelonService = mock(NodeEchelonService.class);
        when(scoped.nodeEchelonService.current())
                .thenReturn(new com.intermarche.edge.domain.store.NodeEchelon("07039", "ITM", "FR"));
        Employee elsewhere = employee(7L, Employee.EmployeeRole.MANAGER);
        elsewhere.echelonScopes = "PDV:07040";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, elsewhere);
            assertFalse(scoped.decideInTransaction("mcurie", Feature.JOURNAL, CrudOption.VIEW).isAllowed());
            elsewhere.echelonScopes = "ENSEIGNE:ITM";
            assertTrue(scoped.decideInTransaction("mcurie", Feature.JOURNAL, CrudOption.VIEW).isAllowed());
        }
    }

    /**
     * The MANAGER keeps the screens the fixed roles opened to it; another
     * role does not get them without a profile.
     */
    @Test
    void theManagerKeepsItsBaseline() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee(7L, Employee.EmployeeRole.MANAGER));
            stubStore(panache, null);
            assertTrue(service.decideInTransaction("mcurie", Feature.SUPERVISION, CrudOption.UPDATE).isAllowed());
            assertFalse(service.decideInTransaction("mcurie", Feature.ACCOUNT, CrudOption.VIEW).isAllowed());
            stubEmployee(panache, employee(7L, Employee.EmployeeRole.CASHIER));
            assertFalse(service.decideInTransaction("mcurie", Feature.JOURNAL, CrudOption.VIEW).isAllowed());
        }
    }

    /**
     * BO-01-01-02, BO-01-02-10: an ADMIN administers everyone; another account
     * only accounts strictly below it and never an ADMIN; nobody without account.
     */
    @Test
    void outranksFollowsTheLevels() {
        Employee admin = employee(1L, Employee.EmployeeRole.ADMIN);
        Employee chief = employee(2L, Employee.EmployeeRole.MANAGER);
        chief.hierarchyLevel = 3;
        Employee clerk = employee(3L, Employee.EmployeeRole.CASHIER);
        clerk.hierarchyLevel = 2;
        Employee peer = employee(4L, Employee.EmployeeRole.CASHIER);
        peer.hierarchyLevel = 3;
        assertFalse(service.outranks(null, clerk));
        assertTrue(service.outranks(admin, admin));
        assertTrue(service.outranks(chief, null));
        assertTrue(service.outranks(chief, clerk));
        assertFalse(service.outranks(chief, peer));
        assertFalse(service.outranks(chief, admin));
    }

    /**
     * BO-01-03-03: an ADMIN hands any profile out; another account only
     * profiles of its own level or below.
     */
    @Test
    void mayAssignFollowsTheLevels() {
        Employee admin = employee(1L, Employee.EmployeeRole.ADMIN);
        Employee chief = employee(2L, Employee.EmployeeRole.MANAGER);
        chief.hierarchyLevel = 3;
        Profile low = profile();
        low.hierarchyLevel = 3;
        Profile high = profile();
        high.hierarchyLevel = 4;
        assertFalse(service.mayAssign(null, low));
        assertFalse(service.mayAssign(chief, null));
        assertTrue(service.mayAssign(admin, high));
        assertTrue(service.mayAssign(chief, low));
        assertFalse(service.mayAssign(chief, high));
    }

    /**
     * BO-01-04-03: without validating profile the actor confirms with their own
     * password; a missing password validates nothing.
     */
    @Test
    void aSelfConfirmationChecksTheActorsPassword() {
        assertNull(service.validate("mcurie", null, null, null));
        assertNull(service.validate("mcurie", null, "", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)) {
            tx.when(() -> QuarkusTransaction.requiringNew().call(any()))
                    .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
            stubLogin(panache, "mcurie", account(7L, "mcurie", Employee.EmployeeRole.MANAGER, "secret-1"));
            assertEquals("mcurie", service.validate("mcurie", null, "secret-1", null));
            assertNull(service.validate("mcurie", null, "wrong", null));
            stubLogin(panache, "mcurie", null);
            assertNull(service.validate("mcurie", null, "secret-1", null));
        }
    }

    /**
     * BO-01-04-05: with a validating profile another account confirms, which
     * must exist, be another one, know its password and hold the profile here
     * (an ADMIN holds every profile).
     */
    @Test
    void aThirdPartyValidationChecksTheValidator() {
        Employee actor = account(7L, "mcurie", Employee.EmployeeRole.MANAGER, "secret-1");
        Employee chief = account(8L, "chief", Employee.EmployeeRole.MANAGER, "secret-2");
        Employee boss = account(9L, "boss", Employee.EmployeeRole.ADMIN, "secret-3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubLogin(panache, "mcurie", actor);
            stubLogin(panache, "chief", chief);
            stubLogin(panache, "boss", boss);
            stubLogin(panache, "ghost", null);
            stubStore(panache, store("07039"));
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(8L)))
                    .thenReturn(List.of(binding(5L, "PDV:07040"), binding(6L, "*"), binding(4L, "07039")));
            assertNull(service.validateInTransaction("mcurie", null, "secret-2", 4L));
            assertNull(service.validateInTransaction("mcurie", " ", "secret-2", 4L));
            assertNull(service.validateInTransaction("mcurie", "ghost", "secret-2", 4L));
            assertNull(service.validateInTransaction("mcurie", "mcurie", "secret-1", 4L));
            assertNull(service.validateInTransaction("mcurie", "chief", "wrong", 4L));
            assertEquals("chief", service.validateInTransaction("mcurie", " chief ", "secret-2", 4L));
            assertNull(service.validateInTransaction("mcurie", "chief", "secret-2", 5L));
            assertEquals("chief", service.validateInTransaction("ghost", "chief", "secret-2", 6L));
            assertNull(service.validateInTransaction("mcurie", "chief", "secret-2", 99L));
            assertEquals("boss", service.validateInTransaction("mcurie", "boss", "secret-3", 99L));
            chief.echelonScopes = "PDV:07040";
            assertNull(service.validateInTransaction("mcurie", "chief", "secret-2", 4L));
        }
    }

    /**
     * A saving gesture keyed on a business code modifies only an existing record.
     */
    @Test
    void recordExistenceIsCounted() {
        PermissionService counting = new PermissionService();
        counting.entityManager = mock(jakarta.persistence.EntityManager.class);
        @SuppressWarnings("unchecked")
        jakarta.persistence.TypedQuery<Long> query = mock(jakarta.persistence.TypedQuery.class);
        when(counting.entityManager.createQuery("select count(e) from Currency e where e.code = :value", Long.class))
                .thenReturn(query);
        when(query.setParameter("value", "USD")).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L, 0L);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class, Answers.RETURNS_DEEP_STUBS)) {
            tx.when(() -> QuarkusTransaction.requiringNew().call(any()))
                    .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
            assertTrue(counting.recordExists(com.intermarche.edge.domain.payment.Currency.class, "code", "USD"));
            assertFalse(counting.recordExists(com.intermarche.edge.domain.payment.Currency.class, "code", "USD"));
        }
    }
}
