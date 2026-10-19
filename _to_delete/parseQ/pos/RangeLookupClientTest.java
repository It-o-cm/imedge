package com.intermarche.pos.ui.scanner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RangeLookupClient} (BO-03-06-41).
 *
 * <p>No third-party service is reachable from a unit test, so the HTTP client
 * is a mock substituted on the field. What is tested is everything the register
 * decides BEFORE and AFTER the call: whether there is anything to ask, where
 * the scanned code goes in the administered URL, and which answers are usable.
 *
 * <p>Branch enumeration: a null and a blank URL; a null and a blank code; a URL
 * carrying the placeholder and one that does not; a 2xx with a body, with a
 * blank body and with a null body; a status below 200 and one at or above 300;
 * a null response; a body longer than the cap; an {@link IOException}; an
 * {@link InterruptedException}, whose flag must be restored; and a malformed
 * administered URL.
 */
class RangeLookupClientTest {

    /** The scanned code every fixture asks about. */
    private static final String CODE = "2101234001509";

    /**
     * Builds a client whose HTTP client is a mock.
     *
     * @return the client, its transport mocked
     */
    private RangeLookupClient newClient() {
        RangeLookupClient client = new RangeLookupClient();
        client.httpClient = mock(HttpClient.class);
        return client;
    }

    /**
     * Builds a mock response with the given status and body.
     *
     * @param status the HTTP status
     * @param body the body, possibly null
     * @return the mock response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        // Answer-based rather than stubbed with {@code when}: this mock is built
        // INSIDE the transport's own stubbing, and a stubbing opened inside
        // another one is what Mockito reports as an unfinished stubbing.
        return mock(HttpResponse.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("statusCode".equals(name)) {
                return status;
            }
            if ("body".equals(name)) {
                return body;
            }
            return null;
        });
    }

    /**
     * Nothing to ask: a null and a blank URL both answer null, and the
     * transport is never touched (both legs of the first guard).
     *
     * @throws Exception never, the transport is mocked
     */
    @Test
    void withoutAnAdministeredUrlNobodyIsAsked() throws Exception {
        RangeLookupClient client = newClient();
        assertNull(client.lookup(null, CODE));
        assertNull(client.lookup("   ", CODE));
        verifyNoInteractions(client.httpClient);
    }

    /**
     * Nothing to ask about: a null and a blank code both answer null, and the
     * transport is never touched (both legs of the second guard).
     */
    @Test
    void withoutACodeNobodyIsAsked() {
        RangeLookupClient client = newClient();
        assertNull(client.lookup("https://partenaire.test/lookup?c=", null));
        assertNull(client.lookup("https://partenaire.test/lookup?c=", "  "));
        verifyNoInteractions(client.httpClient);
    }

    /**
     * A URL carrying the placeholder receives the code WHERE the store put it;
     * one without receives it appended. The request's URI is read back from the
     * captured request, so the substitution is observed and not assumed.
     *
     * @throws Exception never, the transport is mocked
     */
    @Test
    void theCodeGoesWhereTheAdministeredUrlSaysOrAtTheEnd() throws Exception {
        RangeLookupClient client = newClient();
        when(client.httpClient.send(any(), any())).thenReturn(response(200, "3560070000000"));
        assertEquals("3560070000000",
                client.lookup("https://partenaire.test/bons/{code}/etat", "  " + CODE + "  "));
        org.mockito.ArgumentCaptor<HttpRequest> captor =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(client.httpClient).send(captor.capture(), any());
        assertEquals("https://partenaire.test/bons/" + CODE + "/etat",
                captor.getValue().uri().toString());

        RangeLookupClient appended = newClient();
        when(appended.httpClient.send(any(), any())).thenReturn(response(200, "OK"));
        assertEquals("OK", appended.lookup("  https://partenaire.test/bons?c=  ", CODE));
        org.mockito.ArgumentCaptor<HttpRequest> second =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(appended.httpClient).send(second.capture(), any());
        assertEquals("https://partenaire.test/bons?c=" + CODE,
                second.getValue().uri().toString());
    }

