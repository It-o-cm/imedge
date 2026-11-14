package com.intermarche.edge.service.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.intermarche.edge.domain.deploy.UplinkedTicket;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.ui.journal.JournalService;
import com.intermarche.edge.ui.journal.JournalTicketDetail;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link JournalUplinkService}. */
class JournalUplinkServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @param role the node's role
     * @param central the central URL, possibly null
     * @param token the shared token, possibly null
     * @return the service
     */
    private JournalUplinkService service(String role, String central, String token) {
        JournalUplinkService service = new JournalUplinkService();
        service.role = role;
        service.centralUrl = Optional.ofNullable(central);
        service.token = Optional.ofNullable(token);
        service.nodeEchelonService = mock(NodeEchelonService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        service.journalService = mock(JournalService.class);
        service.entityManager = mock(EntityManager.class);
        service.objectMapper = new ObjectMapper();
        service.httpClient = mock(HttpClient.class);
        when(service.posSettingsService.supervisionReportSeconds()).thenReturn(60);
        return service;
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

    /**
     * Builds an HTTP response.
     *
     * @param status the status
     * @param body the body
     * @return the response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<Object> response(int status, String body) {
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * Makes the HTTP client answer the given responses in turn.
     *
     * @param service the service
     * @param responses the responses
     * @return the request captor
     * @throws Exception never
     */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<HttpRequest> answer(JournalUplinkService service, HttpResponse<Object>... responses)
            throws Exception {
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        HttpResponse<Object> first = responses[0];
        HttpResponse<Object>[] rest = java.util.Arrays.copyOfRange(responses, 1, responses.length);
        when(service.httpClient.send(requests.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(first, rest);
        return requests;
    }

    /**
     * Builds a finalized store ticket.
     *
     * @param id its id
     * @return the ticket
     */
    private Ticket ticket(long id) {
        Ticket ticket = new Ticket();
        ticket.id = id;
        ticket.ticketNumber = "POS01-" + id;
        ticket.terminalId = "POS01";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = LocalDateTime.of(2026, 10, 25, 16, 49);
        ticket.closingDate = LocalDateTime.of(2026, 10, 25, 16, 50);
        ticket.updatedAt = LocalDateTime.of(2026, 10, 25, 16, 50, (int) (id % 60));
        ticket.totalIncludingTax = new BigDecimal("36.00");
        ticket.itemCount = 2;
        return ticket;
    }

    /**
     * Makes the ticket query answer a list of tickets.
     *
     * @param service the service
     * @param tickets the tickets answered at every query
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private TypedQuery<Ticket> tickets(JournalUplinkService service, List<Ticket> tickets) {
        TypedQuery<Ticket> query = mock(TypedQuery.class);
        when(service.entityManager.createQuery(anyString(), eq(Ticket.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(tickets);
        return query;
    }

    /** The loop starts on a store node connected to a central node only, and stops cleanly. */
    @Test
    void theLoopRunsOnARelayedStoreOnly() {
        JournalUplinkService central = service("central", "http://central", null);
        central.onStart(null);
        central.stop();
        JournalUplinkService store = service("store", "http://central", null);
        store.onStart(null);
        store.stop();
        assertTrue(store.relayed());
        assertFalse(central.relayed());
        assertFalse(service("store", null, null).relayed());
        assertFalse(service("store", " ", null).relayed());
    }

    /** A tick pushes once the report cadence elapsed, and swallows a failure. */
    @Test
    void aTickPushesWhenDueAndSwallowsFailures() throws Exception {
        JournalUplinkService service = service("store", "http://central", null);
        when(service.nodeEchelonService.currentPdv()).thenReturn("10001");
        answer(service, response(503, ""));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            service.tickSafely();
            assertTrue(service.lastPush > 0);
            long last = service.lastPush;
            service.tickSafely();
            assertEquals(last, service.lastPush);
            verify(service.httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    /** A due tick whose push succeeds runs to its end. */
    @Test
    void aDueTickRunsThePush() throws Exception {
        JournalUplinkService service = service("store", "http://central", null);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            service.tickSafely();
            assertTrue(service.lastPush > 0);
            verify(service.nodeEchelonService).currentPdv();
        }
    }

    /** A store whose point of sale is not commissioned pushes nothing. */
    @Test
    void anUnnamedStorePushesNothing() throws Exception {
        JournalUplinkService service = service("store", "http://central", null);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            assertEquals(0, service.push());
            verify(service.httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    /** BO-02-05-05: the store asks the watermark, then sends what changed since, with the shared token. */
    @Test
    void theStoreSendsWhatChangedSinceTheWatermark() throws Exception {
        JournalUplinkService service = service("store", "http://central/", "s3cret");
        when(service.nodeEchelonService.currentPdv()).thenReturn("10001");
        TypedQuery<Ticket> query = tickets(service, List.of(ticket(1L)));
        ArgumentCaptor<HttpRequest> requests = answer(service,
                response(200, "{\"mark\":\"2026-10-25T16:00:00\"}"),
                response(200, "{\"accepted\":1,\"mark\":\"2026-10-25T16:50:01\"}"));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            assertEquals(1, service.push());
        }
        List<HttpRequest> sent = requests.getAllValues();
        assertEquals("http://central/api/supervision/journal/mark?pdv=10001", sent.get(0).uri().toString());
        assertEquals("http://central/api/supervision/journal", sent.get(1).uri().toString());
        assertEquals("POST", sent.get(1).method());
        assertEquals("s3cret", sent.get(1).headers().firstValue("X-Sync-Token").orElse(null));
        verify(query).setParameter("mark", LocalDateTime.of(2026, 10, 25, 16, 0));
    }

    /** A first push reads the whole journal, and an empty journal sends nothing. */
    @Test
    void anEmptyJournalSendsNothing() throws Exception {
        JournalUplinkService service = service("store", "http://central", " ");
        when(service.nodeEchelonService.currentPdv()).thenReturn("10001");
        TypedQuery<Ticket> query = tickets(service, List.of());
        ArgumentCaptor<HttpRequest> requests = answer(service, response(200, "{\"mark\":null}"));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            assertEquals(0, service.push());
        }
        assertEquals(1, requests.getAllValues().size());
        assertTrue(requests.getValue().headers().firstValue("X-Sync-Token").isEmpty());
        verify(query, never()).setParameter(eq("mark"), any());
    }

    /**
     * Builds a full batch of tickets.
     *
     * @return {@link JournalUplinkService#BATCH} tickets
     */
    private List<Ticket> fullBatch() {
        List<Ticket> tickets = new ArrayList<>();
        for (long id = 1; id <= JournalUplinkService.BATCH; id++) {
            tickets.add(ticket(id));
        }
        return tickets;
    }

    /** A full batch is followed by the next one from the new watermark, until the watermark stops moving. */
    @Test
    void aFullBatchIsFollowedUntilTheWatermarkStops() throws Exception {
        JournalUplinkService service = service("store", "http://central", null);
        when(service.nodeEchelonService.currentPdv()).thenReturn("10001");
        tickets(service, fullBatch());
        ArgumentCaptor<HttpRequest> requests = answer(service,
                response(200, "{\"mark\":null}"),
                response(200, "{\"accepted\":100,\"mark\":\"2026-10-25T16:50:00\"}"),
                response(200, "{\"accepted\":100,\"mark\":\"2026-10-25T16:51:00\"}"),
                response(200, "{\"accepted\":0,\"mark\":\"2026-10-25T16:51:00\"}"));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            assertEquals(200, service.push());
        }
        assertEquals(4, requests.getAllValues().size());
    }

    /** A full batch answered without a watermark ends the cycle. */
    @Test
    void aFullBatchWithoutWatermarkEnds() throws Exception {
        JournalUplinkService service = service("store", "http://central", null);
        when(service.nodeEchelonService.currentPdv()).thenReturn("10001");
        tickets(service, fullBatch());
        ArgumentCaptor<HttpRequest> requests = answer(service, response(200, "{}"), response(200, "{\"accepted\":3}"));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            assertEquals(3, service.push());
        }
        assertEquals(2, requests.getAllValues().size());
    }

    /** A cycle sends at most its ceiling of batches. */
    @Test
    void aCycleSendsAtMostItsCeiling() throws Exception {
        JournalUplinkService service = service("store", "http://central", null);
        when(service.nodeEchelonService.currentPdv()).thenReturn("10001");
        tickets(service, fullBatch());
        List<HttpResponse<Object>> answers = new ArrayList<>();
        answers.add(response(200, "{}"));
        for (int round = 0; round < JournalUplinkService.MAX_BATCHES; round++) {
            answers.add(response(200, "{\"accepted\":1,\"mark\":\"2026-10-25T17:" + (10 + round) + ":00\"}"));
        }
        @SuppressWarnings("unchecked")
        HttpResponse<Object>[] array = answers.toArray(new HttpResponse[0]);
        ArgumentCaptor<HttpRequest> requests = answer(service, array);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            inPlace(tx);
            assertEquals(JournalUplinkService.MAX_BATCHES, service.push());
        }
        assertEquals(JournalUplinkService.MAX_BATCHES + 1, requests.getAllValues().size());
    }

    /** A central node answering badly fails the push, at the watermark and at the batch. */
    @Test
    void aBadAnswerFailsThePush() throws Exception {
        JournalUplinkService refusing = service("store", "http://central", null);
        answer(refusing, response(500, ""));
        assertThrows(IllegalStateException.class, () -> refusing.fetchMark("10001"));
        JournalUplinkService rejecting = service("store", "http://central", null);
        answer(rejecting, response(401, ""));
        assertThrows(IllegalStateException.class, () -> rejecting.post(new JournalUplinkService.Batch()));
    }

    /** A ticket travels with its detail, its cashier and its printout stripped of its images and barcodes. */
    @Test
    void aTicketTravelsWithItsDetail() {
        JournalUplinkService service = service("store", "http://central", null);
        Ticket ticket = ticket(4L);
        Employee cashier = new Employee();
        cashier.badgeId = "12341234";
        ticket.cashier = cashier;
        ticket.formattedContent = "[[IMAGE 384 72 AAAA]]\nINTERMARCHE\nTOTAL 36,00\n[[BARCODE POS01-4]]";
        JournalTicketDetail detail = new JournalTicketDetail("POS01-4", "POS01", "CLOSED", "25/10/2026", "16:49",
                "Jean Dupont", "12341234", "Lyon", "", "36,00", "30,00", "6,00", List.of(), List.of(), List.of());
        when(service.journalService.buildDetail(4L)).thenReturn(detail);
        JournalUplinkService.UplinkTicket up = service.uplink(ticket);
        assertEquals("POS01-4", up.number);
        assertEquals("POS01", up.terminal);
        assertEquals("12341234", up.cashier);
        assertEquals("CLOSED", up.status);
        assertEquals("2026-10-25T16:49", up.date);
        assertEquals("2026-10-25T16:50:04", up.updatedAt);
        assertEquals(new BigDecimal("36.00"), up.total);
        assertEquals(2, up.itemCount);
        assertEquals("Jean Dupont", up.detail.path("cashier").asText());
        assertEquals("INTERMARCHE\nTOTAL 36,00\n", up.printed);
        Ticket bare = ticket(5L);
        bare.status = Ticket.TicketStatus.CANCELLED;
        bare.closingDate = null;
        JournalUplinkService.UplinkTicket cancelled = service.uplink(bare);
        assertNull(cancelled.cashier);
        assertNull(cancelled.printed);
        assertEquals("2026-10-25T16:49", cancelled.date);
        assertEquals("CANCELLED", cancelled.status);
    }

    /** The numbers of a point of sale are its own and its former ones. */
    @Test
    void theNumbersIncludeTheFormerOnes() {
        Pdv pdv = new Pdv();
        pdv.id = 8L;
        PdvHistory renumbered = change(PdvHistory.Kind.NUMBER, "10003");
        PdvHistory moved = change(PdvHistory.Kind.ENSEIGNE, "IF");
        PdvHistory created = change(PdvHistory.Kind.NUMBER, null);
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<PdvHistory> history = mockStatic(PdvHistory.class)) {
            pdvs.when(() -> Pdv.findByNumber("10004")).thenReturn(pdv);
            history.when(() -> PdvHistory.listForPdv(8L)).thenReturn(List.of(renumbered, moved, created));
            assertEquals(List.of("10004", "10003"), new ArrayList<>(JournalUplinkService.numbersOf("10004")));
            assertEquals(Set.of("99999"), JournalUplinkService.numbersOf("99999"));
        }
    }

    /**
     * Builds one history change.
     *
     * @param kind what changed
     * @param oldValue the former value
     * @return the change
     */
    private PdvHistory change(PdvHistory.Kind kind, String oldValue) {
        PdvHistory change = new PdvHistory();
        change.kind = kind;
        change.oldValue = oldValue;
        return change;
    }

    /** The central node answers the latest modification it holds, none for a blank or unknown point of sale. */
    @Test
    void theCentralAnswersItsWatermark() {
        JournalUplinkService service = service("central", null, null);
        UplinkedTicket latest = new UplinkedTicket();
        latest.sourceUpdatedAt = LocalDateTime.of(2026, 10, 25, 16, 50, 1);
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<UplinkedTicket> rows = mockStatic(UplinkedTicket.class)) {
            rows.when(() -> UplinkedTicket.latestOf(Set.of("10001"))).thenReturn(latest);
            assertEquals("2026-10-25T16:50:01", service.mark(" 10001 ").mark);
            assertNull(service.mark("10002").mark);
            assertNull(service.mark(null).mark);
            assertNull(service.mark(" ").mark);
        }
    }

    /** A batch without point of sale or tickets files nothing. */
    @Test
    void anIncompleteBatchFilesNothing() {
        JournalUplinkService service = service("central", null, null);
        assertEquals(0, service.receive(null).accepted);
        JournalUplinkService.Batch unnamed = new JournalUplinkService.Batch();
        assertEquals(0, service.receive(unnamed).accepted);
        unnamed.pdvNumber = " ";
        assertEquals(0, service.receive(unnamed).accepted);
        JournalUplinkService.Batch empty = new JournalUplinkService.Batch();
        empty.pdvNumber = "10001";
        empty.tickets = null;
        assertNull(service.receive(empty).mark);
    }

    /**
     * Builds a travelling ticket.
     *
     * @param number its number
     * @param updatedAt its modification
     * @return the ticket
     */
    private JournalUplinkService.UplinkTicket up(String number, String updatedAt) {
        JournalUplinkService.UplinkTicket up = new JournalUplinkService.UplinkTicket();
        up.number = number;
        up.terminal = "POS01";
        up.cashier = "12341234";
        up.status = "CLOSED";
        up.date = "2026-10-25T16:50";
        up.updatedAt = updatedAt;
        up.total = new BigDecimal("-3");
        up.itemCount = 1;
        up.detail = TextNode.valueOf("d");
        up.printed = "P";
        return up;
    }

    /** BO-02-05-05: the central node files new tickets, restates changed ones and skips repeats and unreadable ones. */
    @Test
    void theCentralFilesRestatesAndSkips() {
        JournalUplinkService service = service("central", null, null);
        UplinkedTicket same = new UplinkedTicket();
        same.sourceUpdatedAt = LocalDateTime.of(2026, 10, 25, 16, 50, 1);
        UplinkedTicket changed = new UplinkedTicket();
        changed.pdvNumber = "10003";
        changed.sourceUpdatedAt = LocalDateTime.of(2026, 10, 25, 16, 0);
        JournalUplinkService.UplinkTicket bare = up("T5", "2026-10-25T16:50:05");
        bare.status = null;
        bare.total = null;
        bare.detail = null;
        JournalUplinkService.UplinkTicket noDate = up("T6", "2026-10-25T16:50:06");
        noDate.date = null;
        JournalUplinkService.Batch batch = new JournalUplinkService.Batch();
        batch.pdvNumber = " 10001 ";
        batch.tickets = List.of(up("T1", "2026-10-25T16:50:01"), up("T2", "2026-10-25T16:50:02"),
                up("T3", "2026-10-25T16:50:03"), up(null, "2026-10-25T16:50:04"), up("T4", "hier"), noDate, bare);
        UplinkedTicket latest = new UplinkedTicket();
        latest.sourceUpdatedAt = LocalDateTime.of(2026, 10, 25, 16, 50, 5);
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<UplinkedTicket> rows = mockStatic(UplinkedTicket.class);
             MockedConstruction<UplinkedTicket> created = mockConstruction(UplinkedTicket.class)) {
            Set<String> numbers = Set.of("10001");
            rows.when(() -> UplinkedTicket.findFiled(numbers, "T1")).thenReturn(same);
            rows.when(() -> UplinkedTicket.findFiled(numbers, "T2")).thenReturn(changed);
            rows.when(() -> UplinkedTicket.kindOf(any(), any())).thenCallRealMethod();
            rows.when(() -> UplinkedTicket.latestOf(numbers)).thenReturn(latest);
            JournalUplinkService.Answer answer = service.receive(batch);
            assertEquals(3, answer.accepted);
            assertEquals("2026-10-25T16:50:05", answer.mark);
            assertEquals("10003", changed.pdvNumber);
            assertEquals(LocalDateTime.of(2026, 10, 25, 16, 50, 2), changed.sourceUpdatedAt);
            assertEquals(UplinkedTicket.NEGATIVE, changed.kind);
            assertEquals("\"d\"", changed.detail);
            assertEquals("P", changed.printed);
            assertEquals("12341234", changed.cashierBadge);
            assertEquals("POS01", changed.terminalId);
            assertEquals(1, changed.itemCount);
            assertEquals(LocalDateTime.of(2026, 10, 25, 16, 50), changed.ticketDate);
            assertTrue(changed.receivedAt != null);
            assertEquals(2, created.constructed().size());
            UplinkedTicket fresh = created.constructed().get(0);
            assertEquals("10001", fresh.pdvNumber);
            assertEquals("T3", fresh.ticketNumber);
            verify(fresh).persist();
            UplinkedTicket defaulted = created.constructed().get(1);
            assertEquals("CLOSED", defaulted.status);
            assertEquals(BigDecimal.ZERO, defaulted.totalIncludingTax);
            assertEquals(UplinkedTicket.SALE, defaulted.kind);
            assertNull(defaulted.detail);
            verify(defaulted).persist();
        }
    }

    /** With nothing filed, the central node answers no watermark. */
    @Test
    void nothingFiledAnswersNoWatermark() {
        JournalUplinkService service = service("central", null, null);
        JournalUplinkService.Batch batch = new JournalUplinkService.Batch();
        batch.pdvNumber = "10001";
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<UplinkedTicket> rows = mockStatic(UplinkedTicket.class)) {
            JournalUplinkService.Answer answer = service.receive(batch);
            assertEquals(0, answer.accepted);
            assertNull(answer.mark);
        }
    }

    /** A date-time is read ISO, an unreadable one as none. */
    @Test
    void stampsAreReadIso() {
        assertEquals(LocalDateTime.of(2026, 10, 25, 16, 50), JournalUplinkService.stamp("2026-10-25T16:50"));
        assertNull(JournalUplinkService.stamp("hier"));
        assertNull(JournalUplinkService.stamp(null));
    }
}
