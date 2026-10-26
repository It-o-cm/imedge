package com.intermarche.edge.service.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import org.mockito.MockedStatic;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
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
import static org.mockito.Mockito.mockStatic;
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
        // Unset, the field is 0, and an HttpRequest refuses a zero timeout.
        service.requestTimeoutSeconds = 30L;
        service.token = Optional.empty();
        service.centralUrl = Optional.empty();
        setHttpClient(service, client);
        return service;
    }

    // --------------------------------------------------
    // onStart / onStop
    // --------------------------------------------------

    /**
     * Covers the second operand of the guard in {@code onStart}: a register
     * whose store-node URL is absent does not start the loop.
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

    /**
     * BO-10-07-19: an outage is dated by its first failed cycle, the next
     * failures leaving the date, and ends with the first cycle that reaches
     * the node; before any cycle there is no outage.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void theOutageIsDatedByItsFirstFailedCycle() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, mock(RefApplyService.class), mapper, client);
        java.time.LocalDateTime later = java.time.LocalDateTime.now().plusHours(1);
        assertFalse(service.isUnreachableFor(0, later));
        doThrow(new java.io.IOException("refused")).when(client).send(any(HttpRequest.class), any());
        invoke(service, "pullSafely");
        invoke(service, "pullSafely");
        assertTrue(service.isUnreachableFor(60, later));
        assertFalse(service.isUnreachableFor(7200, later));
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        invoke(service, "pullSafely");
        assertFalse(service.isUnreachableFor(0, later));
    }

    /**
     * The register's link with the store node follows the versions call of
     * each cycle (BO-03-06-45): unknown before the first cycle, lost when the
     * call fails, back as soon as it answers.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void theNodesReachabilityFollowsTheVersionsCall() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, mock(RefApplyService.class), mapper, client);
        assertFalse(service.isNodeUnreachable());
        doThrow(new java.io.IOException("refused")).when(client).send(any(HttpRequest.class), any());
        invoke(service, "pullSafely");
        assertTrue(service.isNodeUnreachable());
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        invoke(service, "pullSafely");
        assertFalse(service.isNodeUnreachable());
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
        // The three big domains go through the raw FILE when the register holds
        // one; here it holds none, so the page path serves, and that is the one
        // this test describes. The finder is neutralized so that it answers
        // "nothing" instead of looking up a non-enhanced entity.
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> none = mock(PanacheQuery.class);
            when(none.firstResult()).thenReturn(null);
            panache.when(() -> com.intermarche.edge.domain.sync.EngineFeed
                    .find(eq("code"), any(Object[].class))).thenReturn(none);
            service.pullOnce();
        }
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
     * BO-03-09/BO-03-10: a caisse names itself on the request — a non-blank
     * {@code pos.terminal.id} is sent as the {@code X-Pos-Terminal} header (and
     * trimmed), so an updated node can resolve the settings by contact point,
     * island then PDV.
     */
    @Test
    void getSendsTerminalHeaderWhenKnown() throws Throwable {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "BODY")).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), client);
        service.terminalId = " C04 ";
        callGet(service, "/path");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertEquals("C04", captor.getValue().headers().firstValue("X-Pos-Terminal").orElse(null));
    }

    /**
     * Compat: a blank terminal id adds NO {@code X-Pos-Terminal} header, so a
     * caisse that has not been named — and, symmetrically, an older node that
     * would ignore the header — keep working exactly as before.
     */
    @Test
    void getOmitsTerminalHeaderWhenBlank() throws Throwable {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "BODY")).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), client);
        service.terminalId = "   ";
        callGet(service, "/path");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertFalse(captor.getValue().headers().firstValue("X-Pos-Terminal").isPresent());
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

    // --------------------------------------------------
    // applyFromFeedFile (via applyDomain reflection)
    // --------------------------------------------------

    /**
     * Invokes the private {@code applyDomain(String, String)} method by
     * reflection, unwrapping the reflective wrapper on failure.
     *
     * @param service the target service
     * @param domain the domain to apply
     * @param fingerprint the fingerprint to record on success
     * @throws Throwable the underlying cause if {@code applyDomain} throws
     */
    private void callApplyDomain(RefPullService service, String domain, String fingerprint) throws Throwable {
        Method method = RefPullService.class.getDeclaredMethod("applyDomain", String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(service, domain, fingerprint);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Covers the file-served path of {@code applyFromFeedFile} and the true arm
     * of the {@code &&} guard in {@code applyDomain} (early return): a
     * {@code FILE_DOMAINS} domain whose feed content is present and non-blank
     * and whose {@code applyFromFile} yields a non-null response is applied from
     * the raw file, its fingerprint recorded, and the paging/switch path is
     * never reached. This also covers the false arms of {@code content == null},
     * {@code content.isBlank()} and {@code applyFromFile(...) == null}.
     */
    @Test
    void applyDomainAppliesFromFeedFileWhenFilePresent() throws Throwable {
        RefApplyService apply = mock(RefApplyService.class);
        HttpClient client = mock(HttpClient.class);
        when(apply.feedContent("PRODUCTS")).thenReturn("CSV;DATA");
        when(apply.feedStamp("PRODUCTS")).thenReturn(new RefApplyService.FeedStamp("fp-0", "fp-file"));
        when(apply.lastApplied("PRODUCTS")).thenReturn("fp-0");
        when(apply.applyFromFile("PRODUCTS", "CSV;DATA")).thenReturn(mock(jakarta.ws.rs.core.Response.class));
        RefPullService service = service(mock(SyncEndpoints.class), apply, mock(ObjectMapper.class), client);
        callApplyDomain(service, "PRODUCTS", "fp-served");
        verify(apply).applyFromFile("PRODUCTS", "CSV;DATA");
        verify(apply).recordApplied("PRODUCTS", "fp-file");
        verify(apply, never()).applyProducts(any());
        verify(client, never()).send(any(), any());
    }

    /**
     * A raw file already applied cannot explain a new fingerprint: the second
     * change of the domain — a back-office change the file does not carry —
     * is applied from the snapshot pages, and a NEW file is applied from the
     * file again.
     */
    @Test
    void aFeedFileAlreadyAppliedGivesWayToThePages() throws Throwable {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        when(apply.feedContent("PRODUCTS")).thenReturn("CSV;DATA", "CSV;DATA", "CSV;DATA2");
        when(apply.feedStamp("PRODUCTS")).thenReturn(new RefApplyService.FeedStamp("", "fp-1"),
                new RefApplyService.FeedStamp("fp-2", "fp-3"));
        when(apply.lastApplied("PRODUCTS")).thenReturn(null, "fp-2");
        when(apply.applyFromFile(eq("PRODUCTS"), any())).thenReturn(mock(jakarta.ws.rs.core.Response.class));
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String query = request.uri().getQuery();
            return resp(200, query != null && query.contains("page=0") ? "PAGE0" : "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        callApplyDomain(service, "PRODUCTS", "fp-1");
        callApplyDomain(service, "PRODUCTS", "fp-2");
        verify(apply).applyFromFile("PRODUCTS", "CSV;DATA");
        verify(apply).applyProducts(any());
        verify(apply).recordApplied("PRODUCTS", "fp-2");
        callApplyDomain(service, "PRODUCTS", "fp-3");
        verify(apply).applyFromFile("PRODUCTS", "CSV;DATA2");
        verify(apply).recordApplied("PRODUCTS", "fp-3");
    }

    /**
     * Covers the blank arm of {@code content.isBlank()} in
     * {@code applyFromFeedFile}: a {@code FILE_DOMAINS} domain whose feed
     * content is present but blank falls back on the pages, so the file path
     * neither applies nor records anything and the snapshot switch runs instead.
     */
    @Test
    void applyDomainFallsBackToPagesWhenFeedContentBlank() throws Throwable {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        when(apply.feedContent("PRICES")).thenReturn("   ");
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String query = request.uri().getQuery();
            return resp(200, query != null && query.contains("page=0") ? "PAGE0" : "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        callApplyDomain(service, "PRICES", "fp-blank");
        verify(apply, never()).applyFromFile(any(), any());
        verify(apply).applyPrices(any());
        verify(apply).recordApplied("PRICES", "fp-blank");
    }

    /**
     * Covers the true arm of {@code applyFromFile(...) == null} in
     * {@code applyFromFeedFile}: a {@code FILE_DOMAINS} domain whose feed
     * content is present and non-blank but whose {@code applyFromFile} returns
     * null (the file was not served by that route) falls back on the pages, so
     * the snapshot switch runs and records the fingerprint instead.
     */
    @Test
    void applyDomainFallsBackToPagesWhenApplyFromFileReturnsNull() throws Throwable {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        when(apply.feedContent("FAMILIES")).thenReturn("CSV;DATA");
        when(apply.feedStamp("FAMILIES")).thenReturn(new RefApplyService.FeedStamp("", "r"));
        when(apply.applyFromFile("FAMILIES", "CSV;DATA")).thenReturn(null);
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String query = request.uri().getQuery();
            return resp(200, query != null && query.contains("page=0") ? "PAGE0" : "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        callApplyDomain(service, "FAMILIES", "fp-nullfile");
        verify(apply).applyFromFile("FAMILIES", "CSV;DATA");
        verify(apply).applyFamilies(any());
        verify(apply).recordApplied("FAMILIES", "fp-nullfile");
    }

    /**
     * Covers the remaining switch arms of {@code applyDomain}
     * (TOUCH_GROUPS, TENDERS, DOCUMENT_TEMPLATES, POSTAL_CODES, POS_LISTS,
     * STORE_IMAGES, RETURN_TYPES and PREPAID_CARD_TYPES) together with their anonymous {@link TypeReference}
     * deserialization lines: none of them belongs to {@code FILE_DOMAINS},
     * so each is pulled page by page through the snapshot switch and applied,
     * then its fingerprint recorded.
     */
    @Test
    void pullOnceAppliesRemainingSwitchDomains() throws Exception {
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
                "TOUCH_GROUPS", "t1", "TENDERS", "t2",
                "DOCUMENT_TEMPLATES", "t3", "POSTAL_CODES", "t4",
                "POS_LISTS", "t5", "STORE_IMAGES", "t6",
                "RETURN_TYPES", "t7", "PREPAID_CARD_TYPES", "t8");
        doReturn(versions).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        RefPullService service = service(endpoints, apply, mapper, client);
        service.pullOnce();
        verify(apply).applyTouchGroups(any());
        verify(apply).applyTenders(any());
        verify(apply).applyDocumentTemplates(any());
        verify(apply).applyPostalCodes(any());
        verify(apply).applyPosLists(any());
        verify(apply).applyStoreImages(any());
        verify(apply).recordApplied("TOUCH_GROUPS", "t1");
        verify(apply).recordApplied("TENDERS", "t2");
        verify(apply).recordApplied("DOCUMENT_TEMPLATES", "t3");
        verify(apply).recordApplied("POSTAL_CODES", "t4");
        verify(apply).recordApplied("POS_LISTS", "t5");
        verify(apply).recordApplied("STORE_IMAGES", "t6");
        verify(apply).applyReturnTypes(any());
        verify(apply).applyPrepaidCardTypes(any());
        verify(apply).recordApplied("RETURN_TYPES", "t7");
        verify(apply).recordApplied("PREPAID_CARD_TYPES", "t8");
    }

    // --------------------------------------------------
    // Per-domain error map (getLastError / getLastErrors)
    // --------------------------------------------------

    /**
     * Builds a mocked HTTP client routing every request the standard way: the
     * {@code /versions} path yields {@code "VERSIONS"}, the first page yields a
     * non-empty {@code "PAGE0"} body and any later page an empty {@code "PAGEN"}
     * body, so one non-empty page then a break drives every domain's snapshot.
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
     * Covers the catch arm of {@code pullOnce} together with both arms of
     * {@code getLastError}: with EMPLOYEES changed and applied but COUPON_TYPES
     * changed and throwing, the faulty domain does NOT interrupt the cycle — the
     * healthy one is still applied — its message is recorded, and
     * {@code getLastError} returns null for the healthy domain and the message
     * for the faulty one. The whole cycle then rethrows, which the caller reports.
     */
    @Test
    void getLastErrorDistinguishesHealthyFromFaultyDomain() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = versionedClient();
        doReturn(Map.of("EMPLOYEES", "e1", "COUPON_TYPES", "c1")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        doThrow(new IllegalStateException("kaboom")).when(apply).applyCouponTypes(any());
        RefPullService service = service(endpoints, apply, mapper, client);
        assertThrows(IllegalStateException.class, service::pullOnce);
        verify(apply).applyEmployees(any());
        verify(apply).recordApplied("EMPLOYEES", "e1");
        verify(apply, never()).recordApplied(eq("COUPON_TYPES"), any());
        assertNull(service.getLastError("EMPLOYEES"));
        assertEquals("kaboom", service.getLastError("COUPON_TYPES"));
        assertEquals(1, service.getLastErrors().size());
        assertEquals("kaboom", service.getLastErrors().get("COUPON_TYPES"));
    }

    /**
     * Covers the {@code firstFailure != null} skip arm of {@code pullOnce}: with
     * two domains throwing, only the FIRST in iteration order (EMPLOYEES, which
     * precedes COUPON_TYPES in {@code DOMAINS}) is carried in the rethrown cycle
     * message, while both failures are recorded in the per-domain map.
     */
    @Test
    void pullOnceReportsFirstFailureWhenSeveralDomainsFail() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
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
        RefPullService service = service(endpoints, apply, mapper, client);
        IllegalStateException ex = assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("EMPLOYEES: e-emp", ex.getMessage());
        assertEquals("e-emp", service.getLastError("EMPLOYEES"));
        assertEquals("e-coup", service.getLastError("COUPON_TYPES"));
        assertEquals(2, service.getLastErrors().size());
    }

    /**
     * Covers the null-message coalescing arm of {@code pullOnce}: a domain that
     * throws with no message is recorded under its exception class simple name
     * rather than a null.
     */
    @Test
    void getLastErrorUsesExceptionClassNameWhenMessageNull() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = versionedClient();
        doReturn(Map.of("COUPON_TYPES", "c1")).when(mapper)
                .readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        doThrow(new IllegalStateException()).when(apply).applyCouponTypes(any());
        RefPullService service = service(endpoints, apply, mapper, client);
        assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("IllegalStateException", service.getLastError("COUPON_TYPES"));
    }

    /**
     * Covers the success-arm {@code lastErrors.remove} of {@code pullOnce}: a
     * domain that failed one cycle and then applies the next is cleared from the
     * per-domain map — the map is self-healing, reporting only what is still
     * broken.
     */
    @Test
    void getLastErrorClearsWhenDomainRecovers() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
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
        RefPullService service = service(endpoints, apply, mapper, client);
        assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("kaboom", service.getLastError("COUPON_TYPES"));
        service.pullOnce();
        assertNull(service.getLastError("COUPON_TYPES"));
        assertTrue(service.getLastErrors().isEmpty());
    }

    /**
     * {@code getLastErrors} exposes a read-only view: a clean cycle leaves it
     * empty and the returned map refuses mutation, so the screen cannot corrupt
     * the service's state.
     */
    @Test
    void getLastErrorsReturnsUnmodifiableEmptyMapOnCleanCycle() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "VERSIONS")).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, mock(RefApplyService.class), mapper, client);
        service.pullOnce();
        Map<String, String> errors = service.getLastErrors();
        assertTrue(errors.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> errors.put("X", "y"));
    }

    /** BO-03-08/09: provider wording and checkout identities are downloaded, applied and acknowledged as complete snapshots. */
    @Test
    void ancillaryReferentialsAreAppliedAndAcknowledged() throws Exception {
        var endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        var apply = mock(RefApplyService.class);
        var mapper = mock(ObjectMapper.class);
        var client = mock(HttpClient.class);
        var response = resp(200, "EMPTY");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        doReturn(List.of()).when(mapper).readValue(eq("EMPTY"), any(TypeReference.class));
        var service = service(endpoints, apply, mapper, client);
        var method = RefPullService.class.getDeclaredMethod("applyDomain", String.class, String.class);
        method.setAccessible(true);
        method.invoke(service, "PROVIDER_MESSAGES", "v1");
        verify(apply).applyProviderMessages(List.of());
        verify(apply).recordApplied("PROVIDER_MESSAGES", "v1");
        method.invoke(service, "CHECKOUT_POINTS", "v1");
        verify(apply).applyCheckoutPoints(List.of());
        verify(apply).recordApplied("CHECKOUT_POINTS", "v1");        // BO-03-04-06/09/10: the printable documents travel the same road.
        method.invoke(service, "PRINTABLE_DOCUMENTS", "v1");
        verify(apply).applyPrintableDocuments(List.of());
        verify(apply).recordApplied("PRINTABLE_DOCUMENTS", "v1");
    }

    /**
     * BO-01-01-03, BO-01-03-04: a store pulls from the centre the profiles, the
     * accounts and the assignments of its point of sale, naming its point of
     * sale in the {@code X-Pos-Pdv} header.
     *
     * @throws Exception never, the collaborators are mocked
     */
    @Test
    void aStorePullsItsAccountsNamingItsPointOfSale() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        List<HttpRequest> sent = new java.util.ArrayList<>();
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            sent.add(request);
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
        Map<String, String> versions = Map.of("PROFILES", "p1", "ACCOUNTS", "a1", "ACCOUNT_PROFILES", "b1");
        doReturn(versions).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        service.role = "store";
        service.centralUrl = Optional.of("http://central");
        service.nodeEchelonService = mock(com.intermarche.edge.service.NodeEchelonService.class);
        when(service.nodeEchelonService.currentPdv()).thenReturn(" 07039 ");
        service.pullOnce();
        verify(apply).applyProfiles(any());
        verify(apply).applyAccounts(any());
        verify(apply).applyAccountProfiles(any());
        verify(apply).recordApplied("ACCOUNTS", "a1");
        assertEquals("07039", sent.get(0).headers().firstValue("X-Pos-Pdv").orElse(null));
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
     * Covers the four remaining register switch arms of {@code applyDomain}
     * (TOUCH_GROUPS, TENDERS, DOCUMENT_TEMPLATES and POSTAL_CODES) together
     * with their anonymous {@link TypeReference} deserialization lines: each
     * domain differs, is pulled page by page and applied, then its fingerprint
     * is recorded.
     */
    @Test
    void pullOnceAppliesTouchTenderTemplateAndPostalDomains() throws Exception {
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
                "TOUCH_GROUPS", "t1", "TENDERS", "t2", "DOCUMENT_TEMPLATES", "t3", "POSTAL_CODES", "t4");
        doReturn(versions).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        RefPullService service = service(endpoints, apply, mapper, client);
        service.pullOnce();
        verify(apply).applyTouchGroups(any());
        verify(apply).applyTenders(any());
        verify(apply).applyDocumentTemplates(any());
        verify(apply).applyPostalCodes(any());
        verify(apply).recordApplied("TOUCH_GROUPS", "t1");
        verify(apply).recordApplied("TENDERS", "t2");
        verify(apply).recordApplied("DOCUMENT_TEMPLATES", "t3");
        verify(apply).recordApplied("POSTAL_CODES", "t4");
    }

    /**
     * Covers the removal arm of {@code pullOnce}'s per-domain try/catch: a
     * domain that failed on a previous cycle and applies cleanly on the next
     * has its retained error cleared from {@code getLastErrors}, and the clean
     * cycle stamps {@code getLastSuccessfulPull}.
     */
    @Test
    void pullOnceClearsStaleDomainErrorOnSuccess() throws Exception {
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
        doReturn(Map.of("FAMILIES", "f1")).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        doThrow(new RuntimeException("first cycle broke")).doNothing().when(apply).applyFamilies(any());
        RefPullService service = service(endpoints, apply, mapper, client);
        assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("first cycle broke", service.getLastError("FAMILIES"));
        service.pullOnce();
        assertNull(service.getLastError("FAMILIES"));
        assertTrue(service.getLastErrors().isEmpty());
        assertNotNull(service.getLastSuccessfulPull());
    }

    /**
     * Covers the error arm of {@code pullOnce}'s per-domain try/catch: one
     * domain applies cleanly (its stale error, if any, is removed) while a
     * second fails with a non-null message and a third fails with a null
     * message (the {@code getMessage() == null} ternary), so both ternary arms
     * are taken. The first failure is rethrown as an {@link IllegalStateException}
     * and both failures are retained in {@code getLastErrors}/{@code getLastError},
     * the succeeding domain absent. Also asserts the returned map is unmodifiable.
     */
    @Test
    void pullOnceRetainsEachDomainErrorAndRethrowsFirst() throws Exception {
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
                "ENGINE_FEEDS", "e1", "PRODUCTS", "p1", "FAMILIES", "f1");
        doReturn(versions).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        doThrow(new RuntimeException("feeds broke")).when(apply).applyEngineFeeds(any());
        doThrow(new NullPointerException()).when(apply).applyProducts(any());
        RefPullService service = service(endpoints, apply, mapper, client);
        IllegalStateException ex = assertThrows(IllegalStateException.class, service::pullOnce);
        assertEquals("ENGINE_FEEDS: feeds broke", ex.getMessage());
        Map<String, String> errors = service.getLastErrors();
        assertEquals("feeds broke", errors.get("ENGINE_FEEDS"));
        assertEquals("NullPointerException", errors.get("PRODUCTS"));
        assertNull(errors.get("FAMILIES"));
        assertEquals("feeds broke", service.getLastError("ENGINE_FEEDS"));
        assertEquals("NullPointerException", service.getLastError("PRODUCTS"));
        assertNull(service.getLastError("FAMILIES"));
        assertThrows(UnsupportedOperationException.class, () -> errors.put("X", "y"));
        assertNull(service.getLastSuccessfulPull());
    }

    /**
     * A store without commissioned point of sale, a blank one, a store without
     * echelon service and a register name none.
     *
     * @throws Throwable never, the collaborators are mocked
     */
    @Test
    void thePointOfSaleIsNamedOnlyByACommissionedStore() throws Throwable {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "BODY")).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class), mock(ObjectMapper.class), client);
        service.nodeEchelonService = mock(com.intermarche.edge.service.NodeEchelonService.class);
        when(service.nodeEchelonService.currentPdv()).thenReturn(null, " ");
        callGet(service, "/path");
        service.role = "store";
        service.centralUrl = Optional.of("http://central");
        callGet(service, "/path");
        callGet(service, "/path");
        service.nodeEchelonService = null;
        callGet(service, "/path");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, org.mockito.Mockito.times(4)).send(captor.capture(), any());
        for (HttpRequest request : captor.getAllValues()) {
            assertFalse(request.headers().firstValue("X-Pos-Pdv").isPresent());
        }
    }

    /** BO-08-01-04: an audit-storage failure is contained so referential synchronization does not fail solely on its trace. */
    @Test
    void unavailableAuditStorageDoesNotEscapeThePull() throws Exception {
        var service = new RefPullService();
        service.refApplyService = mock(RefApplyService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(service.refApplyService).recordAudit("PRODUCTS", "v1", com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED, "done");
        var audit = RefPullService.class.getDeclaredMethod("recordAudit", String.class, String.class, com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.class, String.class);
        audit.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> audit.invoke(service, "PRODUCTS", "v1", com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED, "done"));
        org.mockito.Mockito.verify(service.refApplyService).recordAudit("PRODUCTS", "v1", com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED, "done");
    }

    // --------------------------------------------------
    // BO-10-08-25: no update reaches a sale in progress
    // --------------------------------------------------

    /**
     * Builds a service whose versions answer one changed VAT_RATES domain and
     * whose snapshot pages answer one row then nothing.
     *
     * @param apply the apply-service mock
     * @return the wired service
     * @throws Exception if reflection fails
     */
    private RefPullService vatRateService(RefApplyService apply) throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String query = request.uri().getQuery();
            if (request.uri().getPath().endsWith("/versions")) {
                return resp(200, "VERSIONS");
            }
            return resp(200, query != null && query.contains("page=0") ? "PAGE0" : "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of("VAT_RATES", "v1")).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        return service(endpoints, apply, mapper, client);
    }

    /**
     * Builds the probe instance a register resolves.
     *
     * @param probe the probe it resolves to, or null for an unresolvable one
     * @return the instance mock
     */
    @SuppressWarnings("unchecked")
    private jakarta.enterprise.inject.Instance<SaleActivityProbe> probeInstance(SaleActivityProbe probe) {
        jakarta.enterprise.inject.Instance<SaleActivityProbe> instance = mock(jakarta.enterprise.inject.Instance.class);
        when(instance.isResolvable()).thenReturn(probe != null);
        when(instance.get()).thenReturn(probe);
        return instance;
    }

    /**
     * A sale domain changed during a sale waits: nothing applied, nothing
     * recorded, the deferral audited once over two cycles and a cycle brought
     * forward once; the sale over, the update applies.
     */
    @Test
    void aSaleInProgressDefersTheSaleDomains() throws Exception {
        RefApplyService apply = mock(RefApplyService.class);
        RefPullService service = vatRateService(apply);
        SaleActivityProbe probe = mock(SaleActivityProbe.class);
        when(probe.saleInProgress()).thenReturn(true, true, false);
        service.saleActivity = probeInstance(probe);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        Field field = RefPullService.class.getDeclaredField("executor");
        field.setAccessible(true);
        field.set(service, executor);
        service.pullOnce();
        service.pullOnce();
        verify(apply, never()).applyVatRates(any());
        verify(apply, never()).recordApplied(any(), any());
        verify(apply, org.mockito.Mockito.times(1)).recordAudit("VAT_RATES", "v1",
                com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.DEFERRED, "Vente en cours");
        verify(executor, org.mockito.Mockito.times(1)).schedule(any(Runnable.class),
                eq(RefPullService.DEFER_RETRY_SECONDS), eq(java.util.concurrent.TimeUnit.SECONDS));
        service.pullOnce();
        verify(apply).applyVatRates(any());
        verify(apply).recordApplied("VAT_RATES", "v1");
    }

    /**
     * The brought-forward cycle frees the retry slot and runs a guarded pull;
     * a deferral with no loop running schedules nothing.
     */
    @Test
    void theRetryRunsAGuardedCycleAndFreesItsSlot() throws Exception {
        RefApplyService apply = mock(RefApplyService.class);
        RefPullService service = vatRateService(apply);
        SaleActivityProbe probe = mock(SaleActivityProbe.class);
        when(probe.saleInProgress()).thenReturn(true);
        service.saleActivity = probeInstance(probe);
        service.retryDeferred();
        verify(apply, never()).applyVatRates(any());
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        Field field = RefPullService.class.getDeclaredField("executor");
        field.setAccessible(true);
        field.set(service, executor);
        service.pullOnce();
        service.retryDeferred();
        verify(executor, org.mockito.Mockito.times(2)).schedule(any(Runnable.class),
                eq(RefPullService.DEFER_RETRY_SECONDS), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    /**
     * A node with no probe, or whose probe cannot be resolved, never defers;
     * a domain the sale is not made of is applied even during a sale.
     */
    @Test
    void aNodeThatSellsNothingNeverDefers() throws Exception {
        RefApplyService unresolvable = mock(RefApplyService.class);
        RefPullService service = vatRateService(unresolvable);
        service.saleActivity = probeInstance(null);
        service.pullOnce();
        verify(unresolvable).applyVatRates(any());
        RefApplyService absent = mock(RefApplyService.class);
        RefPullService bare = vatRateService(absent);
        bare.saleActivity = null;
        bare.pullOnce();
        verify(absent).applyVatRates(any());
        assertFalse(RefPullService.SALE_DOMAINS.contains("EMPLOYEES"));
    }

    /**
     * BO-03-12-01: with a positive watch cadence the loop also schedules the
     * stamp watcher; the executor then still shuts down cleanly.
     */
    @Test
    void onStartAlsoSchedulesTheStampWatcher() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.hasStoreUrl()).thenReturn(true);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefPullService service = service(endpoints, mock(RefApplyService.class),
                mock(ObjectMapper.class), mock(HttpClient.class));
        service.watchSeconds = 5L;
        service.onStart(null);
        ScheduledExecutorService executor = getExecutor(service);
        assertTrue(executor != null && !executor.isShutdown());
        service.onStop();
        assertTrue(executor.isShutdown());
    }

    /**
     * BO-03-12-01: the first stamp is only recorded, the same stamp pulls
     * nothing, a moved stamp runs a cycle at once.
     */
    @Test
    void aMovedStampRunsACycleAtOnce() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        java.util.concurrent.atomic.AtomicInteger stamp = new java.util.concurrent.atomic.AtomicInteger(1);
        java.util.concurrent.atomic.AtomicInteger cycles = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            if (request.uri().getPath().endsWith("/stamp")) {
                return resp(200, " " + stamp.get() + "\n");
            }
            cycles.incrementAndGet();
            return resp(200, "VERSIONS");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of()).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        RefPullService service = service(endpoints, mock(RefApplyService.class), mapper, client);
        service.watchOnce();
        service.watchOnce();
        assertEquals(0, cycles.get());
        stamp.set(2);
        service.watchOnce();
        assertEquals(1, cycles.get());
    }

    /** BO-03-12-01: an unreadable stamp leaves the loop alive. */
    @Test
    void anUnreadableStampIsSwallowed() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> resp(404, "")).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class), mock(ObjectMapper.class), client);
        service.watchSafely();
        verify(client).send(any(HttpRequest.class), any());
    }

    /**
     * BO-03-12-01: a readable stamp goes through the safe watch untouched,
     * the first read only recording it.
     *
     * @throws Exception never
     */
    @Test
    void aReadableStampIsRecordedThroughTheSafeWatch() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> resp(200, "s1")).when(client).send(any(HttpRequest.class), any());
        RefApplyService apply = mock(RefApplyService.class);
        RefPullService service = service(endpoints, apply, mock(ObjectMapper.class), client);
        service.watchSafely();
        verify(client).send(any(HttpRequest.class), any());
        org.mockito.Mockito.verifyNoInteractions(apply);
    }

    /**
     * BO-03-12-01: a stamp read that throws is swallowed too.
     *
     * @throws Exception never
     */
    @Test
    void aFailingStampReadIsSwallowed() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("down")).when(client).send(any(HttpRequest.class), any());
        RefPullService service = service(endpoints, mock(RefApplyService.class), mock(ObjectMapper.class), client);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(service::watchSafely);
    }

    /** BO-03-10-16/19: the cash menus and the store record are applied like any domain. */
    @Test
    void theCashMenusAndTheStoreAreApplied() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String query = request.uri().getQuery();
            if (request.uri().getPath().endsWith("/versions")) {
                return resp(200, "VERSIONS");
            }
            return resp(200, query != null && query.contains("page=0") ? "PAGE0" : "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of("CASH_MENUS", "m1", "STORE", "s1")).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        service.pullOnce();
        verify(apply).applyCashMenus(any());
        verify(apply).applyStore(any());
    }

    /**
     * BO-02-03-17: a changed MEASURE_UNITS domain is pulled page by page and
     * applied, then its fingerprint is recorded.
     *
     * @throws Exception on a mocked transport failure
     */
    @Test
    void pullOnceAppliesTheMeasureUnits() throws Exception {
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String query = request.uri().getQuery();
            if (request.uri().getPath().endsWith("/versions")) {
                return resp(200, "VERSIONS");
            }
            return resp(200, query != null && query.contains("page=0") ? "PAGE0" : "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(Map.of("MEASURE_UNITS", "m1")).when(mapper).readValue(eq("VERSIONS"), any(TypeReference.class));
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        when(apply.lastApplied(any())).thenReturn(null);
        service(endpoints, apply, mapper, client).pullOnce();
        verify(apply).applyMeasureUnits(any());
        verify(apply).recordApplied("MEASURE_UNITS", "m1");
    }

    /**
     * BO-02-03-33: a file the register cannot start from — unstamped, or
     * integrated over another state than the one the register stands at, as
     * after a missed delta — is not applied: the pages are.
     *
     * @throws Throwable on a reflective failure
     */
    @Test
    void aFileTheRegisterDoesNotStartFromGivesWayToThePages() throws Throwable {
        RefApplyService apply = mock(RefApplyService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        HttpClient client = mock(HttpClient.class);
        when(apply.feedContent("PRODUCTS")).thenReturn("CSV;DATA");
        when(apply.feedStamp("PRODUCTS")).thenReturn(null, new RefApplyService.FeedStamp("fp-store", "fp-after"));
        when(apply.lastApplied("PRODUCTS")).thenReturn("fp-register");
        SyncEndpoints endpoints = mock(SyncEndpoints.class);
        when(endpoints.storeUrl()).thenReturn("http://store");
        doAnswer(inv -> {
            HttpRequest request = inv.getArgument(0);
            String query = request.uri().getQuery();
            return resp(200, query != null && query.contains("page=0") ? "PAGE0" : "PAGEN");
        }).when(client).send(any(HttpRequest.class), any());
        doReturn(List.of("row")).when(mapper).readValue(eq("PAGE0"), any(TypeReference.class));
        doReturn(List.of()).when(mapper).readValue(eq("PAGEN"), any(TypeReference.class));
        RefPullService service = service(endpoints, apply, mapper, client);
        callApplyDomain(service, "PRODUCTS", "fp-1");
        callApplyDomain(service, "PRODUCTS", "fp-2");
        verify(apply, never()).applyFromFile(any(), any());
        verify(apply, org.mockito.Mockito.times(2)).applyProducts(any());
        verify(apply).recordApplied("PRODUCTS", "fp-2");
    }
}
