package com.intermarche.pos.service.sync;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EngineFeedDeliveryService}: the delivery of the
 * pending snapshots (POST verbatim with Basic auth, ACK on 2xx), the
 * stop-at-first-failure discipline that preserves the application order,
 * and the survival of an unreachable engine. The engine is a real local
 * {@code HttpServer} (pure JDK) so the HTTP path is exercised for real;
 * the pending snapshots come from a mocked {@link EngineFeedService} —
 * the skipping of absent and acknowledged feeds is the keeper's job,
 * tested in {@code EngineFeedServiceTest}.
 */
class EngineFeedDeliveryServiceTest {

    /** The service under test. */
    private EngineFeedDeliveryService service;

    /** The mocked feed keeper handing snapshots and recording outcomes. */
    private EngineFeedService engineFeedService;

    /** The local fake engine. */
    private HttpServer server;

    /** Status the fake engine answers per path. */
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();

    /** Bodies received by the fake engine per path. */
    private final Map<String, String> bodyByPath = new ConcurrentHashMap<>();

    /** Authorization headers received per path. */
    private final Map<String, String> authByPath = new ConcurrentHashMap<>();

    /**
     * Starts the fake engine and wires a fresh service on it.
     *
     * @throws Exception when the local server cannot start
     */
    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodyByPath.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) authByPath.put(path, auth);
            int status = statusByPath.getOrDefault(path, 200);
            byte[] answer = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        service = new EngineFeedDeliveryService();
        service.url = Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
        service.user = Optional.of("pos");
        service.password = Optional.of("secret");
        service.timeoutMillis = 5000;
        engineFeedService = mock(EngineFeedService.class);
        service.engineFeedService = engineFeedService;
    }

    /**
     * Stops the fake engine.
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /**
     * Builds a detached pending snapshot for a catalog code.
     *
     * @param code the feed code, resolved against the catalog for its path
     * @param version the version awaiting delivery
     * @param lastError the previously recorded error, or null
     * @return the snapshot
     */
    private static EngineFeedService.PendingFeed pending(String code, String version, String lastError) {
        return new EngineFeedService.PendingFeed(code,
                EngineFeedService.catalogEntry(code).orElseThrow().enginePath(),
                "CONTENT-" + code, version, lastError);
    }

    /**
     * A pending snapshot is POSTed verbatim with Basic auth to its engine
     * path and acknowledged on the 2xx answer.
     */
    @Test
    void deliverPendingPostsAndAcknowledges() {
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "v2", null)));
        service.deliverPending();
        verify(engineFeedService).markApplied("PRODUCTS", "v2");
        assertEquals("CONTENT-PRODUCTS", bodyByPath.get("/products/import"));
        assertTrue(authByPath.get("/products/import").startsWith("Basic "));
        assertEquals(1, bodyByPath.size());
    }

    /**
     * A non-2xx answer records the error and STOPS the walk: the feeds
     * after the failing one are not delivered this cycle (application-order
     * preservation).
     */
    @Test
    void deliverPendingStopsAtFirstFailure() {
        statusByPath.put("/products/import", 503);
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "v2", null), pending("OFFERS", "v9", null)));
        service.deliverPending();
        verify(engineFeedService).markError(eq("PRODUCTS"), contains("HTTP 503"));
        verify(engineFeedService, never()).markApplied(eq("OFFERS"), any());
        assertTrue(bodyByPath.keySet().stream().noneMatch(p -> p.contains("offers")));
    }

    /**
     * An unreachable engine records the connection error without killing
     * the loop (exception arm of {@code deliver} + {@code deliverSafely}).
     */
    @Test
    void deliverSafelySurvivesUnreachableEngine() {
        service.url = Optional.of("http://127.0.0.1:1");
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "v2", null)));
        service.deliverSafely();
        verify(engineFeedService).markError(eq("PRODUCTS"), any());
        verify(engineFeedService, never()).markApplied(any(), any());
    }

    /**
     * {@code onStart}: an absent URL disables delivery — the first arm of
     * the guard {@code url.isEmpty() || url.get().isBlank()} short-circuits
     * true, so no executor is scheduled and the keeper is never touched
     * (covers {@code url.isEmpty()==true}).
     */
    @Test
    void onStartWithEmptyUrlDisablesDelivery() {
        service.url = Optional.empty();
        service.onStart(mock(StartupEvent.class));
        service.onStop();
        verifyNoInteractions(engineFeedService);
    }

    /**
     * {@code onStart}: a blank URL disables delivery — the first arm is
     * false and the second arm {@code url.get().isBlank()} is true, so no
     * executor is scheduled (covers {@code isEmpty()==false} and
     * {@code isBlank()==true}).
     */
    @Test
    void onStartWithBlankUrlDisablesDelivery() {
        service.url = Optional.of("   ");
        service.onStart(mock(StartupEvent.class));
        service.onStop();
        verifyNoInteractions(engineFeedService);
    }

    /**
     * {@code onStart}: a present, non-blank URL schedules the delivery loop
     * (both guard arms false) and {@code onStop} then shuts the created
     * executor down (covers the non-null arm of {@code onStop}). The period
     * is set to 3600s so the first cycle (min(20, period) = 20s away) never
     * fires during the test, hence the keeper stays untouched.
     */
    @Test
    void onStartEnabledSchedulesLoopThenOnStopShutsItDown() {
        service.deliverySeconds = 3600;
        service.onStart(mock(StartupEvent.class));
        service.onStop();
        verifyNoInteractions(engineFeedService);
    }

    /**
     * {@code onStop}: when no loop was ever started the executor is null,
     * so the guard's null arm is taken and the call is a harmless no-op
     * (covers {@code executor==null}).
     */
    @Test
    void onStopWithoutExecutorIsNoOp() {
        service.onStop();
        verifyNoInteractions(engineFeedService);
    }

    /**
     * A fully successful walk returns true for every feed: {@code deliver}
     * reaches its {@code return true} (2xx arm) so {@code deliverPending}'s
     * {@code !deliver(feed)} guard is false and the loop runs to its normal
     * exhaustion (covers the loop-exit arm and the continue arm). Versions
     * are 12+ chars so the success log's {@code substring(0, 12)} is safe.
     */
    @Test
    void deliverPendingCompletesWhenEveryFeedAcknowledged() {
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "VERSIONPRODUCTS1", null),
                        pending("OFFERS", "VERSIONOFFERS0001", null)));
        service.deliverPending();
        verify(engineFeedService).markApplied("PRODUCTS", "VERSIONPRODUCTS1");
        verify(engineFeedService).markApplied("OFFERS", "VERSIONOFFERS0001");
        verify(engineFeedService, never()).markError(any(), any());
        assertEquals(2, bodyByPath.size());
    }

    /**
     * An absent auth user omits the Authorization header: the compound
     * guard {@code user.isPresent() && !user.get().isBlank()} short-circuits
     * on its false first arm (covers {@code user.isPresent()==false}). The
     * feed is still delivered and acknowledged.
     */
    @Test
    void deliverWithoutUserOmitsAuthHeader() {
        service.user = Optional.empty();
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "VERSIONPRODUCTS1", null)));
        service.deliverPending();
        verify(engineFeedService).markApplied("PRODUCTS", "VERSIONPRODUCTS1");
        assertEquals("CONTENT-PRODUCTS", bodyByPath.get("/products/import"));
        assertNull(authByPath.get("/products/import"));
    }

    /**
     * A blank auth user omits the Authorization header: the first arm is
     * true but the second arm {@code !user.get().isBlank()} is false
     * (covers {@code isPresent()==true} with {@code isBlank()==true}). The
     * feed is still delivered and acknowledged.
     */
    @Test
    void deliverWithBlankUserOmitsAuthHeader() {
        service.user = Optional.of("");
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "VERSIONPRODUCTS1", null)));
        service.deliverPending();
        verify(engineFeedService).markApplied("PRODUCTS", "VERSIONPRODUCTS1");
        assertEquals("CONTENT-PRODUCTS", bodyByPath.get("/products/import"));
        assertNull(authByPath.get("/products/import"));
    }

    /**
     * A pre-set interrupt makes {@code httpClient.send} throw
     * {@code InterruptedException}: the catch's {@code e instanceof
     * InterruptedException} arm is true, so the thread's interrupt flag is
     * re-raised and the failure is recorded (covers the true arm of the
     * interrupt guard). The flag is consumed here so it never leaks to the
     * next test.
     */
    @Test
    void deliverReinterruptsOnInterruptedException() {
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "VERSIONPRODUCTS1", null)));
        Thread.currentThread().interrupt();
        service.deliverPending();
        assertTrue(Thread.interrupted());
        verify(engineFeedService).markError(eq("PRODUCTS"), contains("InterruptedException"));
        verify(engineFeedService, never()).markApplied(any(), any());
    }

    /**
     * A repeated identical failure is recorded without re-logging: when the
     * feed's {@code lastError} already equals the new error message,
     * {@code Objects.equals} is true so the log guard {@code !equals} is
     * false and only {@code markError} is called (covers the equal arm of
     * {@code recordFailure}).
     */
    @Test
    void recordFailureSkipsLogWhenErrorUnchanged() {
        statusByPath.put("/products/import", 503);
        String repeated = "HTTP 503 sur /products/import: ok";
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "VERSIONPRODUCTS1", repeated)));
        service.deliverPending();
        verify(engineFeedService).markError("PRODUCTS", repeated);
        verify(engineFeedService, never()).markApplied(any(), any());
        assertFalse(bodyByPath.isEmpty());
    }

    /**
     * A 2xx answer to a feed whose version is shorter than twelve characters
     * acknowledges it and records NO error.
     *
     * <p>This is the regression guard of a bug that cost a whole walk: the success log
     * shortened the version with an unguarded {@code substring(0, 12)}, so a short
     * version threw AFTER {@code markApplied} had acknowledged the feed and BEFORE
     * {@code return true}. The method's own catch swallowed the throw, called
     * {@code recordFailure} and returned false — a delivered feed re-recorded as an
     * error, and the walk stopped there.
     */
    @Test
    void deliverAcknowledgesFeedWithShortVersion() {
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "v2", null)));
        service.deliverPending();
        verify(engineFeedService).markApplied("PRODUCTS", "v2");
        verify(engineFeedService, never()).markError(eq("PRODUCTS"), any());
    }

    /**
     * Covers the null arm of {@code shortVersion}: a 2xx answer to a feed whose
     * version is null acknowledges it and logs an empty short version rather
     * than throwing — the defensive null guard on the already-acknowledged
     * success path.
     */
    @Test
    void deliverAcknowledgesFeedWithNullVersion() {
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", null, null)));
        service.deliverPending();
        verify(engineFeedService).markApplied("PRODUCTS", null);
        verify(engineFeedService, never()).markError(eq("PRODUCTS"), any());
    }

    /**
     * Covers the success arm of {@code triggerDelivery}: a manual cycle with
     * nothing pending runs to completion and returns null (no error surfaces).
     */
    @Test
    void triggerDeliveryReturnsNullWhenNothingPending() {
        when(engineFeedService.pendingFeeds()).thenReturn(List.of());
        assertNull(service.triggerDelivery());
    }

    /**
     * Covers the catch arm of {@code triggerDelivery}: an error reaching the
     * pending-feed lookup is caught and its message returned to the screen.
     */
    @Test
    void triggerDeliveryReturnsErrorMessageOnFailure() {
        when(engineFeedService.pendingFeeds()).thenThrow(new RuntimeException("db down"));
        assertEquals("db down", service.triggerDelivery());
    }

    /**
     * {@code getDeliverySeconds} surfaces the configured delivery cadence
     * read-only.
     */
    @Test
    void getDeliverySecondsReturnsConfiguredCadence() {
        service.deliverySeconds = 45;
        assertEquals(45L, service.getDeliverySeconds());
    }
}
