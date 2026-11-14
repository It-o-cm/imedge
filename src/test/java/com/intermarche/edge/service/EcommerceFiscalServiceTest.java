package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.sync.SyncPayloads;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceFiscalService} (BO-07-01-06): an integrated
 * order is stated on the document the platform issued, copied as integrated,
 * or on a simplified invoice the node issues in the series of the virtual
 * register, numbered after the last, signed and chained, with its QR payload.
 */
class EcommerceFiscalServiceTest {

    /**
     * A spied service with no previous document and a store of tax number
     * PT501234567, the ATCUD code ABCDEFGH and the SAF-T identifier 7.
     *
     * @return the service
     */
    private EcommerceFiscalService service() {
        EcommerceFiscalService service = spy(new EcommerceFiscalService());
        service.posSettingsService = mock(PosSettingsService.class);
        service.privateKey = Optional.empty();
        when(service.posSettingsService.atcudCode("FS")).thenReturn("ABCDEFGH");
        when(service.posSettingsService.saftDocumentId("FS")).thenReturn(7);
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn("1234/AT");
        Store store = new Store();
        store.vatNumber = "PT501234567";
        doReturn(List.of(store)).when(service).stores();
        doReturn(null).when(service).last(any());
        return service;
    }

    /**
     * A line of a booked ticket.
     *
     * @param total the line total
     * @param rate the VAT rate, or null
     * @return the line
     */
    private SyncPayloads.LineDto line(String total, String rate) {
        SyncPayloads.LineDto line = new SyncPayloads.LineDto();
        line.totalPrice = new BigDecimal(total);
        line.vatRate = rate == null ? null : new BigDecimal(rate);
        return line;
    }

    /**
     * A booked ticket of the virtual register ECOM, one line per VAT class.
     *
     * @param taxId the customer's NIF
     * @return the ticket
     */
    private SyncPayloads.TicketDto ticket(String taxId) {
        SyncPayloads.TicketDto ticket = new SyncPayloads.TicketDto();
        ticket.ticketNumber = "ECOM-W1";
        ticket.terminalId = "ECOM";
        ticket.closingDate = "2026-10-24T09:30:15.123";
        ticket.customerTaxId = taxId;
        ticket.totalIncludingTax = new BigDecimal("41.00");
        ticket.totalVat = new BigDecimal("4.00");
        ticket.lines = List.of(line("10.00", null), line("10.60", "0.06"), line("11.30", "0.13"), line("9.10", "0.23"));
        return ticket;
    }

    /**
     * With no document from the platform, the node issues the first simplified
     * invoice of the series, its ATCUD, its SAF-T identifier, a signature over
     * the chained data, an empty previous signature and the QR payload of the
     * final consumer.
     */
    @Test
    void theNodeIssuesTheFirstSimplifiedInvoice() throws Exception {
        EcommerceFiscalService service = service();
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        service.privateKey = Optional.of(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        SyncPayloads.FiscalDocumentDto document = service.document(null, ticket("999999990"));
        assertEquals("FS", document.documentType);
        assertEquals("ECOM2026", document.series);
        assertEquals(1L, document.rank);
        assertEquals("FS ECOM2026/1", document.number);
        assertEquals("ABCDEFGH-1", document.atcud);
        assertEquals(7, document.saftDocumentId);
        assertEquals("2026-10-24T09:30:15", document.issuedAt);
        assertEquals("", document.previousHash);
        assertEquals("ECOM", document.terminalId);
        assertNull(document.sourceBilling);
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update("2026-10-24;2026-10-24T09:30:15;FS ECOM2026/1;41.00;".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(document.hash)));
        assertTrue(document.qrPayload.startsWith("A:501234567*B:999999990*C:PT*D:FS*E:N*F:20261024*G:FS ECOM2026/1"
                + "*H:ABCDEFGH-1*I1:PT*I2:10.00*I3:10.00*I4:0.60*I5:10.00*I6:1.30*I7:7.40*I8:1.70*N:4.00*O:41.00*Q:"));
        assertTrue(document.qrPayload.endsWith("*R:1234"));
    }

    /**
     * The next invoice of a series follows the last one: its rank plus one,
     * chained to its signature; with an empty invoice from the platform the
     * node issues its own.
     */
    @Test
    void theNextInvoiceChainsToTheLast() {
        EcommerceFiscalService service = service();
        FiscalDocument previous = new FiscalDocument();
        previous.rank = 4L;
        previous.hash = "PREVIOUS";
        doReturn(previous).when(service).last("ECOM2026");
        EcommerceFiscalService.InvoiceDto empty = new EcommerceFiscalService.InvoiceDto();
        SyncPayloads.FiscalDocumentDto document = service.document(empty, ticket("PT123456789"));
        assertEquals("FS ECOM2026/5", document.number);
        assertEquals("PREVIOUS", document.previousHash);
        assertTrue(document.qrPayload.contains("*B:123456789*C:PT*"));
        empty.number = " ";
        assertEquals("FS ECOM2026/5", service.document(empty, ticket("ES12345678Z")).number);
    }

