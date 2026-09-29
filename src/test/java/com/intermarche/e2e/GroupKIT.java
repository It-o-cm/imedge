package com.intermarche.e2e;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.e2e.support.FormLogin;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Group K — transactional journal and dashboard, exercised end to end against a
 * booted store node (H2, no register). Covers the [E] rows of K1/K1b/K1c
 * (journal tabs), K2 (ticket detail), K3 (CSV export), K4/K8 (dashboard data,
 * incl. the fresh-node zero state) and K5 (dashboard page).
 *
 * <p>The journal is open to ADMIN and MANAGER; {@code /dashboard} and
 * {@code /dashboard-data} to MANAGER/ADMIN. A MANAGER seeded with
 * {@code mustChangePassword=false} is created here (the seeded MANAGER carries a
 * forced change, which the password-change filter would divert). The register-
 * dependent rows ([R]: a rendered ticket detail, non-empty dashboard figures,
 * ack/force-close of live calls/sessions) are deferred; K7 (supervisor call) is
 * already covered by GroupBIT and SyncRoleGuardIT.
 */
@QuarkusTest
@TestProfile(GroupKIT.Cfg.class)
class GroupKIT {

    /** Declares ADMIN credentials; a distinct profile forces a fresh boot. */
    public static class Cfg implements QuarkusTestProfile {
        /**
         * Exposes the seeded ADMIN credentials.
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

    /** Password of the created ZK accounts. */
    private static final String ZK_PASSWORD = "zkpass12";

    /**
     * Creates a MANAGER and a CASHIER (both mustChangePassword=false) for the
     * role-matrix rows.
     */
    @BeforeEach
    void seedAccounts() {
        QuarkusTransaction.requiringNew().run(() -> {
            createAccount("ZKMGR001", "zkmgr", Employee.EmployeeRole.MANAGER);
            createAccount("ZKCASH01", "zkcash", Employee.EmployeeRole.CASHIER);
        });
    }

    /**
     * Creates one active back-office account that does not force a change.
     *
     * @param badge the badge id
     * @param login the login name
     * @param role the role
     */
    private void createAccount(String badge, String login, Employee.EmployeeRole role) {
        Employee e = new Employee();
        e.badgeId = badge;
        e.loginName = login;
        e.password = Employee.hashPassword("1234");
        e.firstName = "Z";
        e.lastName = "K";
        e.email = login + "@zk.test";
        e.role = role;
        e.active = true;
        e.setBackOfficePassword(ZK_PASSWORD);
        e.mustChangePassword = false;
        e.persist();
    }

    /**
     * Removes the ZK accounts.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> Employee.delete("badgeId like ?1", "ZK%"));
    }

    /**
     * A cookie request following no redirect, for the given account.
     *
     * @param login the login name
     * @param password the back-office password
     * @return the request specification
     */
    private RequestSpecification as(String login, String password) {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie(login, password))
                .redirects().follow(false);
    }

    /**
     * An ADMIN cookie request following no redirect.
     *
     * @return the request specification
     */
    private RequestSpecification asAdmin() {
        return as(adminLogin, adminPassword);
    }

    // ================= K1 — transactional journal =================

    /**
     * K1 (access): anonymous is redirected to login.
     */
    @Test
    void k1_journal_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/journal")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * K1 (access): a MANAGER may read the journal.
     */
    @Test
    void k1_journal_manager_ok() {
        as("zkmgr", ZK_PASSWORD).when().get("/admin/journal")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * K1 (access): an ADMIN may read the journal.
     */
    @Test
    void k1_journal_admin_ok() {
        asAdmin().when().get("/admin/journal")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * K1 (access): a CASHIER is refused the journal.
     */
    @Test
    void k1_journal_cashier_forbidden() {
        as("zkcash", ZK_PASSWORD).when().get("/admin/journal")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * K1 (text filter, no match): a filter matching nothing renders a clean
     * empty page.
     */
    @Test
    void k1_journal_textFilterNoMatch_ok() {
        asAdmin().when().get("/admin/journal?text=ZZZ-NO-MATCH")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * K1b (functional tab): the functional journal renders for ADMIN.
     */
    @Test
    void k1b_functional_ok() {
        asAdmin().when().get("/admin/journal/functional")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * K1b (access): anonymous is redirected to login.
     */
    @Test
    void k1b_functional_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/journal/functional")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * K1c (movements tab): the movements journal renders for ADMIN.
     */
    @Test
    void k1c_movements_ok() {
        asAdmin().when().get("/admin/journal/movements")
                .then().log().ifValidationFails().statusCode(200);
    }

    // ================= K2 — ticket detail =================

    /**
     * K2 (unknown id): an unknown ticket id renders the page with a null ticket
     * (never a 500).
     */
    @Test
    void k2_ticketDetail_unknown_ok() {
        asAdmin().when().get("/admin/journal/ticket/999999")
                .then().log().ifValidationFails().statusCode(200);
    }

    // ================= K3 — CSV export =================

    /**
     * K3 (export): the journal export is a CSV attachment named journal.csv with
     * the expected header line.
     */
    @Test
    void k3_export_csv() {
        asAdmin().when().get("/admin/journal/export")
                .then().log().ifValidationFails().statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("filename=\"journal.csv\""))
                .body(containsString("TPV;Transaction;Caissiere;Date;Heure;Montant;Articles"));
    }

    // ================= K4 / K8 — dashboard data =================

    /**
     * K4 (access): an anonymous caller is refused. The catalogue says 401; the
     * {@code /dashboard-data} path is form-authenticated, so the anonymous
     * challenge may be a 302 redirect instead — this asserts "refused" (302 or
     * 401) and the exact code is noted in the lot report.
     */
    @Test
    void k4_dashboardData_anonymous_refused() {
        given().redirects().follow(false).when().get("/dashboard-data")
                .then().log().ifValidationFails()
                .statusCode(anyOf(equalTo(401), equalTo(302)));
    }

    /**
     * K4 (access): a MANAGER gets the dashboard JSON.
     */
    @Test
    void k4_dashboardData_manager_ok() {
        as("zkmgr", ZK_PASSWORD).when().get("/dashboard-data")
                .then().log().ifValidationFails().statusCode(200)
                .contentType(containsString("json"));
    }

    /**
     * K4 (access): a CASHIER is refused the dashboard JSON.
     */
    @Test
    void k4_dashboardData_cashier_forbidden() {
        as("zkcash", ZK_PASSWORD).when().get("/dashboard-data")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * K8 (zero state): on a fresh node with no sales, the dashboard reports zero
     * revenue and zero tickets without any division-by-zero error.
     */
    @Test
    void k8_dashboardData_freshNode_zeroState() {
        asAdmin().when().get("/dashboard-data")
                .then().log().ifValidationFails().statusCode(200)
                .body("ticketCount", is(0))
                .body("revenue", is("0,00"))
                .body("averageBasket", is("0,00"));
    }

    // ================= K5 — dashboard page =================

    /**
     * K5 (page): the dashboard page renders for ADMIN.
     */
    @Test
    void k5_dashboard_admin_ok() {
        asAdmin().when().get("/dashboard")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * K5 (page): anonymous is redirected to login.
     */
    @Test
    void k5_dashboard_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/dashboard")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }
}
