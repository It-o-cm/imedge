package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.EmployeeProfile;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.Profile;
import com.intermarche.pos.domain.people.ProfileGrant;
import com.intermarche.pos.service.PermissionService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminProfileResource}.
 * <p>
 * A Qute-backed back-office resource over the {@link Profile} static finders,
 * which resolve to {@link PanacheEntityBase} under plain {@code mvn test} and
 * are intercepted with {@link org.mockito.Mockito#mockStatic}; the templates,
 * the {@link PermissionService} and the {@link SecurityIdentity} are Mockito
 * mocks. No database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised): each screen method covers its
 * guard's allowed and denied arms; {@code editForm} the null-id, unknown and
 * found arms; {@code save} the create/update split, the blank-name, the
 * unknown-update, the name-clash (reject and self-rename) and the success
 * arms; {@code delete} the blank-id, unknown and success arms. The grant
 * parsing helpers are driven through one comprehensive create form covering
 * every malformed/unknown/duplicate token and every validation arm, and the
 * form renderer through an edit profile mixing validated, unvalidated and
 * ungranted features.
 */
class AdminProfileResourceTest {

    /** The resource under test. */
    private AdminProfileResource resource;

    /** The mocked permission layer. */
    private PermissionService permissionService;

    /**
     * Wires a resource with mocked templates, permission layer and identity.
     */
    @BeforeEach
    void setUp() {
        resource = new AdminProfileResource();
        resource.adminProfiles = mock(Template.class);
        resource.adminProfileForm = mock(Template.class);
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
     * Wires the list template's chained {@code data} to a self-returning
     * instance.
     *
     * @return the mocked template instance
     */
    private TemplateInstance wireList() {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminProfiles.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Wires the form template's chained {@code data} to a self-returning
     * instance.
     *
     * @return the mocked template instance
     */
    private TemplateInstance wireForm() {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminProfileForm.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Stubs {@code Profile.listAllOrdered()} within an active static mock.
     *
     * @param panache the active PanacheEntityBase mock
     * @param profiles the profiles the sorted listing resolves to
     */
    private void stubListing(MockedStatic<PanacheEntityBase> panache, List<Profile> profiles) {
        panache.when(() -> Profile.listAll(any(Sort.class))).thenReturn(profiles);
    }

    /**
     * Builds a profile with an id, a name and grants.
     *
     * @param id the id
     * @param name the name
     * @param grants the grants
     * @return the profile
     */
    private Profile profile(long id, String name, ProfileGrant... grants) {
        Profile profile = new Profile();
        profile.id = id;
        profile.name = name;
        for (ProfileGrant grant : grants) {
            profile.grants.add(grant);
        }
        return profile;
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
     * {@code listPage} refuses an unauthorized account (guard-denied arm).
     */
    @Test
    void listPageDeniedWhenNotPermitted() {
        permit(false);
        assertRedirect(resource.listPage(null, true), "Droit+refus");
    }

    /**
     * {@code listPage} renders the list for an authorized account
     * (guard-allowed arm).
     */
    @Test
    void listPageRendersWhenPermitted() {
        permit(true);
        TemplateInstance instance = wireList();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubListing(panache, List.of(profile(1L, "P")));
            assertEquals(instance, resource.listPage("hi", false));
        }
    }

    /**
     * {@code newForm} refuses an unauthorized account.
     */
    @Test
    void newFormDeniedWhenNotPermitted() {
        permit(false);
        assertRedirect(resource.newForm(), "Droit+refus");
    }

    /**
     * {@code newForm} renders an empty form (profile-null render arms).
     */
    @Test
    void newFormRendersEmptyForm() {
        permit(true);
        TemplateInstance instance = wireForm();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubListing(panache, List.of());
            assertEquals(instance, resource.newForm());
        }
    }

    /**
     * {@code editForm} refuses an unauthorized account.
     */
    @Test
    void editFormDeniedWhenNotPermitted() {
        permit(false);
        assertRedirect(resource.editForm(5L), "Droit+refus");
    }

    /**
     * {@code editForm} redirects on a null id (id-null arm).
     */
    @Test
    void editFormRedirectsOnNullId() {
        permit(true);
        assertRedirect(resource.editForm(null), "introuvable");
    }

    /**
     * {@code editForm} redirects on an unknown id (findById-null arm).
     */
    @Test
    void editFormRedirectsOnUnknownId() {
        permit(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.findById(5L)).thenReturn(null);
            assertRedirect(resource.editForm(5L), "introuvable");
        }
    }

    /**
     * {@code editForm} renders the form of an existing profile, exercising the
     * validated, unvalidated and ungranted feature rows and the
     * other-profiles filter (found arm).
     */
    @Test
    void editFormRendersExistingProfile() {
        permit(true);
        TemplateInstance instance = wireForm();
        Profile edited = profile(5L, "Edited",
                new ProfileGrant("settings", CrudOption.VIEW, true, 9L),
                new ProfileGrant("store", CrudOption.VIEW, false, null));
        Profile other = profile(9L, "Other");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.findById(5L)).thenReturn(edited);
            stubListing(panache, List.of(edited, other));
            assertEquals(instance, resource.editForm(5L));
        }
    }

    /**
     * {@code save} refuses an unauthorized account (guard on CREATE).
     */
    @Test
    void saveDeniedWhenNotPermitted() {
        permit(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", " ");
        assertRedirect(resource.save(form), "Droit+refus");
    }

    /**
     * {@code save} rejects a blank name (blank-name arm).
     */
    @Test
    void saveRejectsBlankName() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("name", "  ");
        assertRedirect(resource.save(form), "obligatoire");
    }

    /**
     * {@code save} rejects an update of an unknown profile (update-null arm).
     */
    @Test
    void saveRejectsUnknownUpdate() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        form.putSingle("name", "X");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.findById(5L)).thenReturn(null);
            assertRedirect(resource.save(form), "introuvable");
        }
    }

    /**
     * {@code save} rejects a name already held by another profile (clash arm).
     */
    @Test
    void saveRejectsNameClash() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        form.putSingle("name", "Taken");
        Profile edited = profile(5L, "Edited");
        Profile clash = profile(9L, "Taken");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.findById(5L)).thenReturn(edited);
            PanacheQuery<Profile> query = query(clash);
            panache.when(() -> Profile.find("name", "Taken")).thenReturn(query);
            assertRedirect(resource.save(form), "porte+d");
        }
    }

    /**
     * {@code save} updates a profile renamed to its own name (self-clash
     * allowed) and stores the built grants (update-success arm).
     */
    @Test
    void saveUpdatesWithSelfName() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        form.putSingle("name", "Edited");
        form.add("grant", "settings:VIEW");
        Profile edited = profile(5L, "Edited");
        Profile self = profile(5L, "Edited");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Profile> selfQuery = query(self);
            panache.when(() -> Profile.findById(5L)).thenReturn(edited);
            panache.when(() -> Profile.find("name", "Edited")).thenReturn(selfQuery);
            assertRedirect(resource.save(form), "enregistr");
            assertEquals(1, edited.grants.size());
        }
    }

    /**
     * {@code save} creates a profile, driving every grant-parsing arm: valid,
     * duplicate, malformed, colon-first, unknown-feature and invalid-option
     * cells; a valid whole-menu expansion and its malformed, unknown-menu and
     * invalid-option siblings; and the validation stamping with a valid, a
     * blank and a malformed validator id (create-success arm).
     */
    @Test
    void saveCreatesWithComprehensiveGrid() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("name", "New");
        form.putSingle("description", "  d  ");
        form.add("grant", "settings:VIEW");
        form.add("grant", "settings:VIEW");
        form.add("grant", "settings:UPDATE");
        form.add("grant", "store:VIEW");
        form.add("grant", "supervision:VIEW");
        form.add("grant", "bogus");
        form.add("grant", ":VIEW");
        form.add("grant", "store:NOPE");
        form.add("grant", "ghost:VIEW");
        form.add("menugrant", "ADMINISTRATION:VIEW");
        form.add("menugrant", "bad");
        form.add("menugrant", "NOPE:VIEW");
        form.add("menugrant", "PARAMETRAGE:BAD");
        // MultivaluedHashMap.add drops a null value, so append the null token
        // directly to the backing list to exercise the null-token arm.
        form.get("grant").add(null);
        form.get("menugrant").add(null);
        form.add("validate", "settings");
        form.add("validate", "profile");
        form.add("validate", "user");
        form.add("validate", "store");
        form.putSingle("validator_settings", "9");
        form.putSingle("validator_profile", "");
        form.putSingle("validator_user", "x");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Profile> noClash = query(null);
            panache.when(() -> Profile.find("name", "New")).thenReturn(noClash);
            org.mockito.MockedConstruction<Profile> construction =
                    org.mockito.Mockito.mockConstruction(Profile.class);
            try {
                assertRedirect(resource.save(form), "enregistr");
                Profile built = construction.constructed().get(0);
                assertEquals("New", built.name);
                assertEquals("d", built.description);
                org.mockito.Mockito.verify(built).persist();
            } finally {
                construction.close();
            }
        }
    }

    /**
     * {@code delete} refuses an unauthorized account (guard on DELETE).
     */
    @Test
    void deleteDeniedWhenNotPermitted() {
        permit(false);
        assertRedirect(resource.delete(new MultivaluedHashMap<>()), "Droit+refus");
    }

    /**
     * {@code delete} redirects on an absent id (id-null arm of the ternary).
     */
    @Test
    void deleteRedirectsOnMissingId() {
        permit(true);
        assertRedirect(resource.delete(new MultivaluedHashMap<>()), "introuvable");
    }

    /**
     * {@code delete} redirects on a blank id (blank-id arm).
     */
    @Test
    void deleteRedirectsOnBlankId() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", " ");
        assertRedirect(resource.delete(form), "introuvable");
    }

    /**
     * {@code delete} redirects on an unknown id (findById-null arm).
     */
    @Test
    void deleteRedirectsOnUnknownId() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.findById(5L)).thenReturn(null);
            assertRedirect(resource.delete(form), "introuvable");
        }
    }

    /**
     * {@code delete} removes the bindings and the profile (success arm).
     */
    @Test
    void deleteRemovesProfileAndBindings() {
        permit(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "5");
        Profile target = mock(Profile.class);
        target.id = 5L;
        target.name = "Gone";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Profile.findById(5L)).thenReturn(target);
            panache.when(() -> EmployeeProfile.delete("profileId", 5L)).thenReturn(0L);
            assertRedirect(resource.delete(form), "supprim");
            org.mockito.Mockito.verify(target).delete();
        }
    }

    /**
     * Builds a single-row Panache query mock resolving to one profile.
     *
     * @param row the row {@code firstResult} returns, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Profile> query(Profile row) {
        PanacheQuery<Profile> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }
}
