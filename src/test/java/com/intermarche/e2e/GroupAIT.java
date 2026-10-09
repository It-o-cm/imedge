package com.intermarche.e2e;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.people.EmployeeProfile;
import com.intermarche.edge.domain.people.Profile;
import com.intermarche.edge.domain.people.UserFavorite;
import com.intermarche.e2e.support.FormLogin;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;

/**
 * Group A — authentication, profiles and rights, exercised end to end against a
 * booted store node (H2, no register). Covers the [E] scenarios A2–A9.
 *
 * <p>These are SCREEN surfaces under {@code /admin/**}: they rely on Quarkus
 * FORM authentication (an anonymous request is met with a 302 redirect to
 * {@code /admin/login}, not a 401), and each screen carries either
 * {@code @Authenticated} + a {@code PermissionService} notice-check, or
 * {@code @RolesAllowed}. A test signs in through {@link FormLogin} and carries
 * the {@code quarkus-credential} cookie, the browser's own mechanism.
 *
 * <p>A1 (the first-administrator bootstrap of {@code EmployeeBootstrap}) is NOT
 * reachable at the [E] level: the dev/test {@code DataInitializer} wipes and
 * reseeds the employee table at every boot, so "empty database" never occurs
 * and {@code Employee.count() > 0} always holds — the bootstrap short-circuits.
 * A1 belongs to a unit test of {@code EmployeeBootstrap}, not to a booted node.
 *
 * <p>The role/notice cases need a non-ADMIN back-office account with
 * {@code mustChangePassword=false} (otherwise the password-change filter
 * diverts screen GETs); none is seeded that way, so this class creates its own
 * {@code ZA*} accounts and profiles and removes them after each test.
 */
@QuarkusTest
@TestProfile(GroupAIT.Cfg.class)
class GroupAIT {

    /**
     * Declares the ADMIN credentials as configuration; a distinct profile class
     * forces a fresh boot and a fresh H2 base for group A.
     */
    public static class Cfg implements QuarkusTestProfile {

        /**
         * Exposes the seeded ADMIN credentials to the test.
         *
         * @return the credential overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("e2e.admin.login", "admin", "e2e.admin.password", "admin");
        }
    }

    /** Login of the seeded ADMIN account. */
    @ConfigProperty(name = "e2e.admin.login")
    String adminLogin;

    /** Back-office password of the seeded ADMIN account. */
    @ConfigProperty(name = "e2e.admin.password")
    String adminPassword;

    /** Badge of the created non-ADMIN MANAGER (no grants, changeable). */
    private static final String MGR_BADGE = "ZAMGR001";
    /** Login of the created MANAGER. */
    private static final String MGR_LOGIN = "zamgr";
    /** Back-office password of the created accounts. */
    private static final String ZA_PASSWORD = "zapass12";
    /** Badge of the account dedicated to the password-change success case. */
    private static final String PWD_BADGE = "ZAPWD001";
    /** Login of the password-change account. */
    private static final String PWD_LOGIN = "zapwd";

    /**
     * Creates the ZA accounts a test may need: a grant-less MANAGER and a
     * throwaway account for the password-change success case, both with a known
     * back-office password and {@code mustChangePassword=false}.
     */
    @BeforeEach
    void seedAccounts() {
        QuarkusTransaction.requiringNew().run(() -> {
            createAccount(MGR_BADGE, MGR_LOGIN, "Zoe", "Manager",
                    Employee.EmployeeRole.MANAGER);
            createAccount(PWD_BADGE, PWD_LOGIN, "Paul", "Word",
                    Employee.EmployeeRole.CASHIER);
        });
    }

    /**
     * Creates and persists one active back-office account with a known password
     * that does not force a change.
     *
     * @param badge the badge id (ZA-prefixed)
     * @param login the login name
     * @param first the first name
     * @param last the last name
     * @param role the employee role
     */
    private void createAccount(String badge, String login, String first, String last,
                               Employee.EmployeeRole role) {
        Employee employee = new Employee();
        employee.badgeId = badge;
        employee.loginName = login;
        employee.password = Employee.hashPassword("1234");
        employee.firstName = first;
        employee.lastName = last;
        employee.email = login + "@za.test";
        employee.role = role;
        employee.active = true;
        employee.setBackOfficePassword(ZA_PASSWORD);
        employee.mustChangePassword = false;
        employee.persist();
    }