    /**
     * The platform's own invoice is copied as issued, marked integrated: its
     * type, number, series and rank, its ATCUD, date and signature; what it
     * leaves out is completed — an FS, the order's date, a zero signature, a
     * zero ATCUD.
     */
    @Test
    void thePlatformsInvoiceIsCopiedAsIntegrated() {
        EcommerceFiscalService service = service();
        EcommerceFiscalService.InvoiceDto invoice = new EcommerceFiscalService.InvoiceDto();
        invoice.type = "ft";
        invoice.number = " FT WEB2026/42 ";
        invoice.atcud = " JFJ12345-42 ";
        invoice.issuedAt = " 2026-10-23T18:00:00 ";
        invoice.hash = " SIGNED ";
        SyncPayloads.FiscalDocumentDto copied = service.document(invoice, ticket("PT123456789"));
        assertEquals("FT", copied.documentType);
        assertEquals("FT WEB2026/42", copied.number);
        assertEquals("WEB2026", copied.series);
        assertEquals(42L, copied.rank);
        assertEquals("JFJ12345-42", copied.atcud);
        assertEquals("2026-10-23T18:00:00", copied.issuedAt);
        assertEquals("SIGNED", copied.hash);
        assertEquals("", copied.previousHash);
        assertEquals("I", copied.sourceBilling);
        assertTrue(copied.qrPayload.contains("*D:FT*E:N*F:20261023*G:FT WEB2026/42*H:JFJ12345-42*"));
        EcommerceFiscalService.InvoiceDto bare = new EcommerceFiscalService.InvoiceDto();
        bare.number = "W42";
        SyncPayloads.FiscalDocumentDto completed = service.document(bare, ticket(null));
        assertEquals("FS", completed.documentType);
        assertEquals("ECOM", completed.series);
        assertEquals(0L, completed.rank);
        assertEquals("0-0", completed.atcud);
        assertEquals("2026-10-24T09:30:15.123", completed.issuedAt);
        assertEquals("0", completed.hash);
        bare.number = "FS A/x";
        bare.type = "FS";
        assertEquals(0L, service.document(bare, ticket(null)).rank);
    }

    /**
     * The QR payload reads the issuer's digits only, none without a store; a
     * certificate number without its suffix is stated as is, an absent or
     * blank one as zero; a ticket with no exempt line states no exempt base.
     */
    @Test
    void theQrPayloadStatesTheIssuerAndTheCertificate() {
        EcommerceFiscalService service = service();
        doReturn(List.of()).when(service).stores();
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn("9999");
        SyncPayloads.TicketDto ticket = ticket(null);
        ticket.lines = List.of(line("9.10", "0.23"), line("9.10", "0.23"));
        SyncPayloads.FiscalDocumentDto document = service.document(null, ticket);
        assertTrue(document.qrPayload.startsWith("A:*B:999999990"));
        assertTrue(document.qrPayload.contains("*I1:PT*I7:14.80*I8:3.40*N:"));
        assertTrue(document.qrPayload.endsWith("*R:9999"));
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn(null);
        assertTrue(service.document(null, ticket).qrPayload.endsWith("*R:0"));
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn(" ");
        assertTrue(service.document(null, ticket).qrPayload.endsWith("*R:0"));
        Store anonymous = new Store();
        doReturn(List.of(anonymous)).when(service).stores();
        assertTrue(service.document(null, ticket).qrPayload.startsWith("A:*"));
    }

    /**
     * The signing key is generated once when none is configured, and an
     * unreadable configured key fails the signature.
     */
    @Test
    void theSigningKeyIsResolvedOnce() throws Exception {
        EcommerceFiscalService service = service();
        assertSame(service.signingKey(), service.signingKey());
        EcommerceFiscalService broken = service();
        broken.privateKey = Optional.of("AAAA");
        assertThrows(IllegalStateException.class, () -> broken.sign("data"));
    }

    /**
     * The signature characters are the 1st, 11th, 21st and 31st, fewer for a
     * shorter signature.
     */
    @Test
    void theSignatureCharactersArePicked() {
        assertEquals("ab", EcommerceFiscalService.hashCharacters("a123456789b123"));
        assertEquals("", EcommerceFiscalService.hashCharacters(""));
        assertEquals("abcd", EcommerceFiscalService.hashCharacters(
                "a123456789b123456789c123456789d123"));
    }

    /**
     * The lookups read the fiscal documents and the stores.
     */
    @Test
    void theLookupsReadTheReferential() {
        EcommerceFiscalService service = new EcommerceFiscalService();
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.list("documentType = ?1 and series = ?2 order by rank desc",
                    FiscalDocument.Type.FS, "ECOM2026")).thenReturn(List.of());
            base.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertNull(service.last("ECOM2026"));
            assertTrue(service.stores().isEmpty());
        }
    }
}
