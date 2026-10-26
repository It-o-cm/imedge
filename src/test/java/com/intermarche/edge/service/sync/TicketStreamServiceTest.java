package com.intermarche.edge.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.domain.sync.StreamEvent;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketStreamService} (BO-11-02-02/03/04/05), targeting
 * 100% branch coverage.
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database: Panache is intercepted
 * statically and the enqueued rows are captured through {@code mockConstruction},
 * which is how every entity-writing service of this repository is tested.
 *
 * <p>Branch enumeration, leg by leg. {@code onTicket}: stream disabled, null
 * ticket, ticket with no number, nominal — and the monetics slip filed only when a
 * card settled the sale. {@code onRefund}: the same four legs.
 * {@code subscribers}: nobody declared, a provider out of service, one declared
 * but not reachable, the monetics type for a slip, the two floor types for a
 * ticket. {@code enqueue}: a first filing, a re-push rewriting the PENDING row
 * instead of doubling it, a re-push after a delivered row, and a payload that
 * cannot be serialized. {@code applyImage}: no printed form, plain, compressed.
 * {@code deliverPending}: a 2xx acknowledgement deleting the row, a non-2xx and an
 * unreachable host each counting an attempt, a subscriber taken out of service
 * counting NO attempt, and one failing subscriber not stopping the pass.
 * {@code health}: caught up, one subscriber, two subscribers, the worst attempt
 * count and the last error of each. {@code tickSafely}: disabled, nominal, and a
 * failure swallowed instead of killing the loop.
 */
class TicketStreamServiceTest {

