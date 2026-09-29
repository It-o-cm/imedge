package com.intermarche.e2e;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The off-role guard shared by the register-synchronization surfaces (group C,
 * {@code /api/sync/**}) and the referential-export surfaces (group D,
 * {@code /api/referential/**}).
 *
 * <p>Both guards read {@code pos.role} at boot, so the refusal can only be
 * observed on a node that is NOT a store — which is incompatible with the
 * store-role upserts of {@code GroupCIT} and the store-role reads of
 * {@code GroupDIT}. This class boots the node under the {@code register} role
 * precisely to prove the two guards reject an off-role node, with the exact
 * messages the catalogue pins (C: "Ce nœud n'a pas le rôle store"; D: "Ce nœud
 * n'a pas le rôle store ou central"). It is the group-C/D counterpart of
 * {@code SecurityExtensionLivenessIT}.
 */
@QuarkusTest
@TestProfile(SyncRoleGuardIT.Profile.class)
class SyncRoleGuardIT {

    /**
     * Boots this node under the {@code register} role, the one role the sync and
     * referential guards refuse. A distinct profile class forces a fresh boot.
     */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Forces the register role for the whole class.
         *
         * @return the role override
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pos.role", "register");
        }
    }

    /**
     * C (handle guard): an ingestion POST on an off-role node is refused with
     * 403 and the store-role message, before any token or body is examined.
     */
    @Test
    void c_ingestSession_offRole_forbidden() {
        given().contentType(ContentType.JSON).body("{\"sessionNumber\":\"ZG-1\"}")
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("Ce nœud n'a pas le rôle store"));
    }

    /**
     * C (balance-ticket GET inline guard): the reading route carries the same
     * off-role refusal as the POSTs.
     */
    @Test
    void c_consumeBalanceTicket_offRole_forbidden() {
        given().when().get("/api/sync/balance-ticket/ZG-REF")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("Ce nœud n'a pas le rôle store"));
    }

    /**
     * C (duplicata GET inline guard): the duplicata route carries the same
     * off-role refusal.
     */
    @Test
    void c_ticketDuplicate_offRole_forbidden() {
        given().when().get("/api/sync/ticket/ZG-1/duplicata")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("Ce nœud n'a pas le rôle store"));
    }

    /**
     * D (gate): the referential versions endpoint refuses an off-role node with
     * 403 and the store-or-central message.
     */
    @Test
    void d_versions_offRole_forbidden() {
        given().when().get("/api/referential/versions")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("Ce nœud n'a pas le rôle store ou central"));
    }

    /**
     * D (gate): the referential page endpoint refuses an off-role node with the
     * same store-or-central message.
     */
    @Test
    void d_page_offRole_forbidden() {
        given().when().get("/api/referential/PRODUCTS")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("Ce nœud n'a pas le rôle store ou central"));
    }

    /**
     * B3 (commands guard): a register node is forbidden from distributing orders;
     * the fetch route answers 403 with the dedicated JSON message (store and
     * central pass this guard — only register is refused).
     */
    @Test
    void b3_commandsFetch_registerForbidden() {
        given().when().get("/api/commands/ZGT01")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("{\"error\":\"Ce nœud ne distribue pas d'ordres\"}"));
    }

    /**
     * B3 (commands guard): the acknowledge route carries the same register
     * refusal.
     */
    @Test
    void b3_commandsAck_registerForbidden() {
        given().contentType(ContentType.JSON)
                .when().post("/api/commands/ZGCMD/ack")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("{\"error\":\"Ce nœud ne distribue pas d'ordres\"}"));
    }

    /**
     * B4 (supervisor call guard): the supervisor-call route is store-only, so a
     * register node is refused with the store-role message.
     */
    @Test
    void b4_supervisorCall_offRole_forbidden() {
        given().contentType(ContentType.JSON).body("{\"terminalId\":\"ZGT01\"}")
                .when().post("/api/supervisor/call")
                .then().log().ifValidationFails()
                .statusCode(403)
                .body(equalTo("Ce nœud n'a pas le rôle store"));
    }
}
