package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.EchelonScope;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import com.intermarche.edge.service.PermissionService;
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
 *
 * <p>ECHELONS AND CREDENTIALS (BO-01-01-03, BO-01-02-03, -06, -10). An account
 * is attached to echelons — countries, enseignes, points of sale, types of
 * point of contact, points of contact — that decide where it belongs and on
 * which registers it signs in. An account created without a typed code gets
 * a generated badge, PIN and temporary password, shown once on a printable
 * sheet; an account above can reset either secret of an account below. An
 * account the central node distributes is administered there, not here.
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

    /** The printable supervisor card (BO-03-06-51). */
    @Inject
    @Location("admin-supervisor-card")
    Template adminSupervisorCard;

    /** The supervisor prefix the card's barcode carries (BO-10-02-32). */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** The draw of a new badge number. */
    java.util.random.RandomGenerator random = new java.security.SecureRandom();

    /** The badge numbers the registers read: eight digits. */
    static final int BADGE_BOUND = 100_000_000;

    /** The register PIN of a shop administering nothing: four digits. */
    static final int PIN_BOUND = 10_000;

    /** The length of a generated temporary back-office password. */
    static final int TEMP_PASSWORD_LENGTH = 10;

    /** The characters of a generated password, without the look-alikes 0/O and 1/l/I. */
    static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** The printable credentials sheet: badge, and the secrets just generated. */
    @Inject
    @Location("admin-account-credentials")
    Template adminAccountCredentials;

    /** The signed-in operator. */
    @Inject
    SecurityIdentity identity;

    /** The hierarchy rules. */
    @Inject
    PermissionService permissionService;

    /**
     * One account as the screen lists it.
     */
    public static class AccountRow {

        /** The account's database id. */
        public Long id;

        /** Surname then first name. */
        public String fullName;

        /** The surname, or the company name of a legal person. */
        public String lastName;

        /** Whether the holder is a legal person, shown by its name alone. */
        public boolean legalEntity;

        /** The sign-in name. */
        public String loginName;

        /** The electronic address. */
        public String email;

        /** The telephone, or an empty string. */
        public String phone;

        /** The day the account takes effect, as dd/MM/yyyy, or an empty string. */
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

        /** Whether the holder carries a supervisor card (BO-03-06-51). */
        public boolean supervisorCard;

        /** The echelons the account is attached to, as the operator reads them. */
        public String scopes;

        /** How the account signs in to the back office. */
        public String authMode;

        /** Whether the central node administers the account. */
        public boolean central;

        /** Whether the signed-in operator may administer the account. */
        public boolean manageable;
    }

    /**
     * The account being modified, as the edit form shows it.
     */
    public static class AccountForm {

        /** The account's database id. */
        public Long id;

        /** The sign-in name. */
        public String loginName;

        /** The surname. */
        public String lastName;

        /** The first name. */
        public String firstName;

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

        /** The badge, or an empty string. */
        public String badgeId;

        /** The language, or an empty string. */
        public String language;

        /** The role name. */
        public String role;

        /** Whether the account is active. */
        public boolean active;

        /** The canonical echelons the account is attached to. */
        public List<String> scopes = new ArrayList<>();

        /** The sign-in mode name. */
        public String authMode;

        /** The Dallas key identifier, or an empty string (BO-01-02-07). */
        public String dallasKey;
    }

    /**
     * Lists the accounts, filtered by a fragment of name, login or department.
     *
     * @param text a fragment to filter on, or null for all of them
     * @param page the 1-based page number
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @param edit the id of the account to modify, or null
     * @return the accounts page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.ACCOUNT)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("text") String text,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                 @QueryParam("edit") Long edit) {
        LOGGER.info("Entering method list with text: " + text + ", page: " + page);
        Employee actor = actor();
        List<AccountRow> rows = new ArrayList<>();
        for (Employee employee : search(text)) {
            AccountRow row = row(employee);
            row.manageable = permissionService.outranks(actor, employee) && !employee.centralManaged;
            rows.add(row);
        }
        Employee edited = edit == null ? null : Employee.<Employee>findById(edit);
        AccountForm editing = edited == null ? null : form(edited);
        LOGGER.info("Exiting method list with " + rows.size() + " account(s)");
        return adminAccounts.data("page", AdminForms.page(rows, page))
                .data("baseQuery", AdminForms.pageParam("text", text))
                .data("text", text == null ? "" : text)
                .data("roles", Employee.EmployeeRole.values())
                .data("authModes", Employee.AuthMode.values())
                .data("echelons", EchelonChoices.accountLevel(editing == null ? null : editing.scopes))
                .data("editing", editing)
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
    @Authenticated
    @FeatureAccess(value = Feature.ACCOUNT, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        Long id = AdminForms.id(form.getFirst("id"));
        String loginName = AdminForms.text(form, "loginName");
        String lastName = AdminForms.text(form, "lastName");
        String email = AdminForms.text(form, "email");
        String firstName = AdminForms.text(form, "firstName");
        if (loginName == null || lastName == null || firstName == null || email == null) {
            // The first name is mandatory on the entity: refused here with a
            // notice rather than by a failed commit.
            LOGGER.info("Exiting method save: incomplete");
            return back("Identifiant, nom, prénom et adresse électronique sont obligatoires.", false);
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
        Employee actor = actor();
        String refusal = refusal(actor, id == null ? null : account);
        if (refusal != null) {
            LOGGER.info("Exiting method save: " + refusal);
            return back(refusal, false);
        }
        int level = Math.max(AdminForms.integer(form.getFirst("hierarchyLevel"), 0), 0);
        Employee.EmployeeRole role = role(form.getFirst("role"));
        if (!isAdmin(actor) && (actor == null || level >= actor.hierarchyLevel
                || role == Employee.EmployeeRole.ADMIN)) {
            LOGGER.info("Exiting method save: above the actor");
            return back("Un compte doit rester d'un niveau strictement inférieur au vôtre, et seul un"
                    + " administrateur crée un administrateur.", false);
        }
        List<String> posted = postedScopes(form);
        List<String> invalid = new ArrayList<>();
        for (String raw : posted) {
            invalid.addAll(EchelonScope.invalidParts(raw));
        }
        if (!invalid.isEmpty()) {
            LOGGER.info("Exiting method save: invalid scope");
            return back("Échelon invalide : " + String.join(", ", invalid) + ".", false);
        }
        String dallasKey = Employee.normalizeDallasKey(form.getFirst("dallasKey"));
        String dallasRefusal = dallasRefusal(dallasKey, id);
        if (dallasRefusal != null) {
            LOGGER.info("Exiting method save: " + dallasRefusal);
            return back(dallasRefusal, false);
        }
        String secret = AdminForms.text(form, "secret");
        if (secret != null && secret.length() < MIN_SECRET) {
            LOGGER.info("Exiting method save: secret too short");
            return back("Le code doit comporter au moins " + MIN_SECRET + " caractères.", false);
        }
        account.loginName = loginName;
        account.lastName = lastName;
        account.firstName = firstName;
        account.email = email;
        account.phone = AdminForms.text(form, "phone");
        account.startDate = AdminForms.day(form.getFirst("startDate"));
        account.department = AdminForms.text(form, "department");
        account.hierarchyLevel = level;
        account.badgeId = AdminForms.text(form, "badgeId");
        account.language = AdminForms.text(form, "language");
        account.role = role;
        account.active = AdminForms.checkbox(form, "active");
        account.echelonScopes = scopes(posted);
        account.authMode = Employee.AuthMode.from(form.getFirst("authMode"));
        account.dallasKey = dallasKey;
        if (account.badgeId == null) {
            // The badge is mandatory on the entity: an account saved without
            // one is given a fresh one rather than refused (BO-01-02-06).
            account.badgeId = newBadge();
        }
        if (id == null && secret == null) {
            // BO-01-02-06: the system generates the secrets of a holder
            // created without a typed code, and shows them ONCE, with the
            // badge, on the sheet the administrator prints and hands over.
            String pin = newPin();
            String temporary = newTemporaryPassword();
            account.password = Employee.hashPassword(pin);
            account.setBackOfficePassword(temporary);
            account.passwordChangedAt = null;
            account.mustChangePassword = true;
            account.persist();
            LOGGER.infof("Compte %s créé avec des identifiants générés", account.loginName);
            LOGGER.info("Exiting method save: generated credentials");
            return credentials(account, pin, temporary, "Compte créé : identifiants générés.");
        }
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
    @Authenticated
    @FeatureAccess(value = Feature.ACCOUNT, option = CrudOption.DELETE)
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
        String refusal = refusal(actor(), account);
        if (refusal != null) {
            LOGGER.info("Exiting method delete: " + refusal);
            return back(refusal, false);
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
        row.lastName = employee.lastName;
        row.legalEntity = employee.legalEntity;
        row.loginName = employee.loginName;
        row.email = employee.email;
        row.phone = orEmpty(employee.phone);
        row.startDate = employee.startDate == null ? ""
                : employee.startDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        row.department = orEmpty(employee.department);
        row.hierarchyLevel = employee.hierarchyLevel;
        row.role = employee.role == null ? "" : employee.role.name();
        row.language = orEmpty(employee.language);
        row.active = employee.active;
        row.referenced = referenced(employee);
        row.supervisorCard = employee.supervisorCard;
        List<String> labels = new ArrayList<>();
        for (String scope : EchelonScope.parseList(employee.echelonScopes)) {
            labels.add(EchelonScope.label(scope));
        }
        row.scopes = labels.isEmpty() ? EchelonScope.label(EchelonScope.ALL) : String.join(", ", labels);
        row.authMode = employee.authMode == null ? Employee.AuthMode.ANY.name() : employee.authMode.name();
        row.central = employee.centralManaged;
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

    /**
     * Issues a supervisor card to a holder, or withdraws it (BO-03-06-51).
     *
     * <p>The card IS the holder's badge behind the supervisor prefix: issuing it
     * gives the holder a badge when they have none, marks them as carrying the
     * card — which lets them endorse at the registers whatever their role — and
     * opens the card to print. Withdrawing it takes that power back; the badge
     * stays theirs.
     *
     * @param form the posted form (id, and action=revoke to withdraw)
     * @return a 303 to the printable card, or back to the list with the outcome
     */
    @POST
    @jakarta.ws.rs.Path("/supervisor-card")
    @Authenticated
    @FeatureAccess(value = Feature.ACCOUNT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response issueSupervisorCard(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method issueSupervisorCard");
        Long id = AdminForms.id(form.getFirst("id"));
        Employee holder = id == null ? null : Employee.<Employee>findById(id);
        if (holder == null) {
            LOGGER.info("Exiting method issueSupervisorCard: unknown account");
            return back("Compte inconnu.", false);
        }
        String refusal = refusal(actor(), holder);
        if (refusal != null) {
            LOGGER.info("Exiting method issueSupervisorCard: " + refusal);
            return back(refusal, false);
        }
        if ("revoke".equals(form.getFirst("action"))) {
            holder.supervisorCard = false;
            holder.persist();
            LOGGER.info("Exiting method issueSupervisorCard: withdrawn");
            return back("Carte superviseur retirée à " + holder.getFullName() + ".", true);
        }
        if (holder.badgeId == null || holder.badgeId.isBlank()) {
            holder.badgeId = newBadge();
        }
        holder.supervisorCard = true;
        holder.persist();
        LOGGER.info("Exiting method issueSupervisorCard: issued");
        return Response.seeOther(java.net.URI.create("/admin/accounts/supervisor-card?id=" + holder.id)).build();
    }

    /**
     * Shows the supervisor card of a holder, ready to print (BO-03-06-51): the
     * holder's name and the barcode of their badge behind the supervisor prefix.
     *
     * @param id the holder's id
     * @return the card page; {@code holder} is null when there is no card to show
     */
    @GET
    @jakarta.ws.rs.Path("/supervisor-card")
    @Authenticated
    @FeatureAccess(value = Feature.ACCOUNT)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance supervisorCard(@QueryParam("id") Long id) {
        LOGGER.info("Entering method supervisorCard with id: " + id);
        Employee holder = id == null ? null : Employee.<Employee>findById(id);
        if (holder == null || !holder.supervisorCard || holder.badgeId == null) {
            LOGGER.info("Exiting method supervisorCard: no card");
            return adminSupervisorCard.data("holder", null).data("code", "").data("barcode", "");
        }
        String prefix = posSettingsService.supervisorBadgePrefix();
        String code = (prefix == null ? "" : prefix.trim()) + holder.badgeId;
        LOGGER.info("Exiting method supervisorCard");
        return adminSupervisorCard.data("holder", holder.getFullName()).data("code", code)
                .data("barcode", Code128Svg.svg(code));
    }

    /**
     * Draws a badge number no holder carries yet.
     *
     * @return eight digits
     */
    String newBadge() {
        String candidate;
        do {
            candidate = String.format("%08d", random.nextInt(BADGE_BOUND));
        } while (Employee.find("badgeId", candidate).firstResult() != null);
        return candidate;
    }

    /**
     * Resets the secrets of an account below the operator (BO-01-02-10): the
     * register PIN, the back-office password, or both. The new secrets are
     * generated, shown once on the printable sheet, and must be changed by
     * their holder.
     *
     * @param form the posted form ({@code id}, {@code what}: pin, password or both)
     * @return the credentials sheet, or a 303 back to the list with a refusal
     */
    @POST
    @jakarta.ws.rs.Path("/reset")
    @Authenticated
    @FeatureAccess(value = Feature.ACCOUNT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response reset(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method reset");
        Long id = AdminForms.id(form.getFirst("id"));
        Employee account = id == null ? null : Employee.<Employee>findById(id);
        if (account == null) {
            LOGGER.info("Exiting method reset: unknown account");
            return back("Compte inconnu.", false);
        }
        String refusal = refusal(actor(), account);
        if (refusal != null) {
            LOGGER.info("Exiting method reset: " + refusal);
            return back(refusal, false);
        }
        String what = form.getFirst("what") == null ? "both" : form.getFirst("what");
        boolean pinToo = !"password".equals(what);
        boolean passwordToo = !"pin".equals(what);
        String pin = pinToo ? newPin() : null;
        String temporary = passwordToo ? newTemporaryPassword() : null;
        if (pinToo) {
            account.password = Employee.hashPassword(pin);
            account.passwordChangedAt = null;
        }
        if (passwordToo) {
            account.setBackOfficePassword(temporary);
            account.mustChangePassword = true;
        }
        account.failedAttempts = 0;
        account.lockedUntil = null;
        account.persist();
        LOGGER.infof("Secrets du compte %s réinitialisés (%s)", account.loginName, what);
        LOGGER.info("Exiting method reset");
        return credentials(account, pin, temporary, "Secrets réinitialisés : remettez-les au titulaire.");
    }

    /**
     * Shows the printable badge of an account, without any secret
     * (BO-01-02-06: the administrator prints the badge).
     *
     * @param id the account id
     * @return the sheet, or a 303 back to the list for an unknown account or one without badge
     */
    @GET
    @jakarta.ws.rs.Path("/badge")
    @Authenticated
    @FeatureAccess(value = Feature.ACCOUNT)
    @Produces(MediaType.TEXT_HTML)
    public Response badge(@QueryParam("id") Long id) {
        LOGGER.info("Entering method badge with id: " + id);
        Employee account = id == null ? null : Employee.<Employee>findById(id);
        if (account == null || account.badgeId == null || account.badgeId.isBlank()) {
            LOGGER.info("Exiting method badge: no badge");
            return back("Ce compte ne porte pas de badge.", false);
        }
        LOGGER.info("Exiting method badge");
        return credentials(account, null, null, null);
    }

    /**
     * Renders the credentials sheet of an account.
     *
     * @param account the account
     * @param pin the PIN just generated, or null
     * @param temporary the temporary password just generated, or null
     * @param notice the message heading the sheet, or null
     * @return the sheet, 200
     */
    Response credentials(Employee account, String pin, String temporary, String notice) {
        String body = adminAccountCredentials.data("holder", account.getFullName())
                .data("loginName", account.loginName)
                .data("badge", account.badgeId)
                .data("barcode", account.badgeId == null ? "" : Code128Svg.svg(account.badgeId))
                .data("pin", pin)
                .data("temporary", temporary)
                .data("notice", notice)
                .render();
        return Response.ok(body, MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /**
     * Says why the operator may not administer an account, if they may not.
     *
     * @param actor the signed-in operator, possibly null
     * @param target the account, or null for a new one
     * @return the refusal, or null when the operator may administer it
     */
    String refusal(Employee actor, Employee target) {
        if (target != null && target.centralManaged) {
            return "Ce compte est administré par le nœud central.";
        }
        if (target != null && actor != null && target.id != null && target.id.equals(actor.id)) {
            return "Votre propre compte ne s'administre pas ici : changez votre mot de passe depuis l'en-tête.";
        }
        if (!permissionService.outranks(actor, target)) {
            return "Vous n'administrez que les comptes d'un niveau strictement inférieur au vôtre.";
        }
        return null;
    }

    /**
     * Tells whether an account is an administrator.
     *
     * @param actor the account, possibly null
     * @return true for an ADMIN
     */
    private boolean isAdmin(Employee actor) {
        return actor != null && actor.role == Employee.EmployeeRole.ADMIN;
    }

    /**
     * Returns the signed-in operator's account.
     *
     * @return the account, or null when none is signed in or it is unknown
     */
    Employee actor() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return null;
        }
        return Employee.findActiveLogin(identity.getPrincipal().getName());
    }

    /**
     * Checks the Dallas key typed for an account (BO-01-02-07): sixteen
     * hexadecimal digits, held by no other account.
     *
     * @param dallasKey the normalized key, or null for none
     * @param id the account being saved, or null for a creation
     * @return the refusal, or null when the key may be stored
     */
    String dallasRefusal(String dallasKey, Long id) {
        if (dallasKey == null) {
            return null;
        }
        if (!Employee.isDallasKey(dallasKey)) {
            return "Clé Dallas invalide : seize chiffres hexadécimaux.";
        }
        Employee holder = Employee.find("dallasKey", dallasKey).firstResult();
        if (holder != null && (id == null || !holder.id.equals(id))) {
            return "Cette clé Dallas est déjà attribuée à " + holder.loginName + ".";
        }
        return null;
    }

    /**
     * Gathers the echelons posted for an account: those picked in the list,
     * plus the registers typed in by identifier, which need not be declared
     * nor seen on the line yet (BO-01-02-03), in upper case like every
     * register identifier.
     *
     * @param form the posted form
     * @return the raw scopes, never null
     */
    List<String> postedScopes(MultivaluedMap<String, String> form) {
        List<String> posted = new ArrayList<>(form.get("scope") == null ? List.of() : form.get("scope"));
        String terminals = AdminForms.text(form, "terminals");
        if (terminals != null) {
            for (String terminal : terminals.split("[,;\\s]+")) {
                if (!terminal.isBlank()) {
                    posted.add("TPV:" + terminal.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        return posted;
    }

    /**
     * Joins the posted echelons into their stored form.
     *
     * @param posted the posted {@code scope} values, possibly null
     * @return the canonical scopes separated by semicolons, or null for the whole organisation
     */
    String scopes(List<String> posted) {
        List<String> scopes = new ArrayList<>();
        for (String raw : posted == null ? List.<String>of() : posted) {
            for (String scope : EchelonScope.parseList(raw)) {
                if (!scopes.contains(scope)) {
                    scopes.add(scope);
                }
            }
        }
        if (scopes.contains(EchelonScope.ALL)) {
            scopes.remove(EchelonScope.ALL);
        }
        return EchelonScope.join(scopes);
    }

    /**
     * Projects an account onto the edit form.
     *
     * @param employee the account
     * @return the form values
     */
    AccountForm form(Employee employee) {
        AccountForm form = new AccountForm();
        form.id = employee.id;
        form.loginName = employee.loginName;
        form.lastName = employee.lastName;
        form.firstName = orEmpty(employee.firstName);
        form.email = employee.email;
        form.phone = orEmpty(employee.phone);
        form.startDate = employee.startDate == null ? "" : employee.startDate.toString();
        form.department = orEmpty(employee.department);
        form.hierarchyLevel = employee.hierarchyLevel;
        form.badgeId = orEmpty(employee.badgeId);
        form.language = orEmpty(employee.language);
        form.role = employee.role == null ? Employee.EmployeeRole.CASHIER.name() : employee.role.name();
        form.active = employee.active;
        form.scopes = EchelonScope.parseList(employee.echelonScopes);
        form.authMode = employee.authMode == null ? Employee.AuthMode.ANY.name() : employee.authMode.name();
        form.dallasKey = orEmpty(employee.dallasKey);
        return form;
    }

    /**
     * Draws a register PIN, on the number of digits the shop administered for
     * its registers (BO-10-02-23) — the registers refuse any other length, so
     * a PIN drawn on four digits for a shop that asks six could never be used.
     *
     * @return the PIN, four digits on a resource built without its parameters
     */
    String newPin() {
        int digits = posSettingsService == null ? 4 : posSettingsService.pinLength();
        int bound = digits == 4 ? PIN_BOUND : (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", random.nextInt(bound));
    }

    /**
     * Draws a temporary back-office password.
     *
     * @return ten characters without look-alikes
     */
    String newTemporaryPassword() {
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }
}
