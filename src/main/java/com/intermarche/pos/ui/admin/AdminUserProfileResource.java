package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.CrudOption;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.people.EmployeeProfile;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.domain.people.Profile;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The USER-to-PROFILE assignment screen ({@code /admin/users}, BO-01-03-02
 * assign a profile, BO-01-03-03 assign several).
 * <p>
 * Each employee lists the profiles bound to it, each with the echelon it
 * applies to; the form binds one more profile at a chosen scope, and every
 * binding can be removed. Several bindings on one employee is the normal case,
 * which is exactly the "affecter plusieurs profils à un même utilisateur" the
 * requirement asks for.
 * <p>
 * The employee's fixed {@link Employee.EmployeeRole role} is left untouched:
 * the register still reads it for its endorsement ceremony, and this screen
 * governs only the back-office authorization built on top of it. Access is
 * decided by {@link PermissionService} on the {@link Feature#USER} feature,
 * the same way the profiles screen guards itself.
 */
@Path("/admin/users")
public class AdminUserProfileResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminUserProfileResource.class);

    /** The users-and-assignments template. */
    @Inject
    @Location("admin-users")
    Template adminUsers;

    /** The administered authorization layer. */
    @Inject
    PermissionService permissionService;

    /** The signed-in operator, whose login name drives the permission check. */
    @Inject
    SecurityIdentity identity;

    /** One rendered binding under an employee. */
    public static class BindingRow {
        /** The binding id (for removal). */
        public Long id;
        /** The bound profile's name, or a placeholder when it vanished. */
        public String profileName;
        /** The echelon scope of the binding. */
        public String scope;
    }

    /** One rendered employee with its bindings. */
    public static class UserRow {
        /** The employee id. */
        public Long id;
        /** The employee's full name. */
        public String fullName;
        /** The employee's login name. */
        public String loginName;
        /** The employee's fixed role name. */
        public String role;
        /** The bindings currently attached to the employee. */
        public List<BindingRow> bindings = new ArrayList<>();
    }

    /** A minimal profile reference for the assignment select. */
    public static class ProfileRef {
        /** The profile id. */
        public Long id;
        /** The profile name. */
        public String name;
    }

    /**
     * Shows the users list with their profile bindings.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page, or a redirect when the account may not view it
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
        Map<Long, String> profileNames = new HashMap<>();
        List<ProfileRef> profiles = new ArrayList<>();
        for (Profile profile : Profile.<Profile>listAllOrdered()) {
            profileNames.put(profile.id, profile.name);
            ProfileRef ref = new ProfileRef();
            ref.id = profile.id;
            ref.name = profile.name;
            profiles.add(ref);
        }
        List<UserRow> users = new ArrayList<>();
        for (Employee employee : Employee.<Employee>listAll()) {
            users.add(userRow(employee, profileNames));
        }
        LOGGER.info("Exiting method listPage");
        return adminUsers.data("users", users)
                .data("profiles", profiles)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Binds a profile to an employee at a chosen echelon scope.
     *
     * @param form the posted form
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/assign")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Authenticated
    @Transactional
    public Response assign(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method assign with form: " + form);
        Response denied = guard(CrudOption.UPDATE);
        if (denied != null) {
            LOGGER.info("Exiting method assign");
            return denied;
        }
        Long employeeId = parseId(form.getFirst("employeeId"));
        Long profileId = parseId(form.getFirst("profileId"));
        if (employeeId == null || profileId == null) {
            LOGGER.info("Exiting method assign");
            return redirect("Utilisateur et profil sont obligatoires.", false);
        }
        Employee employee = Employee.findById(employeeId);
        Profile profile = Profile.findById(profileId);
        if (employee == null || profile == null) {
            LOGGER.info("Exiting method assign");
            return redirect("Utilisateur ou profil introuvable.", false);
        }
        String scope = scope(form.getFirst("echelonScope"));
        if (EmployeeProfile.exists(employeeId, profileId, scope)) {
            LOGGER.info("Exiting method assign");
            return redirect("Ce profil est déjà affecté à cet échelon.", false);
        }
        EmployeeProfile binding = new EmployeeProfile();
        binding.employeeId = employeeId;
        binding.profileId = profileId;
        binding.echelonScope = scope;
        binding.persist();
        LOGGER.info("Exiting method assign");
        return redirect("Profil « " + profile.name + " » affecté à " + employee.getFullName() + ".", true);
    }

    /**
     * Removes a profile binding.
     *
     * @param form the posted form carrying the binding id
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/unassign")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Authenticated
    @Transactional
    public Response unassign(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method unassign with form: " + form);
        Response denied = guard(CrudOption.DELETE);
        if (denied != null) {
            LOGGER.info("Exiting method unassign");
            return denied;
        }
        Long bindingId = parseId(form.getFirst("bindingId"));
        EmployeeProfile binding = bindingId == null ? null : EmployeeProfile.<EmployeeProfile>findById(bindingId);
        if (binding == null) {
            LOGGER.info("Exiting method unassign");
            return redirect("Affectation introuvable.", false);
        }
        binding.delete();
        LOGGER.info("Exiting method unassign");
        return redirect("Affectation retirée.", true);
    }

    /**
     * Builds one employee row with its resolved bindings.
     *
     * @param employee the employee
     * @param profileNames the id-to-name map of profiles
     * @return the rendered row
     */
    private UserRow userRow(Employee employee, Map<Long, String> profileNames) {
        UserRow row = new UserRow();
        row.id = employee.id;
        row.fullName = employee.getFullName();
        row.loginName = employee.loginName;
        row.role = employee.role == null ? "" : employee.role.name();
        for (EmployeeProfile binding : EmployeeProfile.forEmployee(employee.id)) {
            BindingRow bindingRow = new BindingRow();
            bindingRow.id = binding.id;
            String name = profileNames.get(binding.profileId);
            bindingRow.profileName = name == null ? "(profil supprimé)" : name;
            bindingRow.scope = binding.echelonScope;
            row.bindings.add(bindingRow);
        }
        return row;
    }

    /**
     * Runs the permission check for the {@link Feature#USER} feature.
     *
     * @param option the option to check
     * @return a redirect when refused, or null when authorized
     */
    private Response guard(CrudOption option) {
        String loginName = identity.getPrincipal().getName();
        if (permissionService.isAllowed(loginName, Feature.USER, option)) {
            return null;
        }
        return Response.seeOther(URI.create("/admin?notice="
                + URLEncoder.encode("Droit refusé : affectation des profils.", StandardCharsets.UTF_8)
                + "&noticeOk=false")).build();
    }

    /**
     * Normalizes a posted scope: an empty scope means the global echelon.
     *
     * @param raw the posted scope
     * @return the trimmed scope, or {@link EmployeeProfile#GLOBAL} when blank
     */
    private String scope(String raw) {
        if (raw == null || raw.isBlank()) {
            return EmployeeProfile.GLOBAL;
        }
        return raw.trim();
    }

    /**
     * Parses an id, tolerating a null, blank or malformed value.
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
     * Builds the 303 redirect back to the list carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/users?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
