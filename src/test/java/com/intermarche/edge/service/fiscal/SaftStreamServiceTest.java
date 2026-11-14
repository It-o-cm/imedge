package com.intermarche.edge.service.fiscal;

import com.intermarche.edge.domain.fiscal.SaftTransmission;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.service.ExternalRelayService;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.export.SaftExportService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link SaftStreamService} (BO-05-01-05, BO-11-04-02): the
 * documents queued at consolidation for every fiscal endpoint, in the tax
 * administration's RegisterInvoice format or the SAF-T envelope, their sends
 * and retries, the relaunch, the API's data and its key.
 */
class SaftStreamServiceTest {

    /** The instant of the tests. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 10, 0);

    /** The query of the active SAFT providers. */
    private static final String ACTIVE = "protocol = ?1 and active = true order by code";

    /**
     * Builds the service with its collaborators mocked.
     *
     * @return the service
     */
    private SaftStreamService service() {
        SaftStreamService service = new SaftStreamService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.saftExportService = mock(SaftExportService.class);
        service.externalRelayService = mock(ExternalRelayService.class);
        service.atWsSecurity = mock(AtWsSecurity.class,
                invocation -> "secure".equals(invocation.getMethod().getName()) ? invocation.getArgument(0) : null);
        when(service.posSettingsService.fiscalRetrySeconds()).thenReturn(30);
        when(service.saftExportService.exportDocuments(any(), any(), any(), any())).thenReturn("<AuditFile/>");
        return service;
    }

    /**
     * Builds a fiscal endpoint.
     *
     * @param code its code
     * @param key its key, null for none
     * @return the provider
     */
    private ServiceProvider endpoint(String code, String key) {
        ServiceProvider provider = new ServiceProvider();
        provider.code = code;
        provider.endpoint = "http://" + code;
        provider.authKey = key;
        provider.protocol = ServiceProvider.ProviderProtocol.SAFT;
        return provider;
    }

    /**
     * Builds a document.
     *
     * @return the document
     */
    private FiscalDocument document() {
        FiscalDocument document = new FiscalDocument();
        document.number = "FS C04/2026/1";
        document.documentType = FiscalDocument.Type.FS;
        document.issuedAt = NOW;
        return document;
    }

    /**
     * Stubs the active SAFT providers and the rows already queued.
     *
     * @param panache the active PanacheEntityBase mock
     * @param providers the providers
     */
    private void stubEndpoints(MockedStatic<PanacheEntityBase> panache, List<ServiceProvider> providers) {
        panache.when(() -> PanacheEntityBase.list(ACTIVE, ServiceProvider.ProviderProtocol.SAFT))
                .thenReturn(new ArrayList<>(providers));
    }

