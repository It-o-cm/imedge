package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.people.EmployeeProfile;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.Profile;
import com.intermarche.pos.service.PermissionService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
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
 * Unit tests for {@link AdminUserProfileResource}.
 * <p>
 * A Qute-backed back-office resource over the {@link Employee},
 * {@link Profile} and {@link EmployeeProfile} static finders, intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the template, the
 * {@link PermissionService} and the {@link SecurityIdentity} are mocks; the
 * bound row's {@code persist()} is neutralized with
 * {@link org.mockito.Mockito#mockConstruction}.
 * <p>
 * Branch enumeration (every arm exercised): each method covers its guard's
 * allowed/denied arms; {@code listPage} the null/known role and the
 * resolved/orphan binding arms; {@code assign} the missing-id, unknown-entity,
 * blank/explicit scope, duplicate and success arms; {@code unassign} the
 * blank-id, unknown and success arms; {@code parseId} its null, blank, valid
 * and malformed arms.
 */
class AdminUserProfileResourceTest {

    /** The resource under test. */
    private AdminUserProfileResource resource;

    /** The mocked permission layer. */
    private PermissionService permissionService;

    /**
     * Wires a resource with a mocked template, permission layer and identity.
     */
    @BeforeEach
    void setUp() {
        resource = new AdminUserProfileResource();
        resource.adminUsers = mock(Template.class);
        permissionService = mock(PermissionService.class);
        resource.permissionService = permissionService;
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("mcurie");
        when(identity.getPrincipal()).thenReturn(principal);
        resource.identity = identity;
    }

    /**
     * Grants or refuses every permission question.
     *
     * @param allowed whether the account is authorized
     */
    private void permit(boolean allowed) {
        when(permissionService.isAllowed(anyString(), any(Feature.class), any(CrudOption.class)))
                .thenReturn(allowed);
    }

    /**
     * Wires the template's chained {@code data} to a self-returning instance.
     *
     * @return the mocked template instance
     */
    private TemplateInstance wire() {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminUsers.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds an employee.
     *
     * @param id the id
     * @param role the role, possibly null
     * @return the employee
     */
    private Employee employee(long id, Employee.EmployeeRole role) {
        Employee employee = new Employee();
        employee.id = id;
        employee.firstName = "Marie";
        employee.lastName = "Curie";
        employee.loginName = "mcurie";
        employee.role = role;
        return employee;
    }

    /**
     * Builds a profile.
     *
     * @param id the id
     * @param name the name
     * @return the profile
     */
    private Profile profile(long id, String name) {
        Profile profile = new Profile();
        profile.id = id;
        profile.name = name;
        return profile;
    }

    /**
     * Builds a binding.
     *
     * @param id the id
     * @param profileId the bound profile id
     * @param scope the echelon scope
     * @return the binding
     */
    private EmployeeProfile binding(long id, long profileId, String scope) {
        EmployeeProfile binding = new EmployeeProfile();
        binding.id = id;
        binding.employeeId = 7L;
        binding.profileId = profileId;
        binding.echelonScope = scope;
        return binding;
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
     * {@code listPage} refuses an unauthorized account.
     */
    @Test
    void listPageDeniedWhenNotPermitted() {
        permit(false);
        assertRedirect(resource.listPage(null, true), "Droit+refus");
    }

    /**
     * {@code listPage} renders users, covering a known and a null role and a
     * resolved and an orphan binding.
     */
    @Test
    void listPageRendersUsersAndBindings() {
        permit(true);
        TemplateInstance instance = wire();
        Employee withRole = employee(7L, Employee.EmployeeRole.MANAGER);
        Employee withoutRole = employee(8L, null);
        withoutRole.loginName = "ghost";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.listAll(any(Sort.class))).thenReturn(List.of(profile(3L, "P")));
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(withRole, withoutRole));
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(7L)))
                    .thenReturn(List.of(binding(1L, 3L, "*")));
            panache.when(() -> EmployeeProfile.list(eq("employeeId"), any(Sort.class), eq(8L)))
                    .thenReturn(List.of(binding(2L, 99L, "0101")));
            assertEquals(instance, resource.listPage("hi", true));
        }
    }

    /**
     * {@code assign} refuses an unauthorized account.
     */
    @Test
    void assignDeniedWhenNotPermitted() {
        permit(false);
        assertRedirect(resource.assign(new MultivaluedHashMap<>()), "Droit+refus");
    }

    /**
     * {@code assign} rejects a missing employee id (parseId-null arm).
     */
    @Test
    void assignRejectsMissingIds() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("profileId", "3");
        assertRedirect(resource.assign(form), "obligatoires");
    }

    /**
     * {@code assign} rejects a malformed employee id (parseId-malformed arm).
     */
    @Test
    void assignRejectsMalformedId() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("employeeId", "x");
        form.putSingle("profileId", "3");
        assertRedirect(resource.assign(form), "obligatoires");
    }

    /**
     * {@code assign} rejects a missing profile id while the employee id is
     * present (second-condition arm of the id guard).
     */
    @Test
    void assignRejectsMissingProfile() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("employeeId", "7");
        assertRedirect(resource.assign(form), "obligatoires");
    }

    /**
     * {@code assign} rejects an unknown employee (employee-null arm).
     */
    @Test
    void assignRejectsUnknownEmployee() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("employeeId", "7");
        form.putSingle("profileId", "3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(null);
            panache.when(() -> Profile.findById(3L)).thenReturn(profile(3L, "P"));
            assertRedirect(resource.assign(form), "introuvable");
        }
    }

    /**
     * {@code assign} rejects an unknown profile while the employee exists
     * (second-condition arm of the entity guard).
     */
    @Test
    void assignRejectsUnknownProfile() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("employeeId", "7");
        form.putSingle("profileId", "3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee(7L, Employee.EmployeeRole.MANAGER));
            panache.when(() -> Profile.findById(3L)).thenReturn(null);
            assertRedirect(resource.assign(form), "introuvable");
        }
    }

    /**
     * {@code assign} rejects a duplicate binding, covering the blank-scope
     * default (scope-blank arm and exists-true arm).
     */
    @Test
    void assignRejectsDuplicate() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("employeeId", "7");
        form.putSingle("profileId", "3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee(7L, Employee.EmployeeRole.MANAGER));
            panache.when(() -> Profile.findById(3L)).thenReturn(profile(3L, "P"));
            panache.when(() -> EmployeeProfile.count(
                    "employeeId = ?1 and profileId = ?2 and echelonScope = ?3", 7L, 3L, "*")).thenReturn(1L);
            assertRedirect(resource.assign(form), "d%C3%A9j%C3%A0");
        }
    }

    /**
     * {@code assign} normalizes a blank scope to the global echelon
     * (scope-blank arm).
     */
    @Test
    void assignNormalizesBlankScope() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("employeeId", "7");
        form.putSingle("profileId", "3");
        form.putSingle("echelonScope", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee(7L, Employee.EmployeeRole.MANAGER));
            panache.when(() -> Profile.findById(3L)).thenReturn(profile(3L, "P"));
            panache.when(() -> EmployeeProfile.count(
                    "employeeId = ?1 and profileId = ?2 and echelonScope = ?3", 7L, 3L, "*")).thenReturn(1L);
            assertRedirect(resource.assign(form), "d%C3%A9j%C3%A0");
        }
    }

    /**
     * {@code assign} binds the profile at an explicit scope (scope-explicit
     * arm and exists-false success arm).
     */
    @Test
    void assignBindsProfile() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("employeeId", "7");
        form.putSingle("profileId", "3");
        form.putSingle("echelonScope", " 0101 ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EmployeeProfile> construction = mockConstruction(EmployeeProfile.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(employee(7L, Employee.EmployeeRole.MANAGER));
            panache.when(() -> Profile.findById(3L)).thenReturn(profile(3L, "P"));
            panache.when(() -> EmployeeProfile.count(
                    "employeeId = ?1 and profileId = ?2 and echelonScope = ?3", 7L, 3L, "0101")).thenReturn(0L);
            assertRedirect(resource.assign(form), "affect");
            EmployeeProfile built = construction.constructed().get(0);
            assertEquals("0101", built.echelonScope);
            verify(built).persist();
        }
    }

    /**
     * {@code unassign} refuses an unauthorized account.
     */
    @Test
    void unassignDeniedWhenNotPermitted() {
        permit(false);
        assertRedirect(resource.unassign(new MultivaluedHashMap<>()), "Droit+refus");
    }

    /**
     * {@code unassign} redirects on a blank id (parseId-blank arm).
     */
    @Test
    void unassignRedirectsOnBlankId() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("bindingId", " ");
        assertRedirect(resource.unassign(form), "introuvable");
    }

    /**
     * {@code unassign} redirects on an unknown id (findById-null arm).
     */
    @Test
    void unassignRedirectsOnUnknownId() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("bindingId", "2");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EmployeeProfile.findById(2L)).thenReturn(null);
            assertRedirect(resource.unassign(form), "introuvable");
        }
    }

    /**
     * {@code unassign} removes the binding (success arm).
     */
    @Test
    void unassignRemovesBinding() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("bindingId", "2");
        EmployeeProfile bound = mock(EmployeeProfile.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EmployeeProfile.findById(2L)).thenReturn(bound);
            assertRedirect(resource.unassign(form), "retir");
            verify(bound).delete();
        }
    }
}
