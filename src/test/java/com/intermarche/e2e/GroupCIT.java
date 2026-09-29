package com.intermarche.e2e;

import com.intermarche.pos.domain.barcode.BalanceTicket;
import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.sale.Invoice;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Group C — ingestion of the register push ({@code POST /api/sync/**} and the
 * two write-through GETs), exercised end to end against a booted store node
 * (H2, no register).
 *
 * <p>Covers the {@code [E]} scenarios of C1–C10: the common role/token gate,
 * the idempotent upserts, the retryable 409 resolution failures and the enum
 * 500s that a node exhibits when synthetic payloads are pushed to it. The
 * {@code [R]} colour (a real register emitting) is not needed: the payloads are
 * built here as JSON strings.
 *
 * <p>The {@code /api/sync/**} surfaces are NOT behind HTTP Basic (only
 * {@code /feeds/import/*} and {@code /graphql} are); authentication is the
 * in-code role + shared-token gate. This class boots with the store role
 * (default) and a configured {@code pos.sync.token}, so the token-401 case is
 * reachable and the success calls carry the header.
 *
 * <p>The "off-role → 403" case shared by every Cx is NOT tested here: it needs
 * a register-role boot that would break the store-role upserts of this class.
 * It is covered by the dedicated {@code SyncRoleGuardIT} register-role class.
 */
@QuarkusTest
@TestProfile(GroupCIT.Profile.class)
class GroupCIT {

    /**
     * A profile of this class's own — a distinct class forces a fresh boot and
     * H2 base for group C — declaring the shared synchronization token so the
     * token-401 case is reachable and the success calls have a value to present.
     */
    public static class Profile implements QuarkusTestProfile {

        /**
         * Declares the shared ingestion token as configuration.
         *
         * @return the config overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pos.sync.token", "e2e-sync-token");
        }
    }

    /** The shared token this node expects, read from the test profile config. */
    @ConfigProperty(name = "pos.sync.token")
    String syncToken;

    /**
     * Removes every row the group-C tests create, children before parents, so a
     * method that dies mid-way cannot poison the next one. Tickets, refunds and
     * balance tickets are deleted per instance so their orphan-removal children
     * (lines, payments) go with them.
     */
    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            Invoice.delete("documentNumber like ?1", "ZC%");
            Refund.<Refund>find("refundNumber like ?1", "ZC%").list().forEach(r -> r.delete());
            Ticket.<Ticket>find("ticketNumber like ?1", "ZC%").list().forEach(t -> t.delete());
            CashMovement.delete("movementUid like ?1", "ZC%");
            TechnicalEvent.delete("eventUid like ?1", "ZC%");
            BalanceTicket.<BalanceTicket>find("reference like ?1", "ZC%").list()
                    .forEach(b -> b.delete());
            AccountCustomer.delete("accountNumber like ?1", "ZC%");
            CashSession.delete("sessionNumber like ?1", "ZC%");
        });
    }

    // ------------------------------------------------------------------
    // Request builders
    // ------------------------------------------------------------------

    /**
     * A JSON request carrying the valid synchronization token.
     *
     * @return the token-bearing request specification
     */
    private RequestSpecification sync() {
        return given().header("X-Sync-Token", syncToken).contentType(ContentType.JSON);
    }

    /**
     * A JSON request carrying a WRONG synchronization token.
     *
     * @return the bad-token request specification
     */
    private RequestSpecification badToken() {
        return given().header("X-Sync-Token", "wrong-token").contentType(ContentType.JSON);
    }

    // ==================================================================
    // C1 — session
    // ==================================================================

    /**
     * C1 (garde jeton): a wrong token on the session endpoint is refused 401
     * with the shared gate message — representative of the {@code handle} gate
     * used by every POST here.
     */
    @Test
    void c1_session_badToken_unauthorized() {
        badToken().body(sessionJson("ZCSESS0", "OPEN", "jdupont", null))
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(401)
                .body(equalTo("Jeton de synchronisation invalide"));
    }

    /**
     * C1 (upsert): a never-seen session is created and answered 200 OK.
     */
    @Test
    void c1_session_upsert_created() {
        sync().body(sessionJson("ZCSESS1", "OPEN", "jdupont", null))
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> CashSession.count("sessionNumber", "ZCSESS1"));
        Assertions.assertEquals(1, count, "the session was created exactly once");
    }

    /**
     * C1 (idempotence): the same session body replayed lands on the same row.
     */
    @Test
    void c1_session_idempotent_replay() {
        String body = sessionJson("ZCSESS2", "OPEN", "jdupont", null);
        sync().body(body).when().post("/api/sync/session").then().statusCode(200);
        sync().body(body).when().post("/api/sync/session").then()
                .log().ifValidationFails().statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> CashSession.count("sessionNumber", "ZCSESS2"));
        Assertions.assertEquals(1, count, "replaying the same session creates no duplicate");
    }

    /**
     * C1 (opening cashier unknown): an unknown opening cashier is a retryable
     * 409 naming the login.
     */
    @Test
    void c1_session_openingCashierUnknown_conflict() {
        sync().body(sessionJson("ZCSESS3", "OPEN", "zc_ghost", null))
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Employé inconnu sur le nœud magasin: zc_ghost"));
    }

    /**
     * C1 (closing cashier unknown): a non-null unknown closing cashier is a
     * retryable 409 naming the login.
     */
    @Test
    void c1_session_closingCashierUnknown_conflict() {
        sync().body(sessionJson("ZCSESS4", "OPEN", "jdupont", "zc_ghost"))
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Employé inconnu sur le nœud magasin: zc_ghost"));
    }

    /**
     * C1 (null tolerance): a null closing cashier and a null closing date are
     * accepted (both columns are nullable), the opening fields being present.
     */
    @Test
    void c1_session_nullClosingCashier_accepted() {
        sync().body("{\"sessionNumber\":\"ZCSESS5\",\"terminalId\":\"C01\",\"status\":\"OPEN\","
                + "\"openingCashierLogin\":\"jdupont\",\"openingFloat\":50.00,"
                + "\"openingDate\":\"2026-09-28T08:00:00\"}")
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C1 (null tolerance, DISCREPANCY): the catalogue states a null
     * {@code openingDate} is accepted ("parse tolère null"), so this test
     * expects 200. It is left RED on purpose: {@code CashSession.openingDate}
     * is {@code @NotNull}, so the node answers 500 (bean-validation
     * {@code ne doit pas être nul} on {@code openingDate}). See the lot report.
     */
    @Test
    void c1_session_nullOpeningDate_catalogueExpects200() {
        sync().body("{\"sessionNumber\":\"ZCSESS6\",\"terminalId\":\"C01\",\"status\":\"OPEN\","
                + "\"openingCashierLogin\":\"jdupont\",\"openingFloat\":50.00}")
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C1 (invalid enum): a status outside {@code SessionStatus} fails
     * {@code valueOf} and answers 500.
     */
    @Test
    void c1_session_invalidStatus_serverError() {
        sync().body(sessionJson("ZCSESS6", "BOGUS", "jdupont", null))
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(500);
    }

    /**
     * C1 (malformed body): an unparseable JSON body is refused 400 before the
     * gate.
     */
    @Test
    void c1_session_malformedBody_badRequest() {
        sync().body("{ not json")
                .when().post("/api/sync/session")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C2 — ticket
    // ==================================================================

    /**
     * C2 (upsert graph): a fresh ticket with a line and a payment persists and
     * answers 200 OK.
     */
    @Test
    void c2_ticket_upsertGraph_created() {
        sync().body(ticketJson("ZCTK1", "0101", "jdupont", null, true, "CARD"))
                .when().post("/api/sync/ticket")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> Ticket.count("ticketNumber", "ZCTK1"));
        Assertions.assertEquals(1, count, "the ticket was created exactly once");
    }

    /**
     * C2 (idempotence): the same ticket replayed converges to one row.
     */
    @Test
    void c2_ticket_idempotent_replay() {
        String body = ticketJson("ZCTK2", "0101", "jdupont", null, true, "CARD");
        sync().body(body).when().post("/api/sync/ticket").then().statusCode(200);
        sync().body(body).when().post("/api/sync/ticket").then()
                .log().ifValidationFails().statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> Ticket.count("ticketNumber", "ZCTK2"));
        Assertions.assertEquals(1, count, "replaying the same ticket creates no duplicate");
    }

    /**
     * C2 (store fallback): an unknown store code with a single local store
     * present is accepted (attached to that store).
     */
    @Test
    void c2_ticket_unknownStoreCode_fallsBackToLocalStore() {
        sync().body(ticketJson("ZCTK3", "9999", "jdupont", null, false, null))
                .when().post("/api/sync/ticket")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C2 (cashier unknown): an unknown cashier is a retryable 409 naming the
     * login.
     */
    @Test
    void c2_ticket_cashierUnknown_conflict() {
        sync().body(ticketJson("ZCTK4", "0101", "zc_ghost", null, false, null))
                .when().post("/api/sync/ticket")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Employé inconnu sur le nœud magasin: zc_ghost"));
    }

    /**
     * C2 (unknown payment method): a payment key with no factory and no voucher
     * label is a retryable 409 naming the key.
     */
    @Test
    void c2_ticket_unknownPaymentMethod_conflict() {
        sync().body(ticketJson("ZCTK5", "0101", "jdupont", null, true, "ZC_NOPE"))
                .when().post("/api/sync/ticket")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Mode de paiement inconnu: ZC_NOPE"));
    }

    /**
     * C2 (ticket before its session — the real behaviour): a ticket naming an
     * absent session is accepted with a null session link, not a 409.
     */
    @Test
    void c2_ticket_beforeItsSession_acceptedWithNullSession() {
        sync().body(ticketJson("ZCTK6", "0101", "jdupont", "ZCNOSESS", false, null))
                .when().post("/api/sync/ticket")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C2 (invalid enum): a status outside {@code TicketStatus} answers 500.
     */
    @Test
    void c2_ticket_invalidStatus_serverError() {
        sync().body(ticketJson("ZCTK7", "0101", "jdupont", null, false, null)
                .replace("\"status\":\"CLOSED\"", "\"status\":\"BOGUS\""))
                .when().post("/api/sync/ticket")
                .then().log().ifValidationFails()
                .statusCode(500);
    }

    /**
     * C2 (malformed body): an unparseable ticket body is refused 400.
     */
    @Test
    void c2_ticket_malformedBody_badRequest() {
        sync().body("{ nope")
                .when().post("/api/sync/ticket")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C3 — movement
    // ==================================================================

    /**
     * C3 (upsert): a fresh cash movement persists and answers 200 OK.
     */
    @Test
    void c3_movement_upsert_created() {
        sync().body(movementJson("ZCMV1", "WITHDRAWAL", "jdupont", null))
                .when().post("/api/sync/movement")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> CashMovement.count("movementUid", "ZCMV1"));
        Assertions.assertEquals(1, count, "the movement was created exactly once");
    }

    /**
     * C3 (idempotence): the same movement replayed lands on the same row.
     */
    @Test
    void c3_movement_idempotent_replay() {
        String body = movementJson("ZCMV2", "WITHDRAWAL", "jdupont", null);
        sync().body(body).when().post("/api/sync/movement").then().statusCode(200);
        sync().body(body).when().post("/api/sync/movement").then()
                .log().ifValidationFails().statusCode(200);
        long count = QuarkusTransaction.requiringNew().call(
                () -> CashMovement.count("movementUid", "ZCMV2"));
        Assertions.assertEquals(1, count, "replaying the same movement creates no duplicate");
    }

    /**
     * C3 (session tolerated / cashier null): a movement whose session is absent
     * and whose cashier is null is accepted.
     */
    @Test
    void c3_movement_nullSessionAndCashier_accepted() {
        sync().body("{\"movementUid\":\"ZCMV3\",\"terminalId\":\"C01\",\"type\":\"WITHDRAWAL\","
                + "\"amount\":10.00,\"movementDate\":\"2026-09-28T09:00:00\"}")
                .when().post("/api/sync/movement")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C3 (cashier unknown): a non-null unknown cashier is a retryable 409.
     */
    @Test
    void c3_movement_cashierUnknown_conflict() {
        sync().body(movementJson("ZCMV4", "WITHDRAWAL", "zc_ghost", null))
                .when().post("/api/sync/movement")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Employé inconnu sur le nœud magasin: zc_ghost"));
    }

    /**
     * C3 (invalid enum): a type outside {@code MovementType} answers 500.
     */
    @Test
    void c3_movement_invalidType_serverError() {
        sync().body(movementJson("ZCMV5", "BOGUS", "jdupont", null))
                .when().post("/api/sync/movement")
                .then().log().ifValidationFails()
                .statusCode(500);
    }

    /**
     * C3 (malformed body): an unparseable movement body is refused 400.
     */
    @Test
    void c3_movement_malformedBody_badRequest() {
        sync().body("nope")
                .when().post("/api/sync/movement")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C4 — refund
    // ==================================================================

    /**
     * C4 (upsert): a refund tied to an existing ticket line persists 200 OK.
     */
    @Test
    void c4_refund_upsert_created() {
        sync().body(ticketWithLineUid("ZCTKR1", "ZCLUID1")).when()
                .post("/api/sync/ticket").then().statusCode(200);
        sync().body(refundJson("ZCRF1", "ZCTKR1", "ZCLUID1"))
                .when().post("/api/sync/refund")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> Refund.count("refundNumber", "ZCRF1"));
        Assertions.assertEquals(1, count, "the refund was created exactly once");
    }

    /**
     * C4 (original ticket absent): a refund whose original ticket is unknown is
     * a retryable 409 naming the number.
     */
    @Test
    void c4_refund_originalTicketAbsent_conflict() {
        sync().body(refundJson("ZCRF2", "ZCNOPE", "ZCLUID1"))
                .when().post("/api/sync/refund")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Ticket d'origine absent: ZCNOPE"));
    }

    /**
     * C4 (original line absent): a refund line whose uid is not among the
     * original ticket's lines is a retryable 409 naming the uid.
     */
    @Test
    void c4_refund_originalLineAbsent_conflict() {
        sync().body(ticketWithLineUid("ZCTKR3", "ZCLUID3")).when()
                .post("/api/sync/ticket").then().statusCode(200);
        sync().body(refundJson("ZCRF3", "ZCTKR3", "ZCMISSING"))
                .when().post("/api/sync/refund")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Ligne d'origine absente: ZCMISSING"));
    }

    /**
     * C4 (line without uid): a refund line carrying a null original uid is
     * always refused 409 with a null uid in the message.
     */
    @Test
    void c4_refund_lineWithoutUid_conflict() {
        sync().body(ticketWithLineUid("ZCTKR4", "ZCLUID4")).when()
                .post("/api/sync/ticket").then().statusCode(200);
        String refund = "{\"refundNumber\":\"ZCRF4\",\"terminalId\":\"C01\",\"status\":\"CLOSED\","
                + "\"originalTicketNumber\":\"ZCTKR4\",\"creationDate\":\"2026-09-28T10:00:00\","
                + "\"totalAmount\":1.20,\"lines\":[{\"productLabel\":\"X\",\"quantity\":1,"
                + "\"price\":1.20}]}";
        sync().body(refund)
                .when().post("/api/sync/refund")
                .then().log().ifValidationFails()
                .statusCode(409)
                .body(equalTo("Ligne d'origine absente: null"));
    }

    /**
     * C4 (invalid enum): a status outside {@code RefundStatus} answers 500.
     */
    @Test
    void c4_refund_invalidStatus_serverError() {
        sync().body(ticketWithLineUid("ZCTKR5", "ZCLUID5")).when()
                .post("/api/sync/ticket").then().statusCode(200);
        sync().body(refundJson("ZCRF5", "ZCTKR5", "ZCLUID5")
                .replace("\"status\":\"CLOSED\"", "\"status\":\"BOGUS\""))
                .when().post("/api/sync/refund")
                .then().log().ifValidationFails()
                .statusCode(500);
    }

    /**
     * C4 (malformed body): an unparseable refund body is refused 400.
     */
    @Test
    void c4_refund_malformedBody_badRequest() {
        sync().body("{")
                .when().post("/api/sync/refund")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C5 — technical event
    // ==================================================================

    /**
     * C5 (upsert): a fresh technical event persists 200 OK.
     */
    @Test
    void c5_event_upsert_created() {
        sync().body(eventJson("ZCEV1", "TICKET_CLOSED"))
                .when().post("/api/sync/event")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> TechnicalEvent.count("eventUid", "ZCEV1"));
        Assertions.assertEquals(1, count, "the event was created exactly once");
    }

    /**
     * C5 (null tolerance): a null detail is accepted (the column is nullable),
     * the event date being present.
     */
    @Test
    void c5_event_nullDetail_accepted() {
        sync().body("{\"eventUid\":\"ZCEV2\",\"terminalId\":\"C01\",\"type\":\"TICKET_CLOSED\","
                + "\"eventDate\":\"2026-09-28T09:00:00\"}")
                .when().post("/api/sync/event")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C5 (null tolerance, DISCREPANCY): the catalogue states a null
     * {@code eventDate} is accepted, so this test expects 200. It is left RED on
     * purpose: the {@code event_date} column is {@code nullable=false} and
     * {@code ingestEvent} does NOT default it to now (unlike the balance ticket's
     * {@code emittedAt}), so the node answers 500. See the lot report.
     */
    @Test
    void c5_event_nullEventDate_catalogueExpects200() {
        sync().body("{\"eventUid\":\"ZCEV3\",\"terminalId\":\"C01\",\"type\":\"TICKET_CLOSED\"}")
                .when().post("/api/sync/event")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C5 (invalid enum): a type outside {@code EventType} answers 500.
     */
    @Test
    void c5_event_invalidType_serverError() {
        sync().body(eventJson("ZCEV3", "BOGUS"))
                .when().post("/api/sync/event")
                .then().log().ifValidationFails()
                .statusCode(500);
    }

    /**
     * C5 (malformed body): an unparseable event body is refused 400.
     */
    @Test
    void c5_event_malformedBody_badRequest() {
        sync().body("]")
                .when().post("/api/sync/event")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C6 — account customer
    // ==================================================================

    /**
     * C6 (upsert): a fresh account customer persists 200 OK.
     */
    @Test
    void c6_customer_upsert_created() {
        sync().body("{\"accountNumber\":\"ZCACC1\",\"companyName\":\"Test Co\"}")
                .when().post("/api/sync/customer")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> AccountCustomer.count("accountNumber", "ZCACC1"));
        Assertions.assertEquals(1, count, "the customer was created exactly once");
    }

    /**
     * C6 (idempotence): a replay after an edit keeps one row with the new value.
     */
    @Test
    void c6_customer_idempotent_replayAfterEdit() {
        sync().body("{\"accountNumber\":\"ZCACC2\",\"companyName\":\"First\"}")
                .when().post("/api/sync/customer").then().statusCode(200);
        sync().body("{\"accountNumber\":\"ZCACC2\",\"companyName\":\"Second\"}")
                .when().post("/api/sync/customer")
                .then().log().ifValidationFails().statusCode(200);
        String name = QuarkusTransaction.requiringNew().call(
                () -> ((AccountCustomer) AccountCustomer.find("accountNumber", "ZCACC2")
                        .firstResult()).companyName);
        Assertions.assertEquals("Second", name, "the single row carries the edited name");
    }

    /**
     * C6 (malformed body): an unparseable customer body is refused 400.
     */
    @Test
    void c6_customer_malformedBody_badRequest() {
        sync().body("{,}")
                .when().post("/api/sync/customer")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C7 — document
    // ==================================================================

    /**
     * C7 (upsert with default type): a document with a null type defaults to
     * FACTURE and persists 200 OK.
     */
    @Test
    void c7_document_nullType_defaultsToFacture() {
        sync().body("{\"documentNumber\":\"ZCDOC1\",\"terminalId\":\"C01\","
                + "\"issueDate\":\"2026-09-28T10:00:00\",\"customerName\":\"Test Co\","
                + "\"ticketNumber\":\"ZCTKN1\"}")
                .when().post("/api/sync/document")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        String type = QuarkusTransaction.requiringNew().call(
                () -> ((Invoice) Invoice.find("documentNumber", "ZCDOC1").firstResult())
                        .documentType.name());
        Assertions.assertEquals("FACTURE", type, "a null document type defaults to FACTURE");
    }

    /**
     * C7 (ticket not required, DISCREPANCY): the catalogue states a null
     * {@code ticketNumber} is accepted ("ticket=null, ticketNumber conservé"),
     * so this test expects 200. It is left RED on purpose: {@code Invoice.ticketNumber}
     * is {@code @NotBlank}, so a null number answers 500 (bean-validation
     * {@code ne doit pas être vide} on {@code ticketNumber}). See the lot report.
     */
    @Test
    void c7_document_nullTicketNumber_catalogueExpects200() {
        sync().body("{\"documentNumber\":\"ZCDOC9\",\"terminalId\":\"C01\","
                + "\"issueDate\":\"2026-09-28T10:00:00\",\"customerName\":\"Test Co\"}")
                .when().post("/api/sync/document")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C7 (ticket not required): a document naming an absent ticket is accepted
     * with a null ticket link, the number kept.
     */
    @Test
    void c7_document_absentTicket_acceptedWithNullLink() {
        sync().body("{\"documentNumber\":\"ZCDOC2\",\"documentType\":\"FACTURE\","
                + "\"terminalId\":\"C01\",\"issueDate\":\"2026-09-28T10:00:00\","
                + "\"ticketNumber\":\"ZCNOPE\",\"customerName\":\"Test Co\"}")
                .when().post("/api/sync/document")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C7 (invalid enum): a non-null document type outside {@code DocumentType}
     * answers 500.
     */
    @Test
    void c7_document_invalidType_serverError() {
        sync().body("{\"documentNumber\":\"ZCDOC3\",\"documentType\":\"BOGUS\","
                + "\"terminalId\":\"C01\",\"issueDate\":\"2026-09-28T10:00:00\","
                + "\"customerName\":\"Test Co\"}")
                .when().post("/api/sync/document")
                .then().log().ifValidationFails()
                .statusCode(500);
    }

    /**
     * C7 (malformed body): an unparseable document body is refused 400.
     */
    @Test
    void c7_document_malformedBody_badRequest() {
        sync().body("{")
                .when().post("/api/sync/document")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C8 — balance ticket deposit
    // ==================================================================

    /**
     * C8 (upsert): a fresh counter ticket persists 200 OK.
     */
    @Test
    void c8_balanceTicket_upsert_created() {
        sync().body(balanceTicketJson("ZCBT1"))
                .when().post("/api/sync/balance-ticket")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        long count = QuarkusTransaction.requiringNew().call(
                () -> BalanceTicket.count("reference", "ZCBT1"));
        Assertions.assertEquals(1, count, "the balance ticket was created exactly once");
    }

    /**
     * C8 (already consumed): re-pushing a ticket already served is answered 200
     * but ignored — the served ticket is not resurrected (a second GET stays
     * 404).
     */
    @Test
    void c8_balanceTicket_alreadyConsumed_pushIgnored() {
        sync().body(balanceTicketJson("ZCBT2"))
                .when().post("/api/sync/balance-ticket").then().statusCode(200);
        sync().when().get("/api/sync/balance-ticket/ZCBT2?terminal=C01").then().statusCode(200);
        sync().body(balanceTicketJson("ZCBT2"))
                .when().post("/api/sync/balance-ticket")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
        sync().when().get("/api/sync/balance-ticket/ZCBT2?terminal=C01")
                .then().log().ifValidationFails()
                .statusCode(404);
    }

    /**
     * C8 (null emission and lines): a ticket with no emittedAt and null lines is
     * accepted.
     */
    @Test
    void c8_balanceTicket_nullEmittedAtAndLines_accepted() {
        sync().body("{\"reference\":\"ZCBT3\",\"counterLabel\":\"Boucherie\"}")
                .when().post("/api/sync/balance-ticket")
                .then().log().ifValidationFails()
                .statusCode(200).body(equalTo("OK"));
    }

    /**
     * C8 (malformed body): an unparseable balance-ticket body is refused 400.
     */
    @Test
    void c8_balanceTicket_malformedBody_badRequest() {
        sync().body("{")
                .when().post("/api/sync/balance-ticket")
                .then().log().ifValidationFails()
                .statusCode(400);
    }

    // ==================================================================
    // C9 — balance ticket read (write-through GET)
    // ==================================================================

    /**
     * C9 (garde jeton): a wrong token on the read-through GET is refused 401.
     */
    @Test
    void c9_balanceTicket_badToken_unauthorized() {
        badToken().when().get("/api/sync/balance-ticket/ZCANY?terminal=C01")
                .then().log().ifValidationFails()
                .statusCode(401)
                .body(equalTo("Jeton de synchronisation invalide"));
    }

    /**
     * C9 (serve and consume): the first read of a held ticket serves it 200 and
     * consumes it; a second read of the same paper is 404.
     */
    @Test
    void c9_balanceTicket_serveThenConsumed() {
        sync().body(balanceTicketJson("ZCBT9"))
                .when().post("/api/sync/balance-ticket").then().statusCode(200);
        sync().when().get("/api/sync/balance-ticket/ZCBT9?terminal=C01")
                .then().log().ifValidationFails()
                .statusCode(200)
                .body("reference", equalTo("ZCBT9"));
        sync().when().get("/api/sync/balance-ticket/ZCBT9?terminal=C01")
                .then().log().ifValidationFails()
                .statusCode(404);
    }

    /**
     * C9 (unknown reference): a reference the shop does not hold is 404.
     */
    @Test
    void c9_balanceTicket_unknownReference_notFound() {
        sync().when().get("/api/sync/balance-ticket/ZCNOPE?terminal=C01")
                .then().log().ifValidationFails()
                .statusCode(404);
    }

    // ==================================================================
    // C10 — ticket duplicata (read-only GET)
    // ==================================================================

    /**
     * C10 (garde jeton): a wrong token on the duplicata GET is refused 401.
     */
    @Test
    void c10_duplicata_badToken_unauthorized() {
        badToken().when().get("/api/sync/ticket/ZCANY/duplicata")
                .then().log().ifValidationFails()
                .statusCode(401)
                .body(equalTo("Jeton de synchronisation invalide"));
    }

    /**
     * C10 (render): a held ticket's duplicata is rendered 200 as text.
     */
    @Test
    void c10_duplicata_render() {
        sync().body(ticketJson("ZCTK10", "0101", "jdupont", null, true, "CARD"))
                .when().post("/api/sync/ticket").then().statusCode(200);
        sync().when().get("/api/sync/ticket/ZCTK10/duplicata")
                .then().log().ifValidationFails()
                .statusCode(200);
    }

    /**
     * C10 (unknown ticket): an unknown number is 404 with the shop's message.
     */
    @Test
    void c10_duplicata_unknownTicket_notFound() {
        sync().when().get("/api/sync/ticket/ZCNOPE/duplicata")
                .then().log().ifValidationFails()
                .statusCode(404)
                .body(equalTo("Ticket inconnu du magasin"));
    }

    /**
     * C10 (blank number): a blank ticket number renders null and is 404 with the
     * shop's message.
     */
    @Test
    void c10_duplicata_blankNumber_notFound() {
        sync().when().get("/api/sync/ticket/{n}/duplicata", " ")
                .then().log().ifValidationFails()
                .statusCode(404)
                .body(equalTo("Ticket inconnu du magasin"));
    }

    // ------------------------------------------------------------------
    // JSON fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a session JSON body.
     *
     * @param number the session number (key)
     * @param status the session status name
     * @param openingLogin the opening cashier login
     * @param closingLogin the closing cashier login, or null to omit
     * @return the JSON body
     */
    private String sessionJson(String number, String status, String openingLogin,
                               String closingLogin) {
        String closing = closingLogin == null ? ""
                : ",\"closingCashierLogin\":\"" + closingLogin + "\"";
        return "{\"sessionNumber\":\"" + number + "\",\"terminalId\":\"C01\",\"status\":\""
                + status + "\",\"openingDate\":\"2026-09-28T08:00:00\",\"openingCashierLogin\":\""
                + openingLogin + "\",\"openingFloat\":50.00" + closing + "}";
    }

    /**
     * Builds a ticket JSON body, optionally with one line and one payment.
     *
     * @param number the ticket number (key)
     * @param storeCode the store code
     * @param cashierLogin the cashier login
     * @param sessionNumber the session number, or null to omit
     * @param withPayment whether to add one payment line
     * @param methodKey the payment method key when a payment is added
     * @return the JSON body
     */
    private String ticketJson(String number, String storeCode, String cashierLogin,
                              String sessionNumber, boolean withPayment, String methodKey) {
        String session = sessionNumber == null ? ""
                : ",\"sessionNumber\":\"" + sessionNumber + "\"";
        String payments = withPayment
                ? ",\"payments\":[{\"paymentIndex\":1,\"methodKey\":\"" + methodKey
                        + "\",\"amount\":1.20}]"
                : "";
        return "{\"ticketNumber\":\"" + number + "\",\"terminalId\":\"C01\",\"status\":\"CLOSED\","
                + "\"creationDate\":\"2026-09-28T10:00:00\",\"storeCode\":\"" + storeCode + "\","
                + "\"cashierLogin\":\"" + cashierLogin + "\"" + session + ",\"itemCount\":1,"
                + "\"totalExcludingTax\":1.00,\"totalIncludingTax\":1.20,\"totalVat\":0.20,"
                + "\"valuationStatus\":\"NOT_VALUATED\","
                + "\"lines\":[{\"lineNumber\":1,\"productLabel\":\"Test\",\"quantity\":1,"
                + "\"unitPrice\":1.20,\"vatRate\":0.20,\"totalPrice\":1.20}]" + payments + "}";
    }

    /**
     * Builds a ticket JSON body carrying one line with a stable uid, for refund
     * resolution.
     *
     * @param number the ticket number (key)
     * @param lineUid the stable line uid
     * @return the JSON body
     */
    private String ticketWithLineUid(String number, String lineUid) {
        return "{\"ticketNumber\":\"" + number + "\",\"terminalId\":\"C01\",\"status\":\"CLOSED\","
                + "\"creationDate\":\"2026-09-28T10:00:00\",\"storeCode\":\"0101\","
                + "\"cashierLogin\":\"jdupont\",\"itemCount\":1,\"totalExcludingTax\":1.00,"
                + "\"totalIncludingTax\":1.20,\"totalVat\":0.20,\"valuationStatus\":\"NOT_VALUATED\","
                + "\"lines\":[{\"lineNumber\":1,\"lineUid\":\"" + lineUid + "\","
                + "\"productLabel\":\"Test\",\"quantity\":1,\"unitPrice\":1.20,\"vatRate\":0.20,"
                + "\"totalPrice\":1.20}]}";
    }

    /**
     * Builds a refund JSON body.
     *
     * @param number the refund number (key)
     * @param originalTicketNumber the refunded ticket number
     * @param originalLineUid the refunded line uid
     * @return the JSON body
     */
    private String refundJson(String number, String originalTicketNumber, String originalLineUid) {
        return "{\"refundNumber\":\"" + number + "\",\"terminalId\":\"C01\",\"status\":\"CLOSED\","
                + "\"refundMethod\":\"CASH\",\"originalTicketNumber\":\"" + originalTicketNumber
                + "\",\"creationDate\":\"2026-09-28T11:00:00\",\"totalAmount\":1.20,"
                + "\"lines\":[{\"originalLineUid\":\"" + originalLineUid + "\","
                + "\"productLabel\":\"Test\",\"quantity\":1,\"price\":1.20,\"vatRate\":0.20}]}";
    }

    /**
     * Builds a cash movement JSON body.
     *
     * @param uid the movement uid (key)
     * @param type the movement type name
     * @param cashierLogin the cashier login, or null to omit
     * @param sessionNumber the session number, or null to omit
     * @return the JSON body
     */
    private String movementJson(String uid, String type, String cashierLogin,
                                String sessionNumber) {
        String cashier = cashierLogin == null ? ""
                : ",\"cashierLogin\":\"" + cashierLogin + "\"";
        String session = sessionNumber == null ? ""
                : ",\"sessionNumber\":\"" + sessionNumber + "\"";
        return "{\"movementUid\":\"" + uid + "\",\"terminalId\":\"C01\",\"type\":\"" + type
                + "\",\"amount\":10.00,\"movementDate\":\"2026-09-28T09:00:00\"" + cashier
                + session + "}";
    }

    /**
     * Builds a technical event JSON body.
     *
     * @param uid the event uid (key)
     * @param type the event type name
     * @return the JSON body
     */
    private String eventJson(String uid, String type) {
        return "{\"eventUid\":\"" + uid + "\",\"terminalId\":\"C01\",\"type\":\"" + type
                + "\",\"detail\":\"test\",\"eventDate\":\"2026-09-28T09:30:00\"}";
    }

    /**
     * Builds a counter (balance) ticket JSON body with one weighed line.
     *
     * @param reference the ticket reference (key)
     * @return the JSON body
     */
    private String balanceTicketJson(String reference) {
        return "{\"reference\":\"" + reference + "\",\"counterLabel\":\"Boucherie\","
                + "\"emittedAt\":\"2026-09-28T09:00:00\",\"lines\":[{\"ean\":\"3300000000001\","
                + "\"label\":\"Pommes\",\"quantity\":1.000,\"totalIncludingTax\":2.50,"
                + "\"vatRate\":0.055}]}";
    }
}
