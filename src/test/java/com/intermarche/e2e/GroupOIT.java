package com.intermarche.e2e;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.e2e.support.FormLogin;
import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
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
import static org.hamcrest.Matchers.not;

/**
 * Group O — account customers back office ({@code /admin/customers},
 * {@code @RolesAllowed("ADMIN")}), [E] scenarios O1..O3.
 *
 * <p>O4 (register push origin-conflict) and O5 (integration error) are tagged
 * [R]/supervision and are not exercised here: the {@code /api/sync/customer}
 * ingestion mechanics are already covered node-side by {@code GroupCIT} (C6);
 * the origin-conflict rule requires a register push and is deferred.
 *
 * <p>The customer screens are form-authenticated {@code /admin/**}: sign-in is
 * done through {@link FormLogin}. Account-creation blocking is a stored setting
 * ({@code customer.account-creation-blocked}); it is toggled through
 * {@link PosSettingsService#store} (which invalidates the cache) and reset after
 * each test.
 */
@QuarkusTest
@TestProfile(GroupOIT.Profile.class)
class GroupOIT {

    /** A profile of its own, forcing a fresh boot and H2 for group O. */
    public static class Profile implements QuarkusTestProfile {
        /**
         * Declares the seeded ADMIN credentials as configuration.
         *
         * @return the credential overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("e2e.admin.login", "admin", "e2e.admin.password", "admin");
        }
    }

    /** The stored key that blocks back-office account creation. */
    private static final String CREATION_BLOCKED_KEY = "customer.account-creation-blocked";

    /** Login of the seeded ADMIN account. */
    @ConfigProperty(name = "e2e.admin.login")
    String adminLogin;

    /** Back-office password of the seeded ADMIN account. */
    @ConfigProperty(name = "e2e.admin.password")
    String adminPassword;

    /** The settings service, used to toggle the creation-blocked flag. */
    @Inject
    PosSettingsService settings;

