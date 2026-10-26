package com.intermarche.edge.service.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-domain error map added to the STORE-echelon
 * {@link RefPullService} ({@code lastErrors}, {@code getLastError},
 * {@code getLastErrors}), the store-side counterpart of the register-side map:
 * a faulty domain must not interrupt the pull cycle, its error must be recorded
 * and surfaced to the supervision screen, and a recovered domain must clear.
 * <p>
 * The service has no database access: {@link SyncEndpoints},
 * {@link RefApplyService}, {@link ObjectMapper} and the {@link HttpClient} are
 * plain Mockito mocks, so no Quarkus context and no HTTP server boot. The
 * private final {@code httpClient} is injected by reflection; the
 * package-private {@code pullOnce} is called directly. The mocked client routes
 * on the request URI ({@code /versions} → {@code "VERSIONS"}, first page →
 * {@code "PAGE0"}, later pages → {@code "PAGEN"}).
 */
class RefPullServiceErrorMapTest {

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
     * Injects a mocked {@link HttpClient} into the private final
     * {@code httpClient} field of a service instance.
     *
     * @param service the service to patch
     * @param client the client to inject
     * @throws Exception if reflection fails
     */
    private void setHttpClient(RefPullService service, HttpClient client) throws Exception {
        Field field = RefPullService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, client);
    }

    /**
     * Builds a register-role service wired with the collaborator mocks and a
     * mocked HTTP client, so {@code pullDomains()} iterates the register domains
     * — the per-domain error logic is role-agnostic.
     *
     * @param apply the apply-service mock
     * @param mapper the object-mapper mock
     * @param client the HTTP client mock
     * @return the wired service
     * @throws Exception if reflection fails
     */
    private RefPullService service(RefApplyService apply, ObjectMapper mapper, HttpClient client) throws Exception {
        RefPullService service = new RefPullService();
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        service.syncEndpoints = endpoints;
        service.refApplyService = apply;
        service.objectMapper = mapper;
        service.role = "register";
        service.pullSeconds = 300L;
        // Unset, the field is 0, and an HttpRequest refuses a zero timeout.
        service.requestTimeoutSeconds = 30L;
        service.token = Optional.empty();
        service.centralUrl = Optional.empty();
        setHttpClient(service, client);
        return service;
    }

    /**
     * Builds a mocked HTTP client routing every request the standard way.
     *
     * @return the routing HTTP client mock
     * @throws Exception if the mocked send is set up wrong
     */
    private HttpClient versionedClient() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String path = request.uri().getPath();
            String query = request.uri().getQuery();
            if (path.endsWith("/versions")) {
                return resp(200, "VERSIONS");
            }
            if (query != null && query.contains("page=0")) {
                return resp(200, "PAGE0");
            }
            return resp(200, "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        return client;
    }

    /**
     * Central arm of the map — a faulty domain does not stop the cycle: EMPLOYEES
     * applies while COUPON_TYPES throws; the healthy one is still applied, the
     * faulty one's message is recorded, and {@code getLastError} returns null for
     * the healthy domain and the message for the faulty one.
     */
    @Test
    void faultyDomainRecordedWhileHealthyStillApplied() throws Exception {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = versionedClient();
        doReturn(Map.of("EMPLOYEES", "e1", "COUPON_TYPES", "c1")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        doThrow(new IllegalStateException("kaboom")).when(apply).applyCouponTypes(any());
        RefPullService service = service(apply, mapper, client);
        assertThrows(IllegalStateException.class, service::pullOnce);
        verify(apply).applyEmployees(any());
        verify(apply).recordApplied("EMPLOYEES", "e1");
        verify(apply, never()).recordApplied(eq("COUPON_TYPES"), any());
        assertNull(service.getLastError("EMPLOYEES"));
        assertEquals("kaboom", service.getLastError("COUPON_TYPES"));
        assertEquals(1, service.getLastErrors().size());
    }

    /**
     * The rethrown cycle carries only the FIRST failure (EMPLOYEES precedes
     * COUPON_TYPES in {@code DOMAINS}) while both failures are recorded — covers
     * the {@code firstFailure != null} skip arm.
     */
    @Test
    void firstFailureReportedWhenSeveralDomainsFail() throws Exception {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = versionedClient();
        doReturn(Map.of("EMPLOYEES", "e1", "COUPON_TYPES", "c1")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        doThrow(new IllegalStateException("e-emp")).when(apply).applyEmployees(any());
        doThrow(new IllegalStateException("e-coup")).when(apply).applyCouponTypes(any());
        RefPullService service = service(apply, mapper, client);
        IllegalStateException ex = assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("EMPLOYEES: e-emp", ex.getMessage());
        assertEquals("e-emp", service.getLastError("EMPLOYEES"));
        assertEquals("e-coup", service.getLastError("COUPON_TYPES"));
        assertEquals(2, service.getLastErrors().size());
    }

    /**
     * A domain that throws with no message is recorded under its exception class
     * simple name — covers the null-message coalescing arm.
     */
    @Test
    void nullMessageRecordedAsClassName() throws Exception {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = versionedClient();
        doReturn(Map.of("COUPON_TYPES", "c1")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        doThrow(new IllegalStateException()).when(apply).applyCouponTypes(any());
        RefPullService service = service(apply, mapper, client);
        assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("IllegalStateException", service.getLastError("COUPON_TYPES"));
    }

    /**
     * A domain that failed one cycle and applies the next is cleared from the
     * map — covers the success-arm {@code lastErrors.remove} and proves the map
     * is self-healing.
     */
    @Test
    void recoveredDomainClearedFromMap() throws Exception {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = versionedClient();
        doReturn(Map.of("COUPON_TYPES", "c1")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        boolean[] firstCycle = {true};
        doAnswer(inv -> {
            if (firstCycle[0]) {
                firstCycle[0] = false;
                throw new IllegalStateException("kaboom");
            }
            return null;
        }).when(apply).applyCouponTypes(any());
        RefPullService service = service(apply, mapper, client);
        assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("kaboom", service.getLastError("COUPON_TYPES"));
        service.pullOnce();
        assertNull(service.getLastError("COUPON_TYPES"));
        assertTrue(service.getLastErrors().isEmpty());
    }

    /**
     * A clean cycle leaves the map empty and the returned view refuses mutation
     * — covers the healthy-domain arm of {@code getLastErrors} and its read-only
     * contract.
     */
    @Test
    void cleanCycleLeavesEmptyUnmodifiableMap() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(mock(RefApplyService.class), mapper, client);
        service.pullOnce();
        Map<String, String> errors = service.getLastErrors();
        assertTrue(errors.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> errors.put("X", "y"));
    }
}
