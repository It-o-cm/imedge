package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.EegProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EegGateway} (BO-02-03-37/38): the simulated label
 * wall, and the HTTP connector answering, refusing, answering without a price
 * or not answering at all.
 */
class EegGatewayTest {

    /**
     * Builds a provider.
     *
     * @param kind the connector
     * @param url the base URL
     * @return the provider
     */
    private EegProvider provider(String kind, String url) {
        EegProvider provider = new EegProvider();
        provider.code = "P";
        provider.kind = kind;
        provider.baseUrl = url;
        return provider;
    }

    /**
     * Builds a gateway whose HTTP client answers the given status and body.
     *
     * @param status the status
     * @param body the body
     * @return the gateway
     * @throws Exception never, the mock does not throw
     */
    @SuppressWarnings("unchecked")
    private EegGateway answering(int status, String body) throws Exception {
        EegGateway gateway = new EegGateway();
        gateway.http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(gateway.http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return gateway;
    }

    /**
     * The simulated wall displays what was last sent to it, nothing before.
     */
    @Test
    void theSimulatedWallDisplaysWhatItWasSent() {
        EegGateway gateway = new EegGateway();
        EegProvider simulated = provider(EegProvider.KIND_SIMULATED, null);
        assertNull(gateway.readPrice(simulated, "300"));
        assertTrue(gateway.push(simulated, Map.of("300", new BigDecimal("1.99"))));
        assertEquals(new BigDecimal("1.99"), gateway.readPrice(simulated, "300"));
    }

    /**
     * The HTTP connector reads the price of a 200 answer, at the provider's
     * root without its trailing slash.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theHttpConnectorReadsThePrice() throws Exception {
        EegGateway gateway = answering(200, "{\"price\": 2.49}");
        assertEquals(new BigDecimal("2.49"), gateway.readPrice(provider(EegProvider.KIND_HTTP, "http://eeg/"), "300"));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(gateway.http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://eeg/prices/300", request.getValue().uri().toString());
    }

    /**
     * A refusal, an answer without a price, a price that is not a number and a
     * provider that does not answer all read as no price.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theHttpConnectorReadsNoPriceOtherwise() throws Exception {
        EegProvider http = provider(EegProvider.KIND_HTTP, "http://eeg");
        assertNull(answering(404, "").readPrice(http, "300"));
        assertNull(answering(200, "{}").readPrice(http, "300"));
        assertNull(answering(200, "{\"price\": \"x\"}").readPrice(http, "300"));
        EegGateway down = new EegGateway();
        down.http = mock(HttpClient.class);
        when(down.http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("down"));
        assertNull(down.readPrice(provider(EegProvider.KIND_HTTP, null), "300"));
        assertFalse(down.push(http, Map.of("300", BigDecimal.ONE)));
    }

    /**
     * The HTTP connector reports a send taken on a 2xx answer, refused
     * otherwise.
     */
    @Test
    void theHttpConnectorSendsThePrices() throws Exception {
        EegProvider http = provider(EegProvider.KIND_HTTP, "http://eeg");
        assertTrue(answering(204, "").push(http, Map.of("300", BigDecimal.ONE)));
        assertFalse(answering(500, "").push(http, Map.of("300", BigDecimal.ONE)));
    }
}