    /**
     * Seeds a baseline account (ZOACC1, REGISTER) and a second one (ZOACC2,
     * INTEGRATED, blocked) for the list filters, plus a MANAGER account for the
     * role-refusal case. Runs before every test.
     */
    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            AccountCustomer a = new AccountCustomer();
            a.accountNumber = "ZOACC1";
            a.companyName = "ZO Base Co";
            a.taxId = "ZOTAX1";
            a.origin = AccountCustomer.Origin.REGISTER;
            a.blocked = false;
            a.persist();
            AccountCustomer b = new AccountCustomer();
            b.accountNumber = "ZOACC2";
            b.companyName = "ZO Other Firm";
            b.taxId = "ZOTAX2";
            b.origin = AccountCustomer.Origin.INTEGRATED;
            b.blocked = true;
            b.persist();
            Employee m = new Employee();
            m.badgeId = "ZO000001";
            m.loginName = "zomgr";
            m.password = Employee.hashPassword("1234");
            m.firstName = "Z";
            m.lastName = "Mgr";
            m.email = "zomgr@zo.test";
            m.role = Employee.EmployeeRole.MANAGER;
            m.active = true;
            m.setBackOfficePassword("zopass");
            m.mustChangePassword = false;
            m.persist();
        });
    }

    /**
     * Removes the accounts and the manager the tests create, and resets the
     * creation-blocked flag, after every test.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            AccountCustomer.delete("accountNumber like ?1", "ZO%");
            Employee.delete("badgeId like ?1", "ZO%");
        });
        settings.store(CREATION_BLOCKED_KEY, "false");
    }

    /**
     * Signs in as ADMIN and returns the session cookie.
     *
     * @return the ADMIN session cookie value
     */
    private String adminCookie() {
        return FormLogin.cookie(adminLogin, adminPassword);
    }

    /**
     * Reads the database id of a seeded account by its number.
     *
     * @param accountNumber the account number
     * @return the persisted id
     */
    private Long idOf(String accountNumber) {
        return QuarkusTransaction.requiringNew().call(
                () -> ((AccountCustomer) AccountCustomer.find("accountNumber", accountNumber)
                        .firstResult()).id);
    }

    /**
     * Asserts a redirect carries the expected status, target path and, once
     * URL-decoded, the exact notice message.
     *
     * @param r the response
     * @param status the expected HTTP status
     * @param pathFragment a fragment the Location must contain
     * @param message the exact notice message the decoded Location must contain
     */
    private void assertNotice(Response r, int status, String pathFragment, String message) {
        Assertions.assertEquals(status, r.statusCode(), r.asString());
        String loc = r.header("Location");
        Assertions.assertNotNull(loc, "a redirect Location was expected");
        Assertions.assertTrue(loc.contains(pathFragment), "Location=" + loc);
        String decoded = URLDecoder.decode(loc, StandardCharsets.UTF_8);
        Assertions.assertTrue(decoded.contains(message), "decoded Location=" + decoded);
    }

    // ================= O1 — list and export =================

    /**
     * O1 (list, ADMIN): the list renders 200 for an administrator.
     */
    @Test
    void o1_list_admin_ok() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * O1 (text filter): a fragment matches on the company name, in memory and
     * case-insensitively.
     */
    @Test
    void o1_list_filterText() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers?text=base")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("ZO Base Co"))
                .body(not(containsString("ZO Other Firm")));
    }

    /**
     * O1 (taxId filter): a fragment matches on the fiscal id.
     */
    @Test
    void o1_list_filterTaxId() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers?taxId=ZOTAX2")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("ZO Other Firm"))
                .body(not(containsString("ZO Base Co")));
    }

    /**
     * O1 (origin filter): an exact origin name selects only that origin.
     */
    @Test
    void o1_list_filterOrigin() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers?origin=INTEGRATED")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("ZO Other Firm"))
                .body(not(containsString("ZO Base Co")));
    }

    /**
     * O1 (blocked filter): {@code yes} keeps blocked accounts, {@code no} keeps
     * the others.
     */
    @Test
    void o1_list_filterBlocked() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers?blocked=yes")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("ZO Other Firm"))
                .body(not(containsString("ZO Base Co")));
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers?blocked=no")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("ZO Base Co"))
                .body(not(containsString("ZO Other Firm")));
    }

    /**
     * O1 (export): the CSV export is served with the CSV content type and the
     * dated filename.
     */
    @Test
    void o1_export_csv() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers/export")
                .then().log().ifValidationFails().statusCode(200)
                .contentType("text/csv; charset=UTF-8")
                .header("Content-Disposition", containsString("filename=\"clients-"))
                .header("Content-Disposition", containsString(".csv\""));
    }

    /**
     * O1 (access, non-ADMIN): an authenticated MANAGER is refused with 403.
     */
    @Test
    void o1_access_nonAdmin_forbidden() {
        given().cookie(FormLogin.COOKIE, FormLogin.cookie("zomgr", "zopass"))
                .when().get("/admin/customers")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * O1 (access, anonymous): an anonymous caller is redirected to the login
     * page by form authentication (the catalogue's dominant convention; the O
     * row says "302 login").
     */
    @Test
    void o1_access_anonymous_redirectsToLogin() {
        given().redirects().follow(false)
                .when().get("/admin/customers")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    // ================= O2 — file, new, add =================

    /**
     * O2 (edit, known id): the file of an existing account renders 200 with its
     * company name.
     */
    @Test
    void o2_edit_known_ok() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers/edit?id=" + idOf("ZOACC1"))
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("ZO Base Co"));
    }

    /**
     * O2 (edit, unknown id): an unknown id renders the page with a null
     * customer, 200 and no error.
     */
    @Test
    void o2_edit_unknown_ok() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers/edit?id=999999")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * O2 (new, allowed): with creation allowed, the empty file renders 200.
     */
    @Test
    void o2_new_allowed_ok() {
        given().cookie(FormLogin.COOKIE, adminCookie())
                .when().get("/admin/customers/new")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * O2 (new, blocked): with creation blocked by the setting, the form
     * redirects to the list with the refusal notice.
     */
    @Test
    void o2_new_blocked_redirect() {
        settings.store(CREATION_BLOCKED_KEY, "true");
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .when().get("/admin/customers/new").then().extract().response();
        assertNotice(r, 303, "/admin/customers",
                "Création de compte client interdite par le paramétrage.");
    }

    /**
     * O2 (add, blocked): posting a new account while creation is blocked
     * redirects to the list with the refusal notice.
     */
    @Test
    void o2_add_blocked_redirect() {
        settings.store(CREATION_BLOCKED_KEY, "true");
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("accountNumber", "ZOADD1").formParam("companyName", "ZO Add Co")
                .when().post("/admin/customers/new").then().extract().response();
        assertNotice(r, 303, "/admin/customers",
                "Création de compte client interdite par le paramétrage.");
    }

    /**
     * O2 (add, no account number): an empty account number is refused back to
     * the form.
     */
    @Test
    void o2_add_noAccountNumber() {
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("accountNumber", "").formParam("companyName", "ZO Add Co")
                .when().post("/admin/customers/new").then().extract().response();
        assertNotice(r, 303, "/admin/customers/new", "N° de compte obligatoire.");
    }

    /**
     * O2 (add, no company name): an empty company name is refused back to the
     * form.
     */
    @Test
    void o2_add_noCompanyName() {
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("accountNumber", "ZOADD2").formParam("companyName", "")
                .when().post("/admin/customers/new").then().extract().response();
        assertNotice(r, 303, "/admin/customers/new", "Raison sociale obligatoire.");
    }

    /**
     * O2 (add, duplicate account number): reusing an existing number is refused
     * naming it.
     */
    @Test
    void o2_add_duplicateAccountNumber() {
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("accountNumber", "ZOACC1").formParam("companyName", "ZO Dup Co")
                .when().post("/admin/customers/new").then().extract().response();
        assertNotice(r, 303, "/admin/customers/new", "N° de compte déjà attribué : ZOACC1");
    }

    /**
     * O2 (add, duplicate tax id): reusing an existing fiscal id is refused
     * naming it.
     */
    @Test
    void o2_add_duplicateTaxId() {
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("accountNumber", "ZOADD3").formParam("companyName", "ZO Dup Tax Co")
                .formParam("taxId", "ZOTAX1")
                .when().post("/admin/customers/new").then().extract().response();
        assertNotice(r, 303, "/admin/customers/new", "N° fiscal déjà enregistré : ZOTAX1");
    }

    /**
     * O2 (add, success): a valid new account is persisted with origin
     * BACK_OFFICE and redirects to its file with the success notice.
     */
    @Test
    void o2_add_success() {
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("accountNumber", "ZOADD9").formParam("companyName", "ZO New Co")
                .when().post("/admin/customers/new").then().extract().response();
        assertNotice(r, 303, "/admin/customers/edit?id=", "Compte créé.");
        String origin = QuarkusTransaction.requiringNew().call(
                () -> ((AccountCustomer) AccountCustomer.find("accountNumber", "ZOADD9")
                        .firstResult()).origin.name());
        Assertions.assertEquals("BACK_OFFICE", origin,
                "a back-office creation stamps origin BACK_OFFICE");
    }

    // ================= O3 — correction =================

    /**
     * O3 (save, unknown id): saving with an unreadable/unknown id redirects to
     * the list with "Client inconnu.".
     */
    @Test
    void o3_save_unknownId() {
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("id", "999999").formParam("companyName", "ZO X")
                .when().post("/admin/customers/edit").then().extract().response();
        assertNotice(r, 303, "/admin/customers", "Client inconnu.");
    }

    /**
     * O3 (save, no company name): an empty company name is refused back to the
     * file.
     */
    @Test
    void o3_save_noCompanyName() {
        Long id = idOf("ZOACC1");
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("id", id.toString()).formParam("companyName", "")
                .when().post("/admin/customers/edit").then().extract().response();
        assertNotice(r, 303, "/admin/customers/edit?id=" + id, "Raison sociale obligatoire.");
    }

    /**
     * O3 (save, success): a correction persists the company name but never reads
     * the account number nor the origin from the form (both kept).
     */
    @Test
    void o3_save_success_preservesAccountAndOrigin() {
        Long id = idOf("ZOACC1");
        Response r = given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("id", id.toString()).formParam("companyName", "ZO Renamed")
                .formParam("accountNumber", "SHOULD_BE_IGNORED")
                .formParam("origin", "INTEGRATED")
                .when().post("/admin/customers/edit").then().extract().response();
        assertNotice(r, 303, "/admin/customers/edit?id=" + id, "Fiche enregistrée.");
        AccountCustomer saved = QuarkusTransaction.requiringNew().call(
                () -> AccountCustomer.<AccountCustomer>findById(id));
        Assertions.assertEquals("ZO Renamed", saved.companyName, "company name is corrected");
        Assertions.assertEquals("ZOACC1", saved.accountNumber,
                "the account number is not read from the form");
        Assertions.assertEquals("REGISTER", saved.origin.name(),
                "the origin is not read from the form");
    }
}
