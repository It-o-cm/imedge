package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.EmployeeProfile;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.Menu;
import com.intermarche.pos.domain.people.Profile;
import com.intermarche.pos.domain.people.ProfileGrant;
import com.intermarche.pos.service.PermissionService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The PROFILES administration screen ({@code /admin/profiles}, BO-01-03-01,
 * BO-01-04-01/02/03/05/06): create, modify and delete the composable profiles
 * that replace the four fixed roles.
 * <p>
 * The form is the (functionality &times; option) grid: every {@link Feature}
 * is a row, grouped under its {@link Menu} so a whole aggregate can be granted
 * in one gesture (BO-01-04-06) or each function addressed on its own
 * (BO-01-04-01); the four {@link CrudOption} are the columns (BO-01-04-02);
 * per feature, a validation switch and a validating profile express the
 * supervised options (BO-01-04-03/05).
 * <p>
 * Access is itself governed by the administered layer it maintains: the
 * resource requires only a signed-in back-office account
 * ({@code @Authenticated}, which is what lets form authentication redirect an
 * anonymous request to the login), and {@link PermissionService} then decides
 * whether that account's profiles grant the {@link Feature#PROFILE} option in
 * play. The seed administrator passes as a superuser; anyone else passes only
 * through a profile — which is the whole point of the lot.
 */
