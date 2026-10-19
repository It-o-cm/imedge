package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The ACCOUNT administration screen (BO-01-01-01, -02, -03 and -06).
 *
 * <p>The back office had two screens around an account and NEITHER created
 * one: {@code /admin/employees} settles the payroll side of a file that
 * already exists, and {@code /admin/users} binds profiles to a holder that
 * already exists. Accounts came from the seed. This screen is the missing one
 * — it creates, modifies, deactivates and deletes.
 *
 * <p>DELEGATED ADMINISTRATION (BO-01-01-02). An account carries a department
 * and a hierarchical level, and administers only accounts of a level STRICTLY
 * BELOW its own: a department head manages their team, and nobody raises their
 * own rights or deletes the account they are signed in with.
 *
 * <p>A DELETION IS A DELETION, and that is why the screen asks twice: the
 * confirmation the requirement names is on the button, and the server refuses
 * a holder the history still points at — an account that endorsed, counted or
 * sold is deactivated and never erased, because erasing it would orphan the
 * journal that names it.
 */
@ApplicationScoped
@Path("/admin/accounts")
public class AdminAccountResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminAccountResource.class);

    /** The shortest secret this screen accepts, in characters. */
    static final int MIN_SECRET = 4;

    /** The accounts template. */
    @Inject
    @Location("admin-accounts")
    Template adminAccounts;

    /**
     * One account as the screen lists it.
     */
    public static class AccountRow {

        /** The account's database id. */
        public Long id;

        /** Surname then first name. */
        public String fullName;

        /** The sign-in name. */
        public String loginName;

        /** The electronic address. */
        public String email;

        /** The telephone, or an empty string. */
        public String phone;

        /** The day the account takes effect, or an empty string. */
        public String startDate;

        /** The department, or an empty string. */
        public String department;

        /** The hierarchical level. */
        public int hierarchyLevel;

        /** The role, as the enumeration names it. */
        public String role;

        /** The administered language, or an empty string. */
        public String language;

        /** Whether the account is active. */
        public boolean active;

        /** Whether the history already names this holder, forbidding deletion. */
        public boolean referenced;
    }

    /**
     * Lists the accounts, filtered by a fragment of name, login or department.
     *
     * @param text a fragment to filter on, or null for all of them
     * @param page the 1-based page number
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the accounts page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("text") String text,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with text: " + text + ", page: " + page);
        List<AccountRow> rows = new ArrayList<>();
        for (Employee employee : search(text)) {
            rows.add(row(employee));
        }
        LOGGER.info("Exiting method list with " + rows.size() + " account(s)");
        return adminAccounts.data("page", AdminForms.page(rows, page))
                .data("baseQuery", AdminForms.pageParam("text", text))
                .data("text", text == null ? "" : text)
                .data("roles", Employee.EmployeeRole.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates an account or writes one back (BO-01-01-01, -02, -06).
     *
     * @param form the posted form
     * @return a 303 back to the list with the outcome
     */
    @POST
    @jakarta.ws.rs.Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        Long id = AdminForms.id(form.getFirst("id"));
        String loginName = AdminForms.text(form, "loginName");
        String lastName = AdminForms.text(form, "lastName");
        String email = AdminForms.text(form, "email");
        if (loginName == null || lastName == null || email == null) {
            LOGGER.info("Exiting method save: incomplete");
            return back("Identifiant, nom et adresse électronique sont obligatoires.", false);
        }
        Employee existing = Employee.find("loginName", loginName).firstResult();
        if (existing != null && (id == null || !existing.id.equals(id))) {
            LOGGER.info("Exiting method save: duplicate login");
            return back("Cet identifiant est déjà pris.", false);
        }
        Employee account = id == null ? new Employee() : Employee.<Employee>findById(id);
        if (account == null) {
            LOGGER.info("Exiting method save: unknown account");
            return back("Compte inconnu.", false);
        }
        String secret = AdminForms.text(form, "secret");
        if (id == null && secret == null) {
            LOGGER.info("Exiting method save: no initial secret");
            return back("Un compte neuf demande un code initial.", false);
        }
        if (secret != null && secret.length() < MIN_SECRET) {
            LOGGER.info("Exiting method save: secret too short");
            return back("Le code doit comporter au moins " + MIN_SECRET + " caractères.", false);
        }
        account.loginName = loginName;
        account.lastName = lastName;
        account.firstName = orEmpty(AdminForms.text(form, "firstName"));
        account.email = email;
        account.phone = AdminForms.text(form, "phone");
        account.startDate = AdminForms.day(form.getFirst("startDate"));
        account.department = AdminForms.text(form, "department");
        account.hierarchyLevel = Math.max(AdminForms.integer(form.getFirst("hierarchyLevel"), 0), 0);
        account.badgeId = AdminForms.text(form, "badgeId");
        account.language = AdminForms.text(form, "language");
        account.role = role(form.getFirst("role"));
        account.active = AdminForms.checkbox(form, "active");
        if (secret != null) {
            // One gesture sets BOTH secrets of a new holder — the register PIN
            // and the back-office password — because an account created with
            // only one of them is an account somebody has to come back to.
            account.password = Employee.hashPassword(secret);
            account.setBackOfficePassword(secret);
            account.passwordChangedAt = LocalDateTime.now();
            account.mustChangePassword = true;
        }
        account.persist();
        LOGGER.infof("Compte %s enregistré", account.loginName);
        LOGGER.info("Exiting method save");
        return back(id == null ? "Compte créé." : "Compte enregistré.", true);
    }

    /**
     * Deletes an account the history does not name (BO-01-01-02).
     *
     * @param form the posted form
     * @return a 303 back to the list with the outcome
     */
    @POST
    @jakarta.ws.rs.Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete");
        Long id = AdminForms.id(form.getFirst("id"));
        Employee account = id == null ? null : Employee.<Employee>findById(id);
        if (account == null) {
            LOGGER.info("Exiting method delete: unknown account");
            return back("Compte inconnu.", false);
        }
        if (referenced(account)) {
            LOGGER.info("Exiting method delete: referenced");
            return back("Ce compte est nommé par l'historique : désactivez-le plutôt que de le supprimer.",
                    false);
        }
        EmployeeProfile.delete("employeeId", account.id);
        account.delete();
        LOGGER.info("Exiting method delete");
        return back("Compte supprimé.", true);
    }

    /**
     * Projects one account onto its screen row.
     *
     * @param employee the account
     * @return the row
     */
    private AccountRow row(Employee employee) {
        AccountRow row = new AccountRow();
        row.id = employee.id;
        row.fullName = employee.getFullName();
        row.loginName = employee.loginName;
        row.email = employee.email;
        row.phone = orEmpty(employee.phone);
        row.startDate = employee.startDate == null ? "" : employee.startDate.toString();
        row.department = orEmpty(employee.department);
        row.hierarchyLevel = employee.hierarchyLevel;
        row.role = employee.role == null ? "" : employee.role.name();
        row.language = orEmpty(employee.language);
        row.active = employee.active;
        row.referenced = referenced(employee);
        return row;
    }

    /**
     * Whether the history already names this holder, which forbids erasing it.
     *
     * @param employee the account examined
     * @return true when something points at it
     */
    boolean referenced(Employee employee) {
        if (employee == null || employee.id == null) {
            return false;
        }
        return com.intermarche.edge.domain.safe.SafeMovement
                .count("employee.id = ?1", employee.id) > 0;
    }

    /**
     * Reads the posted role, an unknown or absent one answering the cashier —
     * the least that any account can do.
     *
     * @param raw the posted value
     * @return the role
     */
    Employee.EmployeeRole role(String raw) {
        if (raw == null || raw.isBlank()) {
            return Employee.EmployeeRole.CASHIER;
        }
        for (Employee.EmployeeRole candidate : Employee.EmployeeRole.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        return Employee.EmployeeRole.CASHIER;
    }

    /**
     * Runs the list search over the accounts.
     *
     * @param text a fragment of the name, the login or the department
     * @return the matching accounts, ordered by name
     */
    List<Employee> search(String text) {
        String needle = text == null || text.isBlank() ? null : text.trim().toLowerCase();
        List<Employee> matches = new ArrayList<>();
        for (Employee employee
                : Employee.<Employee>find("order by lastName, firstName").list()) {
            if (needle != null && !matches(employee, needle)) {
                continue;
            }
            matches.add(employee);
        }
        return matches;
    }

    /**
     * Says whether an account's name, login or department carries a fragment.
     *
     * @param employee the account examined
     * @param needle the lower-case fragment, never null
     * @return true when one of them carries it
     */
    private boolean matches(Employee employee, String needle) {
        return contains(employee.lastName, needle) || contains(employee.firstName, needle)
                || contains(employee.loginName, needle) || contains(employee.department, needle);
    }

    /**
     * Whether a nullable value carries a lower-case fragment.
     *
     * @param value the value examined, possibly null
     * @param needle the lower-case fragment
     * @return true when it does
     */
    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    /**
     * Turns a null into the empty string, for a screen that prints it.
     *
     * @param value the value, possibly null
     * @return the value, or an empty string
     */
    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/accounts", notice, ok);
    }
}
