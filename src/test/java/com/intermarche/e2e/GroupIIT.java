package com.intermarche.e2e;

import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.CoinMachine;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.e2e.support.FormLogin;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
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
 * Group I — the safe and coin-machine back office ({@code /admin/safe/**},
 * {@code /admin/coin-machines}), exercised end to end against a booted store
 * node (H2, no register). Every surface is {@code @RolesAllowed("ADMIN")} and
 * follows the write-then-redirect cycle: a POST answers 303 to its list with a
 * one-shot {@code notice}/{@code noticeOk} pair in the query string.
 *
 * <p>All surfaces are {@code /admin/**} screens under Quarkus form
 * authentication, so an anonymous caller is met with a 302 redirect to
 * {@code /admin/login} — NOT the "401" a few catalogue rows word it as (group A
 * and the S-E convention note settle it at 302). An authenticated non-ADMIN is
 * refused 403.
 */
@QuarkusTest
@TestProfile(GroupIIT.Profile.class)
class GroupIIT {

    /**
     * A profile of this class's own (forces a fresh boot + H2), carrying the
     * seeded credentials the tests sign in with.
     */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Declares the seeded ADMIN and MANAGER credentials as configuration.
         *
         * @return the credential overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "e2e.admin.login", "admin",
                    "e2e.admin.password", "admin",
                    "e2e.manager.login", "mcurie",
                    "e2e.manager.password", "changeme00");
        }
    }

    /** Login of the seeded ADMIN account. */
    @ConfigProperty(name = "e2e.admin.login")
    String adminLogin;

    /** Back-office password of the seeded ADMIN account. */
    @ConfigProperty(name = "e2e.admin.password")
    String adminPassword;

    /** Login of the seeded MANAGER account (a non-ADMIN, for the 403 cases). */
    @ConfigProperty(name = "e2e.manager.login")
    String managerLogin;

    /** Back-office password of the seeded MANAGER account. */
    @ConfigProperty(name = "e2e.manager.password")
    String managerPassword;

    /** The ADMIN session cookie, refreshed before each test. */
    String adminCookie;

    /**
     * Signs in as ADMIN before each test so the authenticated calls carry a
     * live session cookie.
     */
    @BeforeEach
    void signIn() {
        adminCookie = FormLogin.cookie(adminLogin, adminPassword);
    }

    /**
     * Removes every row the group-I tests create, children before parents: safe
     * movements attached to a test period, then coin machines by test code, then
     * the test periods themselves.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<Long> periodIds = AccountingPeriod.<AccountingPeriod>list("label like ?1", "ZI%")
                    .stream().map(p -> p.id).toList();
            if (!periodIds.isEmpty()) {
                SafeMovement.delete("period.id in ?1", periodIds);
            }
            SafeMovement.delete("label like ?1", "ZI%");
            CoinMachine.delete("code like ?1", "ZI%");
            AccountingPeriod.delete("label like ?1", "ZI%");
        });
    }

    // ------------------------------------------------------------------
    // Request builders and assertions
    // ------------------------------------------------------------------

    /**
     * An ADMIN form POST request (urlencoded body, redirects not followed).
     *
     * @return the request specification
     */
    private RequestSpecification adminForm() {
        return given().cookie(FormLogin.COOKIE, adminCookie)
                .contentType(ContentType.URLENC).redirects().follow(false);
    }

    /**
     * An ADMIN GET request (redirects not followed).
     *
     * @return the request specification
     */
    private RequestSpecification adminGet() {
        return given().cookie(FormLogin.COOKIE, adminCookie).redirects().follow(false);
    }

    /**
     * A MANAGER (non-ADMIN) GET request, for the 403 role cases.
     *
     * @return the request specification
     */
    private RequestSpecification managerGet() {
        return given().cookie(FormLogin.COOKIE, FormLogin.cookie(managerLogin, managerPassword))
                .redirects().follow(false);
    }

    /**
     * Posts a form and asserts the 303 redirect carries the expected notice and
     * outcome flag.
     *
     * @param request the form request (with its params already set)
     * @param path the endpoint path
     * @param message the exact notice message expected in the Location
     * @param ok the expected {@code noticeOk} flag
     */
    private void postExpectNotice(RequestSpecification request, String path,
                                  String message, boolean ok) {
        String location = request.when().post(path)
                .then().log().ifValidationFails().statusCode(303)
                .extract().header("Location");
        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        Assertions.assertTrue(decoded.contains(message),
                "expected notice '" + message + "' in redirect: " + decoded);
        Assertions.assertTrue(decoded.contains("noticeOk=" + ok),
                "expected noticeOk=" + ok + " in redirect: " + decoded);
    }

    /**
     * Asserts an anonymous GET is redirected to the login page (302), the form
     * auth challenge for a screen surface.
     *
     * @param path the guarded path
     */
    private void assertAnonymousRedirects(String path) {
        given().redirects().follow(false).when().get(path)
                .then().log().ifValidationFails().statusCode(302)
                .header("Location", containsString("/admin/login"));
    }

    /**
     * Asserts an authenticated non-ADMIN GET is refused with 403.
     *
     * @param path the guarded path
     */
    private void assertNonAdminForbidden(String path) {
        managerGet().when().get(path).then().log().ifValidationFails().statusCode(403);
    }

    // ==================================================================
    // I1 — safe periods
    // ==================================================================

    /** I1 (list): the periods screen renders 200 for an ADMIN. */
    @Test
    void i1_periods_list_ok() {
        adminGet().when().get("/admin/safe/periods").then().statusCode(200);
    }

    /** I1 (open, blank label): a blank label is refused with its notice. */
    @Test
    void i1_periods_open_blankLabel() {
        postExpectNotice(adminForm().formParam("label", ""), "/admin/safe/periods",
                "Libellé de période obligatoire.", false);
    }

    /** I1 (open, none open): opening a period on a fresh node succeeds. */
    @Test
    void i1_periods_open_success() {
        postExpectNotice(adminForm().formParam("label", "ZIPER_OK"), "/admin/safe/periods",
                "Période ZIPER_OK ouverte.", true);
    }

    /** I1 (open, one already open): a second open is refused. */
    @Test
    void i1_periods_open_alreadyOpen() {
        postExpectNotice(adminForm().formParam("label", "ZIPER_A"), "/admin/safe/periods",
                "Période ZIPER_A ouverte.", true);
        postExpectNotice(adminForm().formParam("label", "ZIPER_B"), "/admin/safe/periods",
                "Une période est déjà ouverte.", false);
    }

    /** I1 (close, none open): closing with no open period is refused. */
    @Test
    void i1_periods_close_noneOpen() {
        postExpectNotice(adminForm(), "/admin/safe/periods/close",
                "Aucune période comptable ouverte.", false);
    }

    /** I1 (close, no pending): a period with nothing to point closes. */
    @Test
    void i1_periods_close_success() {
        postExpectNotice(adminForm().formParam("label", "ZIPER_CL"), "/admin/safe/periods",
                "Période ZIPER_CL ouverte.", true);
        postExpectNotice(adminForm(), "/admin/safe/periods/close", "Période close.", true);
    }

    /** I1 (access): anonymous is redirected to the login. */
    @Test
    void i1_periods_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/periods");
    }

    /** I1 (access): a non-ADMIN is forbidden. */
    @Test
    void i1_periods_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/periods");
    }

    // ==================================================================
    // I2 — safe days
    // ==================================================================

    /** I2 (list): the days screen renders 200. */
    @Test
    void i2_days_list_ok() {
        adminGet().when().get("/admin/safe/days").then().statusCode(200);
    }

    /** I2 (unreadable day): a non-date {@code day} falls back to today, 200. */
    @Test
    void i2_days_unreadableDay_ok() {
        adminGet().when().get("/admin/safe/days?day=abc").then().statusCode(200);
    }

    /** I2 (validate, unknown id): an unknown movement id is refused. */
    @Test
    void i2_days_validate_unknownId() {
        postExpectNotice(adminForm().formParam("id", "999999").formParam("endorsedBy", "Chef"),
                "/admin/safe/days/validate", "Mouvement inconnu.", false);
    }

    /** I2 (export): the day export is a CSV with the day-named file. */
    @Test
    void i2_days_export_csv() {
        adminGet().when().get("/admin/safe/days/export?day=2026-09-28")
                .then().statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("journee-2026-09-28.csv"));
    }

    /** I2 (access): anonymous is redirected. */
    @Test
    void i2_days_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/days");
    }

    /** I2 (access): a non-ADMIN is forbidden. */
    @Test
    void i2_days_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/days");
    }

    // ==================================================================
    // I3 — safe movements
    // ==================================================================

    /** I3 (list): the movements screen renders 200. */
    @Test
    void i3_movements_list_ok() {
        adminGet().when().get("/admin/safe/movements").then().statusCode(200);
    }

    /** I3 (record, unknown type): a non-manual/unknown type is refused. */
    @Test
    void i3_movements_record_unknownType() {
        postExpectNotice(adminForm().formParam("type", "BOGUS").formParam("amount", "10")
                        .formParam("label", "ZIMV_X"), "/admin/safe/movements",
                "Nature de mouvement inconnue.", false);
    }

    /** I3 (record, non-positive amount): a zero amount is refused. */
    @Test
    void i3_movements_record_nonPositiveAmount() {
        postExpectNotice(adminForm().formParam("type", "RECEIPT").formParam("amount", "0")
                        .formParam("label", "ZIMV_X"), "/admin/safe/movements",
                "Montant obligatoire et strictement positif.", false);
    }

    /** I3 (record, blank label): a blank label is refused. */
    @Test
    void i3_movements_record_blankLabel() {
        postExpectNotice(adminForm().formParam("type", "RECEIPT").formParam("amount", "10")
                        .formParam("label", ""), "/admin/safe/movements",
                "Libellé obligatoire.", false);
    }

    /** I3 (record, no open period): recording without an open period is refused. */
    @Test
    void i3_movements_record_noOpenPeriod() {
        postExpectNotice(adminForm().formParam("type", "RECEIPT").formParam("amount", "10")
                        .formParam("label", "ZIMV_NP"), "/admin/safe/movements",
                "Aucune période comptable ouverte.", false);
    }

    /** I3 (record, valid): a valid manual movement is recorded in the open period. */
    @Test
    void i3_movements_record_valid() {
        postExpectNotice(adminForm().formParam("label", "ZIPER_MV"), "/admin/safe/periods",
                "Période ZIPER_MV ouverte.", true);
        postExpectNotice(adminForm().formParam("type", "RECEIPT").formParam("amount", "12.50")
                        .formParam("label", "ZIMV1"), "/admin/safe/movements",
                "Mouvement enregistré.", true);
    }

    /** I3 (validate, unknown id): an unknown movement id is refused. */
    @Test
    void i3_movements_validate_unknownId() {
        postExpectNotice(adminForm().formParam("id", "999999"), "/admin/safe/movements/validate",
                "Mouvement inconnu.", false);
    }

    /** I3 (validate, effective): a recorded movement is pointed. */
    @Test
    void i3_movements_validate_valid() {
        postExpectNotice(adminForm().formParam("label", "ZIPER_VA"), "/admin/safe/periods",
                "Période ZIPER_VA ouverte.", true);
        postExpectNotice(adminForm().formParam("type", "RECEIPT").formParam("amount", "5")
                        .formParam("label", "ZIMV2"), "/admin/safe/movements",
                "Mouvement enregistré.", true);
        Long id = QuarkusTransaction.requiringNew().call(
                () -> ((SafeMovement) SafeMovement.find("label", "ZIMV2").firstResult()).id);
        postExpectNotice(adminForm().formParam("id", String.valueOf(id)),
                "/admin/safe/movements/validate", "Mouvement validé.", true);
    }

    /** I3 (validate-all): the loop reports how many were validated (0 here). */
    @Test
    void i3_movements_validateAll() {
        postExpectNotice(adminForm(), "/admin/safe/movements/validate-all",
                "mouvement(s) validé(s).", true);
    }

    /** I3 (correct, not correctable): a null/closed movement is refused. */
    @Test
    void i3_movements_correct_notCorrectable() {
        postExpectNotice(adminForm().formParam("id", "999999").formParam("amount", "5")
                        .formParam("label", "ZIMV_C"), "/admin/safe/movements/correct",
                "Ce mouvement est validé ou sa période est close.", false);
    }

    /** I3 (access): anonymous is redirected. */
    @Test
    void i3_movements_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/movements");
    }

    /** I3 (access): a non-ADMIN is forbidden. */
    @Test
    void i3_movements_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/movements");
    }

    // ==================================================================
    // I4 — safe inventories
    // ==================================================================

    /** I4 (list): the inventories screen renders 200. */
    @Test
    void i4_inventories_list_ok() {
        adminGet().when().get("/admin/safe/inventories").then().statusCode(200);
    }

    /** I4 (count, no denomination): a fully blank count is refused. */
    @Test
    void i4_inventories_count_noDenomination() {
        postExpectNotice(adminForm(), "/admin/safe/inventories",
                "Aucune coupure comptée.", false);
    }

    /** I4 (adjust, no discrepancy): a null / balanced inventory is refused. */
    @Test
    void i4_inventories_adjust_noDiscrepancy() {
        postExpectNotice(adminForm().formParam("id", ""), "/admin/safe/inventories/adjust",
                "Cet inventaire ne présente pas d'écart à régulariser.", false);
    }

    /** I4 (access): anonymous is redirected. */
    @Test
    void i4_inventories_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/inventories");
    }

    /** I4 (access): a non-ADMIN is forbidden. */
    @Test
    void i4_inventories_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/inventories");
    }

    // ==================================================================
    // I5 — cash pouches
    // ==================================================================

    /** I5 (list): the pouches screen renders 200. */
    @Test
    void i5_pouches_list_ok() {
        adminGet().when().get("/admin/safe/pouches").then().statusCode(200);
    }

    /** I5 (deposit, blank number): a blank pouch number is refused. */
    @Test
    void i5_pouches_deposit_blankNumber() {
        postExpectNotice(adminForm().formParam("amount", "10"), "/admin/safe/pouches",
                "Numéro de pochette obligatoire.", false);
    }

    /** I5 (deposit, non-positive amount): a zero amount is refused (number first). */
    @Test
    void i5_pouches_deposit_nonPositiveAmount() {
        postExpectNotice(adminForm().formParam("pouchNumber", "ZIP_A").formParam("amount", "0"),
                "/admin/safe/pouches", "Montant obligatoire et strictement positif.", false);
    }

    /** I5 (deposit, no open period): a valid pouch without an open period is refused. */
    @Test
    void i5_pouches_deposit_noOpenPeriod() {
        postExpectNotice(adminForm().formParam("pouchNumber", "ZIP_B").formParam("amount", "10"),
                "/admin/safe/pouches", "Aucune période comptable ouverte.", false);
    }

    /** I5 (access): anonymous is redirected. */
    @Test
    void i5_pouches_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/pouches");
    }

    /** I5 (access): a non-ADMIN is forbidden. */
    @Test
    void i5_pouches_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/pouches");
    }

    // ==================================================================
    // I6 — bank deposits
    // ==================================================================

    /** I6 (list): the bank-deposits screen renders 200. */
    @Test
    void i6_bankDeposits_list_ok() {
        adminGet().when().get("/admin/safe/bank-deposits").then().statusCode(200);
    }

    /** I6 (deposit, blank bag): a blank bag number is refused. */
    @Test
    void i6_bankDeposits_deposit_blankBag() {
        postExpectNotice(adminForm(), "/admin/safe/bank-deposits",
                "Numéro de sac obligatoire.", false);
    }

    /** I6 (deposit, no pouch selected): a bag with no pouch is refused. */
    @Test
    void i6_bankDeposits_deposit_noPouchSelected() {
        postExpectNotice(adminForm().formParam("bagNumber", "ZIB_A"), "/admin/safe/bank-deposits",
                "Aucune pochette sélectionnée.", false);
    }

    /** I6 (bordereau, unknown id): an unknown deposit yields 404. */
    @Test
    void i6_bankDeposits_bordereau_unknown() {
        adminGet().when().get("/admin/safe/bank-deposits/999999/bordereau")
                .then().statusCode(404);
    }

    /** I6 (versement, unknown id): an unknown deposit yields 404. */
    @Test
    void i6_bankDeposits_versement_unknown() {
        adminGet().when().get("/admin/safe/bank-deposits/999999/versement")
                .then().statusCode(404);
    }

    /** I6 (access): anonymous is redirected. */
    @Test
    void i6_bankDeposits_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/bank-deposits");
    }

    /** I6 (access): a non-ADMIN is forbidden. */
    @Test
    void i6_bankDeposits_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/bank-deposits");
    }

    // ==================================================================
    // I7 — coin orders
    // ==================================================================

    /** I7 (list): the coin-orders screen renders 200. */
    @Test
    void i7_coinOrders_list_ok() {
        adminGet().when().get("/admin/safe/coin-orders").then().statusCode(200);
    }

    /** I7 (order, blank number): a blank order number is refused. */
    @Test
    void i7_coinOrders_order_blankNumber() {
        postExpectNotice(adminForm(), "/admin/safe/coin-orders",
                "Numéro de demande obligatoire.", false);
    }

    /** I7 (order, no quantity): an order with no quantity is refused. */
    @Test
    void i7_coinOrders_order_noQuantity() {
        postExpectNotice(adminForm().formParam("orderNumber", "ZIO_A"), "/admin/safe/coin-orders",
                "Aucune quantité demandée.", false);
    }

    /** I7 (receive, unknown id): an unknown order is refused. */
    @Test
    void i7_coinOrders_receive_unknownId() {
        postExpectNotice(adminForm().formParam("id", "999999"), "/admin/safe/coin-orders/receive",
                "Demande inconnue.", false);
    }

    /** I7 (cancel, unknown id): an order that cannot be cancelled is refused. */
    @Test
    void i7_coinOrders_cancel_unknownId() {
        postExpectNotice(adminForm().formParam("id", "999999"), "/admin/safe/coin-orders/cancel",
                "Cette demande ne peut plus être annulée.", false);
    }

    /** I7 (slip, unknown id): an unknown order slip yields 404. */
    @Test
    void i7_coinOrders_slip_unknown() {
        adminGet().when().get("/admin/safe/coin-orders/999999/slip").then().statusCode(404);
    }

    /** I7 (access): anonymous is redirected. */
    @Test
    void i7_coinOrders_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/coin-orders");
    }

    /** I7 (access): a non-ADMIN is forbidden. */
    @Test
    void i7_coinOrders_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/coin-orders");
    }

    // ==================================================================
    // I8 — safe journal
    // ==================================================================

    /** I8 (consultation): the read-only journal renders 200. */
    @Test
    void i8_journal_list_ok() {
        adminGet().when().get("/admin/safe/journal").then().statusCode(200);
    }

    /** I8 (export): the journal export is a CSV with the range-named file. */
    @Test
    void i8_journal_export_csv() {
        adminGet().when().get("/admin/safe/journal/export?from=2026-09-01&to=2026-09-28")
                .then().statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("journal-coffre-"));
    }

    /** I8 (access): anonymous is redirected. */
    @Test
    void i8_journal_anonymous_redirects() {
        assertAnonymousRedirects("/admin/safe/journal");
    }

    /** I8 (access): a non-ADMIN is forbidden. */
    @Test
    void i8_journal_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/safe/journal");
    }

    // ==================================================================
    // I9 — coin machines
    // ==================================================================

    /** I9 (list): the coin-machines screen renders 200. */
    @Test
    void i9_coinMachines_list_ok() {
        adminGet().when().get("/admin/coin-machines").then().statusCode(200);
    }

    /** I9 (save, blank code): a blank code is refused. */
    @Test
    void i9_coinMachines_save_blankCode() {
        postExpectNotice(adminForm().formParam("label", "Sans code"), "/admin/coin-machines",
                "Code du monnayeur obligatoire.", false);
    }

    /** I9 (save, valid): a coin machine is created. */
    @Test
    void i9_coinMachines_save_valid() {
        postExpectNotice(adminForm().formParam("code", "ZIMC1").formParam("label", "Machine 1"),
                "/admin/coin-machines", "Monnayeur enregistré.", true);
    }

    /** I9 (save, duplicate code): a code taken by another machine is refused. */
    @Test
    void i9_coinMachines_save_duplicateCode() {
        postExpectNotice(adminForm().formParam("code", "ZIMC2").formParam("label", "First"),
                "/admin/coin-machines", "Monnayeur enregistré.", true);
        postExpectNotice(adminForm().formParam("code", "ZIMC2").formParam("label", "Second"),
                "/admin/coin-machines", "Ce code de monnayeur existe déjà : ZIMC2", false);
    }

    /** I9 (count, unknown id): counting an unknown machine is refused. */
    @Test
    void i9_coinMachines_count_unknownId() {
        postExpectNotice(adminForm().formParam("id", "999999").formParam("countedAmount", "10"),
                "/admin/coin-machines/count", "Monnayeur inconnu.", false);
    }

    /** I9 (count, reconciled): a count matching the held amount (0) is conform. */
    @Test
    void i9_coinMachines_count_reconciled() {
        postExpectNotice(adminForm().formParam("code", "ZIMC3").formParam("label", "M3"),
                "/admin/coin-machines", "Monnayeur enregistré.", true);
        Long id = QuarkusTransaction.requiringNew().call(
                () -> ((CoinMachine) CoinMachine.find("code", "ZIMC3").firstResult()).id);
        postExpectNotice(adminForm().formParam("id", String.valueOf(id)).formParam("countedAmount", "0"),
                "/admin/coin-machines/count",
                "Comptage conforme au montant tenu par le logiciel.", true);
    }

    /** I9 (count, discrepancy): a count differing from the held amount is flagged. */
    @Test
    void i9_coinMachines_count_discrepancy() {
        postExpectNotice(adminForm().formParam("code", "ZIMC4").formParam("label", "M4"),
                "/admin/coin-machines", "Monnayeur enregistré.", true);
        Long id = QuarkusTransaction.requiringNew().call(
                () -> ((CoinMachine) CoinMachine.find("code", "ZIMC4").firstResult()).id);
        postExpectNotice(adminForm().formParam("id", String.valueOf(id)).formParam("countedAmount", "5"),
                "/admin/coin-machines/count", "Comptage enregistré, écart de", false);
    }

    /** I9 (align, never counted): realigning a never-counted machine is refused. */
    @Test
    void i9_coinMachines_align_neverCounted() {
        postExpectNotice(adminForm().formParam("code", "ZIMC5").formParam("label", "M5"),
                "/admin/coin-machines", "Monnayeur enregistré.", true);
        Long id = QuarkusTransaction.requiringNew().call(
                () -> ((CoinMachine) CoinMachine.find("code", "ZIMC5").firstResult()).id);
        postExpectNotice(adminForm().formParam("id", String.valueOf(id)),
                "/admin/coin-machines/align", "Aucun comptage à reprendre.", false);
    }

    /** I9 (load, null amount): a null/zero load amount is refused. */
    @Test
    void i9_coinMachines_load_nullAmount() {
        postExpectNotice(adminForm().formParam("code", "ZIMC6").formParam("amount", "0"),
                "/admin/coin-machines/load",
                "Montant de chargement obligatoire et non nul.", false);
    }

    /** I9 (load, unknown code): loading an unknown machine is refused. */
    @Test
    void i9_coinMachines_load_unknownCode() {
        postExpectNotice(adminForm().formParam("code", "ZINOPE").formParam("amount", "10"),
                "/admin/coin-machines/load", "Monnayeur inconnu.", false);
    }

    /** I9 (export): the coin-machines export is a CSV with the fixed file name. */
    @Test
    void i9_coinMachines_export_csv() {
        adminGet().when().get("/admin/coin-machines/export")
                .then().statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("monnayeurs.csv"));
    }

    /** I9 (access): anonymous is redirected. */
    @Test
    void i9_coinMachines_anonymous_redirects() {
        assertAnonymousRedirects("/admin/coin-machines");
    }

    /** I9 (access): a non-ADMIN is forbidden. */
    @Test
    void i9_coinMachines_nonAdmin_forbidden() {
        assertNonAdminForbidden("/admin/coin-machines");
    }
}
