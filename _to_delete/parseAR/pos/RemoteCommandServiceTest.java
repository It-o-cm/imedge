package com.intermarche.pos.ui.auth;

import com.intermarche.pos.service.sync.SyncEndpoints;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RemoteCommandService}.
 * <p>
 * The service is the register's collection loop over the store node's order
 * book. Every collaborator ({@link SyncEndpoints}, {@link SessionCloseService},
 * {@link PosState}) and the outbound {@link HttpClient} is a plain Mockito
 * mock, and all of them are package-private fields assigned directly since the
 * test lives in the production package: no Quarkus context, no HTTP server and
 * no database is booted.
 * <p>
 * The mocked {@link HttpClient#send} routes on the request method — a GET
 * yields the order book, a POST is the acknowledgement — so a whole turn of
 * the loop can be played without any network.
 * <p>
 * Branch enumeration (every arm exercised): {@code onStart} — the
 * {@code hasStoreUrl} arm and the standalone arm (and the thread-factory
 * lambda); {@code collectSafely} — the success arm and the catch arm;
 * {@code collectOnce} — the known-order arm and the unknown-order arm left
 * pending; {@code apply} — both arms of {@code operatorBadgeId != null} and
 * the three legs of {@code IF_IDLE.equals(type) && !items.isEmpty()} (a
 * back-office order, a period-end order on a register at rest, a period-end
 * order on a register mid-sale) plus both arms of the cause ternary;
 * {@code orders} — the null body arm, the non-null body arm, and zero, one and
 * several matches; {@code get} and {@code post} — the two operands of
 * {@code statusCode &lt; 200 || statusCode &gt;= 300} (1xx throw, 5xx throw,
 * 2xx return); {@code authenticated} — both arms of {@code !shared.isBlank()}
 * (header added, header skipped).
 */
class RemoteCommandServiceTest {

    /**
     * Builds a mocked {@link HttpResponse} of the given status and body.
     *
     * @param status the HTTP status code
     * @param body the response body
     * @return the mocked response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * Builds a service wired with mocked collaborators and a store node.
     *
     * @param badge the operator badge held by the register, or null when locked
     * @return the wired service
     */
    private RemoteCommandService newService(String badge) {
        RemoteCommandService service = new RemoteCommandService();
        service.syncEndpoints = mock(SyncEndpoints.class);
        when(service.syncEndpoints.hasStoreUrl()).thenReturn(true);
        when(service.syncEndpoints.storeUrl()).thenReturn("http://node:8070");
        service.sessionCloseService = mock(SessionCloseService.class);
        service.state = mock(PosState.class);
        service.state.auth = new AuthState();
        service.state.ticket = new TicketState();
        if (badge != null) {
            service.state.auth.login(1L, "Martin Durand", badge);
        }
        service.terminalId = "POS07";
        service.token = Optional.empty();
        service.pollSeconds = 20L;
        service.httpClient = mock(HttpClient.class);
        return service;
    }

    /**
     * Routes the mocked client: a GET answers the order book, a POST answers
     * an acknowledgement.
     *
     * @param service the service whose client is stubbed
     * @param getStatus the status of the collection
     * @param body the order book the collection returns
     * @throws Exception never, the mocked send declares it
     */
    private void route(RemoteCommandService service, int getStatus, String body) throws Exception {
        when(service.httpClient.send(any(), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            return "GET".equals(request.method()) ? resp(getStatus, body) : resp(200, "{}");
        });
    }

    /**
     * Captures every request the mocked client was handed.
     *
     * @param service the service whose client was called
     * @return the requests, in order
     * @throws Exception never, the mocked send declares it
     */
    private List<HttpRequest> sent(RemoteCommandService service) throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(service.httpClient, org.mockito.Mockito.atLeastOnce()).send(captor.capture(), any());
        return captor.getAllValues();
    }

    /**
     * A register with no store node starts no loop at all.
     */
    @Test
    void aStandaloneRegisterCollectsNothing() {
        RemoteCommandService service = newService(null);
        when(service.syncEndpoints.hasStoreUrl()).thenReturn(false);
        service.onStart(new StartupEvent());
        assertNull(service.executor);
    }

    /**
     * A register attached to a node starts its daemon collection loop.
     */
    @Test
    void aRegisterWithANodeStartsItsLoop() {
        RemoteCommandService service = newService(null);
        service.onStart(new StartupEvent());
        assertNotNull(service.executor);
        service.executor.shutdownNow();
    }

    /**
     * A node that is down does not take the loop with it: the failure is
     * swallowed and the order stays pending for the next turn.
     */
    @Test
    void aFailedCollectionIsSwallowed() throws Exception {
        RemoteCommandService service = newService("007");
        when(service.httpClient.send(any(), any())).thenThrow(new IOException("node down"));
        service.collectSafely();
        verify(service.sessionCloseService, never()).close(any(), any());
    }

    /**
     * A turn that succeeds carries the order out through the same entry point.
     */
    @Test
    void aSuccessfulCollectionCarriesTheOrderOut() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}]");
        service.collectSafely();
        verify(service.sessionCloseService).close(service.state, "back-office");
    }

    /**
     * A signed-in register closes on the order, and acknowledges it on the
     * order's own token.
     */
    @Test
    void aCloseOrderClosesTheSessionAndIsAcknowledged() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\",\"issuedBy\":\"Marie\"}]");
        service.collectOnce();
        verify(service.sessionCloseService).close(service.state, "back-office");
        List<HttpRequest> requests = sent(service);
        assertEquals("http://node:8070/api/commands/POS07", requests.get(0).uri().toString());
        assertEquals("GET", requests.get(0).method());
        assertEquals("http://node:8070/api/commands/u-1/ack", requests.get(1).uri().toString());
        assertEquals("POST", requests.get(1).method());
    }

    /**
     * A register ALREADY closed acknowledges the order all the same: the order
     * asked for a closed register and the register is closed, and leaving it
     * pending would close the next operator to take the post.
     */
    @Test
    void anAlreadyClosedRegisterStillAcknowledges() throws Exception {
        RemoteCommandService service = newService(null);
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}]");
        service.collectOnce();
        verify(service.sessionCloseService, never()).close(any(), any());
        List<HttpRequest> requests = sent(service);
        assertEquals(2, requests.size());
        assertEquals("http://node:8070/api/commands/u-1/ack", requests.get(1).uri().toString());
    }

    /**
     * A PERIOD-END order on a register standing idle closes it, naming the end
     * of period as the cause rather than a supervisor (LC-01-02-08).
     */
    @Test
    void aPeriodEndOrderClosesAnIdleRegister() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION_IF_IDLE\"}]");
        service.collectOnce();
        verify(service.sessionCloseService).close(service.state, "fin de période");
        List<HttpRequest> requests = sent(service);
        assertEquals(2, requests.size());
        assertEquals("http://node:8070/api/commands/u-1/ack", requests.get(1).uri().toString());
    }

    /**
     * A PERIOD-END order on a register MID-SALE closes nothing and
     * acknowledges nothing: the customer's cart is left alone and the order
     * waits for the next turn of the loop, which is when the cashier will have
     * finished (BO-09-03-07, « une caisse ouverte sans activité »).
     */
    @Test
    void aPeriodEndOrderSparesARegisterMidSale() throws Exception {
        RemoteCommandService service = newService("007");
        service.state.ticket.items.add(new TicketState.TicketItem());
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION_IF_IDLE\"}]");
        service.collectOnce();
        verify(service.sessionCloseService, never()).close(any(), any());
        List<HttpRequest> requests = sent(service);
        assertEquals(1, requests.size());
        assertEquals("GET", requests.get(0).method());
    }

    /**
     * A BACK-OFFICE order does not wait for the cart to be empty: a person is
     * looking at the screen and asked for this register to be closed now
     * (LC-01-02-07).
     */
    @Test
    void aBackOfficeOrderDoesNotWaitForTheCart() throws Exception {
        RemoteCommandService service = newService("007");
        service.state.ticket.items.add(new TicketState.TicketItem());
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}]");
        service.collectOnce();
        verify(service.sessionCloseService).close(service.state, "back-office");
        assertEquals(2, sent(service).size());
    }

    /**
     * An order this version does not know is left PENDING: nothing is closed
     * and nothing is acknowledged, so a newer register can still carry it out.
     */
    @Test
    void anUnknownOrderIsLeftPending() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"REBOOT\"}]");
        service.collectOnce();
        verify(service.sessionCloseService, never()).close(any(), any());
        List<HttpRequest> requests = sent(service);
        assertEquals(1, requests.size());
        assertEquals("GET", requests.get(0).method());
    }

    /**
     * Several orders in one answer are all read, and the known ones are all
     * carried out.
     */
    @Test
    void severalOrdersAreAllCollected() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"REBOOT\"},"
                + "{\"uid\":\"u-2\",\"type\":\"CLOSE_SESSION\"}]");
        service.collectOnce();
        List<HttpRequest> requests = sent(service);
        assertEquals(2, requests.size());
        assertEquals("http://node:8070/api/commands/u-2/ack", requests.get(1).uri().toString());
    }

    /**
     * An empty order book asks nothing of the register.
     */
    @Test
    void anEmptyOrderBookDoesNothing() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[]");
        service.collectOnce();
        verify(service.sessionCloseService, never()).close(any(), any());
        assertEquals(1, sent(service).size());
    }

    /**
     * The reader tolerates a null body and an answer carrying no order, and
     * reads both fields of each order it does find.
     */
    @Test
    void theReaderToleratesAnEmptyAnswer() {
        RemoteCommandService service = newService(null);
        assertTrue(service.orders(null).isEmpty());
        assertTrue(service.orders("").isEmpty());
        assertTrue(service.orders("pas du JSON").isEmpty());
        List<String[]> found = service.orders("[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}]");
        assertEquals(1, found.size());
        assertEquals("u-1", found.get(0)[0]);
        assertEquals("CLOSE_SESSION", found.get(0)[1]);
    }

    /**
     * A collection answered below 200 is a failure, named by its status.
     */
    @Test
    void aCollectionAnsweredBelow200Fails() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 100, "");
        IllegalStateException failure = assertThrows(IllegalStateException.class, service::collectOnce);
        assertTrue(failure.getMessage().contains("HTTP 100"));
    }

    /**
     * A collection answered above 299 is a failure too — the other operand of
     * the same guard.
     */
    @Test
    void aCollectionAnsweredAbove299Fails() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 500, "");
        IllegalStateException failure = assertThrows(IllegalStateException.class, service::collectOnce);
        assertTrue(failure.getMessage().contains("HTTP 500"));
        assertTrue(failure.getMessage().contains("/api/commands/POS07"));
    }

    /**
     * An acknowledgement the node refuses is a failure: the register must not
     * believe an order was closed out when the node never recorded it.
     */
    @Test
    void aRefusedAcknowledgementFails() throws Exception {
        RemoteCommandService service = newService("007");
        when(service.httpClient.send(any(), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            return "GET".equals(request.method())
                    ? resp(200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}]")
                    : resp(404, "");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, service::collectOnce);
        assertTrue(failure.getMessage().contains("HTTP 404"));
    }

    /**
     * No token configured means no token header: an open node is reached
     * bare-headed.
     */
    @Test
    void anOpenNodeIsReachedWithoutAToken() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[]");
        service.collectOnce();
        assertFalse(sent(service).get(0).headers().firstValue("X-Sync-Token").isPresent());
    }

    /**
     * A token configured as blank is no token at all — the other arm of the
     * same guard.
     */
    @Test
    void aBlankTokenAddsNoHeader() throws Exception {
        RemoteCommandService service = newService("007");
        service.token = Optional.of("   ");
        route(service, 200, "[]");
        service.collectOnce();
        assertFalse(sent(service).get(0).headers().firstValue("X-Sync-Token").isPresent());
    }

    /**
     * A configured token is presented on every call, the collection and the
     * acknowledgement alike.
     */
    @Test
    void aConfiguredTokenIsPresentedOnEveryCall() throws Exception {
        RemoteCommandService service = newService("007");
        service.token = Optional.of("secret");
        route(service, 200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}]");
        service.collectOnce();
        for (HttpRequest request : sent(service)) {
            assertEquals("secret", request.headers().firstValue("X-Sync-Token").orElse(null));
        }
    }

    /**
     * An acknowledgement answered BELOW 200 is a failure too — the low operand
     * of the acknowledgement's own status guard, the mirror of the collection's
     * {@code statusCode < 200} arm. The GET succeeds with a known close order,
     * but the POST that acknowledges it comes back 1xx, so the whole turn throws
     * on the ack path.
     *
     * @throws Exception never, the mocked send declares it
     */
    @Test
    void anAcknowledgementAnsweredBelow200Fails() throws Exception {
        RemoteCommandService service = newService("007");
        when(service.httpClient.send(any(), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            return "GET".equals(request.method())
                    ? resp(200, "[{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}]")
                    : resp(100, "");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, service::collectOnce);
        assertTrue(failure.getMessage().contains("HTTP 100"));
        assertTrue(failure.getMessage().contains("/api/commands/u-1/ack"));
    }

    /**
     * BO-04-02-05: the reader picks up the REASON of each order inside that
     * order's own object — an order carrying none answers null, an escaped
     * quote comes back unescaped, and one order's reason never leaks into
     * the next.
     */
    @Test
    void theReaderPicksUpEachOrdersOwnReason() {
        RemoteCommandService service = newService(null);
        List<String[]> found = service.orders(
                "[{\"uid\":\"u-1\",\"type\":\"SUPERVISOR_REFUSED\",\"issuedBy\":\"Alice\","
                        + "\"reason\":\"Pas de remise au-dela de 10 \\\"euros\\\"\"},"
                        + "{\"uid\":\"u-2\",\"type\":\"CLOSE_SESSION\",\"issuedBy\":null,"
                        + "\"reason\":null}]");
        assertEquals(2, found.size());
        assertEquals("Pas de remise au-dela de 10 \"euros\"", found.get(0)[2]);
        assertNull(found.get(1)[2]);
    }

    /**
     * BO-04-02-05: a refusal is written in the cashier's message zone and
     * acknowledged, whatever the register was doing — an answer delivered
     * twice reads as a second refusal.
     *
     * @throws Exception never, the mocked client declares it
     */
    @Test
    void aRefusalIsShownAndAcknowledged() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[{\"uid\":\"u-9\",\"type\":\"SUPERVISOR_REFUSED\","
                + "\"issuedBy\":\"Alice\",\"reason\":\"Geste non autorise\"}]");
        service.collectOnce();
        assertEquals("Geste non autorise", service.state.ticket.transientError);
        verify(service.sessionCloseService, never()).close(any(), any());
        assertTrue(sent(service).stream()
                .anyMatch(request -> request.uri().getPath().endsWith("/api/commands/u-9/ack")));
    }

    /**
     * BO-04-02-05: a refusal the back office sent with no wording says the
     * least that is still true rather than showing an empty zone.
     *
     * @throws Exception never, the mocked client declares it
     */
    @Test
    void aRefusalWithoutWordingFallsBackOnTheDefault() throws Exception {
        RemoteCommandService service = newService("007");
        route(service, 200, "[{\"uid\":\"u-9\",\"type\":\"SUPERVISOR_REFUSED\","
                + "\"issuedBy\":\"Alice\",\"reason\":null}]");
        service.collectOnce();
        assertEquals("Demande refusée par le back-office", service.state.ticket.transientError);
        service.state.ticket.transientError = null;
        service.refuse("u-8", "   ");
        assertEquals("Demande refusée par le back-office", service.state.ticket.transientError);
    }

    /**
     * The reason reader tolerates an order object that is never closed and
     * one that carries no reason at all.
     */
    @Test
    void theReasonReaderToleratesATruncatedAnswer() {
        RemoteCommandService service = newService(null);
        assertNull(service.reasonOf("{\"uid\":\"u-1\",\"type\":\"CLOSE_SESSION\"}", 0));
        assertEquals("tronque", service.reasonOf("\"reason\":\"tronque\"", 0));
    }

    /**
     * BO-04-02-02: an authorisation from the back office is armed on the
     * gesture the lane is holding, announced to the cashier, and
     * acknowledged.
     *
     * @throws Exception never, the mocked client declares it
     */
    @Test
    void anAuthorisationIsArmedOnThePendingGesture() throws Exception {
        RemoteCommandService service = newService("007");
        service.state.endorsement = new com.intermarche.pos.ui.endorsement.EndorsementState();
        service.state.endorsement.request("CANCEL_LINE");
        route(service, 200, "[{\"uid\":\"u-5\",\"type\":\"SUPERVISOR_GRANTED\","
                + "\"issuedBy\":\"Marie Curie\",\"reason\":\"Autorisé par Marie Curie\"}]");
        service.collectOnce();
        assertEquals("Autorisé par Marie Curie", service.state.endorsement.remoteGrant);
        assertNotNull(service.state.ticket.transientError);
        assertTrue(sent(service).stream()
                .anyMatch(request -> request.uri().getPath().endsWith("/api/commands/u-5/ack")));
    }

    /**
     * BO-04-02-02: an authorisation that lands when no gesture is pending
     * arms nothing and is acknowledged all the same — the back office
     * answered a question the lane has stopped asking.
     *
     * @throws Exception never, the mocked client declares it
     */
    @Test
    void anAuthorisationWithoutAPendingGestureArmsNothing() throws Exception {
        RemoteCommandService service = newService("007");
        service.state.endorsement = new com.intermarche.pos.ui.endorsement.EndorsementState();
        route(service, 200, "[{\"uid\":\"u-6\",\"type\":\"SUPERVISOR_GRANTED\","
                + "\"issuedBy\":null,\"reason\":null}]");
        service.collectOnce();
        assertNull(service.state.endorsement.remoteGrant);
        assertTrue(sent(service).stream()
                .anyMatch(request -> request.uri().getPath().endsWith("/api/commands/u-6/ack")));
    }

    /**
     * BO-04-02-02: an authorisation carrying no wording names the back
     * office rather than nobody.
     *
     * @throws Exception never, the mocked client declares it
     */
    @Test
    void anAuthorisationWithoutWordingNamesTheBackOffice() throws Exception {
        RemoteCommandService service = newService("007");
        service.state.endorsement = new com.intermarche.pos.ui.endorsement.EndorsementState();
        service.state.endorsement.request("CANCEL_LINE");
        route(service, 200, "[]");
        service.grant("u-7", "   ");
        assertEquals("le back-office", service.state.endorsement.remoteGrant);
    }
}
