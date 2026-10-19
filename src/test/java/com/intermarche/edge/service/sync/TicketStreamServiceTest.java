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
}
