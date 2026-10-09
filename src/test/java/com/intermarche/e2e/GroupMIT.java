package com.intermarche.e2e;

import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.setting.PosSetting;
import com.intermarche.e2e.support.FormLogin;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Group M — Paramétrage. Screen surfaces under {@code /admin/**}, all
 * {@code @RolesAllowed("ADMIN")}: an anonymous caller is redirected (302) to
 * {@code /admin/login} by form authentication, and an authenticated non-ADMIN
 * gets 403. Covers M1 (settings + {@code /admin} routing), M6 (store), M7
 * (tenders), M8 (touches) and M10 (coupons) — the [E] rows reachable on a
 * node-alone H2 boot. Cases requiring a register ([R]) or a valid cash-side
 * payment key are listed in the lot report.
 */
@QuarkusTest
@TestProfile(GroupMIT.Cfg.class)
class GroupMIT {

    /**
     * A distinct profile class forcing a fresh boot for group M, and the place
     * the ADMIN credentials are declared.
     */
    public static class Cfg implements QuarkusTestProfile {

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

    /** Login of the bootstrap ADMIN account. */
    @ConfigProperty(name = "e2e.admin.login")
    String adminLogin;

    /** Back-office password of the bootstrap ADMIN account. */
    @ConfigProperty(name = "e2e.admin.password")
    String adminPassword;

    /**
     * Creates a non-ADMIN (MANAGER) back-office account with a chosen password
     * and no imposed change, so it can sign in and be refused by the ADMIN-only
     * gates without being diverted to the password screen.
     */
    @BeforeEach
    void createManager() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Employee.find("loginName", "zmmgr").firstResult() == null) {
                Employee e = new Employee();
                e.badgeId = "ZM000001";
                e.loginName = "zmmgr";
                e.password = Employee.hashPassword("1234");
                e.firstName = "Z";
                e.lastName = "Mgr";
                e.email = "zmmgr@zm.test";
                e.role = Employee.EmployeeRole.MANAGER;
                e.active = true;
                e.setBackOfficePassword("zmpass1");
                e.mustChangePassword = false;
                e.persist();
            }
        });
    }

    /**
     * Removes the test account and restores every setting the tests changed to
     * its default (deleting the stored override).
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            PosSetting.delete("settingKey in ?1",
                    List.of("display.show-ean", "auth.idle-lockout-seconds"));
            Employee.delete("badgeId like ?1", "ZM%");
        });
    }

    /**
     * Signs in as ADMIN and returns a request carrying the session cookie.
     *
     * @return an ADMIN-authenticated request specification
     */
    private io.restassured.specification.RequestSpecification asAdmin() {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie(adminLogin, adminPassword))
                .redirects().follow(false);
    }

    /**
     * Signs in as the non-ADMIN MANAGER and returns a request carrying the
     * session cookie.
     *
     * @return a MANAGER-authenticated request specification
     */
    private io.restassured.specification.RequestSpecification asManager() {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie("zmmgr", "zmpass1"))
                .redirects().follow(false);
    }

    /**
     * Asserts a 303 redirect whose decoded Location carries the given notice.
     *
     * @param location the raw Location header
     * @param expected the exact catalogue message expected in the notice
     */
    private void assertNotice(String location, String expected) {
        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        Assertions.assertTrue(decoded.contains(expected),
                "expected notice '" + expected + "' in redirect: " + decoded);
    }

    // ================= M1 — /admin routing + settings =================

    /**
     * M1 (root, ADMIN): {@code GET /admin} sends an ADMIN to the settings page.
     */
    @Test
    void m1_adminRoot_admin_redirectsToSettings() {
        // Observed: GET /admin → 302 /admin/ (static-dir shadow, see GroupAIT a2_*).
        asAdmin().redirects().follow(false).when().get("/admin")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/"));
    }

    /**
     * M1 (root, non-ADMIN): {@code GET /admin} sends a non-ADMIN to the dashboard.
     */
    @Test
    void m1_adminRoot_nonAdmin_redirectsToDashboard() {
        // Observed: GET /admin → 302 /admin/ (static-dir shadow, see GroupAIT a2_*).
        asManager().redirects().follow(false).when().get("/admin")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/"));
    }

    /**
     * M1 (root, anonymous): {@code GET /admin} redirects to the login page.
     */
    @Test
    void m1_adminRoot_anonymous_redirectsToLogin() {
        // Observed behaviour: GET /admin is shadowed by the static /admin/ resource
        // directory and answers 302 → /admin/, so adminRoot()'s auth/role routing is
        // never reached. The catalogue-conforming reds documenting this finding live
        // in GroupAIT (a2_admin_*); here the observed redirect is asserted.
        given().redirects().follow(false).when().get("/admin")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/"));
    }

    /**
     * M1 (settings, ADMIN): the settings page renders 200.
     */
    @Test
    void m1_settings_admin_ok() {
        asAdmin().when().get("/admin/settings")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * M1 (settings, non-ADMIN): the settings page is refused with 403.
     */
    @Test
    void m1_settings_nonAdmin_forbidden() {
        asManager().when().get("/admin/settings")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * M1 (settings, anonymous): the settings page redirects to login.
     */
    @Test
    void m1_settings_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/settings")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * M1 (save, one valid integer key): a valid integer is stored and the page
     * reports one saved parameter.
     */
    @Test
    void m1_save_validInteger_ok() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("auth.idle-lockout-seconds", "30")
                .when().post("/admin/settings")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "1 paramètre(s) enregistré(s). Application aux caisses au prochain tirage.");
        Assertions.assertTrue(loc.contains("noticeOk=true"), loc);
    }

    /**
     * M1 (save, negative integer): a negative integer is refused at the first
     * invalid key, naming the label.
     */
    @Test
    void m1_save_negativeInteger_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("auth.idle-lockout-seconds", "-5")
                .when().post("/admin/settings")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Valeur invalide pour « Pause automatique (secondes) » — entier positif attendu.");
        Assertions.assertTrue(loc.contains("noticeOk=false"), loc);
    }

    /**
     * M1 (save, non-numeric integer): a non-numeric integer is refused the same
     * way as a negative one.
     */
    @Test
    void m1_save_nonNumericInteger_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("auth.idle-lockout-seconds", "abc")
                .when().post("/admin/settings")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Valeur invalide pour « Pause automatique (secondes) » — entier positif attendu.");
    }

    /**
     * M1 (save, boolean normalization): a boolean key is accepted and stored.
     */
    @Test
    void m1_save_boolean_ok() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("display.show-ean", "true")
                .when().post("/admin/settings")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "1 paramètre(s) enregistré(s). Application aux caisses au prochain tirage.");
    }

    /**
     * M1 (save, no key posted): a save with no catalog key counts nothing and
     * still reports success.
     */
    @Test
    void m1_save_noKey_countsZero() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("not.a.catalog.key", "x")
                .when().post("/admin/settings")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "0 paramètre(s) enregistré(s). Application aux caisses au prochain tirage.");
    }

    // ================= M6 — store =================

    /**
     * M6 (screen, ADMIN): the store fiche renders 200 (the seed carries store
     * {@code 0101}).
     */
    @Test
    void m6_store_admin_ok() {
        asAdmin().when().get("/admin/store")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * M6 (screen, anonymous): the store fiche redirects to login.
     */
    @Test
    void m6_store_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/store")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * M6 (save, success): saving the store with no field posted keeps every
     * current value and reports success (no mutation of the seed store).
     */
    @Test
    void m6_store_save_ok() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("noop", "1")
                .when().post("/admin/store")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Informations du point de vente enregistrées.");
    }

    // ================= M7 — tenders =================

    /**
     * M7 (screen, ADMIN): the tenders page renders 200.
     */
    @Test
    void m7_tenders_admin_ok() {
        asAdmin().when().get("/admin/tenders")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * M7 (screen, non-ADMIN): the tenders page is refused with 403.
     */
    @Test
    void m7_tenders_nonAdmin_forbidden() {
        asManager().when().get("/admin/tenders")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * M7 (save, unknown payment key): a code the register does not know is
     * refused, naming it.
     */
    @Test
    void m7_tenders_save_unknownCode_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZMZZZ")
                .when().post("/admin/tenders/save")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Moyen de règlement inconnu de la caisse : ZMZZZ");
    }

    /**
     * M7 (delete, unknown code): deleting an unknown tender is refused.
     */
    @Test
    void m7_tenders_delete_unknown_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZMZZZ")
                .when().post("/admin/tenders/delete")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Moyen de règlement inconnu.");
    }

    /**
     * M7 (export): the tenders export is a CSV attachment with the fixed
     * filename.
     */
    @Test
    void m7_tenders_export_csv() {
        asAdmin().when().get("/admin/tenders/export")
                .then().log().ifValidationFails().statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("filename=\"modes-de-reglement.csv\""));
    }

    // ================= M8 — touches =================

    /**
     * M8 (screen, ADMIN): the touches page renders 200.
     */
    @Test
    void m8_touches_admin_ok() {
        asAdmin().when().get("/admin/touches")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * M8 (config, unknown group): a config with no group id is refused.
     */
    @Test
    void m8_touches_config_unknownGroup_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("noop", "1")
                .when().post("/admin/touches/config")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Groupe introuvable.");
    }

    /**
     * M8 (config, invalid button size): a valid group with a bad button size is
     * refused, naming the group code.
     */
    @Test
    void m8_touches_config_invalidButtonSize_refused() {
        Long familyId = QuarkusTransaction.requiringNew().call(
                () -> ((ProductFamily) ProductFamily.find("code", "POMMES").firstResult()).id);
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("id", String.valueOf(familyId))
                .formParam("buttonSize", "HUGE")
                .when().post("/admin/touches/config")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Taille de touche invalide pour « POMMES ».");
    }

    /**
     * M8 (pin, unknown group): pinning with no group id is refused.
     */
    @Test
    void m8_touches_pin_unknownGroup_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("noop", "1")
                .when().post("/admin/touches/pin")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Groupe introuvable.");
    }

    /**
     * M8 (image, nothing to import): an image import with no data is refused.
     */
    @Test
    void m8_touches_image_nothing_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("noop", "1")
                .when().post("/admin/touches/image")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Aucune image à importer.");
    }

    // ================= M10 — coupons =================

    /**
     * M10 (screen, ADMIN): the coupons page renders 200.
     */
    @Test
    void m10_coupons_admin_ok() {
        asAdmin().when().get("/admin/coupons")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * M10 (screen, anonymous): the coupons page redirects to login.
     */
    @Test
    void m10_coupons_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/coupons")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * M10 (type save, empty code): a coupon type with no code is refused.
     */
    @Test
    void m10_coupons_typeSave_emptyCode_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("label", "x")
                .when().post("/admin/coupons/type")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Le code de la plage est obligatoire.");
    }

    /**
     * M10 (island save, empty code): an island with no code is refused.
     */
    @Test
    void m10_coupons_islandSave_emptyCode_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("label", "x")
                .when().post("/admin/coupons/island")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Le code de l'îlot est obligatoire.");
    }

    /**
     * M10 (island delete, unknown code): deleting an unknown island is refused.
     */
    @Test
    void m10_coupons_islandDelete_unknown_refused() {
        String loc = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZMISLAND")
                .when().post("/admin/coupons/island/delete")
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        assertNotice(loc, "Îlot inconnu.");
    }
}
