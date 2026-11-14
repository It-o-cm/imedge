package com.intermarche.edge.service.fiscal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.fiscal.EInvoice;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.domain.store.Address;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link EInvoicingService} (BO-05-01-02): who is concerned, the
 * structured invoice (UN/CEFACT CII, EN 16931), its deposit on the platform,
 * its life cycle pulled and pushed, and the scheduler's tick.
 */
class EInvoicingServiceTest {

    /** The instant of the tests. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 0);

    /**
     * Builds the service with its collaborators mocked.
     *
     * @return the service
     */
    private EInvoicingService service() {
        EInvoicingService service = new EInvoicingService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.externalRelayService = mock(ExternalRelayService.class);
        service.objectMapper = new ObjectMapper();
        when(service.posSettingsService.fiscalRetrySeconds()).thenReturn(60);
        when(service.posSettingsService.einvoicingMention()).thenReturn("");
        when(service.posSettingsService.pdpProviderCode()).thenReturn("PDP");
        return service;
    }

    /**
     * Builds a reachable platform.
     *
     * @return the provider
     */
    private ServiceProvider platform() {
        ServiceProvider provider = new ServiceProvider();
        provider.code = "PDP";
        provider.endpoint = "http://pdp";
        provider.authKey = "KEY";
        provider.active = true;
        return provider;
    }

    /**
     * Stubs the platform the code resolves to.
     *
     * @param panache the active PanacheEntityBase mock
     * @param provider the provider, or null
     */
    @SuppressWarnings("unchecked")
    private void stubPlatform(MockedStatic<PanacheEntityBase> panache, ServiceProvider provider) {
        PanacheQuery<ServiceProvider> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(provider);
        panache.when(() -> PanacheEntityBase.find("code", "PDP")).thenReturn(query);
    }

    /**
     * Builds an invoice to a French professional.
     *
     * @return the invoice
     */
    private Invoice invoice() {
        Invoice invoice = new Invoice();
        invoice.id = 9L;
        invoice.documentNumber = "C04-F000123";
        invoice.issueDate = LocalDateTime.of(2026, 10, 19, 15, 0);
        invoice.customerName = "ACME & Fils";
        invoice.customerSiret = "123 456 789 00012";
        invoice.customerVatNumber = "FR 12 123456789";
        invoice.customerAccountNumber = "C-77";
        invoice.ticketNumber = "C04-00000417";
        invoice.totalExcludingTax = new BigDecimal("10.00");
        invoice.totalVat = new BigDecimal("2.00");
        invoice.totalIncludingTax = new BigDecimal("12.00");
        invoice.customerAddress = new Address();
        invoice.customerAddress.streetLine1 = "1 rue du Port";
        invoice.customerAddress.postalCode = "44000";
        invoice.customerAddress.city = "Nantes";
        return invoice;
    }

    /**
     * Builds an e-invoice already queued.
     *
     * @param status its status
     * @return the e-invoice, its persist neutralized
     */
    private EInvoice electronic(EInvoice.Status status) {
        EInvoice invoice = mock(EInvoice.class);
        invoice.invoiceNumber = "C04-F000123";
        invoice.buyerSiren = "123456789";
        invoice.issuedAt = NOW;
        invoice.totalIncludingTax = new BigDecimal("12.00");
        invoice.payload = "<x/>";
        invoice.status = status;
        org.mockito.Mockito.doCallRealMethod().when(invoice).move(any(), any());
        return invoice;
    }

    /**
     * An invoice is concerned when it is an invoice to a customer carrying a
     * SIRET or a French VAT number; a delivery note, a consumer and a foreign
     * operator are not.
     */
    @Test
    void whoIsConcerned() {
        assertFalse(EInvoicingService.eligible(null));
        Invoice note = invoice();
        note.documentType = DocumentType.BON_LIVRAISON;
        assertFalse(EInvoicingService.eligible(note));
        assertTrue(EInvoicingService.eligible(invoice()));
        Invoice vatOnly = invoice();
        vatOnly.customerSiret = "123";
        assertTrue(EInvoicingService.eligible(vatOnly));
        Invoice foreign = invoice();
        foreign.customerSiret = null;
        foreign.customerVatNumber = "BE0123456789";
        assertFalse(EInvoicingService.eligible(foreign));
        foreign.customerVatNumber = null;
        assertFalse(EInvoicingService.eligible(foreign));
    }

