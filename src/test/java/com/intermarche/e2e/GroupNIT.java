package com.intermarche.e2e;

import com.intermarche.pos.domain.campaign.Campaign;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.e2e.support.FormLogin;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Group N — Campagnes et offres ({@code /admin/campaigns}), the [E] scenarios of
 * catalogue sections N1–N4 plus the node-observable part of N5.
 *
 * <p>{@code AdminCampaignResource} carries {@code @RolesAllowed("ADMIN")}
 * throughout and follows the admin POST → 303 → one-shot-notice cycle; every
 * write reprojects the {@code BUDGET_GROUPS} engine feed. The screens use form
 * authentication (see {@link FormLogin}); the ADMIN account reaches them.
 *
 * <p>The engine consumption read ({@code BudgetStatusClient}) tolerates an
 * unreachable engine (returns "unavailable"), so the list and fiche render 200
 * with no engine running. The [V] delivery of {@code BUDGET_GROUPS} to the
 * engine and the exact projected content (header/lines/order, N5) are NOT
 * observable through an [E] HTTP surface — the status endpoint exposes only the
 * feed version, not its content — so only the re-projection (a version change on
 * save) is asserted here; the rest of N5 is deferred to the engine lot.
 */
@QuarkusTest
@TestProfile(GroupNIT.Profile.class)
class GroupNIT {

    /** A profile of this class's own, forcing a fresh boot and H2 base. */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Declares the ADMIN credentials as configuration.
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

