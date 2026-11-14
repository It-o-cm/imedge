package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.deploy.FunctionUsage;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.deploy.FunctionUsageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FunctionUsageFilter}. */
class FunctionUsageFilterTest {

    /**
     * A handler declaring its feature, for the filter to find.
     */
    @FeatureAccess(value = com.intermarche.edge.domain.setting.Feature.SYNC)
    void declared() {
        // Only its annotation is read.
    }

    /**
     * A handler declaring nothing.
     */
    void undeclared() {
        // Only its absence of annotation is read.
    }

    /**
     * Builds the filter on a handler.
     *
     * @param handler the handler's name, or null for no handler
     * @param role the node's role
     * @return the filter
     * @throws Exception never
     */
    private FunctionUsageFilter filter(String handler, String role) throws Exception {
        FunctionUsageFilter filter = new FunctionUsageFilter();
        filter.resourceInfo = mock(ResourceInfo.class);
        Method method = handler == null ? null : FunctionUsageFilterTest.class.getDeclaredMethod(handler);
        when(filter.resourceInfo.getResourceMethod()).thenReturn(method);
        filter.functionUsageService = mock(FunctionUsageService.class);
        filter.nodeEchelonService = mock(NodeEchelonService.class);
        filter.role = role;
        return filter;
    }

    /**
     * Builds a request.
     *
     * @param method the HTTP method
     * @param accept the Accept header, possibly null
     * @return the request
     */
    private ContainerRequestContext request(String method, String accept) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeaderString("Accept")).thenReturn(accept);
        return request;
    }

    /**
     * Runs every transaction in place.
     *
     * @param tx the static mock of the transaction API
     * @throws Exception never
     */
    @SuppressWarnings("unchecked")
    private void inPlace(MockedStatic<QuarkusTransaction> tx) throws Exception {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        when(runner.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<Object>) invocation.getArgument(0)).call());
    }

    /** BO-08-02-02: a gesture and a page opened are uses; a poll or another method is not. */
    @Test
    void countedKeepsGesturesAndPages() {
        assertTrue(FunctionUsageFilter.counted(request("POST", null)));
        assertTrue(FunctionUsageFilter.counted(request("GET", "text/html,*/*")));
        assertFalse(FunctionUsageFilter.counted(request("GET", "*/*")));
        assertFalse(FunctionUsageFilter.counted(request("GET", null)));
        assertFalse(FunctionUsageFilter.counted(request("PUT", "text/html")));
    }

    /** No handler, a handler without feature, or a poll: nothing is counted. */
    @Test
    void nothingToCount() throws Exception {
        FunctionUsageFilter none = filter(null, "store");
        none.filter(request("POST", null));
        FunctionUsageFilter bare = filter("undeclared", "store");
        bare.filter(request("POST", null));
        FunctionUsageFilter poll = filter("declared", "store");
        poll.filter(request("GET", "*/*"));
        FunctionUsageFilter absent = filter("declared", "store");
        absent.resourceInfo = null;
        absent.filter(request("POST", null));
        verify(none.functionUsageService, never()).record(any(), any(), any(), any());
        verify(bare.functionUsageService, never()).record(any(), any(), any(), any());
        verify(poll.functionUsageService, never()).record(any(), any(), any(), any());
    }

    /** BO-08-02-02: a store gesture is counted under its point of sale, or EDGE when it has none. */
    @Test
    void aStoreGestureIsCounted() throws Exception {
        FunctionUsageFilter named = filter("declared", "store");
        when(named.nodeEchelonService.currentPdv()).thenReturn("07039");
        FunctionUsageFilter unnamed = filter("declared", "store");
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            named.filter(request("POST", null));
            unnamed.filter(request("GET", "text/html"));
        }
        verify(named.functionUsageService).record("07039", "07039", FunctionUsage.BACK_OFFICE, "sync:declared");
        verify(unnamed.functionUsageService).record("EDGE", null, FunctionUsage.BACK_OFFICE, "sync:declared");
    }

    /** BO-08-02-02: a central gesture is counted under CENTRAL; a failing count never stops the request. */
    @Test
    void aCentralGestureIsCountedAndNeverBlocks() throws Exception {
        FunctionUsageFilter central = filter("declared", "central");
        central.filter(request("POST", null));
        verify(central.functionUsageService).record("CENTRAL", null, FunctionUsage.BACK_OFFICE, "sync:declared");
        doThrow(new IllegalStateException("db")).when(central.functionUsageService)
                .record(anyString(), any(), anyString(), anyString());
        central.filter(request("POST", null));
    }
}
