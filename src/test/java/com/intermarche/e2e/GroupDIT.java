package com.intermarche.e2e;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Group D — the referential export the registers pull:
 * {@code GET /api/referential/versions} (per-domain fingerprints) and
 * {@code GET /api/referential/{domain}} (paged snapshots), exercised end to
 * end against a booted store node (H2, no register).
 *
 * <p>Covers the {@code [E]} scenarios of catalogue D1 and D2. These surfaces are
 * NOT behind HTTP Basic: the in-code {@code gate} guards them with the node role
 * (store OR central) and the optional shared token. This class boots the node in
 * its default {@code store} role with a configured {@code pos.sync.token}, so the
 * success calls carry the {@code X-Sync-Token} header and the token-mismatch case
 * gets 401.
 *
 * <p>Two cases are NOT reachable from a store-role boot and are covered elsewhere:
 * the off-role 403 ("Ce nœud n'a pas le rôle store ou central") needs a
 * register-role boot (dedicated {@code SyncRoleGuardIT}); and the central-role
 * 4-domain fingerprint list needs a central-role boot. Both are listed in the lot
 * report rather than simulated here.
 *
 * <p>The endpoints are read-only, so this class creates nothing and needs no
 * cleanup.
 */
@QuarkusTest
@TestProfile(GroupDIT.Profile.class)
class GroupDIT {

    /**
     * The group's own profile: a distinct class forces a fresh boot and H2 base,
     * and it configures the shared synchronization token the gate checks.
     */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Configures the shared synchronization token (the node keeps its default
         * store role).
         *
         * @return the token configuration override
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pos.sync.token", "e2e-sync-token");
        }
    }

    /** The shared synchronization token the registers must present. */
    @ConfigProperty(name = "pos.sync.token")
    String syncToken;

    // ==================================================================
    // D1 — per-domain fingerprints
    // ==================================================================

    /**
     * D1 (versions, bad token): a discordant token is refused with 401 and the
     * exact "Jeton invalide" message.
     */
    @Test
    void d1_versions_badToken_unauthorized() {
        given().header("X-Sync-Token", "wrong-token")
                .when().get("/api/referential/versions")
                .then().log().ifValidationFails()
                .statusCode(401)
                .body(equalTo("Jeton invalide"));
    }

    /**
     * D1 (versions, store role): the store node returns 200 and a SHA-256 map of
     * exactly the 16 store domains.
     */
    @Test
    void d1_versions_storeRole_listsSixteenDomains() {
        given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/versions")
                .then().log().ifValidationFails()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", equalTo(16))
                .body("PRODUCTS", notNullValue())
                .body("PRICES", notNullValue())
                .body("ENGINE_FEEDS", notNullValue());
    }

    /**
     * D1 (versions, stability): two calls with no change in between return
     * identical fingerprints (the 60-second cache serves the same digests).
     */
    @Test
    void d1_versions_stableAcrossCalls() {
        Map<?, ?> first = given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/versions")
                .then().statusCode(200).extract().as(Map.class);
        Map<?, ?> second = given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/versions")
                .then().statusCode(200).extract().as(Map.class);
        Assertions.assertEquals(first, second,
                "two consecutive fingerprint reads with no change must be identical");
    }

    /**
     * D1 (versions, empty domain): an empty domain still carries a stable digest
     * — POSTAL_CODES is unseeded yet present in the map with a non-null value.
     */
    @Test
    void d1_versions_emptyDomainHasStableDigest() {
        given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/versions")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("POSTAL_CODES", notNullValue());
    }

    // ==================================================================
    // D2 — paged snapshot
    // ==================================================================

    /**
     * D2 (page, bad token): a discordant token is refused with 401.
     */
    @Test
    void d2_page_badToken_unauthorized() {
        given().header("X-Sync-Token", "wrong-token")
                .when().get("/api/referential/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(401)
                .body(equalTo("Jeton invalide"));
    }

    /**
     * D2 (page, nominal): a known populated domain returns 200 and a non-empty
     * canonical-order list.
     */
    @Test
    void d2_page_nominal_returnsList() {
        given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThan(0));
    }

    /**
     * D2 (page, case normalized): a lower-case domain is upper-cased and served.
     */
    @Test
    void d2_page_lowerCaseDomain_normalized() {
        given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/products")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    /**
     * D2 (page, mixed-case domain): a mixed-case spelling is normalized too.
     */
    @Test
    void d2_page_mixedCaseDomain_normalized() {
        given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/Products")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    /**
     * D2 (page, empty domain): an unseeded domain returns 200 and an empty list,
     * never an error.
     */
    @Test
    void d2_page_emptyDomain_returnsEmptyList() {
        given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/POSTAL_CODES")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    /**
     * D2 (page, beyond last): a page index past the data returns 200 and an empty
     * list.
     */
    @Test
    void d2_page_beyondLast_returnsEmptyList() {
        given().header("X-Sync-Token", syncToken)
                .queryParam("page", 9999)
                .when().get("/api/referential/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    /**
     * D2 (page, unknown domain): a code absent from the switch is refused with
     * 400 and the exact "Domaine inconnu" message (upper-cased) — the code
     * answers 400, not the 404 the doc once suggested.
     */
    @Test
    void d2_page_unknownDomain_badRequest() {
        given().header("X-Sync-Token", syncToken)
                .when().get("/api/referential/nope")
                .then().log().ifValidationFails()
                .statusCode(400)
                .body(equalTo("Domaine inconnu: NOPE"));
    }

    /**
     * D2 (page, size ≤ 0): a non-positive size is clamped to the 1000 default and
     * the request still succeeds.
     */
    @Test
    void d2_page_nonPositiveSize_clampedToDefault() {
        given().header("X-Sync-Token", syncToken)
                .queryParam("size", 0)
                .when().get("/api/referential/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    /**
     * D2 (page, size &gt; 5000): an oversized size is clamped to the 1000 default
     * and the request still succeeds.
     */
    @Test
    void d2_page_oversizedSize_clampedToDefault() {
        given().header("X-Sync-Token", syncToken)
                .queryParam("size", 6000)
                .when().get("/api/referential/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    /**
     * D2 (page, negative page): a negative page index is floored to 0 and returns
     * the same first page as page 0.
     */
    @Test
    void d2_page_negativePage_flooredToZero() {
        int fromZero = given().header("X-Sync-Token", syncToken)
                .queryParam("page", 0)
                .when().get("/api/referential/PRODUCTS")
                .then().statusCode(200).extract().as(java.util.List.class).size();
        int fromNegative = given().header("X-Sync-Token", syncToken)
                .queryParam("page", -1)
                .when().get("/api/referential/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(200).extract().as(java.util.List.class).size();
        Assertions.assertEquals(fromZero, fromNegative,
                "a negative page index must be floored to page 0");
    }
}
