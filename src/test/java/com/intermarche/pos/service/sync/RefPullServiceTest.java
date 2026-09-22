package com.intermarche.pos.service.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Unit tests for {@link RefPullService}.
 * <p>
 * The service is a register-side scheduled pull loop over the store node's
 * HTTP referential API. It has no database access: every collaborator
 * ({@link SyncEndpoints}, {@link RefApplyService}, {@link ObjectMapper})
 * and the outbound {@link HttpClient} is a plain Mockito mock, so no Quarkus
 * context, no HTTP server and no database is booted. Package-private fields
 * ({@code role}, {@code token}, {@code syncEndpoints}, {@code refApplyService},
 * {@code objectMapper}, {@code pullSeconds}) are assigned directly since the
 * test lives in the production package; the private final {@code httpClient}
 * and the private {@code executor} are reached through reflection, as are the
 * private methods {@code pullSafely}, {@code applyDomain} and {@code get}. The
 * {@code get}/{@code pages}/{@code pullOnce}/{@code onStart}/{@code onStop}
 * package-private surface is called directly.
 * <p>
 * The mocked {@link HttpClient#send} routes on the request URI: the
 * {@code /versions} path yields a {@code "VERSIONS"} body, {@code page=0}
 * yields a non-empty {@code "PAGE0"} body and any later page yields an empty
 * {@code "PAGEN"} body; the mocked {@link ObjectMapper} maps those bodies to
 * the versions map and the snapshot pages, driving the whole pull without any
 * real JSON.
 * <p>
 * Branch enumeration: {@code onStart} — the two operands of
 * {@code !"register".equalsIgnoreCase(role) || !isEnabled()} (wrong role,
 * disabled endpoints) plus the enabled arm (and the thread-factory lambda);
 * {@code onStop} — the {@code executor != null} both arms; {@code pullSafely} —
 * the success arm and the catch arm; {@code pullOnce} — the three arms of
 * {@code remoteFingerprint == null || equals(lastApplied)} (absent, unchanged,
 * changed); {@code applyDomain} — the six switch arms plus the default throw
 * and the trailing {@code recordApplied}; {@code pages} — both arms of
 * {@code rows.isEmpty()} (continue, break); {@code get} — the two operands of
 * {@code statusCode < 200 || statusCode >= 300} (1xx throw, 5xx throw, 2xx
 * return) and both arms of {@code !sharedToken.isBlank()} (header added,
 * header skipped).
 */
class RefPullServiceTest {

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
     * Reads the private {@code executor} field of a service instance.
     *
     * @param service the service to inspect
     * @return the current executor, possibly null
     * @throws Exception if reflection fails
     */
    private ScheduledExecutorService getExecutor(RefPullService service) throws Exception {
        Field field = RefPullService.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(service);
    }

    /**
     * Invokes a private no-argument method of the service by reflection.
     *
     * @param service the target service
     * @param name the method name
     * @throws Exception if reflection fails or the method throws
     */
    private void invoke(RefPullService service, String name) throws Exception {
        Method method = RefPullService.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(service);
    }

    /**
     * Builds a service wired with the three collaborator mocks and a mocked
     * HTTP client, leaving role, token and pull period at test defaults.
     *
     * @param endpoints the endpoints mock
     * @param apply the apply-service mock
     * @param mapper the object-mapper mock
     * @param client the HTTP client mock
     * @return the wired service
     * @throws Exception if reflection fails
     */
    private RefPullService service(SyncEndpoints endpoints, RefApplyService apply,
            ObjectMapper mapper, HttpClient client) throws Exception {
        RefPullService service = new RefPullService();
        service.syncEndpoints = endpoints;
        service.refApplyService = apply;
        service.objectMapper = mapper;
        service.role = "register";
        service.pullSeconds = 300L;
        service.token = Optional.empty();
        service.centralUrl = Optional.empty();
        setHttpClient(service, client);
        return service;
    }

    // --------------------------------------------------
    // onStart / onStop
    // --------------------------------------------------

    /**
     * Covers the first operand of the guard in {@code onStart}: a store role
     * disables the pull, so no executor is created.
     */
    @Test
    void onStartSkipsWhenRoleIsNotRegister() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.role = "store";
        service.onStart(null);
        assertNull(getExecutor(service));
        verify(endpoints, never()).hasStoreUrl();
    }

    /**
     * Covers the second operand of the guard in {@code onStart}: a register
     * whose endpoints is disabled does not start the loop.
     */
    @Test
    void onStartSkipsWhenOutboxDisabled() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.hasStoreUrl()).thenReturn(false);
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.onStart(null);
        assertNull(getExecutor(service));
    }

    /**
     * Covers the enabled arm of {@code onStart} and the thread-factory lambda:
     * a register with an enabled endpoints creates the scheduled executor. The
     * loop is torn down immediately via {@code onStop}, also covering its
     * non-null arm.
     */
    @Test
    void onStartCreatesExecutorAndOnStopShutsItDown() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.hasStoreUrl()).thenReturn(true);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.onStart(null);
        ScheduledExecutorService executor = getExecutor(service);
        assertTrue(executor != null && !executor.isShutdown());
        service.onStop();
        assertTrue(executor.isShutdown());
    }

    /**
     * Covers the store-ready arm of {@code onStart} and the non-blank arm of
     * {@code hasCentralUrl}: a store node with a configured central URL starts
     * the same loop (route A, two-level chain). Torn down at once via
     * {@code onStop}.
     */
    @Test
    void onStartStartsForStoreWithCentralUrl() throws Exception {
        RefPullService service = service(mock(SyncEndpoints.class), mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.role = "store";
        service.centralUrl = Optional.of("http://central");
        service.onStart(null);
        ScheduledExecutorService executor = getExecutor(service);
        assertTrue(executor != null && !executor.isShutdown());
        service.onStop();
        assertTrue(executor.isShutdown());
    }

    /**
     * Covers the blank arm of {@code hasCentralUrl}: a store node whose central
     * URL is present but blank does not start the loop.
     */
    @Test
    void onStartSkipsForStoreWithBlankCentralUrl() throws Exception {
        RefPullService service = service(mock(SyncEndpoints.class), mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.role = "store";
        service.centralUrl = Optional.of("   ");
        service.onStart(null);
        assertNull(getExecutor(service));
    }

    /**
     * Covers the false-false skip of {@code onStart} for a central node: neither
     * the register arm (wrong role) nor the store arm (wrong role) is ready, so
     * the central runs no pull — it is the top of the chain.
     */
    @Test
    void onStartSkipsForCentralRole() throws Exception {
        RefPullService service = service(mock(SyncEndpoints.class), mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.role = "central";
        service.centralUrl = Optional.of("http://central");
        service.onStart(null);
        assertNull(getExecutor(service));
    }

    /**
     * Covers the null arm of {@code onStop}: with no executor ever created,
     * shutdown is a no-op and does not throw.
     */
    @Test
    void onStopIsNoOpWhenExecutorNull() throws Exception {
        RefPullService service = service(mock(SyncEndpoints.class), mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.onStop();
        assertNull(getExecutor(service));
    }

    // --------------------------------------------------
    // pullSafely
    // --------------------------------------------------

    /**
     * Covers the success arm of {@code pullSafely}: a clean cycle runs to
     * completion. Every remote fingerprint is absent, so nothing is applied.
     */
    @Test
    void pullSafelyRunsCleanCycle() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        invoke(service, "pullSafely");
        verify(apply, never()).recordApplied(any(), any());
    }

    /**
     * Covers the catch arm of {@code pullSafely}: a failure inside the cycle
     * is swallowed so the loop survives.
     */
    @Test
    void pullSafelySwallowsException() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doThrow(new RuntimeException("boom")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        invoke(service, "pullSafely");
        verify(apply, never()).recordApplied(any(), any());
    }

    // --------------------------------------------------
    // pullOnce / applyDomain / pages
    // --------------------------------------------------

    /**
     * Covers the changed arm of {@code pullOnce}, every switch arm of
     * {@code applyDomain}, both arms of the {@code pages} paging loop and the
     * trailing {@code recordApplied}: all six domains differ and are pulled
     * and applied, each snapshot spanning one non-empty page then an empty one.
     */
    @Test
    void pullOnceAppliesEveryChangedDomain() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
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
        Map<String, String> versions = Map.of(
                "FAMILIES", "f1", "PRODUCTS", "f2", "PRICES", "f3",
                "EMPLOYEES", "f4", "COUPON_TYPES", "f5", "SETTINGS", "f6",
                "ENGINE_FEEDS", "f7", "ARTICLE_RANGES", "f8", "ISLANDS", "f9",
                "VAT_RATES", "f10");
        doReturn(versions).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        RefPullService service = service(endpoints, apply, mapper, client);
        service.pullOnce();
        verify(apply).applyFamilies(any());
        verify(apply).applyProducts(any());
        verify(apply).applyVatRates(any());
        verify(apply).applyPrices(any());
        verify(apply).applyEmployees(any());
        verify(apply).applyCouponTypes(any());
        // BO-03-06-02 : la plage article voyage comme les autres domaines.
        verify(apply).applyArticleBarcodeRanges(any());
        verify(apply).applyCheckoutIslands(any());
        verify(apply).applySettings(any());
        verify(apply).applyEngineFeeds(any());
        verify(apply).recordApplied("FAMILIES", "f1");
        verify(apply).recordApplied("PRODUCTS", "f2");
        verify(apply).recordApplied("VAT_RATES", "f10");
        verify(apply).recordApplied("PRICES", "f3");
        verify(apply).recordApplied("EMPLOYEES", "f4");
        verify(apply).recordApplied("COUPON_TYPES", "f5");
        verify(apply).recordApplied("ARTICLE_RANGES", "f8");
        verify(apply).recordApplied("ISLANDS", "f9");
        verify(apply).recordApplied("SETTINGS", "f6");
        verify(apply).recordApplied("ENGINE_FEEDS", "f7");
    }

    /**
     * Covers the store arm of {@code pullDomains} and {@code upstreamUrl}, and
     * the four echelon switch arms of {@code applyDomain}: a store node pulls
     * the echelon domains from the central node and applies each, proving the
     * two-level chain reuses the same loop unchanged (route A).
     */
    @Test
    void pullOnceAppliesEchelonDomainsForStore() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
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
        Map<String, String> versions = Map.of(
                "COUNTRIES", "c1", "ENSEIGNES", "c2", "PDVS", "c3", "ECHELON_SETTINGS", "c4");
        doReturn(versions).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        RefPullService service = service(endpoints, apply, mapper, client);
        service.role = "store";
        service.centralUrl = Optional.of("http://central");
        service.pullOnce();
        verify(apply).applyCountries(any());
        verify(apply).applyEnseignes(any());
        verify(apply).applyPdvs(any());
        verify(apply).applyEchelonSettings(any());
        verify(apply).recordApplied("COUNTRIES", "c1");
        verify(apply).recordApplied("ENSEIGNES", "c2");
        verify(apply).recordApplied("PDVS", "c3");
        verify(apply).recordApplied("ECHELON_SETTINGS", "c4");
    }

    /**
     * Covers the two remaining register switch arms of {@code applyDomain}
     * (CUSTOMERS and CURRENCIES) together with their anonymous
     * {@link TypeReference} deserialization lines: both domains differ, are
     * pulled page by page and applied, then their fingerprints are recorded.
     */
    @Test
    void pullOnceAppliesCustomerAndCurrencyDomains() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
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
        Map<String, String> versions = Map.of("CUSTOMERS", "u1", "CURRENCIES", "u2");
        doReturn(versions).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        RefPullService service = service(endpoints, apply, mapper, client);
        service.pullOnce();
        verify(apply).applyCustomers(any());
        verify(apply).applyCurrencies(any());
        verify(apply).recordApplied("CUSTOMERS", "u1");
        verify(apply).recordApplied("CURRENCIES", "u2");
    }

    /**
     * Covers the two skip arms of {@code pullOnce}: an absent remote
     * fingerprint (null) and one equal to the last applied fingerprint both
     * leave the domain untouched.
     */
    @Test
    void pullOnceSkipsAbsentAndUnchangedDomains() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of("FAMILIES", "same")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        when(apply.lastApplied("FAMILIES")).thenReturn("same");
        RefPullService service = service(endpoints, apply, mapper, client);
        service.pullOnce();
        verify(apply).lastApplied("FAMILIES");
        verify(apply, never()).applyFamilies(any());
        verify(apply, never()).recordApplied(any(), any());
    }

    /**
     * Covers the default arm of {@code applyDomain}: an unknown domain raises
     * an {@link IllegalArgumentException} carrying the offending name.
     */
    @Test
    void applyDomainThrowsOnUnknownDomain() throws Exception {
        RefPullService service = service(mock(SyncEndpoints.class), mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        Method method = RefPullService.class.getDeclaredMethod("applyDomain", String.class, String.class);
        method.setAccessible(true);
        InvocationTargetException wrapper = assertThrows(InvocationTargetException.class,
                () -> method.invoke(service, "UNKNOWN", "fp"));
        assertTrue(wrapper.getCause() instanceof IllegalArgumentException);
        assertEquals("Domaine inconnu: UNKNOWN", wrapper.getCause().getMessage());
    }

    /**
     * Covers the {@code getLastSuccessfulPull} accessor across a pull: null
     * before any cycle has completed, then the timestamp stamped by a clean
     * {@code pullOnce} once one has.
     */
    @Test
    void getLastSuccessfulPullReflectsCycleCompletion() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        assertNull(service.getLastSuccessfulPull());
        service.pullOnce();
        assertNotNull(service.getLastSuccessfulPull());
    }

    // --------------------------------------------------
    // get
    // --------------------------------------------------

    /**
     * Invokes the private {@code get} method by reflection, unwrapping the
     * reflective wrapper on failure.
     *
     * @param service the target service
     * @param path the path to fetch
     * @return the response body
     * @throws Throwable the underlying cause if {@code get} throws
     */
    private String callGet(RefPullService service, String path) throws Throwable {
        Method method = RefPullService.class.getDeclaredMethod("get", String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, path);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Covers the 2xx return path and the header-added arm of {@code get}: a
     * configured non-blank token is sent as the {@code X-Sync-Token} header
     * and the body is returned verbatim.
     */
    @Test
    void getReturnsBodyAndSendsTokenHeader() throws Throwable {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "BODY")).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), client);
        service.token = Optional.of("secret");
        String body = callGet(service, "/path");
        assertEquals("BODY", body);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertEquals("secret", captor.getValue().headers().firstValue("X-Sync-Token").orElse(null));
    }

    /**
     * Covers the header-skipped arm of {@code get}: an empty token option
     * yields a blank shared token, so no {@code X-Sync-Token} header is set.
     */
    @Test
    void getOmitsHeaderWhenTokenBlank() throws Throwable {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "BODY")).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), client);
        service.token = Optional.empty();
        String body = callGet(service, "/path");
        assertEquals("BODY", body);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertFalse(captor.getValue().headers().firstValue("X-Sync-Token").isPresent());
    }

    /**
     * Covers the first operand of the status guard in {@code get}: a status
     * below 200 raises an {@link IllegalStateException}.
     */
    @Test
    void getThrowsOnStatusBelow200() throws Throwable {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(100, null)).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), client);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> callGet(service, "/path"));
        assertEquals("HTTP 100 sur /path", ex.getMessage());
    }

    /**
     * Covers the second operand of the status guard in {@code get}: a status
     * of 300 or more raises an {@link IllegalStateException}.
     */
    @Test
    void getThrowsOnStatusAtLeast300() throws Throwable {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(500, null)).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), client);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> callGet(service, "/path"));
        assertEquals("HTTP 500 sur /path", ex.getMessage());
    }

    /**
     * Covers the success arm of {@code triggerPull}: a manual cycle runs to
     * completion (every fingerprint absent, nothing applied) and returns null,
     * refreshing the last successful pull like a scheduled cycle.
     */
    @Test
    void triggerPullReturnsNullOnCleanCycle() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        assertNull(service.triggerPull());
        assertNotNull(service.getLastSuccessfulPull());
    }

    /**
     * Covers the catch arm of {@code triggerPull}: a failure inside the cycle is
     * caught and its message returned to the screen rather than swallowed.
     */
    @Test
    void triggerPullReturnsErrorMessageOnFailure() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doThrow(new RuntimeException("boom")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, mock(RefApplyService.class), mapper, client);
        assertEquals("boom", service.triggerPull());
    }

    /**
     * {@code getPullSeconds} surfaces the configured pull cadence read-only.
     */
    @Test
    void getPullSecondsReturnsConfiguredCadence() throws Exception {
        RefPullService service = service(mock(SyncEndpoints.class), mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        assertEquals(300L, service.getPullSeconds());
    }
}