    /**
     * The structured invoice states the document, the seller and the buyer with
     * their SIREN, the lines with their net price and category, the VAT by
     * rate, an exemption, the due date, the order and the totals; deferred, the
     * total is due rather than prepaid.
     */
    @Test
    void thestructuredInvoiceStatesEverything() {
        EInvoicingService service = service();
        when(service.posSettingsService.einvoicingMention()).thenReturn("Opération : livraison de biens");
        Invoice invoice = invoice();
        invoice.orderNumber = "BC-42";
        invoice.customerDueDate = LocalDate.of(2026, 11, 30);
        invoice.customerAddress.country = "be";
        Ticket ticket = new Ticket();
        TicketLine coffee = new TicketLine();
        coffee.ean = "3000000000001";
        coffee.productLabel = "Café";
        coffee.quantity = new BigDecimal("2");
        coffee.totalPrice = new BigDecimal("12.00");
        coffee.vatRate = new BigDecimal("0.20");
        TicketLine book = new TicketLine();
        book.ean = " ";
        book.productLabel = null;
        book.quantity = BigDecimal.ZERO;
        book.totalPrice = new BigDecimal("5.00");
        book.vatRate = null;
        TicketLine gone = new TicketLine();
        gone.cancelled = true;
        gone.totalPrice = BigDecimal.TEN;
        TicketLine empty = new TicketLine();
        empty.totalPrice = null;
        TicketLine lost = new TicketLine();
        lost.quantity = null;
        lost.totalPrice = new BigDecimal("1.05");
        lost.vatRate = new BigDecimal("0.05");
        ticket.lines = new ArrayList<>(List.of(coffee, book, gone, empty, lost));
        invoice.ticket = ticket;
        Store store = new Store();
        store.name = "Intermarché Nantes";
        store.legalName = " ";
        store.siret = "98765432100011";
        store.vatNumber = "FR 99 987654321";
        String xml;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>(List.of(store)));
            xml = service.structured(invoice);
        }
        assertTrue(xml.contains("<ram:ID>urn:cen.eu:en16931:2017</ram:ID>"));
        assertTrue(xml.contains("<ram:ID>C04-F000123</ram:ID>"));
        assertTrue(xml.contains("<ram:TypeCode>380</ram:TypeCode>"));
        assertTrue(xml.contains("<udt:DateTimeString format=\"102\">20261019</udt:DateTimeString>"));
        assertTrue(xml.contains("<ram:Content>Opération : livraison de biens</ram:Content>"));
        assertTrue(xml.contains("<ram:GlobalID schemeID=\"0160\">3000000000001</ram:GlobalID>"));
        assertTrue(xml.contains("<ram:Name>Article</ram:Name>"));
        assertTrue(xml.contains("<ram:ChargeAmount>5.0000</ram:ChargeAmount>"));
        assertTrue(xml.contains("<ram:BilledQuantity unitCode=\"C62\">2</ram:BilledQuantity>"));
        assertTrue(xml.contains("<ram:BuyerReference>C-77</ram:BuyerReference>"));
        assertTrue(xml.contains("<ram:Name>Intermarché Nantes</ram:Name>"));
        assertTrue(xml.contains("<ram:ID schemeID=\"0002\">987654321</ram:ID>"));
        assertTrue(xml.contains("<ram:ID schemeID=\"0002\">123456789</ram:ID>"));
        assertTrue(xml.contains("<ram:ID schemeID=\"VA\">FR99987654321</ram:ID>"));
        assertTrue(xml.contains("<ram:Name>ACME &amp; Fils</ram:Name>"));
        assertTrue(xml.contains("<ram:CountryID>BE</ram:CountryID>"));
        assertTrue(xml.contains("<ram:CountryID>FR</ram:CountryID>"));
        assertTrue(xml.contains("<ram:IssuerAssignedID>BC-42</ram:IssuerAssignedID>"));
        assertTrue(xml.contains("<ram:ExemptionReason>Exonération de TVA</ram:ExemptionReason>"));
        assertTrue(xml.contains("<ram:CategoryCode>E</ram:CategoryCode>"));
        assertTrue(xml.contains("<ram:RateApplicablePercent>20</ram:RateApplicablePercent>"));
        assertTrue(xml.contains("<ram:RateApplicablePercent>5</ram:RateApplicablePercent>"));
        assertTrue(xml.contains("<ram:TaxTotalAmount currencyID=\"EUR\">2.05</ram:TaxTotalAmount>"));
        assertTrue(xml.contains("<ram:GrandTotalAmount>18.05</ram:GrandTotalAmount>"));
        assertTrue(xml.contains("<ram:TotalPrepaidAmount>0.00</ram:TotalPrepaidAmount>"));
        assertTrue(xml.contains("<ram:DuePayableAmount>18.05</ram:DuePayableAmount>"));
        assertTrue(xml.contains("<udt:DateTimeString format=\"102\">20261130</udt:DateTimeString>"));
    }

    /**
     * An invoice whose sale the node does not hold is stated on one line at
     * the rate its totals imply; with no store, no account, no order, no
     * address, a blank order and no date, the parts fall back; paid at the
     * till, the total is prepaid.
     */
    @Test
    void aninvoiceWithoutItsSaleIsStatedOnOneLine() {
        EInvoicingService service = service();
        Invoice invoice = invoice();
        invoice.issueDate = null;
        invoice.customerAccountNumber = null;
        invoice.orderNumber = " ";
        invoice.customerAddress = null;
        invoice.customerVatNumber = " ";
        invoice.customerSiret = "12";
        invoice.customerName = null;
        String xml;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>());
            xml = service.structured(invoice);
        }
        assertTrue(xml.contains("<ram:Name>Ventes du ticket C04-00000417</ram:Name>"));
        assertTrue(xml.contains("<ram:RateApplicablePercent>20</ram:RateApplicablePercent>"));
        assertTrue(xml.contains("<ram:TotalPrepaidAmount>12.00</ram:TotalPrepaidAmount>"));
        assertTrue(xml.contains("<ram:DuePayableAmount>0.00</ram:DuePayableAmount>"));
        assertFalse(xml.contains("BuyerReference"));
        assertFalse(xml.contains("BuyerOrderReferencedDocument"));
        assertFalse(xml.contains("schemeID=\"0002\""));
        assertFalse(xml.contains("schemeID=\"VA\""));
        Invoice empty = invoice();
        empty.totalExcludingTax = null;
        empty.totalVat = null;
        empty.orderNumber = null;
        empty.customerAddress.postalCode = " ";
        empty.customerAddress.city = null;
        empty.customerAddress.country = " ";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>());
            xml = service.structured(empty);
        }
        assertTrue(xml.contains("<ram:CategoryCode>E</ram:CategoryCode>"));
        assertTrue(xml.contains("<ram:GrandTotalAmount>0.00</ram:GrandTotalAmount>"));
        assertFalse(xml.contains("PostcodeCode"));
    }

    /**
     * A new invoice concerned is queued with its structured form; one not
     * concerned or already queued is not.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aninvoiceIsQueuedOnce() {
        EInvoicingService service = service();
        Invoice invoice = invoice();
        invoice.issueDate = null;
        invoice.totalIncludingTax = null;
        PanacheQuery<EInvoice> none = mock(PanacheQuery.class);
        PanacheQuery<EInvoice> one = mock(PanacheQuery.class);
        when(one.firstResult()).thenReturn(new EInvoice());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<EInvoice> created = mockConstruction(EInvoice.class,
                        (mock, context) -> org.mockito.Mockito.doCallRealMethod().when(mock).move(any(), any()))) {
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>());
            panache.when(() -> PanacheEntityBase.find("invoiceNumber", "C04-F000123")).thenReturn(none, one);
            EInvoice queued = service.queue(invoice, NOW);
            assertSame(created.constructed().get(0), queued);
            assertEquals("C04-F000123", queued.invoiceNumber);
            assertEquals(9L, queued.invoiceId);
            assertEquals(NOW, queued.issuedAt);
            assertEquals("ACME & Fils", queued.buyerName);
            assertEquals("123456789", queued.buyerSiren);
            assertEquals(BigDecimal.ZERO, queued.totalIncludingTax);
            assertTrue(queued.payload.contains("CrossIndustryInvoice"));
            assertEquals(EInvoice.Status.PENDING, queued.status);
            assertEquals(NOW, queued.createdAt);
            verify(queued).persist();
            assertNull(service.queue(invoice, NOW));
            Invoice consumer = invoice();
            consumer.customerSiret = null;
            consumer.customerVatNumber = null;
            assertNull(service.queue(consumer, NOW));
            assertEquals(1, created.constructed().size());
        }
        Invoice dated = invoice();
        dated.totalIncludingTax = BigDecimal.ONE;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<EInvoice> created = mockConstruction(EInvoice.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>());
            panache.when(() -> PanacheEntityBase.find("invoiceNumber", "C04-F000123")).thenReturn(none);
            EInvoice queued = service.queue(dated, NOW);
            assertEquals(dated.issueDate, queued.issuedAt);
            assertEquals(BigDecimal.ONE, queued.totalIncludingTax);
        }
    }

    /**
     * The scan queues the recent invoices concerned and counts those queued.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thescanQueuesTheRecentInvoices() {
        EInvoicingService service = spy(service());
        Invoice first = invoice();
        Invoice second = invoice();
        second.id = 10L;
        PanacheQuery<Invoice> query = mock(PanacheQuery.class);
        when(query.page(0, EInvoicingService.BATCH)).thenReturn(query);
        when(query.list()).thenReturn(List.of(first, second));
        doReturn(new EInvoice()).when(service).queue(first, NOW);
        doReturn(null).when(service).queue(second, NOW);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(anyString(), eq(DocumentType.FACTURE),
                    eq(NOW.minusDays(EInvoicingService.SCAN_DAYS)))).thenReturn(query);
            assertEquals(1, service.scan(NOW));
        }
    }

    /**
     * The tick does nothing while e-invoicing is off; on, it queues, deposits
     * and refreshes, counting what moved.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thetickQueuesDepositsAndRefreshes() {
        EInvoicingService service = spy(service());
        assertEquals(0, service.tick(NOW));
        when(service.posSettingsService.einvoicingEnabled()).thenReturn(true);
        EInvoice toDeposit = new EInvoice();
        EInvoice failing = new EInvoice();
        EInvoice toRefresh = new EInvoice();
        EInvoice still = new EInvoice();
        doReturn(2).when(service).scan(NOW);
        doReturn(true).when(service).deposit(toDeposit, NOW);
        doReturn(false).when(service).deposit(failing, NOW);
        doReturn(true).when(service).refresh(toRefresh, NOW);
        doReturn(false).when(service).refresh(still, NOW);
        PanacheQuery<EInvoice> deposits = mock(PanacheQuery.class);
        when(deposits.page(0, EInvoicingService.BATCH)).thenReturn(deposits);
        when(deposits.list()).thenReturn(List.of(toDeposit, failing), List.of(toRefresh, still));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(anyString(), any(List.class), eq(NOW))).thenReturn(deposits);
            assertEquals(4, service.tick(NOW));
        }
    }

    /**
     * A deposit the platform takes records its identifier and the status it
     * states, a code below 200 or none reading as deposited.
     */
    @Test
    void adepositTheplatformTakesIsRecorded() {
        EInvoicingService service = service();
        EInvoice invoice = electronic(EInvoice.Status.PENDING);
        when(service.externalRelayService.relay(eq(Optional.of("http://pdp")), eq("POST"), eq("/invoices"),
                anyString(), eq(EInvoicingService.TIMEOUT_MILLIS), eq("KEY")))
                .thenReturn(new ExternalRelayService.RelayResult(true, 201, "{\"id\":\"P-1\",\"status\":\"201\"}"),
                        new ExternalRelayService.RelayResult(true, 200, ""),
                        new ExternalRelayService.RelayResult(true, 200, "{\"status\":\"0\"}"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPlatform(panache, platform());
            assertTrue(service.deposit(invoice, NOW));
            assertEquals("P-1", invoice.platformId);
            assertEquals(EInvoice.Status.ISSUED, invoice.status);
            assertEquals(NOW, invoice.submittedAt);
            assertEquals(NOW.plusSeconds(60), invoice.nextAttemptAt);
            assertEquals(0, invoice.attempts);
            assertTrue(service.deposit(invoice, NOW));
            assertEquals("C04-F000123", invoice.platformId);
            assertEquals(EInvoice.Status.DEPOSITED, invoice.status);
            assertTrue(service.deposit(invoice, NOW));
            assertEquals(EInvoice.Status.DEPOSITED, invoice.status);
        }
        verify(service.externalRelayService, org.mockito.Mockito.times(3)).relay(any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("\"document\":\"<x/>\""), anyLong(), any());
    }

    /**
     * A deposit fails without a platform, when the platform is not reached,
     * and when it answers outside 2xx; it is tried again after the delay, the
     * status moving to failed once.
     */
    @Test
    void afailedDepositIsTriedAgain() {
        EInvoicingService service = service();
        EInvoice invoice = electronic(EInvoice.Status.PENDING);
        when(service.externalRelayService.relay(any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""),
                        new ExternalRelayService.RelayResult(true, 503, ""),
                        new ExternalRelayService.RelayResult(true, 199, ""));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPlatform(panache, null);
            assertFalse(service.deposit(invoice, NOW));
            assertEquals(EInvoicingService.NO_PLATFORM, invoice.lastError);
            assertEquals(EInvoice.Status.FAILED, invoice.status);
            stubPlatform(panache, platform());
            assertFalse(service.deposit(invoice, NOW));
            assertFalse(service.deposit(invoice, NOW));
            assertEquals("Dépôt refusé ou impossible (statut 503).", invoice.lastError);
            assertFalse(service.deposit(invoice, NOW));
            assertEquals(4, invoice.attempts);
            assertEquals(NOW.plusSeconds(60), invoice.nextAttemptAt);
        }
        verify(invoice, org.mockito.Mockito.times(1)).move(EInvoice.Status.FAILED, NOW);
    }

    /**
     * The platform is the administered provider when it can be called.
     */
    @Test
    void theplatformMustBeReachable() {
        EInvoicingService service = service();
        ServiceProvider off = platform();
        off.active = false;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPlatform(panache, off);
            assertNull(service.platform());
            ServiceProvider on = platform();
            stubPlatform(panache, on);
            assertSame(on, service.platform());
        }
    }

    /**
     * A refresh reads the status the platform states and applies it; without a
     * platform, without an identifier, unreached or refused, it changes nothing
     * and waits for the next delay.
     */
    @Test
    void arefreshAppliesTheStatusStated() {
        EInvoicingService service = service();
        EInvoice invoice = electronic(EInvoice.Status.DEPOSITED);
        when(service.externalRelayService.relay(eq(Optional.of("http://pdp")), eq("GET"), eq("/invoices/P-1"),
                isNull(), anyLong(), eq("KEY")))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""),
                        new ExternalRelayService.RelayResult(true, 404, ""),
                        new ExternalRelayService.RelayResult(true, 100, ""),
                        new ExternalRelayService.RelayResult(true, 200, "{}"),
                        new ExternalRelayService.RelayResult(true, 200, "{\"status\":\"REJECTED\",\"reason\":\"SIREN\"}"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPlatform(panache, null);
            assertFalse(service.refresh(invoice, NOW));
            stubPlatform(panache, platform());
            assertFalse(service.refresh(invoice, NOW));
            invoice.platformId = "P-1";
            assertFalse(service.refresh(invoice, NOW));
            assertFalse(service.refresh(invoice, NOW));
            assertFalse(service.refresh(invoice, NOW));
            assertFalse(service.refresh(invoice, NOW));
            assertEquals(EInvoice.Status.DEPOSITED, invoice.status);
            assertTrue(service.refresh(invoice, NOW));
            assertEquals(EInvoice.Status.REJECTED, invoice.status);
            assertEquals("SIREN", invoice.lastError);
            assertEquals(NOW.plusSeconds(60), invoice.nextAttemptAt);
        }
    }

    /**
     * A status pushed by the platform is applied to the invoice it names — by
     * the platform's identifier or by number —; an unknown invoice or status is
     * refused, and an unchanged status changes nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void apushedStatusIsApplied() {
        EInvoicingService service = service();
        EInvoice invoice = electronic(EInvoice.Status.DEPOSITED);
        PanacheQuery<EInvoice> none = mock(PanacheQuery.class);
        PanacheQuery<EInvoice> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(invoice);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("platformId", "P-1")).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find("platformId", "C04-F000123")).thenReturn(none);
            panache.when(() -> PanacheEntityBase.find("invoiceNumber", "C04-F000123")).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find("platformId", "X")).thenReturn(none);
            panache.when(() -> PanacheEntityBase.find("invoiceNumber", "X")).thenReturn(none);
            assertNull(service.receive("P-1", "205", null));
            assertEquals(EInvoice.Status.APPROVED, invoice.status);
            assertNull(service.receive("C04-F000123", "CASHED", "payé"));
            assertEquals(EInvoice.Status.CASHED, invoice.status);
            assertEquals("payé", invoice.lastError);
            assertNull(service.receive("P-1", "212", null));
            assertEquals("Statut inconnu.", service.receive("P-1", "???", null));
            assertEquals("Facture électronique inconnue.", service.receive("X", "205", null));
        }
        assertEquals("Facture électronique inconnue.", service.receive(null, "205", null));
        verify(invoice, never()).move(eq(EInvoice.Status.PAYMENT_SENT), any());
    }

    /**
     * An answer is read as JSON, blank, absent or unreadable reading as empty;
     * a field is read with its fallback when absent, null or blank.
     */
    @Test
    void answersAreReadWithTheirFallbacks() throws Exception {
        EInvoicingService service = service();
        assertTrue(service.read(null).isEmpty());
        assertTrue(service.read(" ").isEmpty());
        assertTrue(service.read("not json").isEmpty());
        com.fasterxml.jackson.databind.JsonNode node = service.read("{\"a\":\"x\",\"b\":null,\"c\":\" \"}");
        assertEquals("x", EInvoicingService.text(node, "a", "f"));
        assertEquals("f", EInvoicingService.text(node, "b", "f"));
        assertEquals("f", EInvoicingService.text(node, "c", "f"));
        assertEquals("f", EInvoicingService.text(node, "d", "f"));
    }

    /**
     * The small helpers: escaping, categories, percentages, SIREN, digits and
     * the first filled value.
     */
    @Test
    void thehelpersWriteTheStructuredValues() {
        assertEquals("", EInvoicingService.escape(null));
        assertEquals("a&lt;b&gt;&quot;", EInvoicingService.escape("a<b>\""));
        assertEquals("S", EInvoicingService.category(new BigDecimal("0.055")));
        assertEquals("5.5", EInvoicingService.percent(new BigDecimal("0.055")));
        assertEquals("", EInvoicingService.siren(null));
        assertEquals("", EInvoicingService.digits(null));
        assertEquals("b", EInvoicingService.firstFilled(null, "b"));
        assertEquals("a", EInvoicingService.firstFilled("a", "b"));
        assertFalse(EInvoicingService.frenchVat(null));
    }
}
