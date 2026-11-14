package com.intermarche.edge.service.sync.register;

import com.intermarche.edge.domain.sync.FidEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FidEventOutboxService}.
 * <p>
 * The service owns its {@link java.net.http.HttpClient} as a private final
 * field, so the drain is exercised against a real JDK {@link HttpServer} on an
 * ephemeral port — which is the point: what deserves testing here is the WIRE
 * and the RETRY POLICY, both of which a mocked client would hide.
 * <p>
 * The pending rows are supplied by intercepting {@code FidEvent.findPending}
 * ({@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}, since
 * entities are un-enhanced under plain {@code mvn test}), and {@code drain()}
 * is called directly rather than through the scheduler — the loop mechanics
 * are covered separately by the lifecycle tests.
 */
class FidEventOutboxServiceTest {

    /** The stub loyalty service. */
    private HttpServer server;

    /** The service under test, pointed at the stub. */
    private FidEventOutboxService service;

    /** The bodies received by the stub, in order. */
    private final List<String> receivedBodies = new ArrayList<>();

    /** The paths received by the stub, in order. */
    private final List<String> receivedPaths = new ArrayList<>();

    /** The Authorization header of the last call, or null. */
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    /** The loyalty call each request named, in order (BO-11-04-05, -06). */
    private final List<String> receivedCalls = new ArrayList<>();

    /** The status the stub answers next. */
    private final AtomicInteger answeredStatus = new AtomicInteger(202);

    /**
     * Starts the stub server and wires a configured service onto it.
     *
     * @throws IOException if the server cannot bind
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/events", this::record);
        server.createContext("/api/gt", this::record);
        server.start();
        service = new FidEventOutboxService();
        service.url = Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
        service.user = Optional.of("pos");
        service.password = Optional.of("pos-password");
    }

    /**
     * Stops the stub server and the service loop.
     */
    @AfterEach
    void tearDown() {
        service.onStop();
        server.stop(0);
    }

    /**
     * Records the incoming request and answers the canned status.
     *
     * @param exchange the HTTP exchange
     * @throws IOException on write failure
     */
    private void record(HttpExchange exchange) throws IOException {
        receivedPaths.add(exchange.getRequestURI().getPath());
        receivedCalls.add(exchange.getRequestHeaders().getFirst(FidEventOutboxService.CALL_HEADER));
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(answeredStatus.get(), -1);
        try (OutputStream out = exchange.getResponseBody()) {
            out.flush();
        }
        exchange.close();
    }

    /**
     * Builds a pending event.
     *
     * @param id the row id
     * @param type the event kind
     * @param payload the frozen payload
     * @return the event
     */
    private FidEvent event(long id, FidEvent.EventType type, String payload) {
        FidEvent event = new FidEvent();
        event.id = id;
        event.eventType = type;
        event.payloadJson = payload;
        return event;
    }