    /**
     * Stubs the row of an identity.
     *
     * @param panache the active PanacheEntityBase mock
     * @param uid the identity
     * @param row the row, or null
     */
    @SuppressWarnings("unchecked")
    private void stubRow(MockedStatic<PanacheEntityBase> panache, String uid, SaftTransmission row) {
        PanacheQuery<SaftTransmission> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> PanacheEntityBase.find("uid", uid)).thenReturn(query);
    }

    /**
     * A consolidated document is queued once per reachable endpoint with its
     * own SAF-T file: a new row, a waiting row rewritten, a sent row left
     * alone, an unreachable endpoint skipped.
     */
    @Test
    void adocumentIsQueuedPerEndpoint() {
        SaftStreamService service = service();
        when(service.posSettingsService.saftRealtime()).thenReturn(true);
        SaftTransmission waiting = mock(SaftTransmission.class);
        waiting.status = SaftTransmission.Status.FAILED;
        SaftTransmission sent = mock(SaftTransmission.class);
        sent.status = SaftTransmission.Status.SENT;
        FiscalDocument document = document();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SaftTransmission> created = mockConstruction(SaftTransmission.class)) {
            stubEndpoints(panache, List.of(endpoint("AT", "K1"), endpoint("BK", "K2"), endpoint("CC", "K3"),
                    endpoint("DOWN", null)));
            stubRow(panache, "AT|FS C04/2026/1", null);
            stubRow(panache, "BK|FS C04/2026/1", waiting);
            stubRow(panache, "CC|FS C04/2026/1", sent);
            assertEquals(2, service.onDocument(document));
            SaftTransmission row = created.constructed().get(0);
            assertEquals("AT|FS C04/2026/1", row.uid);
            assertEquals("AT", row.providerCode);
            assertEquals("FS C04/2026/1", row.documentNumber);
            assertEquals("FS", row.documentType);
            assertEquals(NOW, row.issuedAt);
            assertEquals("<AuditFile/>", row.payload);
            verify(row).persist();
            assertEquals("<AuditFile/>", waiting.payload);
            verify(waiting).persist();
            verify(sent, org.mockito.Mockito.never()).persist();
        }
        verify(service.saftExportService, org.mockito.Mockito.times(2)).exportDocuments(eq(List.of(document)),
                eq(NOW.toLocalDate()), eq(NOW.toLocalDate()), any());
    }

    /**
     * Nothing is queued for no document, while the SAF-T at the flow is off,
     * or with no endpoint in service.
     */
    @Test
    void nothingIsQueuedWithoutDocumentActivationOrEndpoint() {
        SaftStreamService service = service();
        assertEquals(0, service.onDocument(null));
        assertEquals(0, service.onDocument(document()));
        when(service.posSettingsService.saftRealtime()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEndpoints(panache, List.of());
            assertEquals(0, service.onDocument(document()));
        }
    }

    /**
     * Builds a row waiting for its send.
     *
     * @return the row, its persist neutralized
     */
    private SaftTransmission row() {
        SaftTransmission row = mock(SaftTransmission.class);
        row.providerCode = "AT";
        row.documentNumber = "FS C04/2026/1";
        row.documentType = "FS";
        row.issuedAt = NOW;
        row.payload = "<AuditFile/>";
        return row;
    }

    /**
     * Stubs the endpoint a code resolves to.
     *
     * @param panache the active PanacheEntityBase mock
     * @param provider the provider, or null
     */
    @SuppressWarnings("unchecked")
    private void stubCode(MockedStatic<PanacheEntityBase> panache, ServiceProvider provider) {
        PanacheQuery<ServiceProvider> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(provider);
        panache.when(() -> PanacheEntityBase.find("code", "AT")).thenReturn(query);
    }

    /**
     * A send the endpoint takes is marked sent with its acknowledgement; an
     * endpoint gone, unreachable, unreached or refusing fails the send, tried
     * again after the delay.
     */
    @Test
    void asendIsSentOrTriedAgain() {
        SaftStreamService service = service();
        SaftTransmission row = row();
        when(service.externalRelayService.relay(eq(Optional.of("http://AT")), eq("POST"), eq("/saft/documents"),
                anyString(), anyLong(), eq("K1")))
                .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""),
                        new ExternalRelayService.RelayResult(true, 500, ""),
                        new ExternalRelayService.RelayResult(true, 100, ""),
                        new ExternalRelayService.RelayResult(true, 201, "{\"ack\":\"ok\"}"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubCode(panache, null);
            assertFalse(service.send(row, NOW));
            assertEquals("Point d'accès fiscal hors service ou non configuré.", row.lastError);
            stubCode(panache, endpoint("AT", null));
            assertFalse(service.send(row, NOW));
            stubCode(panache, endpoint("AT", "K1"));
            assertFalse(service.send(row, NOW));
            assertFalse(service.send(row, NOW));
            assertFalse(service.send(row, NOW));
            assertEquals("Envoi refusé ou impossible (statut 100).", row.lastError);
            assertEquals(5, row.attempts);
            assertEquals(SaftTransmission.Status.FAILED, row.status);
            assertEquals(NOW.plusSeconds(30), row.nextAttemptAt);
            assertTrue(service.send(row, NOW));
            assertEquals(SaftTransmission.Status.SENT, row.status);
            assertEquals(NOW, row.sentAt);
            assertNull(row.lastError);
            assertEquals("ok", row.acknowledgement);
        }
        verify(service.externalRelayService, org.mockito.Mockito.atLeastOnce()).relay(any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("\"saft\":\"<AuditFile/>\""), anyLong(), any());
    }

    /**
     * The tick does nothing while the SAF-T at the flow is off; on, it sends
     * the rows due and counts those sent.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thetickSendsTheRowsDue() {
        SaftStreamService service = spy(service());
        assertEquals(0, service.tick(NOW));
        when(service.posSettingsService.saftRealtime()).thenReturn(true);
        SaftTransmission first = new SaftTransmission();
        SaftTransmission second = new SaftTransmission();
        doReturn(true).when(service).send(first, NOW);
        doReturn(false).when(service).send(second, NOW);
        PanacheQuery<SaftTransmission> due = mock(PanacheQuery.class);
        when(due.page(0, SaftStreamService.BATCH)).thenReturn(due);
        when(due.list()).thenReturn(List.of(first, second));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(anyString(), any(List.class), eq(NOW))).thenReturn(due);
            assertEquals(1, service.tick(NOW));
        }
    }

    /**
     * The failed sends are relaunched at once.
     */
    @Test
    void thefailedSendsAreRelaunched() {
        SaftStreamService service = service();
        SaftTransmission failed = mock(SaftTransmission.class);
        failed.nextAttemptAt = NOW;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("status", SaftTransmission.Status.FAILED))
                    .thenReturn(new ArrayList<>(List.of(failed)));
            assertEquals(1, service.relaunch());
        }
        assertNull(failed.nextAttemptAt);
        verify(failed).persist();
    }

    /**
     * The API serves the documents issued since an instant, each with its
     * file.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theApiServesTheDocumentsSinceAnInstant() {
        SaftStreamService service = service();
        FiscalDocument document = document();
        PanacheQuery<FiscalDocument> query = mock(PanacheQuery.class);
        when(query.page(0, 10)).thenReturn(query);
        when(query.list()).thenReturn(List.of(document));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("issuedAt > ?1 order by issuedAt, id", NOW.minusDays(1)))
                    .thenReturn(query);
            List<Object[]> served = service.documentsSince(NOW.minusDays(1), 10);
            assertSame(document, served.get(0)[0]);
            assertEquals("<AuditFile/>", served.get(0)[1]);
        }
    }

    /**
     * Only the key of an active SAFT provider opens the API.
     */
    @Test
    void onlyAnEndpointsKeyOpensTheApi() {
        SaftStreamService service = service();
        assertFalse(service.authorized(null));
        assertFalse(service.authorized(" "));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubEndpoints(panache, List.of(endpoint("NOKEY", null), endpoint("AT", "K1")));
            assertTrue(service.authorized(" K1 "));
            assertFalse(service.authorized("K2"));
        }
    }

    /**
     * Builds the tax administration's endpoint: a Fiscal provider speaking
     * SAFT.
     *
     * @return the provider
     */
    private ServiceProvider administration() {
        ServiceProvider provider = endpoint("AT", "K1");
        provider.providerType = ServiceProvider.ProviderType.FISCAL;
        return provider;
    }

    /**
     * Builds a sale of one line at 23 % in a store of a tax number.
     *
     * @param vatNumber the store's tax number, possibly null
     * @return the sale
     */
    private com.intermarche.edge.domain.sale.Ticket sale(String vatNumber) {
        com.intermarche.edge.domain.sale.Ticket sale = new com.intermarche.edge.domain.sale.Ticket();
        sale.store = new com.intermarche.edge.domain.store.Store();
        sale.store.vatNumber = vatNumber;
        com.intermarche.edge.domain.sale.TicketLine line = new com.intermarche.edge.domain.sale.TicketLine();
        line.vatRate = new BigDecimal("0.2300");
        line.totalPrice = new BigDecimal("12.30");
        sale.lines.add(line);
        return sale;
    }

    /**
     * BO-11-04-02: the tax administration is queued the RegisterInvoice
     * message of a sale document, the other endpoints its SAF-T file, and a
     * receipt goes to the other endpoints only; a document is queued once per
     * endpoint, the sent row never queued again.
     */
    @Test
    void thetaxAdministrationIsQueuedTheRegisterInvoiceOfInvoicesOnly() {
        SaftStreamService service = service();
        when(service.posSettingsService.saftRealtime()).thenReturn(true);
        FiscalDocument document = document();
        document.ticketId = 7L;
        FiscalDocument receipt = document();
        receipt.number = "RC C04/2026/1";
        receipt.documentType = FiscalDocument.Type.RC;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<SaftTransmission> created = mockConstruction(SaftTransmission.class)) {
            stubEndpoints(panache, List.of(administration(), endpoint("BK", "K2")));
            stubRow(panache, "AT|FS C04/2026/1", null);
            stubRow(panache, "BK|FS C04/2026/1", null);
            stubRow(panache, "BK|RC C04/2026/1", null);
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(sale("PT 501 234 567"));
            assertEquals(2, service.onDocument(document));
            assertEquals(1, service.onDocument(receipt));
            assertEquals(3, created.constructed().size());
            SaftTransmission toAdministration = created.constructed().get(0);
            assertEquals("AT", toAdministration.providerCode);
            assertTrue(toAdministration.payload.contains("<ns2:InvoiceNo>FS C04/2026/1</ns2:InvoiceNo>"));
            assertTrue(toAdministration.payload.contains("<TaxRegistrationNumber>501234567</TaxRegistrationNumber>"));
            assertTrue(toAdministration.payload.contains("<ns2:DebitAmount>10.00</ns2:DebitAmount>"));
            assertEquals("<AuditFile/>", created.constructed().get(1).payload);
            assertEquals("BK", created.constructed().get(2).providerCode);
            assertEquals("RC", created.constructed().get(2).documentType);
        }
    }

    /**
     * BO-11-04-02: the RegisterInvoice of a credit note is ventilated on the
     * refund lines and names the store of the original sale; a refund with no
     * original sale, a vanished refund, a vanished sale, a store with no tax
     * number, a sale with no store and a document of neither leave the issuer
     * empty.
     */
    @Test
    void theregisterInvoiceDrawsItsVentilationAndItsIssuer() {
        SaftStreamService service = service();
        com.intermarche.edge.domain.sale.Refund refund = new com.intermarche.edge.domain.sale.Refund();
        refund.originalTicketId = 9L;
        com.intermarche.edge.domain.sale.RefundLine line = new com.intermarche.edge.domain.sale.RefundLine();
        line.quantity = BigDecimal.ONE;
        line.price = new BigDecimal("12.30");
        line.vatRate = new BigDecimal("0.2300");
        refund.lines.add(line);
        com.intermarche.edge.domain.sale.Refund orphan = new com.intermarche.edge.domain.sale.Refund();
        com.intermarche.edge.domain.sale.Ticket noStore = sale(null);
        noStore.store = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(11L)).thenReturn(refund);
            panache.when(() -> PanacheEntityBase.findById(12L)).thenReturn(orphan);
            panache.when(() -> PanacheEntityBase.findById(13L)).thenReturn(null);
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(sale("501234567"));
            panache.when(() -> PanacheEntityBase.findById(20L)).thenReturn(sale(null));
            panache.when(() -> PanacheEntityBase.findById(21L)).thenReturn(noStore);
            panache.when(() -> PanacheEntityBase.findById(22L)).thenReturn(null);
            FiscalDocument note = document();
            note.documentType = FiscalDocument.Type.NC;
            note.refundId = 11L;
            String credit = service.registerInvoiceOf(note);
            assertTrue(credit.contains("<TaxRegistrationNumber>501234567</TaxRegistrationNumber>"));
            assertTrue(credit.contains("<ns2:CreditAmount>10.00</ns2:CreditAmount>"));
            note.refundId = 12L;
            assertTrue(service.registerInvoiceOf(note).contains("<TaxRegistrationNumber></TaxRegistrationNumber>"));
            note.refundId = 13L;
            String vanished = service.registerInvoiceOf(note);
            assertTrue(vanished.contains("<TaxRegistrationNumber></TaxRegistrationNumber>"));
            assertFalse(vanished.contains("<Line>"));
            FiscalDocument sold = document();
            for (Long id : List.of(20L, 21L, 22L)) {
                sold.ticketId = id;
                assertTrue(service.registerInvoiceOf(sold).contains("<TaxRegistrationNumber></TaxRegistrationNumber>"));
            }
            sold.ticketId = null;
            String neither = service.registerInvoiceOf(sold);
            assertTrue(neither.contains("<TaxRegistrationNumber></TaxRegistrationNumber>"));
            assertFalse(neither.contains("<Line>"));
        }
    }

    /**
     * BO-11-04-02: a row for the tax administration is posted as it is, on the
     * address it gave, as SOAP XML.
     */
    @Test
    void thetaxAdministrationIsPostedTheSoapMessage() {
        SaftStreamService service = service();
        SaftTransmission row = row();
        row.payload = "<S:Envelope/>";
        when(service.externalRelayService.relay(Optional.of("http://AT"), "POST", "", "<S:Envelope/>",
                SaftStreamService.TIMEOUT_MILLIS, "K1", SaftStreamService.SOAP_MEDIA_TYPE))
                .thenReturn(new ExternalRelayService.RelayResult(true, 200,
                        "<S:Envelope><S:Body><ns2:ReturnCode>0</ns2:ReturnCode>"
                                + "<ns2:ReturnMessage>Registado AT-1</ns2:ReturnMessage></S:Body></S:Envelope>"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubCode(panache, administration());
            assertTrue(service.send(row, NOW));
        }
        assertEquals(SaftTransmission.Status.SENT, row.status);
        assertEquals("0 — Registado AT-1", row.acknowledgement);
    }

    /**
     * BO-11-04-02: the tax administration's answer reads as its return code and
     * message, either alone when the other is missing, the answer itself when
     * it carries neither, nothing for no answer.
     */
    @Test
    void thetaxAdministrationsAnswerReadsAsItsCodeAndMessage() {
        assertNull(SaftStreamService.soapAcknowledgement(null));
        assertNull(SaftStreamService.soapAcknowledgement(" "));
        assertEquals("-3", SaftStreamService.soapAcknowledgement("<x><ReturnCode> -3 </ReturnCode></x>"));
        assertEquals("Duplicado", SaftStreamService.soapAcknowledgement("<ReturnMessage>Duplicado</ReturnMessage>"));
        assertEquals("<ok/>", SaftStreamService.soapAcknowledgement(" <ok/> "));
    }

    /**
     * BO-11-04-02: the message posted to the tax administration is the queued
     * envelope secured at the send — its WS-Security header drawn for the
     * instant of the send, the stored payload left without it.
     *
     * @throws Exception never, the header being stubbed
     */
    @Test
    void thetaxAdministrationIsPostedTheSecuredMessage() throws Exception {
        SaftStreamService service = service();
        SaftTransmission row = row();
        row.payload = "<S:Envelope><S:Body/></S:Envelope>";
        java.time.Instant instant = NOW.atZone(java.time.ZoneId.systemDefault()).toInstant();
        when(service.atWsSecurity.secure(row.payload, instant)).thenReturn("<S:Envelope><S:Header/><S:Body/></S:Envelope>");
        when(service.externalRelayService.relay(Optional.of("http://AT"), "POST", "",
                "<S:Envelope><S:Header/><S:Body/></S:Envelope>", SaftStreamService.TIMEOUT_MILLIS, "K1",
                SaftStreamService.SOAP_MEDIA_TYPE)).thenReturn(new ExternalRelayService.RelayResult(true, 200, "<ok/>"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubCode(panache, administration());
            assertTrue(service.send(row, NOW));
        }
        assertEquals(SaftTransmission.Status.SENT, row.status);
        assertEquals("<S:Envelope><S:Body/></S:Envelope>", row.payload);
    }

    /**
     * BO-11-04-02: a certificate the header cannot use — unreadable or holding a
     * key that cannot encrypt — fails the send, which is tried again, and
     * nothing is posted.
     *
     * @throws Exception never, the header being stubbed
     */
    @Test
    void anUnusableCertificateFailsTheSend() throws Exception {
        SaftStreamService service = service();
        SaftTransmission row = row();
        row.payload = "<S:Envelope><S:Body/></S:Envelope>";
        when(service.atWsSecurity.secure(any(), any()))
                .thenThrow(new java.security.InvalidKeyException("EC"))
                .thenThrow(new IllegalArgumentException("base64"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubCode(panache, administration());
            assertFalse(service.send(row, NOW));
            assertFalse(service.send(row, NOW));
        }
        assertEquals(SaftTransmission.Status.FAILED, row.status);
        assertEquals(SaftStreamService.AT_CERTIFICATE_UNREADABLE, row.lastError);
        assertEquals(2, row.attempts);
        verify(service.externalRelayService, org.mockito.Mockito.never()).relay(any(), any(), any(), any(), anyLong(), any(), any());
    }
}
