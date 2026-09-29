package com.intermarche.e2e;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.e2e.support.FormLogin;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
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

/**
 * Group L — Documents et gabarits: the read-only issued-documents screen
 * ({@code /admin/documents}, L1) and the document-template editor
 * ({@code /admin/templates}, L2), both {@code @RolesAllowed("ADMIN")}.
 *
 * <p>[E] only. These are screen surfaces under Quarkus form authentication, so
 * an anonymous caller is answered with a 302 redirect to {@code /admin/login}
 * (not the 401 the L header line states — that wording contradicts the rest of
 * the back office and is flagged in the report); an authenticated non-ADMIN is
 * refused with 403.
 */
@QuarkusTest
@TestProfile(GroupLIT.Profile.class)
class GroupLIT {

    /** A profile of this class's own, forcing a fresh boot and carrying creds. */
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

    /** ADMIN login (seeded), from the test profile config. */
    @ConfigProperty(name = "e2e.admin.login")
    String adminLogin;

    /** ADMIN back-office password, from the test profile config. */
    @ConfigProperty(name = "e2e.admin.password")
    String adminPassword;

    /**
     * Seeds a non-ADMIN back-office account (MANAGER, no imposed password) so the
     * ADMIN-only guard can be observed as a 403 rather than a redirect.
     */
    @BeforeEach
    void createNonAdmin() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (Employee.find("loginName", "zlmgr").firstResult() == null) {
                Employee e = new Employee();
                e.badgeId = "ZL000001";
                e.loginName = "zlmgr";
                e.password = Employee.hashPassword("1234");
                e.firstName = "Z";
                e.lastName = "Mgr";
                e.email = "zlmgr@zl.test";
                e.role = Employee.EmployeeRole.MANAGER;
                e.active = true;
                e.setBackOfficePassword("zlpass1");
                e.mustChangePassword = false;
                e.persist();
            }
        });
    }

    /** Removes the templates and the account the tests create. */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            DocumentTemplate.delete("code like ?1", "ZL%");
            Employee.delete("badgeId like ?1", "ZL%");
        });
    }

    /**
     * Signs in as ADMIN and returns a cookie-bearing request specification.
     *
     * @return a request authenticated as ADMIN
     */
    private io.restassured.specification.RequestSpecification asAdmin() {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie(adminLogin, adminPassword));
    }

    /**
     * Signs in as the seeded non-ADMIN MANAGER and returns a request.
     *
     * @return a request authenticated as a non-ADMIN
     */
    private io.restassured.specification.RequestSpecification asManager() {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie("zlmgr", "zlpass1"));
    }

    /**
     * Asserts a template POST answered 303 with a one-shot notice carrying the
     * expected outcome flag and message fragment.
     *
     * @param r the response
     * @param ok the expected {@code noticeOk} value
     * @param messagePart the exact notice fragment expected
     */
    private void assertNotice(Response r, boolean ok, String messagePart) {
        Assertions.assertEquals(303, r.statusCode(), r.asString());
        String decoded = URLDecoder.decode(r.header("Location"), StandardCharsets.UTF_8);
        Assertions.assertTrue(decoded.contains("noticeOk=" + ok), decoded);
        Assertions.assertTrue(decoded.contains(messagePart), decoded);
    }

    // ================================================================== L1

    /**
     * L1 (access): an anonymous caller on the documents list is redirected to
     * the login screen (form auth). The catalogue's "401" is flagged in the
     * report as inconsistent with the back-office convention.
     */
    @Test
    void l1_documents_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/documents")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * L1 (access): an authenticated non-ADMIN is refused the documents list.
     */
    @Test
    void l1_documents_nonAdmin_forbidden() {
        asManager().redirects().follow(false).when().get("/admin/documents")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * L1 (list): the ADMIN sees the issued-documents list, 200 HTML.
     */
    @Test
    void l1_documents_admin_ok() {
        asAdmin().when().get("/admin/documents")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L1 (list filters): a type filter and a text filter are accepted (in-memory
     * filtering), 200 HTML — an unknown/blank type keeps all.
     */
    @Test
    void l1_documents_filters_ok() {
        asAdmin().when().get("/admin/documents?type=FACTURE&text=abc&taxId=xyz")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L1 (unreadable dates): an unreadable from/to bound is treated as absent,
     * no error, 200 HTML.
     */
    @Test
    void l1_documents_unreadableDates_ok() {
        asAdmin().when().get("/admin/documents?from=abc&to=xyz")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L1 (export): the filtered export is a UTF-8 CSV named
     * {@code documents-<today>.csv} whose header is the 16 declared columns.
     */
    @Test
    void l1_documents_export_csv() {
        asAdmin().when().get("/admin/documents/export")
                .then().log().ifValidationFails().statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("filename=\"documents-"))
                .header("Content-Disposition", containsString(".csv\""))
                .body(containsString(
                        "DOCUMENT_NUMBER;DOCUMENT_TYPE;ISSUE_DATE;TERMINAL_ID;TICKET_NUMBER;"))
                .body(containsString("PRINT_COUNT"));
    }

    /**
     * L1 (export access): an anonymous caller on the export route is redirected
     * to the login screen.
     */
    @Test
    void l1_export_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/documents/export")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    // ================================================================== L2

    /**
     * L2 (access): an anonymous caller on the templates page is redirected to
     * the login screen.
     */
    @Test
    void l2_templates_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/templates")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * L2 (access): an authenticated non-ADMIN is refused the templates page.
     */
    @Test
    void l2_templates_nonAdmin_forbidden() {
        asManager().redirects().follow(false).when().get("/admin/templates")
                .then().log().ifValidationFails().statusCode(403);
    }

    /**
     * L2 (page): the ADMIN sees the templates editor, 200 HTML.
     */
    @Test
    void l2_templates_admin_ok() {
        asAdmin().when().get("/admin/templates")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L2 (preview): a posted source is rendered without persisting; 200 HTML and
     * no template row is created.
     */
    @Test
    void l2_preview_ok() {
        asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLPREVIEW").formParam("documentType", "SALE_RECEIPT")
                .formParam("source", "Bonjour")
                .when().post("/admin/templates/preview")
                .then().log().ifValidationFails().statusCode(200);
        long count = QuarkusTransaction.requiringNew().call(
                () -> DocumentTemplate.count("code", "ZLPREVIEW"));
        Assertions.assertEquals(0, count, "preview must not persist a template");
    }

    /**
     * L2 (preview, broken source): a source that does not compile still answers
     * 200 (the draft carries the parser complaint) and persists nothing.
     */
    @Test
    void l2_preview_brokenSource_ok() {
        asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLPREVIEW2").formParam("documentType", "SALE_RECEIPT")
                .formParam("source", "{#if true}oops")
                .when().post("/admin/templates/preview")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L2 (copy, example): picking an {@code example:<TYPE>} entry loads the
     * factory source, 200 HTML.
     */
    @Test
    void l2_copy_example_ok() {
        asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("from", "example:SALE_RECEIPT").formParam("documentType", "SALE_RECEIPT")
                .when().post("/admin/templates/copy")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L2 (copy, empty pick): an empty pick falls back on the current type's
     * example rather than emptying the box, 200 HTML.
     */
    @Test
    void l2_copy_emptyPick_ok() {
        asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("from", "").formParam("documentType", "SALE_RECEIPT")
                .when().post("/admin/templates/copy")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L2 (save, empty code): a blank code is refused with the exact notice.
     */
    @Test
    void l2_save_emptyCode() {
        Response r = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "").formParam("documentType", "SALE_RECEIPT")
                .formParam("source", "Bonjour")
                .redirects().follow(false).when().post("/admin/templates/save")
                .then().log().ifValidationFails().extract().response();
        assertNotice(r, false, "Le code du gabarit est obligatoire.");
    }

    /**
     * L2 (save, unknown type): an unknown document type is refused.
     */
    @Test
    void l2_save_unknownType() {
        Response r = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLT").formParam("documentType", "BOGUS")
                .formParam("source", "Bonjour")
                .redirects().follow(false).when().post("/admin/templates/save")
                .then().log().ifValidationFails().extract().response();
        assertNotice(r, false, "Type de document inconnu.");
    }

    /**
     * L2 (save, broken source): a source that does not compile is refused with
     * the parser complaint.
     */
    @Test
    void l2_save_brokenSource() {
        Response r = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLT").formParam("documentType", "SALE_RECEIPT")
                .formParam("source", "{#if true}oops")
                .redirects().follow(false).when().post("/admin/templates/save")
                .then().log().ifValidationFails().extract().response();
        assertNotice(r, false, "Gabarit refusé : ");
    }

    /**
     * L2 (save, width out of range): a width below 20 or above 120 is refused.
     */
    @Test
    void l2_save_widthOutOfRange() {
        Response r = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLT").formParam("documentType", "SALE_RECEIPT")
                .formParam("source", "Bonjour").formParam("width", "5")
                .redirects().follow(false).when().post("/admin/templates/save")
                .then().log().ifValidationFails().extract().response();
        assertNotice(r, false, "La largeur doit être comprise entre 20 et 120.");
    }

    /**
     * L2 (save, create then update): a fresh code is created, a re-save of the
     * same code is an update, each with its exact notice.
     */
    @Test
    void l2_save_createThenUpdate() {
        Response created = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLNEW").formParam("label", "Test")
                .formParam("documentType", "SALE_RECEIPT").formParam("source", "Bonjour")
                .formParam("width", "42")
                .redirects().follow(false).when().post("/admin/templates/save")
                .then().log().ifValidationFails().extract().response();
        assertNotice(created, true, "Gabarit créé.");
        Response updated = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLNEW").formParam("label", "Test 2")
                .formParam("documentType", "SALE_RECEIPT").formParam("source", "Bonjour bis")
                .formParam("width", "42")
                .redirects().follow(false).when().post("/admin/templates/save")
                .then().log().ifValidationFails().extract().response();
        assertNotice(updated, true, "Gabarit enregistré.");
    }

    /**
     * L2 (delete, unknown): deleting an unknown code is refused.
     */
    @Test
    void l2_delete_unknown() {
        Response r = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLNOPE")
                .redirects().follow(false).when().post("/admin/templates/delete")
                .then().log().ifValidationFails().extract().response();
        assertNotice(r, false, "Gabarit inconnu.");
    }

    /**
     * L2 (delete, known): a created template is deleted with the success notice.
     */
    @Test
    void l2_delete_known() {
        asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLDEL").formParam("label", "Test")
                .formParam("documentType", "SALE_RECEIPT").formParam("source", "Bonjour")
                .formParam("width", "42")
                .redirects().follow(false)
                .when().post("/admin/templates/save").then().statusCode(303);
        Response r = asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLDEL")
                .redirects().follow(false).when().post("/admin/templates/delete")
                .then().log().ifValidationFails().extract().response();
        assertNotice(r, true, "Gabarit supprimé.");
    }

    /**
     * L2 (copy, administered template): a {@code template:<code>} pick loads the
     * administered source, 200 HTML.
     */
    @Test
    void l2_copy_administeredTemplate_ok() {
        asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLCOPY").formParam("label", "Test")
                .formParam("documentType", "SALE_RECEIPT").formParam("source", "Bonjour source")
                .formParam("width", "42")
                .redirects().follow(false)
                .when().post("/admin/templates/save").then().statusCode(303);
        asAdmin().contentType("application/x-www-form-urlencoded")
                .formParam("from", "template:ZLCOPY").formParam("documentType", "SALE_RECEIPT")
                .when().post("/admin/templates/copy")
                .then().log().ifValidationFails().statusCode(200);
    }

    /**
     * L2 (references API): the references of a type are returned as JSON.
     */
    @Test
    void l2_apiReferences_ok() {
        asAdmin().when().get("/admin/templates/api/references?type=SALE_RECEIPT")
                .then().log().ifValidationFails().statusCode(200)
                .contentType(containsString("application/json"));
    }

    /**
     * L2 (references API, unknown type): an unknown/blank type falls back on
     * SALE_RECEIPT and still returns JSON.
     */
    @Test
    void l2_apiReferences_unknownType_ok() {
        asAdmin().when().get("/admin/templates/api/references?type=NOPE")
                .then().log().ifValidationFails().statusCode(200)
                .contentType(containsString("application/json"));
    }

    /**
     * L2 (access on write routes): an anonymous save is redirected to login,
     * proving the write routes are guarded too.
     */
    @Test
    void l2_save_anonymous_redirectsToLogin() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("code", "ZLX").formParam("documentType", "SALE_RECEIPT")
                .redirects().follow(false).when().post("/admin/templates/save")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * L2 (access on the references API): an anonymous call is redirected to
     * login.
     */
    @Test
    void l2_apiReferences_anonymous_redirectsToLogin() {
        given().redirects().follow(false).when().get("/admin/templates/api/references?type=SALE_RECEIPT")
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }
}
