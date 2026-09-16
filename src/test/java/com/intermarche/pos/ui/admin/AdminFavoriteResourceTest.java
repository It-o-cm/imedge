package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.UserFavorite;
import com.intermarche.pos.service.PermissionService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminFavoriteResource}.
 * <p>
 * A Qute-backed self-service resource over the {@link Employee} and
 * {@link UserFavorite} static finders, intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the template, the
 * {@link PermissionService} and the {@link SecurityIdentity} are mocks; the
 * persisted favorite row's {@code persist()} is neutralized with
 * {@link org.mockito.Mockito#mockConstruction}.
 * <p>
 * Branch enumeration (every arm exercised): both methods cover the
 * account-resolved and account-null arms; {@code favoritesPage} covers the
 * viewable/non-viewable feature arms, the first-of-menu true/false arms and
 * the hidden/shown arms; {@code save} covers the null-selection arm and, on a
 * present selection, the unknown-key, non-viewable and persisted arms.
 */
class AdminFavoriteResourceTest {

    /** The exact finder query {@code Employee.findActiveLogin} issues. */
    private static final String ACTIVE_LOGIN_QUERY =
            "(badgeId = ?1 or loginName = ?1) and active = true";

    /** The resource under test. */
    private AdminFavoriteResource resource;

    /** The mocked permission layer. */
    private PermissionService permissionService;

    /**
     * Wires a resource with a mocked template, permission layer and identity.
     */
    @BeforeEach
    void setUp() {
        resource = new AdminFavoriteResource();
        resource.adminFavorites = mock(Template.class);
        permissionService = mock(PermissionService.class);
        resource.permissionService = permissionService;
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("mcurie");
        when(identity.getPrincipal()).thenReturn(principal);
        resource.identity = identity;
    }

    /**
     * Wires the template's chained {@code data} to a self-returning instance.
     *
     * @return the mocked template instance
     */
    private TemplateInstance wire() {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminFavorites.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds the seed manager employee.
     *
     * @return the employee
     */
    private Employee employee() {
        Employee employee = new Employee();
        employee.id = 7L;
        employee.loginName = "mcurie";
        return employee;
    }

    /**
     * Stubs {@code Employee.findActiveLogin("mcurie")} to resolve the employee.
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
     * Asserts a value is a 303 redirect whose location contains a fragment.
     *
     * @param value the returned value
     * @param fragment the fragment the location must contain
     */
    private void assertRedirect(Object value, String fragment) {
        Response response = assertInstanceOf(Response.class, value);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains(fragment),
                () -> "location " + response.getLocation() + " lacks " + fragment);
    }

    /**
     * {@code favoritesPage} redirects when the account cannot be resolved
     * (employee-null arm).
     */
    @Test
    void favoritesPageRedirectsWhenAccountUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, null);
            assertRedirect(resource.favoritesPage(null, true), "introuvable");
        }
    }

    /**
     * {@code favoritesPage} renders the viewable features, one hidden and the
     * rest shown, skipping the non-viewable ones and marking menu breaks.
     */
    @Test
    void favoritesPageRendersViewableFeatures() {
        TemplateInstance instance = wire();
        when(permissionService.isAllowed(anyString(), any(Feature.class), eq(CrudOption.VIEW)))
                .thenReturn(true);
        when(permissionService.isAllowed(anyString(), eq(Feature.PROFILE), eq(CrudOption.VIEW)))
                .thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee());
            UserFavorite hiddenRow = new UserFavorite();
            hiddenRow.employeeId = 7L;
            hiddenRow.featureKey = "settings";
            panache.when(() -> UserFavorite.list("employeeId", 7L)).thenReturn(List.of(hiddenRow));
            assertEquals(instance, resource.favoritesPage("hi", true));
        }
    }

    /**
     * {@code save} redirects when the account cannot be resolved
     * (employee-null arm).
     */
    @Test
    void saveRedirectsWhenAccountUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, null);
            assertRedirect(resource.save(new MultivaluedHashMap<>()), "introuvable");
        }
    }

    /**
     * {@code save} clears the favorites and returns success when nothing is
     * selected (hide-null arm).
     */
    @Test
    void saveClearsWhenNoSelection() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee());
            assertRedirect(resource.save(new MultivaluedHashMap<>()), "enregistr");
        }
    }

    /**
     * {@code save} persists a viewable hidden feature, skipping an unknown key
     * and a non-viewable one (unknown-key, non-viewable and persisted arms).
     */
    @Test
    void savePersistsViewableSelection() {
        when(permissionService.isAllowed(anyString(), any(Feature.class), eq(CrudOption.VIEW)))
                .thenReturn(true);
        when(permissionService.isAllowed(anyString(), eq(Feature.PROFILE), eq(CrudOption.VIEW)))
                .thenReturn(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("hide", "settings");
        form.add("hide", "ghost");
        form.add("hide", "profile");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<UserFavorite> construction = mockConstruction(UserFavorite.class)) {
            stubEmployee(panache, employee());
            assertRedirect(resource.save(form), "enregistr");
            assertEquals(1, construction.constructed().size());
            UserFavorite built = construction.constructed().get(0);
            assertEquals("settings", built.featureKey);
            verify(built).persist();
        }
    }
}
