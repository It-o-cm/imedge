package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.store.SyncIngestService;
import com.intermarche.pos.service.sync.SyncPayloads;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link SyncIngestResource}.
 * <p>
 * The resource is a thin JAX-RS front for {@link SyncIngestService}: four POST
 * endpoints ({@code /session}, {@code /ticket}, {@code /refund}, {@code /event})
 * all funnel through the private {@code handle} guard, which enforces the
 * {@code pos.role=store} rule, an optional shared token and the retryable-failure
 * contract (409 on {@link IllegalStateException}, 500 on any other exception).
 * The three injected members ({@code role}, {@code token}, {@code
 * syncIngestService}) are package-private, so each test sets them directly and
 * mocks the service; no database and no Quarkus context is booted. Every endpoint
 * gets a plain 200 case (so its ingestion lambda runs to completion) plus the
 * gate and exception cases are spread one per endpoint, covering both arms of the
 * role gate, both conditions of the token gate and all three try/catch outcomes.
 */
class SyncIngestResourceTest {

    /**
     * Builds a resource wired with the given role, token and a mock service.
     *
     * @param role    the {@code pos.role} value
     * @param token   the optional shared token
     * @param service the mock ingestion service
     * @return the configured resource under test
     */
    private SyncIngestResource resource(String role, Optional<String> token, SyncIngestService service) {
        SyncIngestResource resource = new SyncIngestResource();
        resource.role = role;
        resource.token = token;
        resource.syncIngestService = service;
        return resource;
    }

    /**
     * {@code ingestSession} succeeds with 200 when the node is a store and no
     * token is configured (role-check false arm, token blank arm short-circuiting
     * the mismatch, ingestion success arm).
     */
    @Test
    void sessionOkWhenStoreAndNoTokenConfigured() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        SyncIngestResource resource = resource("store", Optional.empty(), service);
        Response response = resource.ingestSession(null, dto);
        assertEquals(200, response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(service).ingestSession(dto);
    }

    /**
     * {@code ingestTicket} succeeds with 200, tolerating an uppercase role value
     * (role-check false arm via {@code equalsIgnoreCase}).
     */
    @Test
    void ticketOkWhenStoreUppercaseRole() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        SyncIngestResource resource = resource("STORE", Optional.empty(), service);
        Response response = resource.ingestTicket(null, dto);
        assertEquals(200, response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(service).ingestTicket(dto);
    }

    /**
     * {@code ingestRefund} succeeds with 200 when the presented token matches the
     * configured one (token non-blank arm, mismatch false arm, success arm).
     */
    @Test
    void refundOkWhenTokenMatches() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        SyncIngestResource resource = resource("store", Optional.of("secret"), service);
        Response response = resource.ingestRefund("secret", dto);
        assertEquals(200, response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(service).ingestRefund(dto);
    }

    /**
     * {@code ingestEvent} succeeds with 200 when no token is configured, running
     * the event ingestion lambda to completion (success arm).
     */
    @Test
    void eventOkWhenStoreAndNoTokenConfigured() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        SyncIngestResource resource = resource("store", Optional.empty(), service);
        Response response = resource.ingestEvent(null, dto);
        assertEquals(200, response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(service).ingestEvent(dto);
    }

    /**
     * {@code ingestMovement} succeeds with 200 when the node is a store and no
     * token is configured, running the movement ingestion lambda to completion
     * (lot C5a).
     */
    @Test
    void movementOkWhenStoreAndNoTokenConfigured() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.MovementDto dto = new SyncPayloads.MovementDto();
        SyncIngestResource resource = resource("store", Optional.empty(), service);
        Response response = resource.ingestMovement(null, dto);
        assertEquals(200, response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(service).ingestMovement(dto);
    }

