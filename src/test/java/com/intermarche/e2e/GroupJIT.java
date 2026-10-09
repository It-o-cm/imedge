package com.intermarche.e2e;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.e2e.support.FormLogin;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Group J — the back-office REPORTS surface ({@code /admin/reports}), its
 * catalogue, the eleven reports, the common report screen and the three-format
 * export, exercised end to end against a booted store node (H2, no register).
 *
 * <p>All [E]. These are SCREEN surfaces: they carry {@code @RolesAllowed}
 * ({"ADMIN","MANAGER"}) and use Quarkus FORM authentication, not HTTP Basic —
 * a request signs in through {@link FormLogin} and carries the resulting
 * {@code quarkus-credential} cookie. The seeded ADMIN {@code admin}/{@code admin}
 * has {@code mustChangePassword=false}, so it reaches the screens; ADMIN implies
 * MANAGER, so it covers the happy path for these ADMIN-or-MANAGER reports.
 *
 * <p>The "insufficient role → 403" case needs an authenticated back-office user
 * whose role is below MANAGER; none is seeded, so the test creates a CASHIER
 * with a back-office password through the ADMIN-only CSV door first.
 */
@QuarkusTest
@TestProfile(GroupJIT.Profile.class)
class GroupJIT {

    /** The eleven report codes, exactly as the catalogue J2 lists them. */
    private static final String[] REPORT_CODES = {
            "family-sales", "hourly-sales", "hourly-productivity", "cashiers",
            "cancellations", "price-forcings", "reason-codes", "refunds",
            "pending-tickets", "settlements", "vat-by-family"};

    /**
     * Declares the ADMIN credentials as configuration. A distinct profile class
     * forces a fresh boot and a fresh H2 base for group J.
     */
    public static class Profile implements QuarkusTestProfile {

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

    /** Login of the bootstrap ADMIN account. */
    @ConfigProperty(name = "e2e.admin.login")
    String adminLogin;

    /** Back-office password of the bootstrap ADMIN account. */
    @ConfigProperty(name = "e2e.admin.password")
    String adminPassword;

    /**
     * Removes the CASHIER created for the insufficient-role case; reports write
     * nothing else.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> Employee.delete("badgeId like ?1", "ZJ%"));
    }

    /**
     * Builds a request already carrying the ADMIN form-login cookie.
     *
     * @return the authenticated request specification
     */
    private RequestSpecification asAdmin() {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie(adminLogin, adminPassword));
    }

    // ==================================================================
    // J1 — report catalogue
    // ==================================================================

    /**
     * J1 (GET /): the catalogue lists every report grouped by its non-empty
     * family; the eleven report codes are all present.
     */
    @Test
    void j1_catalogue_listsElevenReportsGroupedByFamily() {
        io.restassured.response.Response r = asAdmin().when().get("/admin/reports")
                .then().log().ifValidationFails().statusCode(200).extract().response();
        String html = r.asString();
        for (String code : REPORT_CODES) {
            org.junit.jupiter.api.Assertions.assertTrue(html.contains(code),
                    "the catalogue must list report '" + code + "'; body was: " + html);
        }
    }

    /**
     * J1 (families shown): only the non-empty families are shown (Ventes, États
     * caissières, Règlements, TVA); the empty CUSTOMERS family ("Clients") never
     * appears.
     */
    @Test
    void j1_catalogue_showsOnlyNonEmptyFamilies() {
        asAdmin().when().get("/admin/reports")
                .then().log().ifValidationFails().statusCode(200)
                .body(containsString("Ventes"))
                .body(containsString("États caissières"))
                .body(containsString("Règlements"))
                .body(containsString("TVA"));
        // The CUSTOMERS family ("Clients") has no report and is not shown as a
        // report family; the word is not asserted absent because it also appears
        // in the back-office navigation, unrelated to the reports grouping.
    }

