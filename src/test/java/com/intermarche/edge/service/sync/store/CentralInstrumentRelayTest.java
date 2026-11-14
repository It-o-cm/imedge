package com.intermarche.edge.service.sync.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.service.sync.SyncPayloads;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CentralInstrumentRelay} (BO-03-06-48).
 *
 * <p>No central node is reachable from a unit test: the HTTP client is a mock
 * put on the field. What is tested is when the store node relays at all, what
 * it sends where, and which answers it keeps.
 */
class CentralInstrumentRelayTest {

    /**
     * Builds a relay of the given role toward the given central node.
     *
     * @param role the node's role
     * @param url the central node's address, or null for none
     * @return the relay, its transport mocked
     */
    private CentralInstrumentRelay relay(String role, String url) {
        CentralInstrumentRelay relay = new CentralInstrumentRelay();
        relay.role = role;
        relay.centralUrl = Optional.ofNullable(url);
        relay.token = Optional.of("secret");
        relay.objectMapper = new ObjectMapper();
        relay.httpClient = mock(HttpClient.class);
        return relay;
    }

    /**
     * Builds a mock response.
     *
     * @param status the HTTP status
     * @param body the body
     * @return the response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        return mock(HttpResponse.class, invocation -> switch (invocation.getMethod().getName()) {
            case "statusCode" -> status;
            case "body" -> body;
            default -> null;
        });
    }

    /**
     * Only a store node with a central node configured relays: a register, a
     * store without a central address and one with a blank address do not.
     */
    @Test
    void onlyAStoreWithACentralNodeRelays() {
        assertFalse(relay("register", "http://central").active());
        assertFalse(relay("store", null).active());
        assertFalse(relay("store", "  ").active());
        assertTrue(relay("STORE", "http://central").active());
        CentralInstrumentRelay off = relay("register", "http://central");
        assertFalse(off.forward(new SyncPayloads.StoredValueDto()));
        assertNull(off.lookup("297"));
        verifyNoInteractions(off.httpClient);
    }

    /**
     * An instrument is posted to the central node's ingestion endpoint with
     * the shared token, a 2xx answer being an acceptance and anything else a
     * refusal.
     *
     * @throws Exception never, the transport being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void anInstrumentIsPostedToTheCentralNode() throws Exception {
        CentralInstrumentRelay relay = relay("store", "http://central/");
        HttpResponse<String> accepted = response(200, "OK");
        HttpResponse<String> early = response(101, "");
        HttpResponse<String> refused = response(403, "");
        when(relay.httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(accepted, early, refused);
        SyncPayloads.StoredValueDto dto = new SyncPayloads.StoredValueDto();
        dto.number = "297";
        dto.pdvNumber = "1234";
        assertTrue(relay.forward(dto));
        assertFalse(relay.forward(dto));
        assertFalse(relay.forward(dto));
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(relay.httpClient, org.mockito.Mockito.times(3)).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = sent.getValue();
        assertEquals("http://central/api/sync/stored-value", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("secret", request.headers().firstValue("X-Sync-Token").orElse(null));
    }

    /**
     * A transport failure and an interruption are refusals, the interruption
     * flag restored.
     *
     * @throws Exception never, the transport being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFailedPostIsARefusal() throws Exception {
        CentralInstrumentRelay relay = relay("store", "http://central");
        when(relay.httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("down"), new InterruptedException("stop"));
        assertFalse(relay.forward(new SyncPayloads.StoredValueDto()));
        assertFalse(relay.forward(new SyncPayloads.StoredValueDto()));
        assertTrue(Thread.interrupted());
    }

    /**
     * A number is asked of the central node, encoded in the path: a 200 is
     * the instrument, any other status and an unreadable answer are nothing.
     *
     * @throws Exception never, the transport being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void aNumberIsAskedOfTheCentralNode() throws Exception {
        CentralInstrumentRelay relay = relay("store", "http://central");
        HttpResponse<String> found = response(200, "{\"number\":\"297 1\",\"pdvNumber\":\"5678\"}");
        HttpResponse<String> missing = response(404, "");
        HttpResponse<String> garbage = response(200, "not json");
        when(relay.httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(found, missing, garbage);
        SyncPayloads.StoredValueDto dto = relay.lookup("297 1");
        assertEquals("5678", dto.pdvNumber);
        assertNull(relay.lookup("297 1"));
        assertNull(relay.lookup("297 1"));
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(relay.httpClient, org.mockito.Mockito.times(3)).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://central/api/sync/stored-value/297%201", sent.getValue().uri().toString());
        assertEquals("GET", sent.getValue().method());
    }

    /**
     * An interrupted lookup answers nothing and restores the flag.
     *
     * @throws Exception never, the transport being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void anInterruptedLookupAnswersNothing() throws Exception {
        CentralInstrumentRelay relay = relay("store", "http://central");
        when(relay.httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stop"));
        assertNull(relay.lookup("297"));
        assertTrue(Thread.interrupted());
    }
}
