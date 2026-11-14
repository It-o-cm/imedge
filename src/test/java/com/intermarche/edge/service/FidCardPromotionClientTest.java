package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FidCardPromotionClient} (DG-01-01-01, -02): the
 * immediate-discount promotions of a card are read on the register's
 * contract, a 404 meaning none, any other status or an unreadable answer
 * failing; the base URL, the account and the timeout are resolved like the
 * node's loyalty outbox, the administered values first.
 */
class FidCardPromotionClientTest {

    /**
     * A client with nothing administered, the given deployment keys.
     *
     * @param url the deployed base URL
     * @param client the HTTP client
     * @return the client
     */
    private FidCardPromotionClient client(Optional<String> url, HttpClient client) {
        FidCardPromotionClient fid = new FidCardPromotionClient();
        fid.posSettingsService = mock(PosSettingsService.class);
        when(fid.posSettingsService.fidelityUrl()).thenReturn("");
        when(fid.posSettingsService.fidelityUser()).thenReturn("");
        when(fid.posSettingsService.fidelityPassword()).thenReturn("");
        fid.url = url;
        fid.user = Optional.of("pos");
        fid.password = Optional.of("secret");
        fid.httpClient = client;
        return fid;
    }

    /**
     * A mocked answer of the programme.
     *
     * @param status the status
     * @param body the body
     * @return the answer
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> answer(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * The promotions are read on the card's path with the benefit filter and
     * the deployed account; a 404 reads as none.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePromotionsAreReadOnTheRegistersContract() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> found = answer(200, "[{\"ean\":\"3300000000022\",\"promotionType\":\"AMOUNT\",\"value\":0.30}]");
        HttpResponse<String> unknown = answer(404, "");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(found, unknown);
        FidCardPromotionClient fid = client(Optional.of(" http://fid "), http);
        Assertions.assertTrue(fid.configured());
        ArrayNode promotions = fid.promotions(" 2900000000001 ");
        Assertions.assertEquals("3300000000022", promotions.get(0).path("ean").asText());
        Assertions.assertEquals(0, fid.promotions("1").size());
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, org.mockito.Mockito.times(2)).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest first = sent.getAllValues().get(0);
        Assertions.assertEquals("http://fid/api/accounts/2900000000001/promotions?benefit=IMMEDIATE_DISCOUNT",
                first.uri().toString());
        Assertions.assertTrue(first.headers().firstValue("Authorization").isPresent());
        Assertions.assertEquals(Duration.ofMillis(2500), first.timeout().orElseThrow());
    }

    /**
     * Another status or an answer that is not a list fails the call.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFailingProgrammeFailsTheCall() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> down = answer(503, "");
        HttpResponse<String> notAList = answer(200, "{}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(down, notAList);
        FidCardPromotionClient fid = client(Optional.of("http://fid"), http);
        Assertions.assertThrows(IllegalStateException.class, () -> fid.promotions("1"));
        Assertions.assertThrows(IllegalStateException.class, () -> fid.promotions("1"));
        HttpClient unreachable = mock(HttpClient.class);
        when(unreachable.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("refused"));
        Assertions.assertThrows(IOException.class, () -> client(Optional.of("http://fid"), unreachable).promotions("1"));
    }

    /**
     * Without both halves of the account, no credential travels.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void anIncompleteAccountSendsNoCredential() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> unknown = answer(404, "");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(unknown);
        FidCardPromotionClient noUser = client(Optional.of("http://fid"), http);
        noUser.user = Optional.empty();
        noUser.promotions("1");
        FidCardPromotionClient noPassword = client(Optional.of("http://fid"), http);
        noPassword.password = Optional.empty();
        noPassword.promotions("1");
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, org.mockito.Mockito.times(2)).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        for (HttpRequest request : sent.getAllValues()) {
            Assertions.assertTrue(request.headers().firstValue("Authorization").isEmpty());
        }
    }

    /**
     * The administered address, account and timeout win over the deployment
     * keys; nothing at all leaves the client unconfigured.
     */
    @Test
    void theAdministeredValuesWin() {
        FidCardPromotionClient fid = client(Optional.empty(), mock(HttpClient.class));
        Assertions.assertFalse(fid.configured());
        when(fid.posSettingsService.fidelityUrl()).thenReturn(" http://admin ");
        when(fid.posSettingsService.fidelityUser()).thenReturn(" adm ");
        when(fid.posSettingsService.fidelityPassword()).thenReturn("pw");
        when(fid.posSettingsService.fidelityReadTimeoutMs()).thenReturn(900);
        Assertions.assertEquals("http://admin", fid.baseUrl());
        Assertions.assertEquals("adm", fid.authUser());
        Assertions.assertEquals("pw", fid.authPassword());
        Assertions.assertEquals(Duration.ofMillis(900), fid.readTimeout());
        when(fid.posSettingsService.fidelityUrl()).thenReturn(null);
        when(fid.posSettingsService.fidelityUser()).thenReturn(null);
        when(fid.posSettingsService.fidelityPassword()).thenReturn(null);
        Assertions.assertEquals("", fid.baseUrl());
        Assertions.assertEquals("pos", fid.authUser());
        Assertions.assertEquals("secret", fid.authPassword());
        when(fid.posSettingsService.fidelityUser()).thenReturn(" ");
        Assertions.assertEquals("pos", fid.authUser());
    }
}