    /**
     * Runs one drain cycle with the given pending rows.
     *
     * @param pending the rows the registry hands back
     */
    private void drainWith(List<FidEvent> pending) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id")).thenReturn(pending);
            // The drain opens its transaction explicitly (a self-call from the
            // executor thread would bypass the interceptor). Outside Quarkus
            // there is no transaction manager, so the runner is intercepted
            // and simply RUNS the body — what the test observes is the drain
            // itself, not the JTA plumbing.
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });
            service.drain();
        }
    }

    // --- lifecycle ---

    /**
     * An UNCONFIGURED service still arms its loop: the address is read on every
     * tick (BO-11-04-06), so a register administered later declares its sales
     * without a restart; a tick with no address drains nothing.
     */
    @Test
    void onStartArmsTheLoopEvenUnconfigured() {
        FidEventOutboxService bare = new FidEventOutboxService();
        bare.url = Optional.empty();
        bare.onStart(null);
        assertDoesNotThrow(bare::onStop);
    }

    /**
     * A CONFIGURED service starts its daemon loop, and stopping it twice is
     * harmless — shutdown must never be a source of errors.
     */
    @Test
    void onStartAndOnStopAreSafe() {
        service.onStart(null);
        assertDoesNotThrow(service::onStop);
        assertDoesNotThrow(service::onStop);
    }

    // --- enqueue ---

    /**
     * Enqueuing persists a PENDING row carrying the kind, the frozen payload
     * and the creation instant. The payload is stored as given: it embeds the
     * verbatim valuation couple, which only exists in memory at the fiscal
     * moment and could not be rebuilt later.
     */
    @Test
    void enqueuePersistsAPendingRow() {
        try (MockedConstruction<FidEvent> rows = mockConstruction(FidEvent.class)) {
            service.enqueue(FidEvent.EventType.TICKET_CLOSED, "{\"ticketRef\":\"2026-C04-1\"}");

            assertEquals(1, rows.constructed().size());
            FidEvent row = rows.constructed().get(0);
            assertEquals(FidEvent.EventType.TICKET_CLOSED, row.eventType);
            assertEquals("{\"ticketRef\":\"2026-C04-1\"}", row.payloadJson);
            assertNotNull(row.createdAt);
            assertNull(row.sentAt);
            verify(row).persist();
        }
    }

    /**
     * A return event is enqueued the same way — the kind is what will pick
     * the endpoint at drain time, nothing else differs.
     */
    @Test
    void enqueuePersistsAReturnEventToo() {
        try (MockedConstruction<FidEvent> rows = mockConstruction(FidEvent.class)) {
            service.enqueue(FidEvent.EventType.TICKET_RETURN, "{\"originTicketRef\":\"2026-C04-1\"}");
            assertEquals(FidEvent.EventType.TICKET_RETURN, rows.constructed().get(0).eventType);
        }
    }

    // --- drain ---

    /**
     * An UNCONFIGURED service drains nothing: no registry read, no request.
     */
    @Test
    void drainDoesNothingWhenUnconfigured() {
        FidEventOutboxService bare = new FidEventOutboxService();
        bare.url = Optional.empty();
        bare.drain();
        assertTrue(receivedPaths.isEmpty());
    }

    /**
     * A 202 marks the row SENT and clears any previous error: the ingestion
     * accepted it, and the next cycle must not post it again.
     */
    @Test
    void drainMarksAnAcceptedEventAsSent() {
        FidEvent closed = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        closed.lastError = "HTTP 500";
        answeredStatus.set(202);

        drainWith(List.of(closed));

        assertNotNull(closed.sentAt);
        assertNull(closed.lastError);
        assertEquals(1, closed.attempts);
        assertEquals("{\"a\":1}", receivedBodies.get(0));
    }

    /**
     * The KIND picks the endpoint: a close goes to {@code ticket-closed}, a
     * return to {@code ticket-return}. Sending one to the other's endpoint
     * would have imfid recompute the wrong movement.
     */
    @Test
    void drainRoutesEachKindToItsEndpoint() {
        answeredStatus.set(202);
        drainWith(List.of(
                event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}"),
                event(2L, FidEvent.EventType.TICKET_RETURN, "{\"b\":2}")));

        assertEquals(List.of("/api/events/ticket-closed", "/api/events/ticket-return"),
                receivedPaths);
    }

    /**
     * A REFUSED event (any status but 202) stays PENDING with its error
     * recorded, and the drain CARRIES ON with the queue: one bad row must not
     * hold back the ones behind it.
     */
    @Test
    void drainKeepsARefusedEventPendingAndContinues() {
        FidEvent refused = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        FidEvent next = event(2L, FidEvent.EventType.TICKET_RETURN, "{\"b\":2}");
        answeredStatus.set(400);

        drainWith(List.of(refused, next));

        assertNull(refused.sentAt);
        assertEquals("HTTP 400", refused.lastError);
        assertEquals(1, refused.attempts);
        assertEquals(2, receivedPaths.size());
        assertEquals(1, next.attempts);
    }

    /**
     * A TRANSPORT failure stops the cycle at once: imfid is down, so
     * hammering the rest of the queue would only waste the register's time.
     * The row stays PENDING and counted as attempted, and the events behind
     * it are NOT even attempted — they go out on the next cycle.
     * <p>
     * Note the error itself is not asserted: the row keeps {@code
     * e.getMessage()}, which several connection failures leave NULL. What
     * matters fiscally is that the event is neither lost nor marked sent.
     */
    @Test
    void drainStopsTheCycleWhenImfidIsUnreachable() {
        FidEvent first = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        FidEvent second = event(2L, FidEvent.EventType.TICKET_RETURN, "{\"b\":2}");
        server.stop(0);

        drainWith(List.of(first, second));

        assertNull(first.sentAt);
        assertEquals(1, first.attempts);
        assertNull(second.sentAt);
        assertEquals(0, second.attempts);
        assertTrue(receivedPaths.isEmpty());
    }

    /**
     * ATTEMPTS accumulate across cycles: a row retried three times says so,
     * which is what lets an operator tell a transient hiccup from an event
     * imfid keeps refusing.
     */
    @Test
    void drainAccumulatesAttemptsAcrossCycles() {
        FidEvent refused = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        answeredStatus.set(500);

        drainWith(List.of(refused));
        drainWith(List.of(refused));
        drainWith(List.of(refused));

        assertEquals(3, refused.attempts);
        assertNull(refused.sentAt);
    }

    /**
     * An event ACCEPTED after failures is cleaned up: the error disappears
     * with the send, so a later reader does not mistake a delivered event for
     * a broken one.
     */
    @Test
    void drainClearsTheErrorOnceTheEventGoesThrough() {
        FidEvent event = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        answeredStatus.set(503);
        drainWith(List.of(event));
        assertEquals("HTTP 503", event.lastError);

        answeredStatus.set(202);
        drainWith(List.of(event));

        assertNull(event.lastError);
        assertNotNull(event.sentAt);
        assertEquals(2, event.attempts);
    }

    /**
     * An EMPTY queue drains silently — the common case, ten times a minute.
     */
    @Test
    void drainOnAnEmptyQueuePostsNothing() {
        drainWith(List.of());
        assertTrue(receivedPaths.isEmpty());
    }

    /**
     * The POS machine account travels on every event: the ingestion must know
     * which register is declaring.
     */
    @Test
    void drainCarriesTheBasicCredentials() {
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}")));

        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("pos:pos-password".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, receivedAuthorization.get());
    }

    /**
     * WITHOUT credentials no header is sent — a dev imfid without security
     * stays usable, and half a credential is never sent as a whole one.
     */
    @Test
    void drainSendsNoAuthorizationWithoutCredentials() {
        service.password = Optional.empty();
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}")));
        assertNull(receivedAuthorization.get());
    }

    /**
     * A USER without a password sends no header either (the mirror leg): half
     * a credential is never sent as a whole one — an incomplete Basic string
     * would fail the call outright instead of degrading to anonymous.
     */
    @Test
    void drainSendsNoAuthorizationWithoutAUser() {
        service.user = Optional.empty();
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}")));
        assertNull(receivedAuthorization.get());
    }

    // --- administered loyalty configuration (BO-10-03-08, -10) ---

    /**
     * The ADMINISTERED URL, user and password win over the deployment keys:
     * the event reaches the administered imfid with the administered account,
     * the surrounding blanks of the URL and the user trimmed.
     */
    @Test
    void drainUsesTheAdministeredUrlAndAccount() {
        com.intermarche.edge.service.PosSettingsService settings =
                mock(com.intermarche.edge.service.PosSettingsService.class);
        when(settings.fidelityUrl()).thenReturn(" " + service.url.get() + " ");
        when(settings.fidelityUser()).thenReturn(" admin-user ");
        when(settings.fidelityPassword()).thenReturn("S3cret!");
        when(settings.fidelityReadTimeoutMs()).thenReturn(4000);
        service.url = Optional.of("http://127.0.0.1:1");
        service.posSettingsService = settings;
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}")));
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("admin-user:S3cret!".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("/api/events/ticket-closed"), receivedPaths);
        assertEquals(expected, receivedAuthorization.get());
        assertEquals(java.time.Duration.ofMillis(4000), service.readTimeout());
    }

    /**
     * BLANK administered values (an empty password, a blank URL and user, a
     * zero delay) leave the deployment keys and the historical 2500 ms delay
     * in force.
     */
    @Test
    void blankAdministeredValuesKeepTheDeploymentKeys() {
        com.intermarche.edge.service.PosSettingsService settings =
                mock(com.intermarche.edge.service.PosSettingsService.class);
        when(settings.fidelityUrl()).thenReturn("  ");
        when(settings.fidelityUser()).thenReturn(" ");
        when(settings.fidelityPassword()).thenReturn("");
        service.posSettingsService = settings;
        assertEquals(service.url.get(), service.baseUrl());
        assertEquals("pos", service.authUser());
        assertEquals("pos-password", service.authPassword());
        assertEquals(java.time.Duration.ofMillis(2500), service.readTimeout());
    }

    /**
     * NULL administered values (parameters never read) leave the deployment
     * keys in force; without a container no settings exist at all and the
     * deployment keys apply alone, the delay being the historical one.
     */
    @Test
    void absentAdministeredValuesKeepTheDeploymentKeys() {
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        assertEquals(service.url.get(), service.baseUrl());
        assertEquals("pos", service.authUser());
        assertEquals("pos-password", service.authPassword());
        service.posSettingsService = null;
        assertEquals("pos", service.authUser());
        assertEquals("pos-password", service.authPassword());
        assertEquals(java.time.Duration.ofMillis(2500), service.readTimeout());
    }

    /**
     * An URL administered while no URL is deployed is enough to start the
     * drain loop: the back office alone enables the loyalty events.
     */
    @Test
    void onStartStartsTheLoopOnAnAdministeredUrlAlone() {
        FidEventOutboxService administered = new FidEventOutboxService();
        administered.url = Optional.empty();
        administered.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(administered.posSettingsService.fidelityUrl()).thenReturn("http://127.0.0.1:1");
        administered.onStart(null);
        assertEquals("http://127.0.0.1:1", administered.baseUrl());
        assertDoesNotThrow(administered::onStop);
    }

    // --- the loop never dies ---

    /**
     * The loop's SAFETY WRAPPER: a cycle that throws is logged and swallowed
     * so the scheduled task survives. Without it, a single unexpected error
     * would kill the daemon thread for good — the register would keep taking
     * sales while silently never declaring another one to imfid, and nothing
     * on screen would say so.
     * <p>
     * The failure is forced through the registry read, which the wrapper sits
     * above; the drain is then invoked the way the scheduler does, through
     * the private wrapper.
     */
    @Test
    void aCycleThatThrowsNeverKillsTheLoop() throws Exception {
        java.lang.reflect.Method drainSafely =
                FidEventOutboxService.class.getDeclaredMethod("drainSafely");
        drainSafely.setAccessible(true);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenThrow(new IllegalStateException("registre indisponible"));
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });

            assertDoesNotThrow(() -> drainSafely.invoke(service));
        }
    }

    /**
     * A cycle whose failure carries NO message is swallowed just as well: the
     * wrapper formats {@code e.getMessage()}, and a null there must not turn
     * the log line into a second failure.
     */
    @Test
    void aCycleThrowingWithoutAMessageIsSwallowedToo() throws Exception {
        java.lang.reflect.Method drainSafely =
                FidEventOutboxService.class.getDeclaredMethod("drainSafely");
        drainSafely.setAccessible(true);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenThrow(new NullPointerException());
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });

            assertDoesNotThrow(() -> drainSafely.invoke(service));
        }
    }

    /**
     * A NOMINAL cycle passes through the wrapper untouched: the safety net
     * must not swallow the work itself.
     */
    @Test
    void aNominalCycleGoesThroughTheWrapper() throws Exception {
        java.lang.reflect.Method drainSafely =
                FidEventOutboxService.class.getDeclaredMethod("drainSafely");
        drainSafely.setAccessible(true);
        FidEvent pending = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        answeredStatus.set(202);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenReturn(List.of(pending));
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });

            drainSafely.invoke(service);
        }

        assertNotNull(pending.sentAt);
        assertEquals(1, receivedPaths.size());
    }

    /**
     * The payload is posted BYTE FOR BYTE: it carries the engine's verbatim
     * valuation couple, which imfid replays — a reserialization would change
     * what the ingestion recomputes.
     */
    @Test
    void drainPostsThePayloadVerbatim() {
        String payload = "{\"ticketRef\":\"2026-C04-000001\",\"valuationResponse\":{\"total\":42.00}}";
        answeredStatus.set(202);

        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, payload)));

        assertEquals(payload, receivedBodies.get(0));
    }

    // --- BO-11-04-05 / -06: the VT and GT calls ---

    /**
     * The GT resend is due at first, then once the cadence has elapsed — ten
     * seconds without the administered parameters, the administered cadence
     * otherwise — and never while the GT call is switched off.
     */
    @Test
    void theGtResendFollowsItsSwitchAndCadence() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 25, 9, 0);
        assertTrue(service.resendDue(now));
        assertFalse(service.resendDue(now.plusSeconds(9)));
        assertTrue(service.resendDue(now.plusSeconds(10)));
        FidEventOutboxService administered = new FidEventOutboxService();
        administered.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(administered.posSettingsService.fidelityGtEnabled()).thenReturn(true);
        when(administered.posSettingsService.fidelityGtSeconds()).thenReturn(60);
        assertTrue(administered.resendDue(now));
        assertFalse(administered.resendDue(now.plusSeconds(59)));
        assertTrue(administered.resendDue(now.plusSeconds(60)));
        when(administered.posSettingsService.fidelityGtEnabled()).thenReturn(false);
        assertFalse(administered.resendDue(now.plusSeconds(600)));
    }

    /**
     * A tick whose resend is not due drains nothing.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void aTickNotDueDrainsNothing() throws Exception {
        java.lang.reflect.Method drainSafely = FidEventOutboxService.class.getDeclaredMethod("drainSafely");
        drainSafely.setAccessible(true);
        service.lastDrain = java.time.LocalDateTime.now().plusMinutes(5);
        drainSafely.invoke(service);
        assertTrue(receivedPaths.isEmpty());
    }

    /**
     * The GT call resends on the administered path, or on the VT path of the
     * event's kind when none is administered, and names itself GT.
     */
    @Test
    void theGtCallUsesItsAdministeredPath() {
        assertEquals("/api/events/ticket-closed", service.gtPath(FidEvent.EventType.TICKET_CLOSED));
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(service.posSettingsService.fidelityGtPath()).thenReturn(" ");
        assertEquals("/api/events/ticket-return", service.gtPath(FidEvent.EventType.TICKET_RETURN));
        when(service.posSettingsService.fidelityGtPath()).thenReturn(null);
        assertEquals("/api/events/ticket-return", service.gtPath(FidEvent.EventType.TICKET_RETURN));
        when(service.posSettingsService.fidelityGtPath()).thenReturn(" /api/gt ");
        when(service.posSettingsService.fidelityUrl()).thenReturn("");
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{}")));
        assertEquals(List.of("/api/gt"), receivedPaths);
        assertEquals(List.of("GT"), receivedCalls);
    }

    /**
     * The synchronous VT call: accepted, the event is recorded as SENT; refused,
     * unreachable or unconfigured, it is recorded PENDING with its error for
     * the GT resend.
     */
    @Test
    void theVtCallIsSynchronousAndNeverLosesAnEvent() {
        try (MockedConstruction<FidEvent> rows = mockConstruction(FidEvent.class)) {
            answeredStatus.set(202);
            assertTrue(service.sendNow(FidEvent.EventType.TICKET_CLOSED, "{\"t\":1}"));
            FidEvent sent = rows.constructed().get(0);
            assertNotNull(sent.sentAt);
            assertNull(sent.lastError);
            assertEquals(1, sent.attempts);
            assertEquals(List.of("/api/events/ticket-closed"), receivedPaths);
            assertEquals(List.of("VT"), receivedCalls);
            verify(sent).persist();
            answeredStatus.set(500);
            assertFalse(service.sendNow(FidEvent.EventType.TICKET_RETURN, "{}"));
            FidEvent refused = rows.constructed().get(1);
            assertNull(refused.sentAt);
            assertEquals("HTTP 500", refused.lastError);
            assertEquals("/api/events/ticket-return", receivedPaths.get(1));
            service.url = Optional.of("http://127.0.0.1:1");
            assertFalse(service.sendNow(FidEvent.EventType.TICKET_CLOSED, "{}"));
            assertNull(rows.constructed().get(2).sentAt);
            service.url = Optional.empty();
            assertFalse(service.sendNow(FidEvent.EventType.TICKET_CLOSED, "{}"));
            assertEquals("service fidélité non configuré", rows.constructed().get(3).lastError);
            verify(rows.constructed().get(3)).persist();
        }
    }
}
