package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.CustomerOrder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommercialOrderPullService}: the loop runs on a store
 * node only; a round pulls only in PULL mode, with an address, once the
 * administered interval has elapsed; a pulled list stores each readable
 * document like a deposit; a failing round never stops the loop.
 */
class CommercialOrderPullServiceTest {

    /**
     * A service over mocks: PULL to http://tool/orders every 15 minutes, the
     * clock at the given instant.
     *
     * @param now the clock, epoch milliseconds
     * @return the service
     */
    private CommercialOrderPullService service(long now) {
        CommercialOrderPullService service = new CommercialOrderPullService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.customerOrderService = mock(CustomerOrderService.class);
        service.httpClient = mock(HttpClient.class);
        service.now = () -> now;
        service.role = "store";
        when(service.posSettingsService.commercialTransmission()).thenReturn("PULL");
        when(service.posSettingsService.commercialPullUrl()).thenReturn("http://tool/orders");
        when(service.posSettingsService.commercialPullMinutes()).thenReturn(15);
        return service;
    }

    /**
     * Makes the tool answer.
     *
     * @param service the service
     * @param status the HTTP status
     * @param body the body
     * @throws Exception never, the client being a mock
     */
    @SuppressWarnings("unchecked")
    private void answer(CommercialOrderPullService service, int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        doReturn(response).when(service.httpClient).send(any(HttpRequest.class), any());
    }

    /**
     * Reads the loop executor.
     *
     * @param service the service
     * @return the executor, or null
     * @throws Exception if reflection fails
     */
    private ScheduledExecutorService executor(CommercialOrderPullService service) throws Exception {
        Field field = CommercialOrderPullService.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(service);
    }

    /**
     * The loop runs on a store node only, and stops with or without having
     * started.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void theLoopRunsOnAStoreNodeOnly() throws Exception {
        CommercialOrderPullService central = service(0L);
        central.role = "central";
        central.onStart(null);
        assertNull(executor(central));
        central.onStop();
        CommercialOrderPullService store = service(0L);
        store.onStart(null);
        ScheduledExecutorService running = executor(store);
        assertFalse(running.isShutdown());
        store.onStop();
        assertTrue(running.isShutdown());
        assertNull(executor(store));
    }

    /**
     * No round pulls in PUSH mode or without an address; a due round pulls, the
     * next waits for the interval — at least a minute when zero is administered.
     *
     * @throws Exception never, the client being a mock
     */
    @Test
    void aRoundPullsWhenDueOnly() throws Exception {
        CommercialOrderPullService push = service(10_000_000L);
        when(push.posSettingsService.commercialTransmission()).thenReturn("PUSH");
        assertEquals(0, push.round());
        CommercialOrderPullService nowhere = service(10_000_000L);
        when(nowhere.posSettingsService.commercialPullUrl()).thenReturn("");
        assertEquals(0, nowhere.round());
        verify(nowhere.httpClient, never()).send(any(HttpRequest.class), any());
        CommercialOrderPullService due = service(10_000_000L);
        when(due.posSettingsService.commercialPullMinutes()).thenReturn(0);
        answer(due, 200, "[]");
        assertEquals(0, due.round());
        assertEquals(0, due.round());
        verify(due.httpClient, times(1)).send(any(HttpRequest.class), any());
        due.now = () -> 10_060_000L;
        due.round();
        verify(due.httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    /**
     * A non-2xx answer stores nothing; a list stores each readable document it
     * carries, the unreadable ones and those the mechanism refuses being left.
     *
     * @throws Exception never, the client being a mock
     */
    @Test
    void aPulledListStoresItsReadableDocuments() throws Exception {
        CommercialOrderPullService low = service(0L);
        answer(low, 199, "[]");
        assertEquals(0, low.pull("http://tool/orders"));
        CommercialOrderPullService high = service(0L);
        answer(high, 503, "[]");
        assertEquals(0, high.pull("http://tool/orders"));
        CommercialOrderPullService service = service(0L);
        answer(service, 200, "[{\"code\":\"DV1\",\"kind\":\"DEVIS\",\"extra\":1},"
                + "{\"code\":\"DV2\",\"kind\":\"DEVIS\"},{\"code\":\"X\",\"kind\":\"NOPE\"}]");
        when(service.customerOrderService.createFromTill(eq("DV1"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CustomerOrder());
        assertEquals(1, service.pull("http://tool/orders"));
        verify(service.customerOrderService, times(2)).createFromTill(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A tick runs the round in a transaction of its own, and a failing round is
     * logged, never thrown.
     *
     * @throws Exception never
     */
    @Test
    @SuppressWarnings("unchecked")
    void aTickRunsTheRoundAndSwallowsItsFailure() throws Exception {
        CommercialOrderPullService service = service(10_000_000L);
        when(service.httpClient.send(any(HttpRequest.class), any())).thenThrow(new java.io.IOException("down"));
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        when(runner.call(any(Callable.class))).thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            service.tick();
            when(service.posSettingsService.commercialTransmission()).thenReturn("PUSH");
            service.tick();
        }
        verify(service.httpClient, times(1)).send(any(HttpRequest.class), any());
    }
}