    /**
     * J1 (anonymous): an anonymous caller on the reports screen is refused. The
     * catalogue's J rows say "401", but that contradicts group A's own rows
     * (A2/A6–A9, all "302 /admin/login") and the S-E convention note: every
     * {@code /admin/**} screen relies on form authentication, whose anonymous
     * challenge is a 302 redirect to {@code /admin/login}. The dominant catalogue
     * convention (302) is asserted; the localized "401" wording is flagged in the
     * lot report.
     */
    @Test
    void j1_catalogue_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/reports")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", org.hamcrest.Matchers.containsString("/admin/login"));
    }

    /**
     * J1 (insufficient role): a signed-in CASHIER (created for the test through
     * the ADMIN-only CSV door) is refused the ADMIN-or-MANAGER catalogue with
     * 403.
     */
    @Test
    void j1_catalogue_insufficientRole_forbidden() {
        given().auth().preemptive().basic(adminLogin, adminPassword).contentType(ContentType.TEXT)
                .body("BADGE_ID|LOGIN|PIN|FIRST_NAME|LAST_NAME|EMAIL|ROLE|BACK_OFFICE_PASSWORD\n"
                        + "ZJ0001|zjcashier|1234|C|A|zjcashier@zj.test|CASHIER|zjpass1")
                .when().post("/feeds/import/EMPLOYEES")
                .then().statusCode(200);
        given().cookie(FormLogin.COOKIE, FormLogin.cookie("zjcashier", "zjpass1"))
                .redirects().follow(false)
                .when().get("/admin/reports")
                .then().log().ifValidationFails().statusCode(403);
    }

    // ==================================================================
    // J2 — the eleven reports each render
    // ==================================================================

    /**
     * J2 (family-sales): the report screen renders 200.
     */
    @Test
    void j2_familySales_renders() {
        asAdmin().when().get("/admin/reports/family-sales").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (hourly-sales): the report screen renders 200.
     */
    @Test
    void j2_hourlySales_renders() {
        asAdmin().when().get("/admin/reports/hourly-sales").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (hourly-productivity): the report screen renders 200.
     */
    @Test
    void j2_hourlyProductivity_renders() {
        asAdmin().when().get("/admin/reports/hourly-productivity").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (cashiers): the report screen renders 200.
     */
    @Test
    void j2_cashiers_renders() {
        asAdmin().when().get("/admin/reports/cashiers").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (cancellations): the report screen renders 200.
     */
    @Test
    void j2_cancellations_renders() {
        asAdmin().when().get("/admin/reports/cancellations").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (price-forcings): the report screen renders 200.
     */
    @Test
    void j2_priceForcings_renders() {
        asAdmin().when().get("/admin/reports/price-forcings").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (reason-codes): the report screen renders 200.
     */
    @Test
    void j2_reasonCodes_renders() {
        asAdmin().when().get("/admin/reports/reason-codes").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (refunds): the report screen renders 200.
     */
    @Test
    void j2_refunds_renders() {
        asAdmin().when().get("/admin/reports/refunds").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (pending-tickets): the report screen renders 200.
     */
    @Test
    void j2_pendingTickets_renders() {
        asAdmin().when().get("/admin/reports/pending-tickets").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (settlements): the report screen renders 200.
     */
    @Test
    void j2_settlements_renders() {
        asAdmin().when().get("/admin/reports/settlements").then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J2 (vat-by-family): the report screen renders 200.
     */
    @Test
    void j2_vatByFamily_renders() {
        asAdmin().when().get("/admin/reports/vat-by-family").then().log().ifValidationFails().statusCode(200);
    }

    // ==================================================================
    // J3 — common report screen mechanics
    // ==================================================================

    /**
     * J3 (unknown code): an unknown report code answers 404 naming it.
     */
    @Test
    void j3_unknownCode_notFound() {
        // DISCREPANCY (left RED): the catalogue states the 404 body carries
        // "Rapport inconnu : <code>", but ReportResource throws
        // NotFoundException(message) (line 183) and Quarkus returns 404 with an
        // EMPTY body, so the message is not observable in the response. See the
        // lot report.
        asAdmin().when().get("/admin/reports/nope")
                .then().log().ifValidationFails().statusCode(404)
                .body(containsString("Rapport inconnu : nope"));
    }

    /**
     * J3 (period TODAY): the current-day period renders 200.
     */
    @Test
    void j3_periodToday_renders() {
        asAdmin().when().get("/admin/reports/family-sales?period=TODAY")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (period WEEK): the week period renders 200.
     */
    @Test
    void j3_periodWeek_renders() {
        asAdmin().when().get("/admin/reports/family-sales?period=WEEK")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (period MONTH): the month period renders 200.
     */
    @Test
    void j3_periodMonth_renders() {
        asAdmin().when().get("/admin/reports/family-sales?period=MONTH")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (period YEAR): the year period renders 200.
     */
    @Test
    void j3_periodYear_renders() {
        asAdmin().when().get("/admin/reports/family-sales?period=YEAR")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (period RANGE): a valid from/to range renders 200.
     */
    @Test
    void j3_periodRange_renders() {
        asAdmin().when().get("/admin/reports/family-sales?period=RANGE&from=2026-01-01&to=2026-01-31")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (period unknown): an unknown period value falls back to TODAY without
     * error (200).
     */
    @Test
    void j3_periodUnknown_fallsBackNoError() {
        asAdmin().when().get("/admin/reports/family-sales?period=WHATEVER")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (unreadable date): an unparseable reference/from date is read as absent,
     * not refused (200).
     */
    @Test
    void j3_unreadableDate_noError() {
        asAdmin().when().get("/admin/reports/family-sales?period=RANGE&from=abc&to=def")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (inverted range): a from later than to yields an empty report, never a
     * 500.
     */
    @Test
    void j3_invertedRange_emptyNo500() {
        asAdmin().when().get("/admin/reports/family-sales?period=RANGE&from=2026-12-31&to=2026-01-01")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (page bounding): page 0 is clamped to the first page (200).
     */
    @Test
    void j3_pageZero_clampedToFirst() {
        asAdmin().when().get("/admin/reports/family-sales?page=0")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (page bounding): a page far past the end is clamped to the last (200).
     */
    @Test
    void j3_pageBeyondEnd_clampedToLast() {
        asAdmin().when().get("/admin/reports/family-sales?page=9999")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (option, hourly-productivity slice width): a valid slice width renders
     * 200.
     */
    @Test
    void j3_hourlyProductivityOption_applied() {
        asAdmin().when().get("/admin/reports/hourly-productivity?option=30")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (option, hourly-productivity invalid width): an invalid slice width
     * falls back to the default (200).
     */
    @Test
    void j3_hourlyProductivityOptionInvalid_fallsBack() {
        asAdmin().when().get("/admin/reports/hourly-productivity?option=999")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (option, price-forcings direction): a valid direction renders 200.
     */
    @Test
    void j3_priceForcingsOption_applied() {
        asAdmin().when().get("/admin/reports/price-forcings?option=down")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * J3 (option, hourly-sales article filter): an article filter renders 200.
     */
    @Test
    void j3_hourlySalesOption_applied() {
        asAdmin().when().get("/admin/reports/hourly-sales?option=33")
                .then().log().ifValidationFails().statusCode(200);
    }

    // ==================================================================
    // J4 — export in the three formats
    // ==================================================================

    /**
     * J4 (csv): the CSV export is 200 with the CSV content type and a
     * code-and-date filename.
     */
    @Test
    void j4_exportCsv_contentTypeAndFilename() {
        asAdmin().when().get("/admin/reports/family-sales/export?format=csv")
                .then().log().ifValidationFails().statusCode(200)
                .header("Content-Type", containsString("text/csv"))
                .header("Content-Disposition", containsString("family-sales-"))
                .header("Content-Disposition", containsString(".csv"));
    }

    /**
     * J4 (xlsx): the XLSX export is 200 with the spreadsheet content type.
     */
    @Test
    void j4_exportXlsx_contentType() {
        asAdmin().when().get("/admin/reports/family-sales/export?format=xlsx")
                .then().log().ifValidationFails().statusCode(200)
                .header("Content-Type", containsString(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header("Content-Disposition", containsString(".xlsx"));
    }

    /**
     * J4 (pdf): the PDF export is 200 with the PDF content type.
     */
    @Test
    void j4_exportPdf_contentType() {
        asAdmin().when().get("/admin/reports/family-sales/export?format=pdf")
                .then().log().ifValidationFails().statusCode(200)
                .header("Content-Type", containsString("application/pdf"))
                .header("Content-Disposition", containsString(".pdf"));
    }

    /**
     * J4 (blank format): a blank format token is refused with 404 naming the
     * (empty) format.
     */
    @Test
    void j4_exportBlankFormat_notFound() {
        // Same "Format de rapport inconnu" message as j4_exportUnknownFormat,
        // whose body assertion documents the empty-404-body discrepancy; here we
        // assert only the 404 status to avoid duplicating that red.
        asAdmin().when().get("/admin/reports/family-sales/export?format=")
                .then().log().ifValidationFails().statusCode(404);
    }

    /**
     * J4 (unknown format): the {@code xls} token is refused with 404 naming it.
     */
    @Test
    void j4_exportUnknownFormat_notFound() {
        // DISCREPANCY (left RED): the catalogue states the 404 body carries
        // "Format de rapport inconnu : <format>", but ReportResource throws
        // NotFoundException(message) (line 137) and Quarkus returns 404 with an
        // EMPTY body. See the lot report.
        asAdmin().when().get("/admin/reports/family-sales/export?format=xls")
                .then().log().ifValidationFails().statusCode(404)
                .body(containsString("Format de rapport inconnu : xls"));
    }

    /**
     * J4 (unknown code before writer): an unknown report code on the export route
     * is refused with "Rapport inconnu" — raised before the writer lookup.
     */
    @Test
    void j4_exportUnknownCode_notFound() {
        // Same "Rapport inconnu" message as j3_unknownCode, whose body assertion
        // documents the empty-404-body discrepancy; here only the 404 status is
        // asserted (the code check is raised before the writer).
        asAdmin().when().get("/admin/reports/nope/export?format=csv")
                .then().log().ifValidationFails().statusCode(404);
    }

    /**
     * J4 (anonymous): an anonymous caller on the export route is refused by form
     * auth with a 302 redirect to {@code /admin/login} — the dominant catalogue
     * convention (see {@link #j1_catalogue_anonymous_redirectsToLogin}); the J
     * rows' "401" wording is flagged in the lot report.
     */
    @Test
    void j4_export_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/reports/family-sales/export?format=csv")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", org.hamcrest.Matchers.containsString("/admin/login"));
    }
}
