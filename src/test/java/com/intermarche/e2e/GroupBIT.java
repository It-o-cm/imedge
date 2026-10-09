package com.intermarche.e2e;

import com.intermarche.edge.domain.sync.RegisterCommand;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Group B — node role and shared-token guards — the parts NOT already proven
 * elsewhere: B3 (the register order book {@code /api/commands/**}) and B4 (the
 * supervisor call {@code /api/supervisor/call}).
 *
 * <p>B1 ({@code /api/sync/**}) and B2 ({@code /api/referential/**}) are the same
 * role/token gate already exercised by {@code GroupCIT} (sync upserts + token
 * 401), {@code GroupDIT} (referential reads + token 401) and
 * {@code SyncRoleGuardIT} (off-role 403); they are not duplicated here.
 *
 * <p>These are {@code /api/**} machine surfaces gated in code (role + optional
 * token), not behind HTTP Basic or the form. This class boots under the store
 * role and a configured token, so it covers the authorized paths and the token
 * refusal. The register-forbidden 403 of B3 (register may not distribute orders)
 * and the off-role 403 of B4 need a register boot and are deferred to
 * {@code SyncRoleGuardIT}.
 */
@QuarkusTest
@TestProfile(GroupBIT.Profile.class)
class GroupBIT {

    /**
     * Boots the node as a store with a configured shared token, so the token
     * guard is live and the register order book serves.
     */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Sets the shared synchronization token for the class.
         *
         * @return the token override
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pos.sync.token", "e2e-sync-token");
        }
    }

    /** The configured shared token, read from the test profile. */
    @ConfigProperty(name = "pos.sync.token")
    String token;

    /**
     * Removes the register commands created by these tests.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(
                () -> RegisterCommand.delete("terminalId like ?1 or commandUid like ?2",
                        "ZB%", "ZB%"));
    }

    /**
     * Persists one pending register command for a terminal.
     *
     * @param uid the command uid
     * @param terminal the terminal id
     */
    private void issueCommand(String uid, String terminal) {
        QuarkusTransaction.requiringNew().run(() -> {
            RegisterCommand command = new RegisterCommand();
            command.commandUid = uid;
            command.terminalId = terminal;
            command.commandType = RegisterCommand.CommandType.CLOSE_SESSION;
            command.issuedBy = "admin";
            command.reason = "e2e";
            command.persist();
        });
    }

    // ==================================================================
    // B3 — register order book /api/commands/**
    // ==================================================================

    /**
     * B3 (pending, store role + token): a register is handed its unacknowledged
     * orders as a JSON array carrying uid, type, issuedBy and reason.
     */
    @Test
    void b3_pending_listsUnacknowledgedOrders() {
        issueCommand("ZBCMD1", "ZBT01");
        given().header("X-Sync-Token", token)
                .when().get("/api/commands/ZBT01")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("uid", hasItem("ZBCMD1"))
                .body("find { it.uid == 'ZBCMD1' }.type", equalTo("CLOSE_SESSION"))
                .body("find { it.uid == 'ZBCMD1' }.issuedBy", equalTo("admin"))
                .body("find { it.uid == 'ZBCMD1' }.reason", equalTo("e2e"));
    }

    /**
     * B3 (token guard): a wrong token is refused with 401 and the sync-token
     * message, before any order is read.
     */
    @Test
    void b3_pending_badToken_unauthorized() {
        given().header("X-Sync-Token", "wrong")
                .when().get("/api/commands/ZBT01")
                .then().log().ifValidationFails()
                .statusCode(401)
                .body(equalTo("{\"error\":\"Jeton de synchronisation invalide\"}"));
    }

    /**
     * B3 (ack, stamped): acknowledging a pending order stamps it and answers
     * {@code acknowledged:true}.
     */
    @Test
    void b3_ack_pendingOrder_acknowledged() {
        issueCommand("ZBCMD2", "ZBT02");
        given().header("X-Sync-Token", token)
                .when().post("/api/commands/ZBCMD2/ack")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body(equalTo("{\"acknowledged\":true}"));
    }

    /**
     * B3 (ack, already stamped): acknowledging the same order a second time is
     * 404 {@code acknowledged:false} (no resurrection).
     */
    @Test
    void b3_ack_alreadyStamped_notFound() {
        issueCommand("ZBCMD3", "ZBT03");
        given().header("X-Sync-Token", token)
                .when().post("/api/commands/ZBCMD3/ack")
                .then().statusCode(200);
        given().header("X-Sync-Token", token)
                .when().post("/api/commands/ZBCMD3/ack")
                .then().log().ifValidationFails()
                .statusCode(404)
                .body(equalTo("{\"acknowledged\":false}"));
    }

    /**
     * B3 (ack, unknown order): acknowledging an unknown uid is 404
     * {@code acknowledged:false}.
     */
    @Test
    void b3_ack_unknownOrder_notFound() {
        given().header("X-Sync-Token", token)
                .when().post("/api/commands/ZBNOPE/ack")
                .then().log().ifValidationFails()
                .statusCode(404)
                .body(equalTo("{\"acknowledged\":false}"));
    }

    // ==================================================================
    // B4 — supervisor call /api/supervisor/call
    // ==================================================================

    /**
     * B4 (store role + token): a supervisor call with a valid body and the right
     * token is recorded and answers 200 "OK".
     */
    @Test
    void b4_supervisorCall_recorded() {
        given().header("X-Sync-Token", token)
                .contentType(ContentType.JSON)
                .body("{\"terminalId\":\"ZBT09\",\"operator\":\"Jean\",\"reason\":\"Assistance\"}")
                .when().post("/api/supervisor/call")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body(equalTo("OK"));
    }

    /**
     * B4 (token guard): a wrong token is refused with 401 and the referential
     * token message ("Jeton invalide", distinct from the sync surfaces).
     */
    @Test
    void b4_supervisorCall_badToken_unauthorized() {
        given().header("X-Sync-Token", "wrong")
                .contentType(ContentType.JSON)
                .body("{\"terminalId\":\"ZBT09\",\"operator\":\"Jean\",\"reason\":\"Assistance\"}")
                .when().post("/api/supervisor/call")
                .then().log().ifValidationFails()
                .statusCode(401)
                .body(equalTo("Jeton invalide"));
    }
}