@Path("/admin/profiles")
public class AdminProfileResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminProfileResource.class);

    /** The profiles list template. */
    @Inject
    @Location("admin-profiles")
    Template adminProfiles;

    /** The profile create/edit form template. */
    @Inject
    @Location("admin-profile-form")
    Template adminProfileForm;

    /** The administered authorization layer. */
    @Inject
    PermissionService permissionService;

    /** The signed-in operator, whose login name drives the permission check. */
    @Inject
    SecurityIdentity identity;

    /** One rendered option cell of the grid: its token and checked state. */
    public static class OptionCell {
        /** The posted token {@code featureKey:OPTION}. */
        public String token;
        /** The option label. */
        public String label;
        /** Whether the profile currently holds this grant. */
        public boolean checked;
    }

    /** One rendered feature row of the grid. */
    public static class FeatureRow {
        /** The feature key. */
        public String key;
        /** The feature label. */
        public String label;
        /** The four option cells, in declaration order. */
        public List<OptionCell> cells = new ArrayList<>();
        /** Whether every option of the feature is under validation. */
        public boolean requiresValidation;
        /** The chosen validating profile id, or null. */
        public Long validatorId;
    }

    /** One rendered menu group of the grid. */
    public static class MenuGroup {
        /** The menu label. */
        public String label;
        /** The menu name (posted token prefix for whole-menu grants). */
        public String name;
        /** The whole-menu option tokens {@code MENU:OPTION}. */
        public List<OptionCell> menuCells = new ArrayList<>();
        /** The feature rows under the menu. */
        public List<FeatureRow> features = new ArrayList<>();
    }

    /** A minimal profile reference for the validating-profile select. */
    public static class ProfileRef {
        /** The profile id. */
        public Long id;
        /** The profile name. */
        public String name;
    }

    /**
     * Shows the profiles list.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page, or a redirect when the account may not view it
     */
    @GET
    @Authenticated
    public Object listPage(@QueryParam("notice") String notice,
                           @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method listPage with notice: " + notice + ", noticeOk: " + noticeOk);
        Response denied = guard(CrudOption.VIEW);
        if (denied != null) {
            LOGGER.info("Exiting method listPage");
            return denied;
        }
        LOGGER.info("Exiting method listPage");
        return adminProfiles.data("profiles", Profile.listAllOrdered())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Shows the empty create form.
     *
     * @return the form for a new profile, or a redirect when not permitted
     */
    @GET
    @Path("/new")
    @Authenticated
    public Object newForm() {
        LOGGER.info("Entering method newForm");
        Response denied = guard(CrudOption.VIEW);
        if (denied != null) {
            LOGGER.info("Exiting method newForm");
            return denied;
        }
        LOGGER.info("Exiting method newForm");
        return form(null);
    }

    /**
     * Shows the edit form of an existing profile.
     *
     * @param id the profile id
     * @return the form, or a redirect when not permitted or unknown
     */
    @GET
    @Path("/edit")
    @Authenticated
    public Object editForm(@QueryParam("id") Long id) {
        LOGGER.info("Entering method editForm with id: " + id);
        Response denied = guard(CrudOption.VIEW);
        if (denied != null) {
            LOGGER.info("Exiting method editForm");
            return denied;
        }
        Profile profile = id == null ? null : Profile.<Profile>findById(id);
        if (profile == null) {
            LOGGER.info("Exiting method editForm");
            return redirect("Profil introuvable.", false);
        }
        LOGGER.info("Exiting method editForm");
        return form(profile);
    }

    /**
     * Saves a profile — create when no id is posted, update otherwise.
     *
     * @param form the posted form
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Authenticated
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        String idRaw = form.getFirst("id");
        boolean creating = idRaw == null || idRaw.isBlank();
        Response denied = guard(creating ? CrudOption.CREATE : CrudOption.UPDATE);
        if (denied != null) {
            LOGGER.info("Exiting method save");
            return denied;
        }
        String name = trimmed(form.getFirst("name"));
        if (name.isEmpty()) {
            LOGGER.info("Exiting method save");
            return redirect("Le nom du profil est obligatoire.", false);
        }
        Profile profile;
        if (creating) {
            profile = new Profile();
        } else {
            profile = Profile.findById(Long.valueOf(idRaw));
            if (profile == null) {
                LOGGER.info("Exiting method save");
                return redirect("Profil introuvable.", false);
            }
        }
        Profile clash = Profile.findByName(name);
        if (clash != null && !clash.equals(profile)) {
            LOGGER.info("Exiting method save");
            return redirect("Un profil porte déjà le nom « " + name + " ».", false);
        }
        profile.name = name;
        profile.description = trimmed(form.getFirst("description"));
        profile.grants = buildGrants(form);
        if (creating) {
            profile.persist();
        }
        LOGGER.info("Exiting method save");
        return redirect("Profil « " + name + " » enregistré.", true);
    }

    /**
     * Deletes a profile and the bindings that referenced it.
     *
     * @param form the posted form carrying the id
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Authenticated
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        Response denied = guard(CrudOption.DELETE);
        if (denied != null) {
            LOGGER.info("Exiting method delete");
            return denied;
        }
        String idRaw = form.getFirst("id");
        Profile profile = idRaw == null || idRaw.isBlank() ? null : Profile.<Profile>findById(Long.valueOf(idRaw));
        if (profile == null) {
            LOGGER.info("Exiting method delete");
            return redirect("Profil introuvable.", false);
        }
        String name = profile.name;
        EmployeeProfile.delete("profileId", profile.id);
        profile.delete();
        LOGGER.info("Exiting method delete");
        return redirect("Profil « " + name + " » supprimé.", true);
    }

    /**
     * Builds the grant list from the posted grid: the individually checked
     * feature cells, plus the whole-menu cells expanded over every feature of
     * the menu, de-duplicated by (feature, option) and stamped with each
     * feature's validation terms.
     *
     * @param form the posted form
     * @return the grants, never null
     */
    private List<ProfileGrant> buildGrants(MultivaluedMap<String, String> form) {
        Map<String, ProfileGrant> byToken = new LinkedHashMap<>();
        for (String token : safeList(form.get("grant"))) {
            addGrant(byToken, token);
        }
        for (String token : safeList(form.get("menugrant"))) {
            expandMenuGrant(byToken, token);
        }
        for (ProfileGrant grant : byToken.values()) {
            applyValidation(grant, form);
        }
        return new ArrayList<>(byToken.values());
    }

    /**
     * Adds one {@code featureKey:OPTION} token to the accumulator, ignoring a
     * malformed token or one naming an unknown feature or option.
     *
     * @param byToken the accumulator keyed by canonical token
     * @param token the posted token
     */
    private void addGrant(Map<String, ProfileGrant> byToken, String token) {
        int colon = token == null ? -1 : token.indexOf(':');
        if (colon <= 0) {
            return;
        }
        Feature feature = Feature.fromKey(token.substring(0, colon));
        CrudOption option = parseOption(token.substring(colon + 1));
        if (feature == null || option == null) {
            return;
        }
        String canonical = feature.getKey() + ":" + option.name();
        byToken.putIfAbsent(canonical, new ProfileGrant(feature.getKey(), option, false, null));
    }

    /**
     * Expands one {@code MENU:OPTION} token into a grant on every feature of
     * the menu, ignoring a malformed token or an unknown menu or option.
     *
     * @param byToken the accumulator keyed by canonical token
     * @param token the posted token
     */
    private void expandMenuGrant(Map<String, ProfileGrant> byToken, String token) {
        int colon = token == null ? -1 : token.indexOf(':');
        if (colon <= 0) {
            return;
        }
        Menu menu = parseMenu(token.substring(0, colon));
        CrudOption option = parseOption(token.substring(colon + 1));
        if (menu == null || option == null) {
            return;
        }
        for (Feature feature : Feature.values()) {
            if (feature.inMenu(menu)) {
                addGrant(byToken, feature.getKey() + ":" + option.name());
            }
        }
    }

    /**
     * Stamps a grant with its feature's validation terms, read from the
     * {@code validate}/{@code validator_<key>} fields.
     *
     * @param grant the grant to stamp
     * @param form the posted form
     */
    private void applyValidation(ProfileGrant grant, MultivaluedMap<String, String> form) {
        boolean required = safeList(form.get("validate")).contains(grant.featureKey);
        grant.requiresValidation = required;
        grant.validatingProfileId = required ? parseId(form.getFirst("validator_" + grant.featureKey)) : null;
    }

    /**
     * Builds the render model for the form, reflecting the given profile's
     * grants (all cells clear when the profile is null — the create case).
     *
     * @param profile the profile to edit, or null to create
     * @return the rendered form template
     */
    private TemplateInstance form(Profile profile) {
        List<MenuGroup> groups = new ArrayList<>();
        for (Menu menu : Menu.values()) {
            MenuGroup group = new MenuGroup();
            group.label = menu.getLabel();
            group.name = menu.name();
            for (CrudOption option : CrudOption.values()) {
                OptionCell menuCell = new OptionCell();
                menuCell.token = menu.name() + ":" + option.name();
                menuCell.label = option.getLabel();
                menuCell.checked = false;
                group.menuCells.add(menuCell);
            }
            for (Feature feature : Feature.values()) {
                if (feature.inMenu(menu)) {
                    group.features.add(featureRow(feature, profile));
                }
            }
            groups.add(group);
        }
        List<ProfileRef> otherProfiles = new ArrayList<>();
        for (Profile candidate : Profile.<Profile>listAllOrdered()) {
            if (!candidate.equals(profile)) {
                ProfileRef ref = new ProfileRef();
                ref.id = candidate.id;
                ref.name = candidate.name;
                otherProfiles.add(ref);
            }
        }
        return adminProfileForm.data("profile", profile)
                .data("groups", groups)
                .data("otherProfiles", otherProfiles);
    }

    /**
     * Builds one feature row against a profile's current grants.
     *
     * @param feature the feature
     * @param profile the profile being edited, or null
     * @return the rendered row
     */
    private FeatureRow featureRow(Feature feature, Profile profile) {
        FeatureRow row = new FeatureRow();
        row.key = feature.getKey();
        row.label = feature.getLabel();
        ProfileGrant validationSource = null;
        for (CrudOption option : CrudOption.values()) {
            OptionCell cell = new OptionCell();
            cell.token = feature.getKey() + ":" + option.name();
            cell.label = option.getLabel();
            ProfileGrant held = profile == null ? null : profile.grant(feature, option);
            cell.checked = held != null;
            if (held != null) {
                validationSource = held;
            }
            row.cells.add(cell);
        }
        row.requiresValidation = validationSource != null && validationSource.requiresValidation;
        row.validatorId = validationSource == null ? null : validationSource.validatingProfileId;
        return row;
    }

    /**
     * Runs the permission check for the {@link Feature#PROFILE} feature.
     *
     * @param option the option to check
     * @return a redirect when refused, or null when authorized
     */
    private Response guard(CrudOption option) {
        String loginName = identity.getPrincipal().getName();
        if (permissionService.isAllowed(loginName, Feature.PROFILE, option)) {
            return null;
        }
        return Response.seeOther(URI.create("/admin?notice="
                + URLEncoder.encode("Droit refusé : gestion des profils.", StandardCharsets.UTF_8)
                + "&noticeOk=false")).build();
    }

    /**
     * Parses an option name, tolerating an unknown value.
     *
     * @param name the option name
     * @return the option, or null when the name is unknown
     */
    private CrudOption parseOption(String name) {
        for (CrudOption option : CrudOption.values()) {
            if (option.name().equals(name)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Parses a menu name, tolerating an unknown value.
     *
     * @param name the menu name
     * @return the menu, or null when the name is unknown
     */
    private Menu parseMenu(String name) {
        for (Menu menu : Menu.values()) {
            if (menu.name().equals(name)) {
                return menu;
            }
        }
        return null;
    }

    /**
     * Parses a profile id, tolerating a null, blank or malformed value.
     *
     * @param raw the posted id
     * @return the id, or null when absent or malformed
     */
    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns a posted list, never null.
     *
     * @param list the posted list, possibly null
     * @return the list, or an empty list when null
     */
    private List<String> safeList(List<String> list) {
        return list == null ? List.of() : list;
    }

    /**
     * Trims a posted field, mapping a null to an empty string.
     *
     * @param raw the posted value
     * @return the trimmed value, never null
     */
    private String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * Builds the 303 redirect back to the list carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/profiles?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