    /**
     * Builds the service with the given administered settings.
     *
     * @param enabled whether the stream is activated
     * @param compress whether the ticket image is compressed
     * @param relay the forwarder mock
     * @return the service under test
     */
    private TicketStreamService service(boolean enabled, boolean compress,
            ExternalRelayService relay) {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.streamEnabled()).thenReturn(enabled);
        when(settings.streamCompressImage()).thenReturn(compress);
        when(settings.streamDeliverySeconds()).thenReturn(10);
        when(settings.pdvNumber()).thenReturn("12345");
        TicketStreamService service = new TicketStreamService();
        service.posSettingsService = settings;
        service.externalRelayService = relay;
        service.objectMapper = new ObjectMapper();
        return service;
    }

    /**
     * Builds a declared provider.
     *
     * @param code its code
     * @param type the kind of service it answers for
     * @param endpoint its address, possibly null
     * @param authKey its credential, possibly null
     * @param active whether it is in service
     * @return the provider
     */
    private ServiceProvider provider(String code, ServiceProvider.ProviderType type,
            String endpoint, String authKey, boolean active) {
        ServiceProvider provider = new ServiceProvider();
        provider.code = code;
        provider.label = "Tiers " + code;
        provider.providerType = type;
        provider.endpoint = endpoint;
        provider.authKey = authKey;
        provider.active = active;
        return provider;
    }

    /**
     * Stubs the provider lookups of one or two declared providers.
     *
     * @param panache the Panache static mock
     * @param floor the providers of the two floor types, possibly empty
     * @param monetics the providers of the monetics type, possibly empty
     */
    private void stubProviders(MockedStatic<PanacheEntityBase> panache,
            List<ServiceProvider> floor, List<ServiceProvider> monetics) {
        panache.when(() -> ServiceProvider.list(
                        "providerType = ?1 and active = true order by code",
                        ServiceProvider.ProviderType.EXPLOITATION)).thenReturn(floor);
        panache.when(() -> ServiceProvider.list(
                        "providerType = ?1 and active = true order by code",
                        ServiceProvider.ProviderType.DECISIONNEL)).thenReturn(List.of());
        panache.when(() -> ServiceProvider.list(
                        "providerType = ?1 and active = true order by code",
                        ServiceProvider.ProviderType.MONETIQUE)).thenReturn(monetics);
        for (ServiceProvider provider : floor) {
            stubFindByCode(panache, provider);
        }
        for (ServiceProvider provider : monetics) {
            stubFindByCode(panache, provider);
        }
    }

    /**
     * Stubs {@code ServiceProvider.find("code", …)} for one provider.
     *
     * @param panache the Panache static mock
     * @param provider the provider to resolve
     */
    @SuppressWarnings("unchecked")
    private void stubFindByCode(MockedStatic<PanacheEntityBase> panache,
            ServiceProvider provider) {
        PanacheQuery<ServiceProvider> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(provider);
        panache.when(() -> ServiceProvider.find("code", provider.code)).thenReturn(query);
    }

    /**
     * Stubs the identity lookup of the stream rows.
     *
     * @param panache the Panache static mock
     * @param existing the row the lookup resolves, or null for none
     */
    @SuppressWarnings("unchecked")
    private void stubFindByUid(MockedStatic<PanacheEntityBase> panache, StreamEvent existing) {
        PanacheQuery<StreamEvent> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(existing);
        panache.when(() -> StreamEvent.find(eq("eventUid"), anyString())).thenReturn(query);
    }

    /**
     * Builds a closed ticket with one line and the given settlements.
     *
     * @param number the ticket number
     * @param image the printed form, possibly null
     * @param payments the settlements
     * @return the ticket
     */
    private Ticket ticket(String number, String image, TicketPayment... payments) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = number;
        ticket.terminalId = "CA01";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = LocalDateTime.of(2026, 3, 17, 18, 0);
        ticket.closingDate = LocalDateTime.of(2026, 3, 17, 18, 30);
        ticket.itemCount = 1;
        ticket.totalExcludingTax = new BigDecimal("10.00");
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.formattedContent = image;
        Employee cashier = new Employee();
        cashier.loginName = "mdupont";
        ticket.cashier = cashier;
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        ticket.session = session;
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.ean = "3250390000000";
        line.productLabel = "CAFE";
        line.familyCode = "100";
        line.quantity = new BigDecimal("1");
        line.unitPrice = new BigDecimal("12.00");
        line.vatRate = new BigDecimal("0.2000");
        line.totalPrice = new BigDecimal("12.00");
        ticket.lines.add(line);
        for (TicketPayment payment : payments) {
            ticket.payments.add(payment);
        }
        return ticket;
    }

    /**
     * Builds a settlement of the given method.
     *
     * @param method the method key
     * @param amount the amount
     * @return the settlement mock
     */
    private TicketPayment payment(String method, String amount) {
        TicketPayment payment = mock(TicketPayment.class);
        when(payment.getMethodKey()).thenReturn(method);
        payment.amount = new BigDecimal(amount);
        payment.paymentIndex = 1;
        return payment;
    }

    /**
     * Builds a card settlement carrying its authorisation.
     *
     * @param authorization the authorisation number, possibly null
     * @param degraded whether the terminal answered in degraded mode
     * @return the card settlement
     */
    private CardPayment cardPayment(String authorization, boolean degraded) {
        CardPayment card = new CardPayment(new BigDecimal("12.00"));
        card.paymentIndex = 2;
        card.authorizationNumber = authorization;
        card.degradedMode = degraded;
        return card;
    }

    /**
     * Builds a pending stream row.
     *
     * @param code the subscriber's code
     * @param kind what it carries
     * @param reference the business reference
     * @param attempts how many attempts it has counted
     * @param lastError its last error, possibly null
     * @return the row
     */
    private StreamEvent event(String code, StreamEvent.Kind kind, String reference,
            int attempts, String lastError) {
        StreamEvent event = new StreamEvent();
        event.eventUid = StreamEvent.uidOf(code, kind, reference);
        event.providerCode = code;
        event.kind = kind;
        event.reference = reference;
        event.payload = "{}";
        event.attempts = attempts;
        event.lastError = lastError;
        return event;
    }

    /** A relay mock answering the given status for every call. */
    private ExternalRelayService relayAnswering(boolean reached, int status) {
        ExternalRelayService relay = mock(ExternalRelayService.class);
        when(relay.relay(any(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(new ExternalRelayService.RelayResult(reached, status, ""));
        return relay;
    }

    /** A disabled stream enqueues nothing at all. */
    @Test
    void aDisabledStreamEnqueuesNothing() {
        TicketStreamService service = service(false, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            service.onRefund(refund("R1"));
            Assertions.assertTrue(filed.constructed().isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /** A null ticket, and one with no number, are not events. */
    @Test
    void aTicketWithoutANumberIsNotAnEvent() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            service.onTicket(null);
            service.onTicket(ticket(null, null));
            Assertions.assertTrue(filed.constructed().isEmpty());
        }
    }

    /** A null refund, and one with no number, are not events either. */
    @Test
    void aRefundWithoutANumberIsNotAnEvent() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            service.onRefund(null);
            Refund nameless = refund("R1");
            nameless.refundNumber = null;
            service.onRefund(nameless);
            Assertions.assertTrue(filed.constructed().isEmpty());
        }
    }

    /** With nobody declared, nothing is filed and no payload is even built. */
    @Test
    void withoutASubscriberNothingIsFiled() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(), List.of());
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            Assertions.assertTrue(filed.constructed().isEmpty());
        }
    }

    /**
     * A declared provider that is NOT reachable — no address or no credential — is
     * not a subscriber: filing for it would be filing a certain failure.
     */
    @Test
    void anUnreachableProviderIsNotASubscriber() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProviders(panache, List.of(
                    provider("NOADDR", ServiceProvider.ProviderType.EXPLOITATION, null, "k", true),
                    provider("NOKEY", ServiceProvider.ProviderType.EXPLOITATION,
                            "https://x", null, true)), List.of());
            Assertions.assertTrue(service.subscribers(StreamEvent.Kind.TICKET).isEmpty());
        }
    }

    /**
     * A monetics slip goes to the monetics providers and a ticket to the floor
     * ones: the two sets never cross.
     */
    @Test
    void eachKindGoesToItsOwnSubscribers() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        ServiceProvider floor = provider("ANAVEO", ServiceProvider.ProviderType.EXPLOITATION,
                "https://anaveo", "k", true);
        ServiceProvider monetics = provider("MONEX", ServiceProvider.ProviderType.MONETIQUE,
                "https://monex", "k", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProviders(panache, List.of(floor), List.of(monetics));
            Assertions.assertEquals(List.of(floor), service.subscribers(StreamEvent.Kind.TICKET));
            Assertions.assertEquals(List.of(floor), service.subscribers(StreamEvent.Kind.REFUND));
            Assertions.assertEquals(List.of(monetics),
                    service.subscribers(StreamEvent.Kind.MONETICS));
        }
    }

    /**
     * A cash sale files ONE ticket event and no monetics slip: a monetics collector
     * reconciles authorisations, and a cash sale has none.
     */
    @Test
    void aCashSaleFilesNoMoneticsSlip() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                            ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of(provider("MONEX", ServiceProvider.ProviderType.MONETIQUE,
                            "https://m", "k", true)));
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            Assertions.assertEquals(1, filed.constructed().size());
            Assertions.assertEquals(StreamEvent.Kind.TICKET, filed.constructed().get(0).kind);
        }
    }

    /**
     * A card sale files the ticket AND the monetics slip, each to its own
     * subscriber, the slip carrying the authorisation and the degraded flag.
     */
    @Test
    void aCardSaleFilesTheTicketAndTheSlip() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                            ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of(provider("MONEX", ServiceProvider.ProviderType.MONETIQUE,
                            "https://m", "k", true)));
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", null, payment("CASH", "5.00"),
                    cardPayment("AUTH42", true)));
            Assertions.assertEquals(2, filed.constructed().size());
            StreamEvent ticketEvent = filed.constructed().get(0);
            StreamEvent slip = filed.constructed().get(1);
            Assertions.assertEquals(StreamEvent.Kind.TICKET, ticketEvent.kind);
            Assertions.assertEquals("ANAVEO", ticketEvent.providerCode);
            Assertions.assertEquals(StreamEvent.Kind.MONETICS, slip.kind);
            Assertions.assertEquals("MONEX", slip.providerCode);
            Assertions.assertTrue(slip.payload.contains("\"authorizationNumber\":\"AUTH42\""));
            Assertions.assertTrue(slip.payload.contains("\"degradedMode\":true"));
            // The basket is deliberately absent from a monetics slip.
            Assertions.assertFalse(slip.payload.contains("CAFE"));
        }
    }

    /** The published ticket body carries the identity, the totals and the lines. */
    @Test
    void theTicketBodyCarriesWhatAThirdPartyNeeds() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of());
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            String payload = filed.constructed().get(0).payload;
            Assertions.assertTrue(payload.contains("\"kind\":\"TICKET\""));
            Assertions.assertTrue(payload.contains("\"ticketNumber\":\"T1\""));
            Assertions.assertTrue(payload.contains("\"pdvNumber\":\"12345\""));
            Assertions.assertTrue(payload.contains("\"status\":\"CLOSED\""));
            Assertions.assertTrue(payload.contains("\"cashierLogin\":\"mdupont\""));
            Assertions.assertTrue(payload.contains("\"sessionNumber\":\"S1\""));
            Assertions.assertTrue(payload.contains("\"ean\":\"3250390000000\""));
            Assertions.assertTrue(payload.contains("\"method\":\"CASH\""));
        }
    }

    /** A ticket pushed without its printed form attaches no image and no encoding. */
    @Test
    void aTicketWithoutItsPrintedFormAttachesNoImage() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of());
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", "   ", payment("CASH", "12.00")));
            String payload = filed.constructed().get(0).payload;
            Assertions.assertTrue(payload.contains("\"image\":null"));
            Assertions.assertTrue(payload.contains("\"imageEncoding\":null"));
        }
    }

    /** The printed form travels plain when compression is not administered. */
    @Test
    void thePrintedFormTravelsPlainByDefault() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of());
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", "INTERMARCHE\nCAFE 12,00", payment("CASH", "12.00")));
            String payload = filed.constructed().get(0).payload;
            Assertions.assertTrue(payload.contains("\"imageEncoding\":\"plain\""));
            Assertions.assertTrue(payload.contains("INTERMARCHE"));
        }
    }

    /**
     * Administered compressed, the image travels gzipped then Base64 — and comes
     * back out as what the register printed.
     */
    @Test
    void thePrintedFormTravelsCompressedWhenAdministered() throws Exception {
        TicketStreamService service = service(true, true, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of());
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", "INTERMARCHE CAFE", payment("CASH", "12.00")));
            String payload = filed.constructed().get(0).payload;
            Assertions.assertTrue(payload.contains("\"imageEncoding\":\"gzip+base64\""));
            Assertions.assertFalse(payload.contains("INTERMARCHE CAFE"));
            Assertions.assertEquals("INTERMARCHE CAFE", decode(payload));
        }
    }

    /**
     * A re-push rewrites the PENDING row instead of doubling it: a register that
     * pushes one ticket twenty times must leave one event carrying the latest
     * state, not twenty the subscriber has to de-duplicate.
     */
    @Test
    void aRePushRewritesThePendingRow() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        StreamEvent pending = event("ANAVEO", StreamEvent.Kind.TICKET, "T1", 3, "HTTP 500");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of());
            stubFindByUid(panache, pending);
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            Assertions.assertTrue(filed.constructed().isEmpty());
            Assertions.assertTrue(pending.payload.contains("\"ticketNumber\":\"T1\""));
            // The attempt count and the error are reset: this is a new state, and
            // charging it the failures of the previous one would read as an
            // endpoint that keeps refusing.
            Assertions.assertEquals(0, pending.attempts);
            Assertions.assertNull(pending.lastError);
        }
    }

    /**
     * A ticket re-pushed AFTER its event was delivered files a new event, the
     * delivered row being replaced: the ticket changed after being streamed.
     */
    @Test
    void aRePushAfterDeliveryFilesANewEvent() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        StreamEvent delivered = event("ANAVEO", StreamEvent.Kind.TICKET, "T1", 1, null);
        delivered.deliveredAt = LocalDateTime.of(2026, 3, 17, 18, 31);
        StreamEvent spy = org.mockito.Mockito.spy(delivered);
        // Un espion execute la VRAIE methode : le delete() de Panache passe par Arc,
        // absent sous mvn test. Neutralise ici, la verification porte sur l'appel.
        doNothing().when(spy).delete();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true)),
                    List.of());
            stubFindByUid(panache, spy);
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            verify(spy, times(1)).delete();
            Assertions.assertEquals(1, filed.constructed().size());
        }
    }

    /** A 2xx acknowledgement deletes the row, which is what "delivered" means. */
    @Test
    void anAcknowledgedEventIsDeleted() {
        ExternalRelayService relay = relayAnswering(true, 204);
        TicketStreamService service = service(true, false, relay);
        StreamEvent pending = org.mockito.Mockito.spy(
                event("ANAVEO", StreamEvent.Kind.TICKET, "T1", 0, null));
        // Un espion execute la VRAIE methode : le delete() de Panache passe par Arc,
        // absent sous mvn test. Neutralise ici, la verification porte sur l'appel.
        doNothing().when(pending).delete();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPending(panache, List.of(pending));
            stubFindByCode(panache, provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true));
            Assertions.assertEquals(1, service.deliverPending());
            verify(pending, times(1)).delete();
            // The credential travels as the provider's own, never a shared one.
            verify(relay).relay(any(), eq("POST"), eq("/events"), eq("{}"), anyLong(), eq("k"));
        }
    }

    /**
     * A refusal and an unreachable host each count an attempt and keep the row:
     * nothing is ever dropped, which is the one failure mode the requirement
     * forbids.
     */
    @Test
    void aFailedDeliveryCountsAnAttemptAndKeepsTheRow() {
        StreamEvent refused = org.mockito.Mockito.spy(
                event("ANAVEO", StreamEvent.Kind.TICKET, "T1", 2, null));
        TicketStreamService service = service(true, false, relayAnswering(true, 503));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPending(panache, List.of(refused));
            stubFindByCode(panache, provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true));
            Assertions.assertEquals(0, service.deliverPending());
            verify(refused, never()).delete();
            Assertions.assertEquals(3, refused.attempts);
            Assertions.assertEquals("HTTP 503", refused.lastError);
        }

        StreamEvent unreachable = org.mockito.Mockito.spy(
                event("ANAVEO", StreamEvent.Kind.TICKET, "T2", 0, null));
        TicketStreamService offline = service(true, false, relayAnswering(false, 0));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPending(panache, List.of(unreachable));
            stubFindByCode(panache, provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true));
            Assertions.assertEquals(0, offline.deliverPending());
            Assertions.assertEquals(1, unreachable.attempts);
            Assertions.assertEquals("injoignable", unreachable.lastError);
        }
    }

    /**
     * A subscriber taken out of service in the back office counts NO attempt: the
     * attempt figure must read as "the other side is failing", not as "an
     * administrator switched it off".
     */
    @Test
    void aSubscriberTakenOutOfServiceCountsNoAttempt() {
        StreamEvent waiting = org.mockito.Mockito.spy(
                event("ANAVEO", StreamEvent.Kind.TICKET, "T1", 1, null));
        ExternalRelayService relay = relayAnswering(true, 200);
        TicketStreamService service = service(true, false, relay);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPending(panache, List.of(waiting));
            stubFindByCode(panache, provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", false));
            Assertions.assertEquals(0, service.deliverPending());
            Assertions.assertEquals(1, waiting.attempts);
            verify(waiting, never()).delete();
            verify(relay, never()).relay(any(), anyString(), anyString(), anyString(),
                    anyLong(), any());
        }
    }

    /** An event whose subscriber was deleted stays, untouched and visible. */
    @Test
    void anEventOfAVanishedSubscriberStays() {
        StreamEvent orphan = org.mockito.Mockito.spy(
                event("GONE", StreamEvent.Kind.TICKET, "T1", 4, "HTTP 500"));
        TicketStreamService service = service(true, false, relayAnswering(true, 200));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPending(panache, List.of(orphan));
            stubMissingProvider(panache, "GONE");
            Assertions.assertEquals(0, service.deliverPending());
            Assertions.assertEquals(4, orphan.attempts);
            verify(orphan, never()).delete();
        }
    }

    /** One failing subscriber does not stop the pass for the others. */
    @Test
    void oneFailingSubscriberDoesNotStopThePass() {
        StreamEvent failing = org.mockito.Mockito.spy(
                event("DOWN", StreamEvent.Kind.TICKET, "T1", 0, null));
        StreamEvent fine = org.mockito.Mockito.spy(
                event("UP", StreamEvent.Kind.TICKET, "T2", 0, null));
        // Un espion execute la VRAIE methode : le delete() de Panache passe par Arc,
        // absent sous mvn test. Neutralise ici, la verification porte sur l'appel.
        doNothing().when(fine).delete();
        ExternalRelayService relay = mock(ExternalRelayService.class);
        when(relay.relay(any(), anyString(), anyString(), anyString(), anyLong(), eq("down")))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""));
        when(relay.relay(any(), anyString(), anyString(), anyString(), anyLong(), eq("up")))
                .thenReturn(new ExternalRelayService.RelayResult(true, 200, ""));
        TicketStreamService service = service(true, false, relay);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPending(panache, List.of(failing, fine));
            stubFindByCode(panache, provider("DOWN",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://down", "down", true));
            stubFindByCode(panache, provider("UP",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://up", "up", true));
            Assertions.assertEquals(1, service.deliverPending());
            Assertions.assertEquals(1, failing.attempts);
            verify(fine, times(1)).delete();
        }
    }

    /** A caught-up stream produces no healthcheck row at all. */
    @Test
    void aCaughtUpStreamProducesNoRow() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.list("deliveredAt is null order by providerCode, id"))
                    .thenReturn(List.of());
            Assertions.assertTrue(service.health().isEmpty());
        }
    }

    /**
     * The healthcheck groups by subscriber, keeps the WORST attempt count and the
     * last error of each, and names the subscriber by its label.
     */
    @Test
    void theHealthcheckGroupsBySubscriber() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        List<StreamEvent> pending = new ArrayList<>();
        pending.add(event("ANAVEO", StreamEvent.Kind.TICKET, "T1", 2, "HTTP 500"));
        pending.add(event("ANAVEO", StreamEvent.Kind.TICKET, "T2", 7, "injoignable"));
        pending.add(event("MONEX", StreamEvent.Kind.MONETICS, "T1", 0, null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.list("deliveredAt is null order by providerCode, id"))
                    .thenReturn(pending);
            stubFindByCode(panache, provider("ANAVEO",
                    ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true));
            stubFindByCode(panache, provider("MONEX",
                    ServiceProvider.ProviderType.MONETIQUE, null, "k", true));
            List<TicketStreamService.StreamRow> rows = service.health();
            Assertions.assertEquals(2, rows.size());
            Assertions.assertEquals("Tiers ANAVEO", rows.get(0).label());
            Assertions.assertEquals(2, rows.get(0).pending());
            Assertions.assertEquals(7, rows.get(0).maxAttempts());
            Assertions.assertEquals("injoignable", rows.get(0).lastError());
            Assertions.assertTrue(rows.get(0).reachable());
            Assertions.assertEquals(1, rows.get(1).pending());
            Assertions.assertEquals(0, rows.get(1).maxAttempts());
            Assertions.assertNull(rows.get(1).lastError());
            // No address: the row says so rather than looking merely slow.
            Assertions.assertFalse(rows.get(1).reachable());
        }
    }

    /** A row whose subscriber vanished is named by its code, and is not reachable. */
    @Test
    void aVanishedSubscriberIsNamedByItsCode() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.list("deliveredAt is null order by providerCode, id"))
                    .thenReturn(List.of(event("GONE", StreamEvent.Kind.TICKET, "T1", 1, null)));
            stubMissingProvider(panache, "GONE");
            List<TicketStreamService.StreamRow> rows = service.health();
            Assertions.assertEquals("GONE", rows.get(0).label());
            Assertions.assertFalse(rows.get(0).reachable());
        }
    }

    /** A subscriber with no label is named by its code too. */
    @Test
    void aSubscriberWithoutALabelIsNamedByItsCode() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        ServiceProvider nameless = provider("ANAVEO",
                ServiceProvider.ProviderType.EXPLOITATION, "https://x", "k", true);
        nameless.label = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.list("deliveredAt is null order by providerCode, id"))
                    .thenReturn(List.of(event("ANAVEO", StreamEvent.Kind.TICKET, "T1", 0, null)));
            stubFindByCode(panache, nameless);
            Assertions.assertEquals("ANAVEO", service.health().get(0).label());
        }
    }

    /** The loop does nothing while the stream is disabled. */
    @Test
    void theLoopDoesNothingWhileDisabled() {
        ExternalRelayService relay = mock(ExternalRelayService.class);
        TicketStreamService service = service(false, false, relay);
        service.tickSafely();
        verify(relay, never()).relay(any(), anyString(), anyString(), anyString(), anyLong(),
                any());
    }

    /** The loop swallows a failure instead of letting the scheduler cancel it. */
    @Test
    void theLoopSwallowsAFailure() {
        TicketStreamService service = service(true, false, relayAnswering(true, 200));
        // No Panache interception: the static entity call fails, which is exactly
        // the kind of failure that must not reach the scheduler.
        Assertions.assertDoesNotThrow(service::tickSafely);
    }

    /** Outside a CDI container, the bean's own proxy resolves to itself. */
    @Test
    void withoutAContainerTheBeanIsItsOwnProxy() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        Assertions.assertSame(service, service.self());
    }

    /** The administered cadence is surfaced for the supervision screen. */
    @Test
    void theCadenceIsSurfaced() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        Assertions.assertEquals(10L, service.getDeliverySeconds());
        Assertions.assertTrue(service.isEnabled());
    }

    /**
     * Stubs the pending-batch reads of the delivery pass.
     *
     * @param panache the Panache static mock
     * @param pending the rows the pass finds
     */
    @SuppressWarnings("unchecked")
    private void stubPending(MockedStatic<PanacheEntityBase> panache, List<StreamEvent> pending) {
        PanacheQuery<StreamEvent> query = mock(PanacheQuery.class);
        when(query.page(0, 50)).thenReturn(query);
        when(query.list()).thenReturn(pending);
        panache.when(() -> StreamEvent.find("deliveredAt is null order by id")).thenReturn(query);
        panache.when(() -> StreamEvent.count("deliveredAt is null")).thenReturn(0L);
    }

    /**
     * Stubs a provider code that resolves to nothing.
     *
     * @param panache the Panache static mock
     * @param code the vanished code
     */
    @SuppressWarnings("unchecked")
    private void stubMissingProvider(MockedStatic<PanacheEntityBase> panache, String code) {
        PanacheQuery<ServiceProvider> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        panache.when(() -> ServiceProvider.find("code", code)).thenReturn(query);
    }

    /**
     * Builds a refund.
     *
     * @param number the refund number
     * @return the refund
     */
    private Refund refund(String number) {
        Refund refund = new Refund();
        refund.refundNumber = number;
        refund.terminalId = "CA01";
        refund.status = Refund.RefundStatus.OPEN;
        refund.creationDate = LocalDateTime.of(2026, 3, 17, 19, 0);
        refund.totalAmount = new BigDecimal("12.00");
        refund.totalVat = new BigDecimal("2.00");
        return refund;
    }

    /**
     * Pulls the compressed image out of a payload and inflates it.
     *
     * @param payload the serialized event
     * @return the original printed form
     * @throws Exception when the payload does not hold an inflatable image
     */
    private String decode(String payload) throws Exception {
        String marker = "\"image\":\"";
        int start = payload.indexOf(marker) + marker.length();
        int end = payload.indexOf('"', start);
        byte[] gzipped = Base64.getDecoder().decode(payload.substring(start, end));
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    /** BO-11-02: nullable wire metadata and a legacy CARD discriminator preserve every payload leg. */
    @Test
    void payloadsTolerateMissingMetadataAndLegacyCardTypes() throws Exception {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "T1";
        ticket.status = null;
        ticket.creationDate = null;
        ticket.closingDate = null;
        ticket.cashier = new Employee();
        ticket.cashier.loginName = "OP";
        ticket.session = new CashSession();
        ticket.session.sessionNumber = "S1";
        ticket.lines = new ArrayList<>();
        ticket.payments = new ArrayList<>();
        TicketPayment legacy = mock(TicketPayment.class);
        when(legacy.getMethodKey()).thenReturn("CARD");
        ticket.payments.add(legacy);
        var ticketMethod = TicketStreamService.class.getDeclaredMethod("ticketEvent", Ticket.class);
        ticketMethod.setAccessible(true);
        var payload = (StreamPayloads.TicketEvent) ticketMethod.invoke(service, ticket);
        Assertions.assertNull(payload.status);
        Assertions.assertNull(payload.creationDate);
        Assertions.assertEquals("OP", payload.cashierLogin);
        Assertions.assertEquals("S1", payload.sessionNumber);
        var moneticsMethod = TicketStreamService.class.getDeclaredMethod("moneticsEvent", Ticket.class);
        moneticsMethod.setAccessible(true);
        var monetics = (StreamPayloads.MoneticsEvent) moneticsMethod.invoke(service, ticket);
        Assertions.assertEquals(1, monetics.cards.size());
        Assertions.assertNull(monetics.cards.get(0).authorizationNumber);
        var refundMethod = TicketStreamService.class.getDeclaredMethod("refundEvent", Refund.class);
        refundMethod.setAccessible(true);
        Refund refund = refund("R1");
        refund.status = null;
        refund.refundMethod = null;
        refund.session = ticket.session;
        var returned = (StreamPayloads.RefundEvent) refundMethod.invoke(service, refund);
        Assertions.assertNull(returned.status);
        Assertions.assertNull(returned.method);
        Assertions.assertEquals("S1", returned.sessionNumber);
        refund.status = Refund.RefundStatus.OPEN;
        refund.refundMethod = Refund.RefundMethod.CASH;
        returned = (StreamPayloads.RefundEvent) refundMethod.invoke(service, refund);
        Assertions.assertEquals("OPEN", returned.status);
        Assertions.assertEquals("CASH", returned.method);
    }

    /** BO-11-02-02: compression failure keeps the exact printed text and advertises plain encoding. */
    @Test
    void compressionFailureFallsBackToPlainImage() throws Exception {
        TicketStreamService service = service(true, true, mock(ExternalRelayService.class));
        var payload = new StreamPayloads.TicketEvent();
        var method = TicketStreamService.class.getDeclaredMethod("applyImage", StreamPayloads.TicketEvent.class, String.class);
        method.setAccessible(true);
        try (var gzip = mockConstruction(java.util.zip.GZIPOutputStream.class, (mocked, context) -> {
            org.mockito.Mockito.doThrow(new java.io.IOException("compression unavailable")).when(mocked).write(any(byte[].class));
        })) {
            method.invoke(service, payload, "PRINTED BODY");
        }
        Assertions.assertEquals("PRINTED BODY", payload.image);
        Assertions.assertEquals(StreamPayloads.IMAGE_PLAIN, payload.imageEncoding);
    }

    /** BO-11-02-05: the CDI proxy is used when present and a null handle result falls back to the bean. */
    @Test
    void selfUsesTheAvailableCdiProxy() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        TicketStreamService proxy = mock(TicketStreamService.class);
        io.quarkus.arc.ArcContainer container = mock(io.quarkus.arc.ArcContainer.class);
        io.quarkus.arc.InstanceHandle<TicketStreamService> handle = mock(io.quarkus.arc.InstanceHandle.class);
        when(container.instance(TicketStreamService.class)).thenReturn(handle);
        try (var arc = mockStatic(io.quarkus.arc.Arc.class)) {
            arc.when(io.quarkus.arc.Arc::container).thenReturn(container);
            Assertions.assertSame(service, service.self());
            when(handle.get()).thenReturn(proxy);
            Assertions.assertSame(proxy, service.self());
        }
    }

    /** BO-11-02-02: a serializing failure with a real subscriber never creates an empty queued event. */
    @Test
    void serializerFailureStopsEnqueueForARealSubscriber() throws Exception {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        service.objectMapper = mock(ObjectMapper.class);
        when(service.objectMapper.writeValueAsString(any())).thenThrow(new IllegalStateException("serialization failed"));
        ServiceProvider provider = provider("VIDEO", ServiceProvider.ProviderType.EXPLOITATION, "https://video", "key", true);
        try (var base = mockStatic(PanacheEntityBase.class);
             var events = mockConstruction(StreamEvent.class)) {
            stubProviders(base, List.of(provider), List.of());
            service.onRefund(refund("R1"));
            Assertions.assertTrue(events.constructed().isEmpty());
        }
    }

    /** BO-11-02-05: an HTTP response below 200 increments retry state instead of acknowledging the event. */
    @Test
    void informationalStatusDoesNotAcknowledgeAnEvent() {
        TicketStreamService service = service(true, false, relayAnswering(true, 199));
        StreamEvent pending = event("VIDEO", StreamEvent.Kind.TICKET, "T1", 0, null);
        ServiceProvider provider = provider("VIDEO", ServiceProvider.ProviderType.EXPLOITATION, "https://video", "key", true);
        try (var base = mockStatic(PanacheEntityBase.class)) {
            stubPending(base, List.of(pending));
            stubFindByCode(base, provider);
            Assertions.assertEquals(0, service.deliverPending());
        }
        Assertions.assertEquals(1, pending.attempts);
        Assertions.assertEquals("HTTP 199", pending.lastError);
    }

    /** BO-11-02-02: tickets without a cashier or session publish null identities explicitly. */
    @Test
    void ticketPayloadKeepsMissingCashierAndSessionNull() throws Exception {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        Ticket ticket = new Ticket();
        ticket.lines = new ArrayList<>();
        ticket.cashier = null;
        ticket.session = null;
        var method = TicketStreamService.class.getDeclaredMethod("ticketEvent", Ticket.class);
        method.setAccessible(true);
        var payload = (StreamPayloads.TicketEvent) method.invoke(service, ticket);
        Assertions.assertNull(payload.cashierLogin);
        Assertions.assertNull(payload.sessionNumber);
    }

    /** BO-11: startup installs one daemon worker at the administered cadence without performing an export. */
    @org.junit.jupiter.api.Test
    void startupUsesOneDaemonAtTheAdministeredCadence() {
        var service = service(true, false, mock(ExternalRelayService.class));
        var executor = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        var factory = org.mockito.ArgumentCaptor.forClass(java.util.concurrent.ThreadFactory.class);
        try (var executors = org.mockito.Mockito.mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(org.mockito.ArgumentMatchers.any(java.util.concurrent.ThreadFactory.class))).thenReturn(executor);
            service.onStart(new io.quarkus.runtime.StartupEvent());
            executors.verify(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(factory.capture()));
            var thread = factory.getValue().newThread(() -> { });
            org.junit.jupiter.api.Assertions.assertEquals("ticket-stream", thread.getName());
            org.junit.jupiter.api.Assertions.assertTrue(thread.isDaemon());
            org.junit.jupiter.api.Assertions.assertEquals(Thread.State.NEW, thread.getState());
            org.mockito.Mockito.verify(executor).scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    /** BO-11-02-05: successful empty delivery passes return normally so the scheduler remains usable after its failure path. */
    @Test
    void enabledLoopCompletesEmptyDeliveryPasses() {
        var service = service(true, false, mock(ExternalRelayService.class));
        try (var events = mockStatic(StreamEvent.class)) {
            events.when(() -> StreamEvent.nextPending(50)).thenReturn(List.of());
            Assertions.assertDoesNotThrow(service::tickSafely);
            Assertions.assertDoesNotThrow(service::tickSafely);
            events.verify(() -> StreamEvent.nextPending(50), times(2));
        }
    }

    /**
     * Builds a Catalina server.
     *
     * @param code its code
     * @param endpoint its address, possibly null
     * @return the provider
     */
    private ServiceProvider catalina(String code, String endpoint) {
        ServiceProvider provider = provider(code, ServiceProvider.ProviderType.EXPLOITATION, endpoint, "k", true);
        provider.protocol = ServiceProvider.ProviderProtocol.CATALINA;
        return provider;
    }

    /**
     * BO-03-09-20: a closed sale of a register whose Catalina flow is on is
     * filed for the reachable Catalina servers, the general stream being off.
     */
    @Test
    void aSaleOfACatalinaRegisterIsFiledForTheCatalinaServers() {
        TicketStreamService service = service(false, false, mock(ExternalRelayService.class));
        when(service.posSettingsService.catalinaEnabledFor("CA01")).thenReturn(true);
        ServiceProvider server = catalina("CATA", "https://catalina");
        ServiceProvider unreachable = catalina("CATA2", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            panache.when(() -> ServiceProvider.list("protocol = ?1 and active = true order by code",
                    ServiceProvider.ProviderProtocol.CATALINA)).thenReturn(List.of(server, unreachable));
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            Assertions.assertEquals(1, filed.constructed().size());
            Assertions.assertEquals("CATA", filed.constructed().get(0).providerCode);
            Assertions.assertEquals(StreamEvent.Kind.TICKET, filed.constructed().get(0).kind);
        }
    }

    /** BO-03-09-20: a parked cart, or a register whose flow is off, is not sent to Catalina. */
    @Test
    void onlyTheClosedSalesOfAnActivatedRegisterGoToCatalina() {
        TicketStreamService service = service(false, false, mock(ExternalRelayService.class));
        when(service.posSettingsService.catalinaEnabledFor("CA01")).thenReturn(true);
        Ticket parked = ticket("T2", null, payment("CASH", "12.00"));
        parked.status = Ticket.TicketStatus.PARKED;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            service.onTicket(parked);
            Assertions.assertTrue(filed.constructed().isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /** BO-03-09-20: a Catalina server is never a subscriber of the general stream. */
    @Test
    void aCatalinaServerIsNotAStreamSubscriber() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        ServiceProvider floor = provider("ANAVEO", ServiceProvider.ProviderType.EXPLOITATION,
                "https://anaveo", "k", true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProviders(panache, List.of(floor, catalina("CATA", "https://catalina")), List.of());
            Assertions.assertEquals(List.of(floor), service.subscribers(StreamEvent.Kind.TICKET));
        }
    }

    /** BO-03-09-20: the loop delivers what is pending even while the general stream is off. */
    @Test
    void theLoopDeliversPendingEventsWhileTheStreamIsOff() {
        ExternalRelayService relay = relayAnswering(true, 200);
        TicketStreamService service = service(false, false, relay);
        ServiceProvider server = catalina("CATA", "https://catalina");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            StreamEvent pending = mock(StreamEvent.class);
            pending.providerCode = "CATA";
            pending.payload = "{}";
            stubPending(panache, List.of(pending));
            panache.when(() -> StreamEvent.count("deliveredAt is null")).thenReturn(1L);
            stubFindByCode(panache, server);
            service.tickSafely();
            verify(relay).relay(any(), anyString(), anyString(), anyString(), anyLong(), any());
        }
        ExternalRelayService idle = mock(ExternalRelayService.class);
        TicketStreamService quiet = service(false, false, idle);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.count("deliveredAt is null")).thenReturn(0L);
            quiet.tickSafely();
        }
        verify(idle, never()).relay(any(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    /** BO-03-09-20: the pending check answers true with one event waiting and false with none. */
    @Test
    void hasPendingReflectsTheBacklog() {
        TicketStreamService service = service(false, false, mock(ExternalRelayService.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.count("deliveredAt is null")).thenReturn(1L, 0L);
            Assertions.assertTrue(service.hasPending());
            Assertions.assertFalse(service.hasPending());
        }
    }

    /**
     * Builds a reachable provider speaking a protocol.
     *
     * @param code its code
     * @param type its kind of service
     * @param protocol its protocol
     * @param endpoint its address, possibly null
     * @return the provider
     */
    private ServiceProvider speaking(String code, ServiceProvider.ProviderType type,
            ServiceProvider.ProviderProtocol protocol, String endpoint) {
        ServiceProvider provider = provider(code, type, endpoint, "k", true);
        provider.protocol = protocol;
        return provider;
    }

    /**
     * Stubs the active providers of one protocol.
     *
     * @param panache the Panache static mock
     * @param protocol the protocol
     * @param providers the providers speaking it
     */
    private void stubProtocol(MockedStatic<PanacheEntityBase> panache, ServiceProvider.ProviderProtocol protocol,
            List<ServiceProvider> providers) {
        panache.when(() -> ServiceProvider.list("protocol = ?1 and active = true order by code", protocol))
                .thenReturn(providers);
    }

    /**
     * BO-11-02-01 / -04: a closed sale is filed for the TFREMA, DS and Anaveo
     * receivers in their own shapes, unreachable ones left out; a parked cart
     * reaches only the video journal, and a sale rung on no shift (an
     * e-commerce order) every receiver but the video journal.
     */
    @Test
    void theProtocolReceiversGetTheirOwnShapes() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        ServiceProvider tfrema = speaking("GC", ServiceProvider.ProviderType.COMMERCIAL,
                ServiceProvider.ProviderProtocol.TFREMA, "https://gc");
        ServiceProvider deaf = speaking("GC2", ServiceProvider.ProviderType.COMMERCIAL,
                ServiceProvider.ProviderProtocol.TFREMA, null);
        ServiceProvider ds = speaking("MERC", ServiceProvider.ProviderType.COMMERCIAL,
                ServiceProvider.ProviderProtocol.DS_VENTES, "https://mercalys");
        ServiceProvider video = speaking("ANA", ServiceProvider.ProviderType.EXPLOITATION,
                ServiceProvider.ProviderProtocol.ANAVEO, "https://anaveo");
        Ticket parked = ticket("T2", null);
        parked.status = Ticket.TicketStatus.PARKED;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            stubProviders(panache, List.of(), List.of());
            stubProtocol(panache, ServiceProvider.ProviderProtocol.TFREMA, List.of(tfrema, deaf));
            stubProtocol(panache, ServiceProvider.ProviderProtocol.DS_VENTES, List.of(ds));
            stubProtocol(panache, ServiceProvider.ProviderProtocol.ANAVEO, List.of(video));
            stubFindByUid(panache, null);
            service.onTicket(ticket("T1", null, payment("CASH", "12.00")));
            Assertions.assertEquals(3, filed.constructed().size());
            Assertions.assertEquals("GC", filed.constructed().get(0).providerCode);
            Assertions.assertTrue(filed.constructed().get(0).payload.contains("\"lignes\""));
            Assertions.assertEquals("MERC", filed.constructed().get(1).providerCode);
            Assertions.assertTrue(filed.constructed().get(1).payload.startsWith("DS12345"));
            Assertions.assertEquals("ANA", filed.constructed().get(2).providerCode);
            Assertions.assertTrue(filed.constructed().get(2).payload.contains("|TOTAL|CLOSED|"));
            service.onTicket(parked);
            Assertions.assertEquals(4, filed.constructed().size());
            Assertions.assertEquals("ANA", filed.constructed().get(3).providerCode);
            Ticket online = ticket("ECOM-1", null, payment("CARD", "12.00"));
            online.session = null;
            service.onTicket(online);
            Assertions.assertEquals(6, filed.constructed().size());
            Assertions.assertEquals("GC", filed.constructed().get(4).providerCode);
            Assertions.assertEquals("MERC", filed.constructed().get(5).providerCode);
        }
    }

    /** BO-11-02-01 / -04: a receiver speaking its own protocol is never a subscriber of the house stream. */
    @Test
    void aProtocolReceiverIsNotAStreamSubscriber() {
        TicketStreamService service = service(true, false, mock(ExternalRelayService.class));
        ServiceProvider floor = provider("FLOOR", ServiceProvider.ProviderType.EXPLOITATION, "https://f", "k", true);
        ServiceProvider video = speaking("ANA", ServiceProvider.ProviderType.EXPLOITATION,
                ServiceProvider.ProviderProtocol.ANAVEO, "https://anaveo");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProviders(panache, List.of(floor, video), List.of());
            Assertions.assertEquals(List.of(floor), service.subscribers(StreamEvent.Kind.TICKET));
        }
    }

    /**
     * BO-11-04-03: the integration report goes back to the reachable e-commerce
     * platforms with the point of sale; a null report, one with no reference,
     * or a store that does not return the status files nothing.
     */
    @Test
    void theEcommerceStatusIsReturnedToThePlatforms() {
        TicketStreamService service = service(false, false, mock(ExternalRelayService.class));
        ServiceProvider shop = provider("SHOP", ServiceProvider.ProviderType.ECOMMERCE, "https://shop", "k", true);
        ServiceProvider deaf = provider("SHOP2", ServiceProvider.ProviderType.ECOMMERCE, null, "k", true);
        com.intermarche.edge.service.EcommerceIntegrationService.Report report =
                new com.intermarche.edge.service.EcommerceIntegrationService.Report();
        report.reference = "WEB-1";
        report.status = "INTEGRATED";
        report.ticketNumber = "ECOM-1";
        report.total = new BigDecimal("42.00");
        report.message = "ok";
        report.unknownLines = 1;
        com.intermarche.edge.service.EcommerceIntegrationService.Report nameless =
                new com.intermarche.edge.service.EcommerceIntegrationService.Report();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StreamEvent> filed = mockConstruction(StreamEvent.class)) {
            panache.when(() -> ServiceProvider.list("providerType = ?1 and active = true order by code",
                    ServiceProvider.ProviderType.ECOMMERCE)).thenReturn(List.of(shop, deaf));
            stubFindByUid(panache, null);
            service.onEcommerceReport(report);
            Assertions.assertTrue(filed.constructed().isEmpty());
            when(service.posSettingsService.ecommerceStatusReturn()).thenReturn(true);
            service.onEcommerceReport(null);
            service.onEcommerceReport(nameless);
            Assertions.assertTrue(filed.constructed().isEmpty());
            service.onEcommerceReport(report);
            Assertions.assertEquals(1, filed.constructed().size());
            StreamEvent event = filed.constructed().get(0);
            Assertions.assertEquals("SHOP", event.providerCode);
            Assertions.assertEquals(StreamEvent.Kind.ECOMMERCE, event.kind);
            Assertions.assertTrue(event.payload.contains("\"pdvNumber\":\"12345\""));
            Assertions.assertTrue(event.payload.contains("\"status\":\"INTEGRATED\""));
        }
    }

    /**
     * Each receiver is posted in its protocol: the house JSON on /events, the
     * Catalina and TFREMA JSON on their own address, the DS and Anaveo text with
     * their media type — a provider with no protocol reading as the house JSON.
     */
    @Test
    void eachReceiverIsPostedInItsProtocol() {
        ExternalRelayService relay = mock(ExternalRelayService.class);
        ExternalRelayService.RelayResult ok = new ExternalRelayService.RelayResult(true, 200, "");
        when(relay.relay(any(), anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(ok);
        when(relay.relay(any(), anyString(), anyString(), anyString(), anyLong(), any(), anyString())).thenReturn(ok);
        TicketStreamService service = service(true, false, relay);
        List<ServiceProvider> providers = new ArrayList<>();
        providers.add(speaking("CAT", ServiceProvider.ProviderType.DECISIONNEL,
                ServiceProvider.ProviderProtocol.CATALINA, "https://cat"));
        providers.add(speaking("TFR", ServiceProvider.ProviderType.COMMERCIAL,
                ServiceProvider.ProviderProtocol.TFREMA, "https://tfr"));
        providers.add(speaking("DSV", ServiceProvider.ProviderType.COMMERCIAL,
                ServiceProvider.ProviderProtocol.DS_VENTES, "https://ds"));
        providers.add(speaking("ANA", ServiceProvider.ProviderType.EXPLOITATION,
                ServiceProvider.ProviderProtocol.ANAVEO, "https://ana"));
        providers.add(speaking("JSON", ServiceProvider.ProviderType.EXPLOITATION,
                ServiceProvider.ProviderProtocol.HTTP_JSON, "https://json"));
        ServiceProvider bare = provider("BARE", ServiceProvider.ProviderType.EXPLOITATION, "https://bare", "k", true);
        bare.protocol = null;
        providers.add(bare);
        List<StreamEvent> pending = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            for (ServiceProvider provider : providers) {
                StreamEvent event = mock(StreamEvent.class);
                event.providerCode = provider.code;
                event.payload = "body-" + provider.code;
                pending.add(event);
                stubFindByCode(panache, provider);
            }
            stubPending(panache, pending);
            Assertions.assertEquals(6, service.deliverPending());
        }
        verify(relay).relay(any(), eq("POST"), eq(""), eq("body-CAT"), anyLong(), eq("k"));
        verify(relay).relay(any(), eq("POST"), eq(""), eq("body-TFR"), anyLong(), eq("k"));
        verify(relay).relay(any(), eq("POST"), eq(""), eq("body-DSV"), anyLong(), eq("k"),
                eq("text/plain; charset=UTF-8"));
        verify(relay).relay(any(), eq("POST"), eq(""), eq("body-ANA"), anyLong(), eq("k"),
                eq("text/plain; charset=UTF-8"));
        verify(relay).relay(any(), eq("POST"), eq("/events"), eq("body-JSON"), anyLong(), eq("k"));
        verify(relay).relay(any(), eq("POST"), eq("/events"), eq("body-BARE"), anyLong(), eq("k"));
    }
}