    /**
     * A refused status answers null, on both sides of the accepted range, and a
     * null response answers null too (the three legs of the status guard).
     *
     * @throws Exception never, the transport is mocked
     */
    @Test
    void aRefusedStatusOrNoResponseAtAllAnswersNothing() throws Exception {
        RangeLookupClient low = newClient();
        when(low.httpClient.send(any(), any())).thenReturn(response(100, "ATTENDS"));
        assertNull(low.lookup("https://partenaire.test/?c=", CODE));

        RangeLookupClient high = newClient();
        when(high.httpClient.send(any(), any())).thenReturn(response(500, "BOUM"));
        assertNull(high.lookup("https://partenaire.test/?c=", CODE));

        RangeLookupClient none = newClient();
        when(none.httpClient.send(any(), any())).thenReturn(null);
        assertNull(none.lookup("https://partenaire.test/?c=", CODE));
    }

    /**
     * An accepted status with nothing in it answers null: a null body and a
     * blank one are both "no usable answer" (both legs of the body guard).
     *
     * @throws Exception never, the transport is mocked
     */
    @Test
    void anEmptyAnswerIsNoAnswer() throws Exception {
        RangeLookupClient nullBody = newClient();
        when(nullBody.httpClient.send(any(), any())).thenReturn(response(204, null));
        assertNull(nullBody.lookup("https://partenaire.test/?c=", CODE));

        RangeLookupClient blankBody = newClient();
        when(blankBody.httpClient.send(any(), any())).thenReturn(response(200, "  \n "));
        assertNull(blankBody.lookup("https://partenaire.test/?c=", CODE));
    }

    /**
     * A service that writes a novel is TRUNCATED rather than shown whole: the
     * register's message band is two lines, not a page (both arms of the cap).
     *
     * @throws Exception never, the transport is mocked
     */
    @Test
    void anOverlongAnswerIsTruncated() throws Exception {
        RangeLookupClient client = newClient();
        when(client.httpClient.send(any(), any()))
                .thenReturn(response(200, "X".repeat(500)));
        assertEquals(200, client.lookup("https://partenaire.test/?c=", CODE).length());

        RangeLookupClient short_ = newClient();
        when(short_.httpClient.send(any(), any())).thenReturn(response(200, "BON REFUSE"));
        assertEquals("BON REFUSE", short_.lookup("https://partenaire.test/?c=", CODE));
    }

    /**
     * A transport failure answers null: a customer at a register never waits on
     * somebody else's outage (the general catch arm).
     *
     * @throws Exception never, the transport is mocked
     */
    @Test
    void aTransportFailureAnswersNothing() throws Exception {
        RangeLookupClient client = newClient();
        when(client.httpClient.send(any(), any())).thenThrow(new IOException("coupé"));
        assertNull(client.lookup("https://partenaire.test/?c=", CODE));
    }

    /**
     * An interrupted wait answers null AND restores the interrupt flag, so the
     * register's own shutdown is not swallowed by this call (the interrupt arm).
     *
     * @throws Exception never, the transport is mocked
     */
    @Test
    void anInterruptedWaitRestoresTheFlag() throws Exception {
        RangeLookupClient client = newClient();
        when(client.httpClient.send(any(), any()))
                .thenThrow(new InterruptedException("stop"));
        assertNull(client.lookup("https://partenaire.test/?c=", CODE));
        assertTrue(Thread.interrupted(), "le drapeau d'interruption doit être reposé");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    /**
     * A malformed administered URL answers null instead of raising: a typo in
     * the back office must not stop a register (the URI arm of the catch).
     */
    @Test
    void aMalformedAdministeredUrlAnswersNothing() {
        RangeLookupClient client = newClient();
        assertNull(client.lookup("pas une url du tout :: {code}", CODE));
    }
}
