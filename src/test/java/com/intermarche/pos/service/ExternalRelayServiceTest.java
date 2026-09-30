package com.intermarche.pos.service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExternalRelayService}, targeting 100% branch coverage.
 * <p>
 * Plain JUnit 5 + Mockito: the {@link HttpClient} is a mock assigned to the
 * package-private field, so no real network is touched. Every arm of the
 * upstream guard (null, empty, blank), the GET and POST branches, the success
 * and the exception path are exercised.
 */
class ExternalRelayServiceTest {

    /**
     * Builds a service whose HTTP client is the given mock.
     *
     * @param client the mock client, or null to leave the default
     * @return the service
     */
    private ExternalRelayService serviceWith(HttpClient client) {
        ExternalRelayService service = new ExternalRelayService();
        if (client != null) {
            service.httpClient = client;
        }
        return service;
    }

    /**
     * A null, empty or blank upstream is not reached and never touches the client
     * (the three arms of the guard).
     */
    @Test
    void anAbsentUpstreamIsNotReached() {
        ExternalRelayService service = serviceWith(null);
        assertFalse(service.relay(null, "GET", "/x", null, 1000).reached());
        assertFalse(service.relay(Optional.empty(), "GET", "/x", null, 1000).reached());
        assertFalse(service.relay(Optional.of("   "), "GET", "/x", null, 1000).reached());
    }

    /**
     * A configured upstream answering a GET is relayed: reached, with its status
     * and body.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aGetIsRelayed() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"valid\":true}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        ExternalRelayService service = serviceWith(client);
        ExternalRelayService.RelayResult result = service.relay(Optional.of("http://issuer.local"), "GET", "/CARD1", null, 1000);
        assertTrue(result.reached());
        assertEquals(200, result.status());
        assertEquals("{\"valid\":true}", result.body());
    }

    /**
     * A configured upstream answering a POST is relayed with its body (the POST
     * branch).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aPostIsRelayed() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(202);
        when(response.body()).thenReturn("{\"accepted\":true}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        ExternalRelayService service = serviceWith(client);
        ExternalRelayService.RelayResult result = service.relay(Optional.of("http://flash.local"), "POST", null, "{\"amount\":5}", 1000);
        assertTrue(result.reached());
        assertEquals(202, result.status());
        assertEquals("{\"accepted\":true}", result.body());
    }

    /**
     * An upstream that throws is swallowed and reported as not reached (the catch
     * arm), so a sale is never blocked by a failing integration.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFailingUpstreamIsNotReached() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doThrow(new java.io.IOException("down")).when(client).send(any(HttpRequest.class), any());
        ExternalRelayService service = serviceWith(client);
        assertFalse(service.relay(Optional.of("http://issuer.local"), "GET", "/CARD1", null, 1000).reached());
    }
}