    /**
     * Removes every row a group-A test creates, children before parents.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeProfile.deleteAll();
            UserFavorite.deleteAll();
            Profile.delete("name like ?1", "ZA%");
            Employee.delete("badgeId like ?1", "ZA%");
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Builds a request carrying the given account's form-login cookie, following
     * no redirect (so the redirect status and Location can be asserted).
     *
     * @param login the login name
     * @param password the back-office password
     * @return the authenticated request specification
     */
    private RequestSpecification as(String login, String password) {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie(login, password))
                .redirects().follow(false);
    }

    /**
     * Builds a request carrying the ADMIN form-login cookie.
     *
     * @return the authenticated request specification
     */
    private RequestSpecification asAdmin() {
        return as(adminLogin, adminPassword);
    }

    /**
     * Reads the id of an employee by badge.
     *
     * @param badge the badge id
     * @return the persisted id
     */
    private Long employeeId(String badge) {
        return QuarkusTransaction.requiringNew().call(
                () -> ((Employee) Employee.find("badgeId", badge).firstResult()).id);
    }

    /**
     * Reads the id of a profile by name.
     *
     * @param name the profile name
     * @return the persisted id, or null when absent
     */
    private Long profileId(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Profile profile = Profile.find("name", name).firstResult();
            return profile == null ? null : profile.id;
        });
    }

    /**
     * URL-decodes the {@code Location} header of a redirect response.
     *
     * @param location the raw Location header
     * @return the decoded location
     */
    private String decode(String location) {
        return URLDecoder.decode(location, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // A2 — node entry routing
    // ==================================================================

    /**
     * A2 (GET /): the open root redirects to the back office with 303.
     */
    @Test
    void a2_root_redirectsToAdmin() {
        given().redirects().follow(false).when().get("/")
                .then().log().ifValidationFails().statusCode(303)
                .header("Location", containsString("/admin"));
    }

    /**
     * A2 (GET /admin anonymous): form auth diverts to the login page with 302.
     */
    @Test
    void a2_admin_anonymous_redirectsToLogin() {
        // DISCREPANCY (left RED): the catalogue expects GET /admin to reach
        // adminRoot() (@Authenticated → 302 /admin/login for anonymous). Observed:
        // 302 to /admin/ — the static resource directory META-INF/resources/admin/
        // shadows GET /admin, so adminRoot()'s auth/role routing is never reached.
        given().redirects().follow(false).when().get("/admin")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * A2 (GET /admin as ADMIN): the administrator lands on the parameters.
     */
    @Test
    void a2_admin_asAdmin_redirectsToSettings() {
        // DISCREPANCY (left RED): see a2_admin_anonymous_redirectsToLogin —
        // GET /admin is shadowed by the static /admin/ directory (302 → /admin/),
        // so adminRoot()'s 303 → /admin/settings routing is never reached.
        asAdmin().when().get("/admin")
                .then().log().ifValidationFails().statusCode(303)
                .header("Location", containsString("/admin/settings"));
    }

    /**
     * A2 (GET /admin as non-ADMIN): an authenticated non-administrator lands on
     * the dashboard.
     */
    @Test
    void a2_admin_asNonAdmin_redirectsToDashboard() {
        // DISCREPANCY (left RED): see a2_admin_anonymous_redirectsToLogin —
        // GET /admin is shadowed by the static /admin/ directory (302 → /admin/),
        // so adminRoot()'s 303 → /dashboard routing is never reached.
        as(MGR_LOGIN, ZA_PASSWORD).when().get("/admin")
                .then().log().ifValidationFails().statusCode(303)
                .header("Location", containsString("/dashboard"));
    }

    // ==================================================================
    // A3 — sign-in / sign-out
    // ==================================================================

    /**
     * A3 (GET /admin/login anonymous): the sign-in page is open (200).
     */
    @Test
    void a3_login_anonymous_ok() {
        given().when().get("/admin/login")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A3 (GET /admin/login authenticated): the page stays accessible even to a
     * signed-in operator (200).
     */
    @Test
    void a3_login_authenticated_ok() {
        asAdmin().when().get("/admin/login")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A3 (GET /admin/login?error): the exact combined refusal message is shown,
     * naming neither field.
     */
    @Test
    void a3_login_error_showsExactMessage() {
        given().when().get("/admin/login?error=true")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString(
                        "Identifiant ou code incorrect, ou compte désactivé ou verrouillé."));
    }

    /**
     * A3 (GET /admin/login?notice): the one-shot notice is shown verbatim.
     */
    @Test
    void a3_login_notice_shownVerbatim() {
        given().queryParam("notice", "Bonjour ici").when().get("/admin/login")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("Bonjour ici"));
    }

    /**
     * A3 (POST /j_security_check valid): valid credentials set the session
     * cookie and redirect (302) to the landing page.
     */
    @Test
    void a3_jSecurityCheck_valid_setsCookie() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", adminLogin).formParam("j_password", adminPassword)
                .redirects().follow(false)
                .when().post("/j_security_check")
                .then().log().ifValidationFails().statusCode(302)
                .cookie(FormLogin.COOKIE);
    }

    /**
     * A3 (POST /j_security_check invalid): wrong credentials redirect to the
     * error page and issue NO session cookie.
     */
    @Test
    void a3_jSecurityCheck_invalid_noCookie() {
        String cookie = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", adminLogin).formParam("j_password", "wrong-password")
                .redirects().follow(false)
                .when().post("/j_security_check")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"))
                .extract().cookie(FormLogin.COOKIE);
        Assertions.assertTrue(cookie == null || cookie.isEmpty(),
                "a rejected login must issue no session cookie; got: " + cookie);
    }

    /**
     * A3 (POST /admin/logout authenticated): sign-out redirects to the login
     * page with the sign-out notice and clears the session cookie.
     */
    @Test
    void a3_logout_authenticated_clearsCookieWithNotice() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .when().post("/admin/logout")
                .then().log().ifValidationFails().statusCode(303)
                .cookie(FormLogin.COOKIE, is(emptyOrNullString()))
                .extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Vous êtes déconnecté."),
                "the sign-out notice must be carried; location was: " + decode(location));
    }

    /**
     * A3 (POST /admin/logout anonymous): the sign-out route is guarded and
     * diverts an anonymous caller to the login page (302).
     */
    @Test
    void a3_logout_anonymous_redirectsToLogin() {
        given().contentType("application/x-www-form-urlencoded").redirects().follow(false)
                .when().post("/admin/logout")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    // ==================================================================
    // A4 — imposed-password filter
    // ==================================================================

    /**
     * A4 (must change): a signed-in operator whose password is imposed is
     * diverted from a guarded screen to the password page (303). Uses
     * {@code /admin/favorites} — an {@code @Authenticated} page the MANAGER
     * passes authorization on, so the filter (not a role check) is what diverts.
     */
    @Test
    void a4_mustChange_divertsToPasswordPage() {
        as("mcurie", "changeme00").when().get("/admin/favorites")
                .then().log().ifValidationFails().statusCode(303)
                .header("Location", containsString("/admin/password"));
    }

    /**
     * A4 (excluded page): the password page itself is not diverted (else the
     * redirect would loop) — it answers 200.
     */
    @Test
    void a4_passwordPage_notDiverted() {
        given().cookie(FormLogin.COOKIE, FormLogin.cookie("mcurie", "changeme00"))
                .when().get("/admin/password")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A4 (no imposed change): an operator whose password is not imposed reaches
     * the guarded screen normally (200).
     */
    @Test
    void a4_notImposed_reachesScreen() {
        given().cookie(FormLogin.COOKIE, FormLogin.cookie(adminLogin, adminPassword))
                .when().get("/admin/settings")
                .then().log().ifValidationFails().statusCode(200);
    }

    // ==================================================================
    // A5 — password change
    // ==================================================================

    /**
     * A5 (GET authenticated): the password page renders 200.
     */
    @Test
    void a5_get_authenticated_ok() {
        given().cookie(FormLogin.COOKIE, FormLogin.cookie(PWD_LOGIN, ZA_PASSWORD))
                .when().get("/admin/password")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A5 (GET anonymous): the password page diverts an anonymous caller to the
     * login page (302).
     */
    @Test
    void a5_get_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/password")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * A5 (too short): a new password under eight characters is refused with the
     * exact message.
     */
    @Test
    void a5_post_tooShort_refused() {
        String location = as(PWD_LOGIN, ZA_PASSWORD).contentType("application/x-www-form-urlencoded")
                .formParam("current", ZA_PASSWORD).formParam("renewed", "short")
                .formParam("confirmation", "short")
                .when().post("/admin/password")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains(
                "Le nouveau mot de passe doit compter au moins 8 caractères."),
                "location was: " + decode(location));
    }

    /**
     * A5 (mismatch): a new password differing from its confirmation is refused
     * with the exact message.
     */
    @Test
    void a5_post_mismatch_refused() {
        String location = as(PWD_LOGIN, ZA_PASSWORD).contentType("application/x-www-form-urlencoded")
                .formParam("current", ZA_PASSWORD).formParam("renewed", "brandnew1")
                .formParam("confirmation", "brandnew2")
                .when().post("/admin/password")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains(
                "Les deux saisies du nouveau mot de passe diffèrent."),
                "location was: " + decode(location));
    }

    /**
     * A5 (same as current): a new password equal to the current one is refused
     * with the exact message.
     */
    @Test
    void a5_post_sameAsCurrent_refused() {
        String location = as(PWD_LOGIN, ZA_PASSWORD).contentType("application/x-www-form-urlencoded")
                .formParam("current", ZA_PASSWORD).formParam("renewed", ZA_PASSWORD)
                .formParam("confirmation", ZA_PASSWORD)
                .when().post("/admin/password")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains(
                "Le nouveau mot de passe doit être différent de l'actuel."),
                "location was: " + decode(location));
    }

    /**
     * A5 (wrong current): a wrong current password is refused with the exact
     * message and nothing is changed.
     */
    @Test
    void a5_post_wrongCurrent_refused() {
        String location = as(PWD_LOGIN, ZA_PASSWORD).contentType("application/x-www-form-urlencoded")
                .formParam("current", "not-the-password").formParam("renewed", "brandnew1")
                .formParam("confirmation", "brandnew1")
                .when().post("/admin/password")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Mot de passe actuel incorrect."),
                "location was: " + decode(location));
    }

    /**
     * A5 (success): a valid change redirects to the login page with the
     * reconnect notice.
     */
    @Test
    void a5_post_success_redirectsWithNotice() {
        String location = as(PWD_LOGIN, ZA_PASSWORD).contentType("application/x-www-form-urlencoded")
                .formParam("current", ZA_PASSWORD).formParam("renewed", "brandnew1")
                .formParam("confirmation", "brandnew1")
                .when().post("/admin/password")
                .then().log().ifValidationFails().statusCode(303)
                .cookie(FormLogin.COOKIE, is(emptyOrNullString()))
                .extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Mot de passe modifié. Reconnectez-vous."),
                "location was: " + decode(location));
    }

    // ==================================================================
    // A6 — profiles
    // ==================================================================

    /**
     * A6 (anonymous): any profiles route diverts an anonymous caller to login.
     */
    @Test
    void a6_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/profiles")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * A6 (no grant): a non-ADMIN without the PROFILE grant is refused with the
     * exact "droit refusé" notice (a 303 redirect, never a 403).
     */
    @Test
    void a6_noGrant_deniedWithNotice() {
        String location = as(MGR_LOGIN, ZA_PASSWORD).when().get("/admin/profiles")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Droit refusé : gestion des profils."),
                "location was: " + decode(location));
    }

    /**
     * A6 (list as ADMIN): the profiles list renders 200.
     */
    @Test
    void a6_list_asAdmin_ok() {
        asAdmin().when().get("/admin/profiles")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A6 (new form): the blank creation form renders 200.
     */
    @Test
    void a6_new_asAdmin_ok() {
        asAdmin().when().get("/admin/profiles/new")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A6 (edit unknown): editing an unknown profile id redirects with the
     * "profil introuvable" notice.
     */
    @Test
    void a6_editUnknown_notFoundNotice() {
        String location = asAdmin().when().get("/admin/profiles/edit?id=999999")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Profil introuvable."),
                "location was: " + decode(location));
    }

    /**
     * A6 (save empty name): a blank name is refused with the exact message.
     */
    @Test
    void a6_save_emptyName_refused() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("name", "")
                .when().post("/admin/profiles")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Le nom du profil est obligatoire."),
                "location was: " + decode(location));
    }

    /**
     * A6 (save free name, then duplicate): a free name is saved with its notice;
     * re-saving the same name is refused as a clash.
     */
    @Test
    void a6_save_freeThenDuplicate() {
        String created = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("name", "ZAPROF")
                .when().post("/admin/profiles")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(created).contains("Profil « ZAPROF » enregistré."),
                "location was: " + decode(created));
        String clash = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("name", "ZAPROF")
                .when().post("/admin/profiles")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(clash).contains("Un profil porte déjà le nom « ZAPROF »."),
                "location was: " + decode(clash));
    }

    /**
     * A6 (delete unknown): deleting an unknown profile id redirects with the
     * "profil introuvable" notice.
     */
    @Test
    void a6_deleteUnknown_notFoundNotice() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("id", "999999")
                .when().post("/admin/profiles/delete")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Profil introuvable."),
                "location was: " + decode(location));
    }

    /**
     * A6 (delete existing): deleting a saved profile redirects with the
     * "profil supprimé" notice.
     */
    @Test
    void a6_deleteExisting_removed() {
        asAdmin().contentType("application/x-www-form-urlencoded").formParam("name", "ZAPROF")
                .when().post("/admin/profiles").then().statusCode(303);
        Long id = profileId("ZAPROF");
        Assertions.assertNotNull(id, "the profile must have been created");
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("id", String.valueOf(id))
                .when().post("/admin/profiles/delete")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Profil « ZAPROF » supprimé."),
                "location was: " + decode(location));
    }

    // ==================================================================
    // A7 — profile assignment
    // ==================================================================

    /**
     * A7 (anonymous): any users route diverts an anonymous caller to login.
     */
    @Test
    void a7_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/users")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * A7 (no grant): a non-ADMIN without the USER grant is refused with the
     * exact "droit refusé" notice.
     */
    @Test
    void a7_noGrant_deniedWithNotice() {
        String location = as(MGR_LOGIN, ZA_PASSWORD).when().get("/admin/users")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Droit refusé : affectation des profils."),
                "location was: " + decode(location));
    }

    /**
     * A7 (list as ADMIN): the users list renders 200.
     */
    @Test
    void a7_list_asAdmin_ok() {
        asAdmin().when().get("/admin/users")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A7 (assign missing ids): a missing employee or profile id is refused with
     * the exact message.
     */
    @Test
    void a7_assign_missingIds_refused() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .when().post("/admin/users/assign")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Utilisateur et profil sont obligatoires."),
                "location was: " + decode(location));
    }

    /**
     * A7 (assign unknown): unknown employee and profile ids are refused with the
     * exact message.
     */
    @Test
    void a7_assign_unknown_refused() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("employeeId", "999999").formParam("profileId", "999999")
                .when().post("/admin/users/assign")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Utilisateur ou profil introuvable."),
                "location was: " + decode(location));
    }

    /**
     * A7 (assign then duplicate, then unassign): a new binding is created with
     * its notice; re-assigning the same triple is refused; removing it succeeds.
     */
    @Test
    void a7_assign_thenDuplicate_thenUnassign() {
        asAdmin().contentType("application/x-www-form-urlencoded").formParam("name", "ZAPROF")
                .when().post("/admin/profiles").then().statusCode(303);
        Long profileId = profileId("ZAPROF");
        Long employeeId = employeeId(MGR_BADGE);
        String created = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("employeeId", String.valueOf(employeeId))
                .formParam("profileId", String.valueOf(profileId))
                .when().post("/admin/users/assign")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(created).contains("Profil « ZAPROF » affecté à "),
                "location was: " + decode(created));
        String duplicate = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("employeeId", String.valueOf(employeeId))
                .formParam("profileId", String.valueOf(profileId))
                .when().post("/admin/users/assign")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(duplicate).contains("Ce profil est déjà affecté à cet échelon."),
                "location was: " + decode(duplicate));
        Long bindingId = QuarkusTransaction.requiringNew().call(
                () -> ((EmployeeProfile) EmployeeProfile.find("employeeId", employeeId)
                        .firstResult()).id);
        String removed = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("bindingId", String.valueOf(bindingId))
                .when().post("/admin/users/unassign")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(removed).contains("Affectation retirée."),
                "location was: " + decode(removed));
    }

    /**
     * A7 (unassign unknown): removing an unknown binding is refused with the
     * exact message.
     */
    @Test
    void a7_unassign_unknown_refused() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("bindingId", "999999")
                .when().post("/admin/users/unassign")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Affectation introuvable."),
                "location was: " + decode(location));
    }

    // ==================================================================
    // A8 — display favorites
    // ==================================================================

    /**
     * A8 (anonymous): the favorites route diverts an anonymous caller to login.
     */
    @Test
    void a8_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/favorites")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * A8 (authenticated): any signed-in account may administer its own favorites
     * (200), without a dedicated grant.
     */
    @Test
    void a8_authenticated_ok() {
        asAdmin().when().get("/admin/favorites")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A8 (save): saving the display preferences redirects with the exact notice;
     * a forged/unknown key is ignored rather than rejected.
     */
    @Test
    void a8_save_ignoresForgedKeyWithNotice() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("hide", "NOT_A_REAL_FEATURE_KEY")
                .when().post("/admin/favorites")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Préférences d'affichage enregistrées."),
                "location was: " + decode(location));
    }

    // ==================================================================
    // A9 — payroll file
    // ==================================================================

    /**
     * A9 (anonymous): the employees route diverts an anonymous caller to login.
     */
    @Test
    void a9_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/employees")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * A9 (non-ADMIN): an authenticated non-ADMIN is refused with 403 (this
     * surface is {@code @RolesAllowed("ADMIN")}, so it is a hard 403, not a
     * notice redirect).
     */
    @Test
    void a9_nonAdmin_forbidden() {
        as(MGR_LOGIN, ZA_PASSWORD).when().get("/admin/employees")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * A9 (list as ADMIN): the paginated, filtered list renders 200.
     */
    @Test
    void a9_list_asAdmin_ok() {
        asAdmin().when().get("/admin/employees?text=cur&page=1")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * A9 (save unknown file): a null/unknown id is refused with the exact
     * message.
     */
    @Test
    void a9_save_unknown_refused() {
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .when().post("/admin/employees")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Salarié inconnu."),
                "location was: " + decode(location));
    }

    /**
     * A9 (negative ceiling): a negative advance limit is refused with the exact
     * message.
     */
    @Test
    void a9_save_negativeCeiling_refused() {
        Long id = employeeId(MGR_BADGE);
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("id", String.valueOf(id)).formParam("advanceLimit", "-5")
                .when().post("/admin/employees")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains(
                "Le plafond d'acompte ne peut pas être négatif."),
                "location was: " + decode(location));
    }

    /**
     * A9 (valid payroll save): valid payroll columns are saved with the exact
     * notice; a negative forcing level is floored to zero rather than refused.
     */
    @Test
    void a9_save_valid_saved() {
        Long id = employeeId(MGR_BADGE);
        String location = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("id", String.valueOf(id)).formParam("legalEntity", "on")
                .formParam("forcingLevel", "-3").formParam("advanceLimit", "100")
                .when().post("/admin/employees")
                .then().log().ifValidationFails().statusCode(303).extract().header("Location");
        Assertions.assertTrue(decode(location).contains("Fiche salarié enregistrée."),
                "location was: " + decode(location));
    }
}