    /**
     * Seeds a non-ADMIN (MANAGER) back-office account with a chosen password
     * (mustChangePassword=false), so the ADMIN-only guard can be exercised.
     */
    @BeforeEach
    void seedManager() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Employee.find("loginName", "znmgr").firstResult() == null) {
                Employee e = new Employee();
                e.badgeId = "ZN000001";
                e.loginName = "znmgr";
                e.password = Employee.hashPassword("1234");
                e.firstName = "Z";
                e.lastName = "Mgr";
                e.email = "znmgr@zn.test";
                e.role = Employee.EmployeeRole.MANAGER;
                e.active = true;
                e.setBackOfficePassword("znpass1");
                e.mustChangePassword = false;
                e.persist();
            }
        });
    }

    /**
     * Removes the campaigns and the manager the tests create (seed data kept).
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            Campaign.delete("code like ?1", "ZN%");
            Employee.delete("badgeId like ?1", "ZN%");
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Signs in as ADMIN and returns the session cookie.
     *
     * @return the ADMIN session cookie
     */
    private String adminCookie() {
        return FormLogin.cookie(adminLogin, adminPassword);
    }

    /**
     * Persists a valid campaign directly, as a fixture for the edit/offer/
     * duplicate tests (bypassing the screen validation, which those tests
     * exercise separately).
     *
     * @param code the campaign code
     */
    private void persistCampaign(String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            Campaign c = new Campaign();
            c.code = code;
            c.label = "Campagne " + code;
            c.validFrom = LocalDate.of(2026, 1, 1);
            c.validTo = LocalDate.of(2026, 12, 31);
            c.maxAmount = new BigDecimal("1000.00");
            c.maxApplications = 100;
            c.perStore = false;
            c.offerCodes = new ArrayList<>();
            c.persist();
        });
    }

    /**
     * Reads the stored version of the {@code BUDGET_GROUPS} feed through the
     * ADMIN status surface, or null when the feed is absent.
     *
     * @return the BUDGET_GROUPS version, or null
     */
    private String budgetGroupsVersion() {
        return given().auth().preemptive().basic(adminLogin, adminPassword)
                .when().get("/feeds/import/status")
                .then().statusCode(200).extract()
                .path("find { it.code == 'BUDGET_GROUPS' }.version");
    }

    /**
     * URL-decodes the {@code Location} header of a redirect response.
     *
     * @param response the redirect response
     * @return the decoded Location
     */
    private String decodedLocation(Response response) {
        return URLDecoder.decode(response.getHeader("Location"), StandardCharsets.UTF_8);
    }

    /**
     * Posts a form to a campaign route as ADMIN, without following redirects.
     *
     * @param path the target path
     * @param form the form fields (alternating key, value)
     * @return the redirect response
     */
    private Response postForm(String path, String... form) {
        io.restassured.specification.RequestSpecification req = given()
                .cookie(FormLogin.COOKIE, adminCookie())
                .contentType("application/x-www-form-urlencoded")
                .redirects().follow(false);
        for (int i = 0; i < form.length; i += 2) {
            req = req.formParam(form[i], form[i + 1]);
        }
        return req.when().post(path);
    }

    // ==================================================================
    // N1 — list and fiche
    // ==================================================================

    /**
     * N1 (list, ADMIN): the campaign list renders 200 with the seeded campaign,
     * live consumption folded in even with no engine running.
     */
    @Test
    void n1_list_admin_ok() {
        given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .when().get("/admin/campaigns")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("CAMP_ETE"));
    }

    /**
     * N1 (fiche, known code): the fiche of a known campaign renders 200.
     */
    @Test
    void n1_fiche_knownCode_ok() {
        given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .when().get("/admin/campaigns/fiche?code=CAMP_ETE")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("CAMP_ETE"));
    }

    /**
     * N1 (fiche, unknown code): an unknown code renders the page with a null
     * campaign, still 200.
     */
    @Test
    void n1_fiche_unknownCode_ok() {
        given().cookie(FormLogin.COOKIE, adminCookie()).redirects().follow(false)
                .when().get("/admin/campaigns/fiche?code=ZNNOPE")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * N1 (access, anonymous): an anonymous caller is redirected to the login.
     */
    @Test
    void n1_access_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/campaigns")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * N1 (access, non-ADMIN): an authenticated MANAGER is refused with 403.
     */
    @Test
    void n1_access_nonAdmin_forbidden() {
        given().cookie(FormLogin.COOKIE, FormLogin.cookie("znmgr", "znpass1"))
                .redirects().follow(false)
                .when().get("/admin/campaigns")
                .then().log().ifValidationFails().statusCode(403);
    }

    // ==================================================================
    // N2 — creation
    // ==================================================================

    /**
     * N2 (create, duplicate code): creating with an existing code is refused with
     * the taken-code notice.
     */
    @Test
    void n2_create_duplicateCode() {
        Response r = postForm("/admin/campaigns/create",
                "code", "CAMP_ETE", "label", "X",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "100", "maxApplications", "10");
        r.then().log().ifValidationFails().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Une campagne porte déjà le code CAMP_ETE."), loc);
        Assertions.assertTrue(loc.contains("noticeOk=false"), loc);
    }

    /**
     * N2 (create, invalid period): validFrom not before validTo is refused with
     * "Campagne refusée : period.invalid".
     */
    @Test
    void n2_create_periodInvalid() {
        Response r = postForm("/admin/campaigns/create",
                "code", "ZNCAMP1", "label", "X",
                "validFrom", "2026-09-01", "validTo", "2026-06-01",
                "maxAmount", "100", "maxApplications", "10");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne refusée : period.invalid"), loc);
        Assertions.assertTrue(loc.contains("noticeOk=false"), loc);
    }

    /**
     * N2 (create, invalid amount): a ceiling amount ≤ 0 is refused with
     * "Campagne refusée : amount.invalid".
     */
    @Test
    void n2_create_amountInvalid() {
        Response r = postForm("/admin/campaigns/create",
                "code", "ZNCAMP1", "label", "X",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "0", "maxApplications", "10");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne refusée : amount.invalid"), loc);
    }

    /**
     * N2 (create, invalid applications): a ceiling of applications ≤ 0 is refused
     * with "Campagne refusée : applications.invalid".
     */
    @Test
    void n2_create_applicationsInvalid() {
        Response r = postForm("/admin/campaigns/create",
                "code", "ZNCAMP1", "label", "X",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "100", "maxApplications", "0");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne refusée : applications.invalid"), loc);
    }

    /**
     * N2 (create, success): a valid campaign is created, redirecting to the list
     * with the success notice.
     */
    @Test
    void n2_create_success() {
        Response r = postForm("/admin/campaigns/create",
                "code", "ZNCAMP2", "label", "Test",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "1000", "maxApplications", "100");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne ZNCAMP2 créée."), loc);
        Assertions.assertTrue(loc.contains("noticeOk=true"), loc);
        Assertions.assertTrue(loc.contains("/admin/campaigns?"), loc);
    }

    // ==================================================================
    // N3 — edit and offers
    // ==================================================================

    /**
     * N3 (save, unknown): saving an unknown campaign redirects to the list with
     * "Campagne introuvable.".
     */
    @Test
    void n3_save_unknown() {
        Response r = postForm("/admin/campaigns/save",
                "code", "ZNNOPE", "label", "X",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "100", "maxApplications", "10");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne introuvable."), loc);
        Assertions.assertTrue(loc.contains("noticeOk=false"), loc);
    }

    /**
     * N3 (save, validation fails): an invalid edit redirects to the fiche with
     * the validation notice.
     */
    @Test
    void n3_save_validationFails() {
        persistCampaign("ZNCAMP2");
        Response r = postForm("/admin/campaigns/save",
                "code", "ZNCAMP2", "label", "X",
                "validFrom", "2026-09-01", "validTo", "2026-06-01",
                "maxAmount", "100", "maxApplications", "10");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne refusée : period.invalid"), loc);
        Assertions.assertTrue(loc.contains("/admin/campaigns/fiche?code=ZNCAMP2"), loc);
    }

    /**
     * N3 (save, success): a valid edit redirects to the fiche with the success
     * notice.
     */
    @Test
    void n3_save_success() {
        persistCampaign("ZNCAMP2");
        Response r = postForm("/admin/campaigns/save",
                "code", "ZNCAMP2", "label", "Edited",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "500", "maxApplications", "50");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne ZNCAMP2 enregistrée."), loc);
        Assertions.assertTrue(loc.contains("noticeOk=true"), loc);
    }

    /**
     * N3 (offers, unknown campaign): associating an offer on an unknown campaign
     * redirects to the list with "Campagne introuvable.".
     */
    @Test
    void n3_offers_unknownCampaign() {
        Response r = postForm("/admin/campaigns/offers",
                "code", "ZNNOPE", "offerCode", "ZNOFFER", "action", "add");
        r.then().statusCode(303);
        Assertions.assertTrue(decodedLocation(r).contains("Campagne introuvable."), decodedLocation(r));
    }

    /**
     * N3 (offers, null offer): omitting the offer code redirects to the fiche with
     * "Aucune offre indiquée.".
     */
    @Test
    void n3_offers_nullOffer() {
        persistCampaign("ZNCAMP2");
        Response r = postForm("/admin/campaigns/offers", "code", "ZNCAMP2", "action", "add");
        r.then().statusCode(303);
        Assertions.assertTrue(decodedLocation(r).contains("Aucune offre indiquée."), decodedLocation(r));
    }

    /**
     * N3 (offers, claimed elsewhere): adding an offer already attached to another
     * campaign is refused (the seeded CAMP_ETE claims PROMO_BANANES_15).
     */
    @Test
    void n3_offers_claimedElsewhere() {
        persistCampaign("ZNCAMP2");
        Response r = postForm("/admin/campaigns/offers",
                "code", "ZNCAMP2", "offerCode", "PROMO_BANANES_15", "action", "add");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains(
                "L'offre PROMO_BANANES_15 est déjà rattachée à une autre campagne."), loc);
        Assertions.assertTrue(loc.contains("noticeOk=false"), loc);
    }

    /**
     * N3 (offers, add then remove): a free offer is added and then removed, each
     * confirmed with "Offres de <code> mises à jour.".
     */
    @Test
    void n3_offers_addThenRemove() {
        persistCampaign("ZNCAMP2");
        Response added = postForm("/admin/campaigns/offers",
                "code", "ZNCAMP2", "offerCode", "ZNOFFER", "action", "add");
        added.then().statusCode(303);
        Assertions.assertTrue(decodedLocation(added).contains("Offres de ZNCAMP2 mises à jour."),
                decodedLocation(added));
        Response removed = postForm("/admin/campaigns/offers",
                "code", "ZNCAMP2", "offerCode", "ZNOFFER", "action", "remove");
        removed.then().statusCode(303);
        Assertions.assertTrue(decodedLocation(removed).contains("Offres de ZNCAMP2 mises à jour."),
                decodedLocation(removed));
    }

    // ==================================================================
    // N4 — duplication
    // ==================================================================

    /**
     * N4 (duplicate, no new code): omitting the new code is refused with
     * "Le nouveau code est obligatoire.".
     */
    @Test
    void n4_duplicate_newCodeNull() {
        persistCampaign("ZNCAMP2");
        Response r = postForm("/admin/campaigns/duplicate", "code", "ZNCAMP2");
        r.then().statusCode(303);
        Assertions.assertTrue(decodedLocation(r).contains("Le nouveau code est obligatoire."),
                decodedLocation(r));
    }

    /**
     * N4 (duplicate, new code taken): a new code already in use is refused.
     */
    @Test
    void n4_duplicate_newCodeTaken() {
        persistCampaign("ZNCAMP2");
        Response r = postForm("/admin/campaigns/duplicate",
                "code", "ZNCAMP2", "newCode", "CAMP_ETE");
        r.then().statusCode(303);
        Assertions.assertTrue(decodedLocation(r).contains("Une campagne porte déjà le code CAMP_ETE."),
                decodedLocation(r));
    }

    /**
     * N4 (duplicate, source unknown): duplicating an unknown source is refused
     * with "Campagne refusée : source.unknown".
     */
    @Test
    void n4_duplicate_sourceUnknown() {
        Response r = postForm("/admin/campaigns/duplicate",
                "code", "ZNNOPE", "newCode", "ZNDUP1");
        r.then().statusCode(303);
        Assertions.assertTrue(decodedLocation(r).contains("Campagne refusée : source.unknown"),
                decodedLocation(r));
    }

    /**
     * N4 (duplicate, success): a valid duplication copies the source and
     * redirects to the copy's fiche with the duplication notice.
     */
    @Test
    void n4_duplicate_success() {
        persistCampaign("ZNCAMP2");
        Response r = postForm("/admin/campaigns/duplicate",
                "code", "ZNCAMP2", "newCode", "ZNDUP2", "withOffers", "on");
        r.then().statusCode(303);
        String loc = decodedLocation(r);
        Assertions.assertTrue(loc.contains("Campagne ZNDUP2 créée par duplication."), loc);
        Assertions.assertTrue(loc.contains("/admin/campaigns/fiche?code=ZNDUP2"), loc);
        Assertions.assertTrue(loc.contains("noticeOk=true"), loc);
    }

    // ==================================================================
    // N5 — feed projection (node-observable part only)
    // ==================================================================

    /**
     * N5 (projection on save): every save reprojects the BUDGET_GROUPS feed, so a
     * save that changes the projected content changes the stored feed version.
     * The exact projected content (header/lines/order) and the [V] delivery to
     * the engine are not observable through an [E] surface and are deferred.
     */
    @Test
    void n5_projection_reprojectsOnSave() {
        postForm("/admin/campaigns/create",
                "code", "ZNCAMP2", "label", "Test",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "1000", "maxApplications", "100").then().statusCode(303);
        String before = budgetGroupsVersion();
        postForm("/admin/campaigns/save",
                "code", "ZNCAMP2", "label", "Test",
                "validFrom", "2026-01-01", "validTo", "2026-12-31",
                "maxAmount", "777", "maxApplications", "100").then().statusCode(303);
        String after = budgetGroupsVersion();
        Assertions.assertNotEquals(before, after,
                "changing a campaign's ceiling must reproject BUDGET_GROUPS to a new version");
    }
}
