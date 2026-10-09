package com.intermarche.e2e;

import com.intermarche.edge.domain.catalog.EgalimRegime;
import com.intermarche.edge.domain.catalog.Nomenclature;
import com.intermarche.edge.domain.catalog.NomenclatureLevel;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.edge.domain.store.PostalCode;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.domain.sync.EngineFeed;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * Group F — the single import door {@code POST /feeds/import/{code}}, its
 * status surface and the eleven importer delegations, exercised end to end
 * against a booted store node (H2, no register).
 *
 * <p>This class covers ONLY the {@code [E]} scenarios of catalogue group F
 * (F1–F14): the node-alone behaviour that a fresh H2 base exhibits without a
 * register, a central or a valuation engine. The {@code [V]} scenarios (feed
 * delivery to the engine, F15, and deferred scheduling, F16) are left for the
 * engine-backed lots; the verbatim capture that a delegated import performs is
 * node-side and IS checked here through the status surface. The anonymous CSV
 * import refusal (F1 "importFeed Anonyme 401") is already proven by
 * {@code SecurityExtensionLivenessIT#anonymousCsvImportIsRefused} and is not
 * duplicated here.
 *
 * <p>The machine surfaces {@code /feeds/import/*} are forced onto HTTP Basic by
 * {@code quarkus.http.auth.permission.api.auth-mechanism=basic}, so every
 * authenticated call here carries a Basic header, never a form cookie. The
 * credentials are the bootstrap-seeded accounts and are supplied by the test
 * profile configuration ({@code e2e.admin.*}, {@code e2e.manager.*}), never
 * hard-coded in a method body.
 */
@QuarkusTest
@TestProfile(GroupFIT.Profile.class)
class GroupFIT {

    /**
     * A profile of this class's own, even though its only job is to declare the
     * known test credentials as configuration. Quarkus compares the profile
     * CLASS to decide whether to restart the application between test classes:
     * a distinct class forces a fresh boot and a fresh H2 base for group F, so a
     * red here is a fact about group F and not a leak from the class before.
     */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Declares the seeded accounts' credentials as configuration, so the
         * secrets live here and not scattered as literals in the assertions.
         *
         * @return the credential configuration overrides
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

    /** The full "known codes" list the unknown-code error appends, verbatim. */
    private static final String KNOWN_CODES =
            "CUSTOMERS, EGALIM_REGIMES, EMPLOYEES, FAMILIES, NOMENCLATURE, POSTAL_CODES,"
            + " PRICES, PRODUCTS, SAFE_RECEIPTS, STORES, VAT_RATES, STORE_GROUPS,"
            + " CATEGORY_STORAGES, BUDGET_GROUPS, OFFERS";

    /** The start date the price fixtures use, distinct from the seed's window. */
    private static final String PRICE_START = "2029-01-01T00:00:00";

    /** The seeded EAN a price import can attach to (engine mirror row). */
    private static final String SEEDED_EAN = "3300000000001";

    /** Login of the bootstrap ADMIN account (from the test profile config). */
    @ConfigProperty(name = "e2e.admin.login")
    String adminLogin;

    /** Back-office password of the bootstrap ADMIN account. */
    @ConfigProperty(name = "e2e.admin.password")
    String adminPassword;

    /** Login of a seeded non-ADMIN (MANAGER) account. */
    @ConfigProperty(name = "e2e.manager.login")
    String managerLogin;

    /** Back-office password of the seeded MANAGER account. */
    @ConfigProperty(name = "e2e.manager.password")
    String managerPassword;

    /**
     * Removes every row the group-F tests create, in an order that respects the
     * foreign keys, and drops the engine feeds the imports captured while
     * keeping the seed's BUDGET_GROUPS projection. Runs after every test so a
     * method that dies mid-way cannot poison the next one.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            SafeMovement.delete("reference like ?1", "ZF%");
            AccountingPeriod.delete("label like ?1", "ZF%");
            Price.delete("startDateTime = ?1", LocalDateTime.parse(PRICE_START));
            Product.delete("ean like ?1", "ZF%");
            List<Nomenclature> zfSchemes = Nomenclature.list("code like ?1", "ZF%");
            List<Long> zfSchemeIds = zfSchemes.stream().map(n -> n.id).toList();
            if (!zfSchemeIds.isEmpty()) {
                NomenclatureLevel.delete("nomenclature.id in ?1", zfSchemeIds);
                ProductFamily.delete("nomenclature.id in ?1", zfSchemeIds);
            }
            ProductFamily.delete("code like ?1 or code = ?2", "ZF%", "ZY");
            Nomenclature.delete("code like ?1", "ZF%");
            VatRate.delete("number >= ?1", 90);
            EgalimRegime.delete("code like ?1", "ZF%");
            Employee.delete("badgeId like ?1", "ZF%");
            AccountCustomer.delete("accountNumber like ?1", "ZF%");
            PostalCode.delete("code like ?1", "ZF%");
            Store.delete("code like ?1", "ZF%");
            EngineFeed.delete("code <> ?1", "BUDGET_GROUPS");
        });
    }

    // ------------------------------------------------------------------
    // Request builders
    // ------------------------------------------------------------------

    /**
     * Builds a Basic-authenticated request as the ADMIN account, carrying a
     * text/plain body as the import door consumes.
     *
     * @return the ADMIN request specification
     */
    private RequestSpecification admin() {
        return given().auth().preemptive().basic(adminLogin, adminPassword)
                .contentType(ContentType.TEXT);
    }

    /**
     * Builds a Basic-authenticated request as the MANAGER account (a non-ADMIN
     * with back-office access), used to prove the ADMIN-only guard.
     *
     * @return the MANAGER request specification
     */
    private RequestSpecification manager() {
        return given().auth().preemptive().basic(managerLogin, managerPassword)
                .contentType(ContentType.TEXT);
    }

    // ==================================================================
    // F1 — single import door
    // ==================================================================

    /**
     * F1 (importFeed, non-ADMIN): an authenticated MANAGER posting to the
     * ADMIN-only door is refused with 403, not merely un-authenticated.
     */
    @Test
    void f1_importFeed_nonAdmin_forbidden() {
        manager().body(storesCsv("ZFROLE"))
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(403);
    }

    /**
     * F1 (importFeed, recognized code): a code owned by an importer is delegated
     * to it and the importer's own created/updated shape comes back.
     */
    @Test
    void f1_importFeed_recognizedCode_delegatesToImporter() {
        admin().body(storesCsv("ZFDEL1"))
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1))
                .body("updatedCount", equalTo(0));
    }

    /**
     * F1 (importFeed, code case-insensitive): the code is normalized trim+upper,
     * so a lower-case spelling still reaches the same importer.
     */
    @Test
    void f1_importFeed_codeCaseInsensitive_delegates() {
        admin().body(storesCsv("ZFDEL2"))
                .when().post("/feeds/import/  stores  ".trim())
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
    }

    /**
     * F1 (importFeed, engine catalog code without importer): a catalog code with
     * no delegate and a non-empty body is stored verbatim; 200 returns the code
     * and its SHA-256 version.
     */
    @Test
    void f1_importFeed_catalogCodeNoImporter_storesVerbatim() {
        admin().body("STORE GROUP PARCEL\n")
                .when().post("/feeds/import/STORE_GROUPS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("code", equalTo("STORE_GROUPS"))
                .body("version", matchesPattern("[a-f0-9]{64}"));
    }

    /**
     * F1 (importFeed, catalog code with empty body): a catalog code posted with a
     * blank body is refused with 400 and the exact empty-body message.
     */
    @Test
    void f1_importFeed_catalogCodeEmptyBody_badRequest() {
        admin().body("   ")
                .when().post("/feeds/import/STORE_GROUPS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Empty feed body\"}"));
    }

    /**
     * F1 (importFeed, unknown code): a code that is neither a delegate nor a
     * catalog entry is refused with 400 and the full sorted known-codes list.
     */
    @Test
    void f1_importFeed_unknownCode_badRequest() {
        admin().body("whatever\n")
                .when().post("/feeds/import/ZZZ")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Unknown feed code 'ZZZ' - known codes: "
                        + KNOWN_CODES + "\"}"));
    }

    /**
     * F1 (importFeed, blank code): a blank code normalizes to the empty string,
     * matches no delegate and no catalog entry, and is refused with 400 naming
     * the empty code — the reachable equivalent of the "code null" row.
     */
    @Test
    void f1_importFeed_blankCode_normalizedToEmpty_badRequest() {
        admin().body("whatever\n")
                .when().post("/feeds/import/{code}", " ")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Unknown feed code '' - known codes: "
                        + KNOWN_CODES + "\"}"));
    }

    /**
     * F1 (status, anonymous): the status surface refuses an anonymous caller
     * with 401 (the machine mechanism answers, never a login redirect).
     */
    @Test
    void f1_status_anonymous_unauthorized() {
        given().when().get("/feeds/import/status")
                .then().log().ifValidationFails()
                .statusCode(401);
    }

    /**
     * F1 (status, non-ADMIN): an authenticated MANAGER is refused the ADMIN-only
     * status surface with 403.
     */
    @Test
    void f1_status_nonAdmin_forbidden() {
        manager().when().get("/feeds/import/status")
                .then().log().ifValidationFails()
                .statusCode(403);
    }

    /**
     * F1 (status, ADMIN): the ADMIN caller gets 200 and a JSON array where a
     * freshly stored feed appears with applied=false and a null appliedVersion.
     */
    @Test
    void f1_status_admin_listsStoredFeeds() {
        admin().body("STORE GROUP PARCEL\n")
                .when().post("/feeds/import/STORE_GROUPS")
                .then().statusCode(200);
        admin().when().get("/feeds/import/status")
                .then().log().ifValidationFails()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("find { it.code == 'STORE_GROUPS' }.applied", equalTo(false))
                .body("find { it.code == 'STORE_GROUPS' }.appliedVersion", nullValue());
    }

    // ==================================================================
    // F2 — common CSV mechanics (exercised through the STORES / PRODUCTS
    //      / EMPLOYEES importers)
    // ==================================================================

    /**
     * F2 (missing key column): a header lacking the key column is refused with
     * 400 naming it — the key comes first in the missing list.
     */
    @Test
    void f2_missingKeyColumn_badRequest() {
        admin().body("NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY\n")
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: CODE\"}"));
    }

    /**
     * F2 (missing required column): a header lacking a required column is
     * refused with 400 naming that column.
     */
    @Test
    void f2_missingRequiredColumn_badRequest() {
        admin().body("CODE|NAME|STREET_LINE1|STREET_LINE2|CITY|COUNTRY\n")
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: POSTAL_CODE\"}"));
    }

    /**
     * F2 (missing ordering): several missing columns are listed key-first, then
     * the required columns in declaration order.
     */
    @Test
    void f2_missingColumns_keyFirstThenRequiredOrder() {
        admin().body("NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|COUNTRY\n")
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: CODE, CITY\"}"));
    }

    /**
     * F2 (valid header, correct lines): a nominal file returns 200 with the
     * created/updated counts.
     */
    @Test
    void f2_validFile_returnsCounts() {
        admin().body(storesCsv("ZFOK1"))
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1))
                .body("updatedCount", equalTo(0));
    }

    /**
     * F2 (line with fewer cells than the header): a truncated row is reported as
     * ignored and the import still returns 200.
     */
    @Test
    void f2_truncatedLine_reportedAndIgnored() {
        String body = storesHeader() + "\n" + "ZF02|Only three|cells";
        String payload = admin().body(body)
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .extract().asString();
        Assertions.assertTrue(payload.contains(
                "Line 2 ignored (fewer cells than the header): ZF02|Only three|cells"),
                "the truncated line must be reported; body was: " + payload);
    }

    /**
     * F2 (empty key cell): a row whose key cell is empty is reported as ignored
     * and the import still returns 200.
     */
    @Test
    void f2_emptyKeyCell_reportedAndIgnored() {
        String body = storesHeader() + "\n" + "|Store NoCode|1 rue|ZI|59000|Lille|France";
        String payload = admin().body(body)
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .extract().asString();
        Assertions.assertTrue(payload.contains("Line 2 ignored (empty key 'CODE')"),
                "the empty-key line must be reported; body was: " + payload);
    }

    /**
     * F2 (blank line): a blank data line is skipped silently — the valid row
     * still imports and no error is reported.
     */
    @Test
    void f2_blankLine_skippedSilently() {
        String body = storesHeader() + "\n\n" + storeRow("ZF03");
        admin().body(body)
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1))
                .body("errors", nullValue());
    }

    /**
     * F2 (duplicate header column): a repeated column keeps its first occurrence,
     * so no field is swapped — the store takes the value under the first NAME.
     */
    @Test
    void f2_duplicateHeaderColumn_firstOccurrenceWins() {
        String header = "CODE|NAME|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY";
        String row = "ZF04|FirstName|SecondName|1 rue|ZI|59000|Lille|France";
        admin().body(header + "\n" + row)
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
        String stored = QuarkusTransaction.requiringNew().call(
                () -> ((Store) Store.find("code", "ZF04").firstResult()).name);
        Assertions.assertEquals("FirstName", stored,
                "the first NAME occurrence wins; the second is ignored");
    }

    /**
     * F2 (column unknown to the importer): a column belonging to another tool is
     * ignored, and the row still imports (union schema tolerated).
     */
    @Test
    void f2_unknownColumn_ignored() {
        String header = storesHeader() + "|FOOBAR";
        String row = storeRow("ZF05") + "|other-tool-value";
        admin().body(header + "\n" + row)
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
    }

    /**
     * F2 (re-import of the same file): the per-line checksum makes a second
     * identical import a no-op — nothing created, nothing updated.
     */
    @Test
    void f2_reimportSameFile_isNoOp() {
        String body = productsCsv("ZFEAN1");
        int firstCreated = admin().body(body).when().post("/feeds/import/PRODUCTS")
                .then().statusCode(200).extract().jsonPath().getInt("createdCount");
        Assertions.assertEquals(1, firstCreated, "the first import creates the product");
        io.restassured.path.json.JsonPath second = admin().body(body).when()
                .post("/feeds/import/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200).extract().jsonPath();
        Assertions.assertEquals(0, second.getInt("createdCount"),
                "the second identical import creates nothing");
        Assertions.assertEquals(0, second.getInt("updatedCount"),
                "the unchanged checksum makes the second import a strict no-op");
    }

    /**
     * F2 (poison line in the chunk): a row that fails its transaction (an
     * unparseable ROLE) is isolated by the staged fallback into its own error
     * entry while the sound rows pass — 200 with one created and one line error.
     */
    @Test
    void f2_poisonLine_isolatedByStagedFallback() {
        String header = employeesHeader();
        String good = "ZFEMP1|zfemp1|1234|First|Last|zfemp1@zf.test|CASHIER";
        String poison = "ZFEMPBAD|zfempbad|1234|Bad|Role|zfempbad@zf.test|BOGUS";
        String payload = admin().body(header + "\n" + good + "\n" + poison)
                .when().post("/feeds/import/EMPLOYEES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .extract().asString();
        Assertions.assertTrue(payload.contains("\"createdCount\":1"),
                "the sound employee row still passes; body was: " + payload);
        Assertions.assertTrue(payload.contains("Line 3 (ZFEMPBAD): "),
                "the poison row is isolated as its own error entry; body was: " + payload);
    }

    // ==================================================================
    // F3 — StoreCsvResource
    // ==================================================================

    /**
     * F3 (importStores, valid): a complete store file imports and the STORES feed
     * is captured verbatim (visible on the status surface).
     */
    @Test
    void f3_importStores_validCapturesFeed() {
        admin().body(storesCsv("ZFS1")).when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
        admin().when().get("/feeds/import/status")
                .then().statusCode(200)
                .body("find { it.code == 'STORES' }.version", matchesPattern("[a-f0-9]{64}"));
    }

    /**
     * F3 (importStores, CODE absent): the store key column missing is refused.
     */
    @Test
    void f3_importStores_missingCode_badRequest() {
        admin().body("NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY\n")
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: CODE\"}"));
    }

    /**
     * F3 (importStores, a required column absent): POSTAL_CODE missing is refused.
     */
    @Test
    void f3_importStores_missingPostalCode_badRequest() {
        admin().body("CODE|NAME|STREET_LINE1|STREET_LINE2|CITY|COUNTRY\n")
                .when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: POSTAL_CODE\"}"));
    }

    /**
     * F3 (importStores, empty CODE on a line): the row is ignored with the empty
     * key message.
     */
    @Test
    void f3_importStores_emptyCode_lineIgnored() {
        String body = storesHeader() + "\n" + "|X|1|2|59000|Lille|France";
        String payload = admin().body(body).when().post("/feeds/import/STORES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .extract().asString();
        Assertions.assertTrue(payload.contains("Line 2 ignored (empty key 'CODE')"),
                "the empty-key line must be reported; body was: " + payload);
    }

    // ==================================================================
    // F4 — ProductCsvResource
    // ==================================================================

    /**
     * F4 (importProducts, valid): a product file imports and the PRODUCTS feed is
     * captured verbatim.
     */
    @Test
    void f4_importProducts_validCapturesFeed() {
        admin().body(productsCsv("ZFP1")).when().post("/feeds/import/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
        admin().when().get("/feeds/import/status")
                .then().statusCode(200)
                .body("find { it.code == 'PRODUCTS' }.version", matchesPattern("[a-f0-9]{64}"));
    }

    /**
     * F4 (importProducts, EAN absent): the product key column missing is refused.
     */
    @Test
    void f4_importProducts_missingEan_badRequest() {
        admin().body("NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|"
                + "PRODUCT_TYPE|UNIT_NAME|ACTIVE\n")
                .when().post("/feeds/import/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: EAN\"}"));
    }

    /**
     * F4 (importProducts, a required column absent): PRODUCT_TYPE missing is
     * refused.
     */
    @Test
    void f4_importProducts_missingProductType_badRequest() {
        admin().body("EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|"
                + "UNIT_NAME|ACTIVE\n")
                .when().post("/feeds/import/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: PRODUCT_TYPE\"}"));
    }

    /**
     * F4 (importProducts, empty EAN on a line): the row is ignored with the empty
     * key message.
     */
    @Test
    void f4_importProducts_emptyEan_lineIgnored() {
        String body = productsHeader() + "\n" + "|N|D|B|1.000|1.000|UNIT|kg|true";
        String payload = admin().body(body).when().post("/feeds/import/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .extract().asString();
        Assertions.assertTrue(payload.contains("Line 2 ignored (empty key 'EAN')"),
                "the empty-key line must be reported; body was: " + payload);
    }

    // ==================================================================
    // F5 — ProductFamilyCsvResource
    // ==================================================================

    /**
     * F5 (importProductFamilies, valid): a family file with no links imports and
     * the FAMILIES feed is captured verbatim.
     */
    @Test
    void f5_importFamilies_validCapturesFeed() {
        admin().body(familiesCsv("ZFFAM1")).when().post("/feeds/import/FAMILIES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
        admin().when().get("/feeds/import/status")
                .then().statusCode(200)
                .body("find { it.code == 'FAMILIES' }.version", matchesPattern("[a-f0-9]{64}"));
    }

    /**
     * F5 (importProductFamilies, CODE absent): the family key column missing is
     * refused.
     */
    @Test
    void f5_importFamilies_missingCode_badRequest() {
        admin().body("DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n")
                .when().post("/feeds/import/FAMILIES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: CODE\"}"));
    }

    /**
     * F5 (importProductFamilies, a required column absent): SUBFAMILY_CODES
     * missing is refused.
     */
    @Test
    void f5_importFamilies_missingSubfamilyCodes_badRequest() {
        admin().body("CODE|DESCRIPTION|FLAGS|PRODUCT_EANS\n")
                .when().post("/feeds/import/FAMILIES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: SUBFAMILY_CODES\"}"));
    }

    // ==================================================================
    // F6 — VatRateCsvResource
    // ==================================================================

    /**
     * F6 (importVatRates, valid): a VAT file imports (this importer captures no
     * engine feed).
     */
    @Test
    void f6_importVatRates_valid() {
        admin().body("VAT_NUMBER|RATE|LABEL\n91|0.2000|Test Rate")
                .when().post("/feeds/import/VAT_RATES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
    }

    /**
     * F6 (importVatRates, VAT_NUMBER absent): the key column missing is refused.
     */
    @Test
    void f6_importVatRates_missingVatNumber_badRequest() {
        admin().body("RATE|LABEL\n")
                .when().post("/feeds/import/VAT_RATES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: VAT_NUMBER\"}"));
    }

    /**
     * F6 (importVatRates, RATE absent): the required rate column missing is
     * refused.
     */
    @Test
    void f6_importVatRates_missingRate_badRequest() {
        admin().body("VAT_NUMBER|LABEL\n")
                .when().post("/feeds/import/VAT_RATES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: RATE\"}"));
    }

    // ==================================================================
    // F7 — EgalimRegimeCsvResource
    // ==================================================================

    /**
     * F7 (importEgalimRegimes, valid): a regime file imports and the
     * EGALIM_REGIMES feed is captured verbatim.
     */
    @Test
    void f7_importEgalimRegimes_validCapturesFeed() {
        admin().body("CODE|LABEL|CAP_RATE\nZFEG1|Test Regime|0.3400")
                .when().post("/feeds/import/EGALIM_REGIMES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
        admin().when().get("/feeds/import/status")
                .then().statusCode(200)
                .body("find { it.code == 'EGALIM_REGIMES' }.version",
                        matchesPattern("[a-f0-9]{64}"));
    }

    /**
     * F7 (importEgalimRegimes, CODE absent): the key column missing is refused.
     */
    @Test
    void f7_importEgalimRegimes_missingCode_badRequest() {
        admin().body("LABEL|CAP_RATE\n")
                .when().post("/feeds/import/EGALIM_REGIMES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: CODE\"}"));
    }

    /**
     * F7 (importEgalimRegimes, CODE-only header): a header with the key alone is
     * accepted (this importer declares no required column).
     */
    @Test
    void f7_importEgalimRegimes_codeOnlyHeader_accepted() {
        admin().body("CODE\nZFEG2")
                .when().post("/feeds/import/EGALIM_REGIMES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
    }

    // ==================================================================
    // F8 — PriceCsvResource
    // ==================================================================

    /**
     * F8 (importPrices, valid): a price row on a seeded EAN imports and the
     * PRICES feed is captured verbatim.
     */
    @Test
    void f8_importPrices_validCapturesFeed() {
        String body = pricesHeader() + "\n" + SEEDED_EAN + "|1.00|1.20|0.2000|3|"
                + PRICE_START + "|";
        admin().body(body).when().post("/feeds/import/PRICES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
        admin().when().get("/feeds/import/status")
                .then().statusCode(200)
                .body("find { it.code == 'PRICES' }.version", matchesPattern("[a-f0-9]{64}"));
    }

    /**
     * F8 (importPrices, EAN absent): the key column missing is refused.
     */
    @Test
    void f8_importPrices_missingEan_badRequest() {
        admin().body("PRICE_EXCL_TAX|PRICE_INCL_TAX|PRIORITY|START_DATE|END_DATE\n")
                .when().post("/feeds/import/PRICES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: EAN\"}"));
    }

    /**
     * F8 (importPrices, a required column absent): START_DATE missing is refused.
     */
    @Test
    void f8_importPrices_missingStartDate_badRequest() {
        admin().body("EAN|PRICE_EXCL_TAX|PRICE_INCL_TAX|PRIORITY|END_DATE\n")
                .when().post("/feeds/import/PRICES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: START_DATE\"}"));
    }

    // ==================================================================
    // F9 — EmployeeCsvResource
    // ==================================================================

    /**
     * F9 (importEmployees, valid): an employee row imports; no engine feed is
     * captured.
     */
    @Test
    void f9_importEmployees_valid() {
        admin().body(employeesHeader() + "\n"
                + "ZFEMPOK|zfempok|1234|First|Last|zfempok@zf.test|CASHIER")
                .when().post("/feeds/import/EMPLOYEES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
    }

    /**
     * F9 (importEmployees, BADGE_ID absent): the key column missing is refused.
     */
    @Test
    void f9_importEmployees_missingBadgeId_badRequest() {
        admin().body("LOGIN|PIN|FIRST_NAME|LAST_NAME|EMAIL|ROLE\n")
                .when().post("/feeds/import/EMPLOYEES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: BADGE_ID\"}"));
    }

    /**
     * F9 (importEmployees, a required column absent): ROLE missing is refused.
     */
    @Test
    void f9_importEmployees_missingRole_badRequest() {
        admin().body("BADGE_ID|LOGIN|PIN|FIRST_NAME|LAST_NAME|EMAIL\n")
                .when().post("/feeds/import/EMPLOYEES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: ROLE\"}"));
    }

    // ==================================================================
    // F10 — AccountCustomerCsvResource
    // ==================================================================

    /**
     * F10 (importCustomers, delta mode): with no mode the import is a delta and
     * does not block the accounts it does not carry.
     */
    @Test
    void f10_importCustomers_deltaMode_doesNotBlock() {
        admin().body("ACCOUNT_NUMBER|COMPANY_NAME\nZFACC1|Kept Delta")
                .when().post("/feeds/import/CUSTOMERS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
        boolean blocked = QuarkusTransaction.requiringNew().call(
                () -> ((AccountCustomer) AccountCustomer.find("accountNumber", "ZFACC1")
                        .firstResult()).blocked);
        Assertions.assertFalse(blocked, "a delta import must not block an account");
    }

    /**
     * F10 (importCustomers, mode=full): an account absent from a full feed and
     * not already blocked is blocked (case-insensitive mode).
     */
    @Test
    void f10_importCustomers_fullMode_blocksAbsent() {
        admin().body("ACCOUNT_NUMBER|COMPANY_NAME\nZFACC2|To Be Blocked")
                .when().post("/feeds/import/CUSTOMERS")
                .then().statusCode(200);
        admin().body("ACCOUNT_NUMBER|COMPANY_NAME\nZFACC3|Still Here")
                .queryParam("mode", "FULL")
                .when().post("/feeds/import/CUSTOMERS")
                .then().log().ifValidationFails()
                .statusCode(200);
        boolean blocked = QuarkusTransaction.requiringNew().call(
                () -> ((AccountCustomer) AccountCustomer.find("accountNumber", "ZFACC2")
                        .firstResult()).blocked);
        Assertions.assertTrue(blocked,
                "an account absent from a full feed is blocked, not deleted");
    }

    /**
     * F10 (importCustomers, mode=full with empty feed): a full feed carrying no
     * account blocks every unblocked account.
     */
    @Test
    void f10_importCustomers_fullModeEmptyFeed_blocksAll() {
        admin().body("ACCOUNT_NUMBER|COMPANY_NAME\nZFACC4|Lonely")
                .when().post("/feeds/import/CUSTOMERS")
                .then().statusCode(200);
        admin().body("ACCOUNT_NUMBER|COMPANY_NAME\n")
                .queryParam("mode", "full")
                .when().post("/feeds/import/CUSTOMERS")
                .then().log().ifValidationFails()
                .statusCode(200);
        boolean blocked = QuarkusTransaction.requiringNew().call(
                () -> ((AccountCustomer) AccountCustomer.find("accountNumber", "ZFACC4")
                        .firstResult()).blocked);
        Assertions.assertTrue(blocked, "an empty full feed blocks all accounts");
    }

    /**
     * F10 (importCustomers, ACCOUNT_NUMBER absent): the key column missing is
     * refused.
     */
    @Test
    void f10_importCustomers_missingAccountNumber_badRequest() {
        admin().body("COMPANY_NAME\n")
                .when().post("/feeds/import/CUSTOMERS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: ACCOUNT_NUMBER\"}"));
    }

    /**
     * F10 (importCustomers, COMPANY_NAME absent): the required column missing is
     * refused.
     */
    @Test
    void f10_importCustomers_missingCompanyName_badRequest() {
        admin().body("ACCOUNT_NUMBER\n")
                .when().post("/feeds/import/CUSTOMERS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: COMPANY_NAME\"}"));
    }

    // ==================================================================
    // F11 — PostalCodeCsvResource
    // ==================================================================

    /**
     * F11 (importPostalCodes, valid): a postal-code row imports.
     */
    @Test
    void f11_importPostalCodes_valid() {
        admin().body("CODE|PLACE\nZF59000|LILLE TEST")
                .when().post("/feeds/import/POSTAL_CODES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
    }

    /**
     * F11 (importPostalCodes, CODE absent): the key column missing is refused.
     */
    @Test
    void f11_importPostalCodes_missingCode_badRequest() {
        admin().body("PLACE\n")
                .when().post("/feeds/import/POSTAL_CODES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: CODE\"}"));
    }

    /**
     * F11 (importPostalCodes, PLACE absent): the required column missing is
     * refused.
     */
    @Test
    void f11_importPostalCodes_missingPlace_badRequest() {
        admin().body("CODE\n")
                .when().post("/feeds/import/POSTAL_CODES")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: PLACE\"}"));
    }

    // ==================================================================
    // F12 — SafeReceiptCsvResource
    // ==================================================================

    /**
     * F12 (importReceipts, valid): a receipt row imports into an open accounting
     * period. The open period is a safe-domain precondition created here so the
     * import door's nominal path can be observed at the [E] level.
     */
    @Test
    void f12_importReceipts_valid() {
        QuarkusTransaction.requiringNew().run(() -> {
            AccountingPeriod period = new AccountingPeriod();
            period.label = "ZFPERIOD";
            period.startDate = LocalDate.of(2026, 9, 1);
            period.persist();
        });
        admin().body("REFERENCE|LABEL|AMOUNT|DATE\nZFREC1|Fuel takings|10.00|2026-09-28")
                .when().post("/feeds/import/SAFE_RECEIPTS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1));
    }

    /**
     * F12 (importReceipts, REFERENCE absent): the key column missing is refused.
     */
    @Test
    void f12_importReceipts_missingReference_badRequest() {
        admin().body("LABEL|AMOUNT|DATE\n")
                .when().post("/feeds/import/SAFE_RECEIPTS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: REFERENCE\"}"));
    }

    /**
     * F12 (importReceipts, AMOUNT absent): the required column missing is refused.
     */
    @Test
    void f12_importReceipts_missingAmount_badRequest() {
        admin().body("REFERENCE|LABEL|DATE\n")
                .when().post("/feeds/import/SAFE_RECEIPTS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Missing required columns: AMOUNT\"}"));
    }

    // ==================================================================
    // F13 — NomenclatureCsvResource (its own mechanics, outside ImporterCsv)
    // ==================================================================

    /**
     * F13 (importNomenclature, valid): a well-formed file loaded into a fresh
     * scheme returns 200 with the created/level counts.
     */
    @Test
    void f13_importNomenclature_valid() {
        admin().body("NIVEAU|NUMERO|LIBELLE\n0|ZY|Activite Test")
                .queryParam("scheme", "ZFSCHEME")
                .queryParam("label", "Test Scheme")
                .queryParam("enseigne", "ITM")
                .when().post("/feeds/import/NOMENCLATURE")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("createdCount", equalTo(1))
                .body("levelCount", equalTo(1));
    }

    /**
     * F13 (importNomenclature, scheme blank): a missing scheme is refused with
     * the exact obligatory-scheme complaint.
     */
    @Test
    void f13_importNomenclature_schemeBlank_badRequest() {
        admin().body("NIVEAU|NUMERO|LIBELLE\n0|ZY|Activite Test")
                .when().post("/feeds/import/NOMENCLATURE")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body("errors[0]", equalTo("Le paramètre scheme est obligatoire."));
    }

    /**
     * F13 (importNomenclature, empty file): a body with no header is refused with
     * the "Fichier vide." complaint.
     */
    @Test
    void f13_importNomenclature_emptyFile_badRequest() {
        admin().body("")
                .queryParam("scheme", "ZFSCHEME")
                .when().post("/feeds/import/NOMENCLATURE")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body("errors[0]", equalTo("Fichier vide."));
    }

    /**
     * F13 (importNomenclature, a column absent): a header missing LIBELLE is
     * refused naming that column.
     */
    @Test
    void f13_importNomenclature_missingColumn_badRequest() {
        admin().body("NIVEAU|NUMERO\n0|ZY")
                .queryParam("scheme", "ZFSCHEME")
                .when().post("/feeds/import/NOMENCLATURE")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body("errors[0]", equalTo("Colonne LIBELLE absente."));
    }

    /**
     * F13 (importNomenclature, unreadable level): a data line whose level cell is
     * not a number is refused naming the line.
     */
    @Test
    void f13_importNomenclature_unreadableLevel_badRequest() {
        admin().body("NIVEAU|NUMERO|LIBELLE\nx|ZY|Bad Level")
                .queryParam("scheme", "ZFSCHEME")
                .when().post("/feeds/import/NOMENCLATURE")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body("errors[0]", equalTo("Ligne 2 : niveau illisible."));
    }

    /**
     * F13 (importNomenclature, business errors): a file whose level lengths
     * disagree is a non-clean outcome and answers 409 with the outcome errors.
     */
    @Test
    void f13_importNomenclature_businessErrors_conflict() {
        admin().body("NIVEAU|NUMERO|LIBELLE\n0|AB|Alpha\n0|ABC|Beta")
                .queryParam("scheme", "ZFSCHEME")
                .queryParam("label", "Test Scheme")
                .queryParam("enseigne", "ITM")
                .when().post("/feeds/import/NOMENCLATURE")
                .then().log().ifValidationFails()
                .statusCode(409);
    }

    // ==================================================================
    // F14 — engine verbatim codes
    // ==================================================================

    /**
     * F14 (STORE_GROUPS): a non-empty body is captured verbatim; 200 echoes the
     * code and a SHA-256 version.
     */
    @Test
    void f14_storeGroups_verbatim() {
        assertVerbatim("STORE_GROUPS");
    }

    /**
     * F14 (CATEGORY_STORAGES): a non-empty body is captured verbatim.
     */
    @Test
    void f14_categoryStorages_verbatim() {
        assertVerbatim("CATEGORY_STORAGES");
    }

    /**
     * F14 (BUDGET_GROUPS): a non-empty body is captured verbatim (normally
     * projected from campaigns, but the door accepts a verbatim parcel too).
     */
    @Test
    void f14_budgetGroups_verbatim() {
        assertVerbatim("BUDGET_GROUPS");
    }

    /**
     * F14 (OFFERS): a non-empty body is captured verbatim.
     */
    @Test
    void f14_offers_verbatim() {
        assertVerbatim("OFFERS");
    }

    /**
     * F14 (each, empty body): an engine code posted with a blank body is refused
     * with the empty-body message (checked on STORE_GROUPS).
     */
    @Test
    void f14_emptyBody_badRequest() {
        admin().body("   ")
                .when().post("/feeds/import/OFFERS")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("{\"error\":\"Empty feed body\"}"));
    }

    /**
     * F14 (each, same content re-posted): the same bytes yield the same version,
     * a no-op store (checked on STORE_GROUPS).
     */
    @Test
    void f14_sameContentReposted_sameVersion() {
        String v1 = admin().body("SAME CONTENT\n").when().post("/feeds/import/STORE_GROUPS")
                .then().statusCode(200).extract().path("version");
        String v2 = admin().body("SAME CONTENT\n").when().post("/feeds/import/STORE_GROUPS")
                .then().statusCode(200).extract().path("version");
        Assertions.assertEquals(v1, v2, "identical content keeps the same SHA-256 version");
    }

    /**
     * F14 (each, modified content re-posted): changed bytes yield a new version
     * (checked on STORE_GROUPS).
     */
    @Test
    void f14_modifiedContentReposted_newVersion() {
        String v1 = admin().body("FIRST CONTENT\n").when().post("/feeds/import/STORE_GROUPS")
                .then().statusCode(200).extract().path("version");
        String v2 = admin().body("SECOND CONTENT\n").when().post("/feeds/import/STORE_GROUPS")
                .then().statusCode(200).extract().path("version");
        Assertions.assertNotEquals(v1, v2, "changed content produces a new SHA-256 version");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Posts a non-empty verbatim body under an engine code and asserts the code
     * and version come back.
     *
     * @param code the engine catalog code
     */
    private void assertVerbatim(String code) {
        admin().body(code + " PARCEL CONTENT\n")
                .when().post("/feeds/import/" + code)
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("code", equalTo(code))
                .body("version", matchesPattern("[a-f0-9]{64}"));
    }

    /**
     * The STORES importer header.
     *
     * @return the pipe-separated STORES header line
     */
    private String storesHeader() {
        return "CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY";
    }

    /**
     * One valid STORES data row for a given code.
     *
     * @param code the store code
     * @return the pipe-separated store row
     */
    private String storeRow(String code) {
        return code + "|Store " + code + "|1 rue Test|ZI Nord|59000|Lille|France";
    }

    /**
     * A one-row valid STORES file.
     *
     * @param code the store code
     * @return the header plus one row
     */
    private String storesCsv(String code) {
        return storesHeader() + "\n" + storeRow(code);
    }

    /**
     * The PRODUCTS importer header.
     *
     * @return the pipe-separated PRODUCTS header line
     */
    private String productsHeader() {
        return "EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|"
                + "PRODUCT_TYPE|UNIT_NAME|ACTIVE";
    }

    /**
     * A one-row valid PRODUCTS file.
     *
     * @param ean the product EAN
     * @return the header plus one row
     */
    private String productsCsv(String ean) {
        return productsHeader() + "\n" + ean + "|Test Product|Desc|BrandT|1.000|1.000|UNIT|kg|true";
    }

    /**
     * A one-row valid FAMILIES file with no links.
     *
     * @param code the family code
     * @return the header plus one row
     */
    private String familiesCsv(String code) {
        return "CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n" + code + "|Test Family|||";
    }

    /**
     * The PRICES importer header (VAT resolved by rate).
     *
     * @return the pipe-separated PRICES header line
     */
    private String pricesHeader() {
        return "EAN|PRICE_EXCL_TAX|PRICE_INCL_TAX|VAT_RATE|PRIORITY|START_DATE|END_DATE";
    }

    /**
     * The EMPLOYEES importer header.
     *
     * @return the pipe-separated EMPLOYEES header line
     */
    private String employeesHeader() {
        return "BADGE_ID|LOGIN|PIN|FIRST_NAME|LAST_NAME|EMAIL|ROLE";
    }
}