    /**
     * {@code ingestSession} refuses a non-store node with 403 (role-check true
     * arm) and never touches the service.
     */
    @Test
    void sessionForbiddenWhenRoleNotStore() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncIngestResource resource = resource("register", Optional.empty(), service);
        Response response = resource.ingestSession("any", new SyncPayloads.SessionDto());
        assertEquals(403, response.getStatus());
        assertEquals("Ce nœud n'a pas le rôle store", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * {@code ingestTicket} refuses a wrong token with 401 (token non-blank arm,
     * mismatch true arm) and never touches the service.
     */
    @Test
    void ticketUnauthorizedWhenTokenMismatch() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncIngestResource resource = resource("store", Optional.of("secret"), service);
        Response response = resource.ingestTicket("wrong", new SyncPayloads.TicketDto());
        assertEquals(401, response.getStatus());
        assertEquals("Jeton de synchronisation invalide", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * {@code ingestRefund} maps an {@link IllegalStateException} from the service
     * to a retryable 409 carrying the exception message (first catch arm).
     */
    @Test
    void refundConflictOnIllegalState() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        doThrow(new IllegalStateException("Ticket absent")).when(service).ingestRefund(dto);
        SyncIngestResource resource = resource("store", Optional.empty(), service);
        Response response = resource.ingestRefund(null, dto);
        assertEquals(409, response.getStatus());
        assertEquals("Ticket absent", response.getEntity());
    }

    /**
     * {@code ingestEvent} maps any other exception from the service to a 500
     * carrying the exception message (second catch arm).
     */
    @Test
    void eventServerErrorOnGenericException() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        doThrow(new RuntimeException("Boom")).when(service).ingestEvent(dto);
        SyncIngestResource resource = resource("store", Optional.empty(), service);
        Response response = resource.ingestEvent(null, dto);
        assertEquals(500, response.getStatus());
        assertEquals("Boom", response.getEntity());
    }

    // --------------------------------------------------
    // balance ticket (LC-06-01-02)
    // --------------------------------------------------

