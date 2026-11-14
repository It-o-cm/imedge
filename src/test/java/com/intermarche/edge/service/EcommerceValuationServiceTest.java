package com.intermarche.edge.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceValuationService} (BO-11-04-03), targeting
 * 100% branch coverage: the exposure off, an empty or blank request, no
 * engine configured, an engine account with and without its two halves, the
 * engine's answer relayed, an interrupted call and an unreachable engine.
 */
class EcommerceValuationServiceTest {

    /**
     * Builds the service over a mocked HTTP client.
     *
     * @param enabled whether the exposure is administered on
     * @param url the engine URL, possibly empty
     * @param client the HTTP client
     * @return the service
     */
    private EcommerceValuationService service(boolean enabled, Optional<String> url, HttpClient client) {
        EcommerceValuationService service = new EcommerceValuationService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.ecommerceValuationEnabled()).thenReturn(enabled);
        service.url = url;
        service.user = Optional.of("admin");
        service.password = Optional.of("secret");
        service.httpClient = client;
        return service;
    }

    /** The exposure off, an absent or blank request and a missing engine are refused before any call. */
    @Test
    void theRefusalsComeBeforeAnyCall() {
        HttpClient client = mock(HttpClient.class);
        Assertions.assertEquals(403, service(false, Optional.of("http://e"), client).valuate("{}").status());
        Assertions.assertEquals(400, service(true, Optional.of("http://e"), client).valuate(null).status());
        Assertions.assertEquals(400, service(true, Optional.of("http://e"), client).valuate(" ").status());
        Assertions.assertEquals(503, service(true, Optional.empty(), client).valuate("{}").status());
        Assertions.assertEquals(503, service(true, Optional.of(" "), client).valuate("{}").status());
    }

    /**
     * The request goes verbatim to the engine's valuation path with the node's
     * engine account, and its answer is relayed as it came.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void theEngineAnswerIsRelayed() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"total\":9.99}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        EcommerceValuationService.Answer answer = service(true, Optional.of(" http://engine "), client)
                .valuate("{\"lines\":[]}");
        Assertions.assertEquals(200, answer.status());
        Assertions.assertEquals("{\"total\":9.99}", answer.body());
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        Assertions.assertEquals("http://engine/valuation", sent.getValue().uri().toString());
        Assertions.assertTrue(sent.getValue().headers().firstValue("Authorization").isPresent());
    }

    /**
     * Without both halves of the engine account, no credential travels.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void anIncompleteAccountSendsNoCredential() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(422);
        when(response.body()).thenReturn("refused");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        EcommerceValuationService noUser = service(true, Optional.of("http://engine"), client);
        noUser.user = Optional.empty();
        Assertions.assertEquals(422, noUser.valuate("{}").status());
        EcommerceValuationService noPassword = service(true, Optional.of("http://engine"), client);
        noPassword.password = Optional.empty();
        Assertions.assertEquals(422, noPassword.valuate("{}").status());
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, org.mockito.Mockito.times(2)).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        for (HttpRequest request : sent.getAllValues()) {
            Assertions.assertTrue(request.headers().firstValue("Authorization").isEmpty());
        }
    }

    /**
     * An interrupted call and an unreachable engine both answer 503, the
     * interruption being restored on the thread.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void anEngineThatDoesNotAnswerIsUnavailable() throws Exception {
        HttpClient interrupted = mock(HttpClient.class);
        when(interrupted.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stop"));
        Assertions.assertEquals(503, service(true, Optional.of("http://engine"), interrupted).valuate("{}").status());
        Assertions.assertTrue(Thread.interrupted());
        HttpClient down = mock(HttpClient.class);
        when(down.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("refused"));
        Assertions.assertEquals(503, service(true, Optional.of("http://engine"), down).valuate("{}").status());
    }

    /**
     * DG-01-01: the relay is refused while the exposure is off, and carries
     * any method and path to the engine, a call without body sending none.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRelayCarriesAnyCallToTheEngine() throws Exception {
        HttpClient client = mock(HttpClient.class);
        Assertions.assertEquals(403, service(false, Optional.of("http://e"), client)
                .relay("GET", "/baskets/B1", null).status());
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(409);
        when(response.body()).thenReturn("use a return");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        EcommerceValuationService.Answer answer = service(true, Optional.of("http://engine"), client)
                .relay("DELETE", "/baskets/B1", null);
        Assertions.assertEquals(409, answer.status());
        Assertions.assertEquals("use a return", answer.body());
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        Assertions.assertEquals("DELETE", sent.getValue().method());
        Assertions.assertEquals("http://engine/baskets/B1", sent.getValue().uri().toString());
        Assertions.assertEquals(0L, sent.getValue().bodyPublisher().orElseThrow().contentLength());
    }
}
