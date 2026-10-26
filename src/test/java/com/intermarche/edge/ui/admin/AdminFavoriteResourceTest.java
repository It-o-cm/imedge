package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.UserFavorite;
import com.intermarche.edge.service.PermissionService;
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
 * viewable/non-viewable feature arms, a menu left without viewable feature,
 * the first-of-menu true/false arms, the hidden/shown, starred and hidden-menu
 * arms, and the report types offered or not (BO-01-04-08); {@code save} covers
 * the null-selection arm and, on a present selection, the unknown-key,
 * non-viewable and persisted arms of features and favorites, a known and an
 * unknown menu, a known and an unknown report type.
 */
class AdminFavoriteResourceTest {

    /** The exact finder query {@code Employee.findActiveLogin} issues. */
    private static final String ACTIVE_LOGIN_QUERY =
            "(badgeId = ?1 or loginName = ?1) and active = true";

    /** The resource under test. */
    private AdminFavoriteResource resource;

    /** The mocked permission layer. */
    private PermissionService permissionService;

    /** The mocked report catalog. */
    private com.intermarche.edge.ui.report.ReportCatalog catalog;

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
        resource.reportService = mock(com.intermarche.edge.ui.report.ReportService.class);
        catalog = mock(com.intermarche.edge.ui.report.ReportCatalog.class);
        when(resource.reportService.catalog()).thenReturn(catalog);
        com.intermarche.edge.ui.report.ReportDefinition report = mock(com.intermarche.edge.ui.report.ReportDefinition.class);
        when(report.code()).thenReturn("RCA");
        when(report.label()).thenReturn("Chiffre d'affaires");
        when(report.family()).thenReturn(com.intermarche.edge.ui.report.ReportFamily.values()[0]);
        when(catalog.all()).thenReturn(List.of(report));
        when(catalog.find("RCA")).thenReturn(report);
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
     * {@code favoritesPage} renders the viewable features menu by menu, one
     * hidden, one starred, a hidden menu, the report types; a menu without any
     * viewable feature is left out, and without the report right no report
     * type is offered.
     */
    @Test
    @SuppressWarnings("unchecked")
    void favoritesPageRendersViewableFeatures() {
        TemplateInstance instance = wire();
        when(permissionService.isAllowed(anyString(), any(Feature.class), eq(CrudOption.VIEW)))
                .thenReturn(true);
        when(permissionService.isAllowed(anyString(), eq(Feature.PROFILE), eq(CrudOption.VIEW)))
                .thenReturn(false);
        for (Feature feature : com.intermarche.edge.domain.people.Menu.FINANCIER.features()) {
            when(permissionService.isAllowed(anyString(), eq(feature), eq(CrudOption.VIEW))).thenReturn(false);
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEmployee(panache, employee());
            List<UserFavorite> tokens = new java.util.ArrayList<>();
            for (String key : new String[]{"settings", "STAR:store", "MENU:ARTICLES", "REPORT:RCA"}) {
                UserFavorite row = new UserFavorite();
                row.employeeId = 7L;
                row.featureKey = key;
                tokens.add(row);
            }
            panache.when(() -> UserFavorite.list("employeeId", 7L)).thenReturn(tokens);
            assertEquals(instance, resource.favoritesPage("hi", true));
            org.mockito.ArgumentCaptor<Object> menus = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(resource.adminFavorites).data(eq("menus"), menus.capture());
            List<AdminFavoriteResource.MenuRow> rows = (List<AdminFavoriteResource.MenuRow>) menus.getValue();
            assertTrue(rows.stream().noneMatch(row -> "FINANCIER".equals(row.name)));
            AdminFavoriteResource.MenuRow parametrage = rows.get(0);
            assertEquals("PARAMETRAGE", parametrage.name);
            assertTrue(parametrage.features.get(0).firstOfMenu);
            assertTrue(parametrage.features.get(0).hidden);
            assertTrue(parametrage.features.get(1).starred);
            assertTrue(!parametrage.features.get(1).firstOfMenu);
            assertTrue(rows.stream().anyMatch(row -> row.hidden && "ARTICLES".equals(row.name)));
            org.mockito.ArgumentCaptor<Object> reports = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(instance).data(eq("reports"), reports.capture());
            AdminFavoriteResource.ReportRow report = ((List<AdminFavoriteResource.ReportRow>) reports.getValue()).get(0);
            assertEquals("RCA", report.code);
            assertTrue(report.hidden);
            assertEquals(com.intermarche.edge.ui.report.ReportFamily.values()[0].getLabel(), report.family);
            assertEquals("Chiffre d'affaires", report.label);
            when(permissionService.isAllowed(anyString(), eq(Feature.REPORT), eq(CrudOption.VIEW))).thenReturn(false);
            resource.favoritesPage(null, true);
            verify(instance).data("reports", List.of());
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

    /**
     * {@code save} records the favorites, the hidden menus and report types:
     * a viewable favorite is kept, an unknown or non-viewable one skipped, an
     * unknown menu or report type ignored.
     */
    @Test
    void savePersistsEveryPreference() {
        when(permissionService.isAllowed(anyString(), any(Feature.class), eq(CrudOption.VIEW)))
                .thenReturn(true);
        when(permissionService.isAllowed(anyString(), eq(Feature.PROFILE), eq(CrudOption.VIEW)))
                .thenReturn(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("star", "store");
        form.add("star", "ghost");
        form.add("star", "profile");
        form.add("hideMenu", "ARTICLES");
        form.add("hideMenu", "NOWHERE");
        form.add("hideReport", "RCA");
        form.add("hideReport", "UNKNOWN");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<UserFavorite> construction = mockConstruction(UserFavorite.class)) {
            stubEmployee(panache, employee());
            assertRedirect(resource.save(form), "enregistr");
            assertEquals(List.of("STAR:store", "MENU:ARTICLES", "REPORT:RCA"),
                    construction.constructed().stream().map(row -> row.featureKey).toList());
        }
    }
}