    /**
     * {@code ingestBalanceTicket} goes through the shared guard like every other
     * push: a store node with no token configured answers 200 and the payload
     * reaches the service.
     */
    @Test
    void balanceTicketPushOkWhenStoreAndNoTokenConfigured() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.BalanceTicketDto dto = new SyncPayloads.BalanceTicketDto();
        Response response = resource("store", Optional.empty(), service)
                .ingestBalanceTicket(null, dto);
        assertEquals(200, response.getStatus());
        verify(service).ingestBalanceTicket(dto);
    }

    /**
     * {@code ingestBalanceTicket} on a register is refused: only the shop holds
     * counter tickets (role-check true arm).
     */
    @Test
    void balanceTicketPushForbiddenOffRole() {
        SyncIngestService service = mock(SyncIngestService.class);
        Response response = resource("register", Optional.of("jeton"), service)
                .ingestBalanceTicket("jeton", new SyncPayloads.BalanceTicketDto());
        assertEquals(403, response.getStatus());
        verifyNoInteractions(service);
    }

    /**
     * The pick-up repeats the role gate inline: a register may not serve counter
     * tickets to another register.
     */
    @Test
    void pickUpForbiddenOffRole() {
        SyncIngestService service = mock(SyncIngestService.class);
        Response response = resource("register", Optional.empty(), service)
                .consumeBalanceTicket(null, "B1", "CAISSE-01");
        assertEquals(403, response.getStatus());
        verifyNoInteractions(service);
    }

    /**
     * The pick-up repeats the token gate inline: a configured token that does not
     * match is a 401, and nothing is consumed.
     */
    @Test
    void pickUpUnauthorizedOnTokenMismatch() {
        SyncIngestService service = mock(SyncIngestService.class);
        Response response = resource("store", Optional.of("jeton"), service)
                .consumeBalanceTicket("mauvais", "B1", "CAISSE-01");
        assertEquals(401, response.getStatus());
        verifyNoInteractions(service);
    }

    /**
     * With no token configured the presented one is ignored (token blank arm
     * short-circuiting the mismatch) and the pick-up proceeds.
     */
    @Test
    void pickUpSkipsTheTokenGateWhenNoneIsConfigured() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.BalanceTicketDto served = new SyncPayloads.BalanceTicketDto();
        when(service.consumeBalanceTicket("B1", "CAISSE-01")).thenReturn(served);
        Response response = resource("store", Optional.empty(), service)
                .consumeBalanceTicket("peu importe", "B1", "CAISSE-01");
        assertEquals(200, response.getStatus());
        assertSame(served, response.getEntity());
    }

    /**
     * A reference the shop no longer holds is a 404 — the answer that tells the
     * register the paper was already used, and the whole point of the route.
     */
    @Test
    void pickUpNotFoundWhenAlreadyConsumed() {
        SyncIngestService service = mock(SyncIngestService.class);
        when(service.consumeBalanceTicket("B2", "CAISSE-01")).thenReturn(null);
        Response response = resource("store", Optional.of("jeton"), service)
                .consumeBalanceTicket("jeton", "B2", "CAISSE-01");
        assertEquals(404, response.getStatus());
    }

    /**
     * A matching token on a store node serves the detail with 200.
     */
    @Test
    void pickUpServesTheDetailOnMatchingToken() {
        SyncIngestService service = mock(SyncIngestService.class);
        SyncPayloads.BalanceTicketDto served = new SyncPayloads.BalanceTicketDto();
        served.reference = "B3";
        when(service.consumeBalanceTicket("B3", "CAISSE-02")).thenReturn(served);
        Response response = resource("store", Optional.of("jeton"), service)
                .consumeBalanceTicket("jeton", "B3", "CAISSE-02");
        assertEquals(200, response.getStatus());
        assertSame(served, response.getEntity());
    }

    // --------------------------------------------------
    // ticket duplicata (LC-08-05-05)
    // --------------------------------------------------

    /**
     * {@code ticketDuplicate} repeats the role gate inline: a register does not
     * serve the shop's duplicata (role-check true arm), answering 403 without
     * touching the service.
     */
    @Test
    void duplicateForbiddenOffRole() {
        SyncIngestService service = mock(SyncIngestService.class);
        Response response = resource("register", Optional.empty(), service)
                .ticketDuplicate("any", "T1");
        assertEquals(403, response.getStatus());
        assertEquals("Ce nœud n'a pas le rôle store", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * {@code ticketDuplicate} repeats the token gate inline: a configured token
     * that does not match is a 401 (token non-blank arm, mismatch true arm), and
     * nothing is rendered.
     */
    @Test
    void duplicateUnauthorizedOnTokenMismatch() {
        SyncIngestService service = mock(SyncIngestService.class);
        Response response = resource("store", Optional.of("jeton"), service)
                .ticketDuplicate("mauvais", "T1");
        assertEquals(401, response.getStatus());
        assertEquals("Jeton de synchronisation invalide", response.getEntity());
        verifyNoInteractions(service);
    }

    /**
     * With no token configured the presented one is ignored (token blank arm
     * short-circuiting the mismatch) and a rendered duplicata is served with 200
     * (rendered non-null arm).
     */
    @Test
    void duplicateSkipsTheTokenGateWhenNoneIsConfigured() {
        SyncIngestService service = mock(SyncIngestService.class);
        when(service.renderTicketDuplicate("T1")).thenReturn("DUPLICATA T1");
        Response response = resource("store", Optional.empty(), service)
                .ticketDuplicate("peu importe", "T1");
        assertEquals(200, response.getStatus());
        assertEquals("DUPLICATA T1", response.getEntity());
    }

    /**
     * A matching token on a store node renders and serves the duplicata with 200
     * (token non-blank arm, mismatch false arm, rendered non-null arm).
     */
    @Test
    void duplicateServesTheRenderedTicketOnMatchingToken() {
        SyncIngestService service = mock(SyncIngestService.class);
        when(service.renderTicketDuplicate("T2")).thenReturn("DUPLICATA T2");
        Response response = resource("store", Optional.of("jeton"), service)
                .ticketDuplicate("jeton", "T2");
        assertEquals(200, response.getStatus());
        assertEquals("DUPLICATA T2", response.getEntity());
    }

    /**
     * A ticket number the shop does not hold renders null and is a 404 carrying
     * the unknown-ticket message (rendered null arm).
     */
    @Test
    void duplicateNotFoundWhenShopHoldsNoSuchTicket() {
        SyncIngestService service = mock(SyncIngestService.class);
        when(service.renderTicketDuplicate("T3")).thenReturn(null);
        Response response = resource("store", Optional.of("jeton"), service)
                .ticketDuplicate("jeton", "T3");
        assertEquals(404, response.getStatus());
        assertEquals("Ticket inconnu du magasin", response.getEntity());
    }
}
