package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.TechnicalEventService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceDuplicateService}: the reprint of a known
 * document counted and journaled, a first reprint numbered after the original
 * print, an unknown or missing number, and the document the frame renders —
 * its title, header, address and lines.
 */
class InvoiceDuplicateServiceTest {

    /**
     * Builds an invoice.
     *
     * @return the invoice
     */
    private Invoice invoice() {
        Invoice invoice = new Invoice();
        invoice.documentNumber = "F1";
        invoice.documentType = DocumentType.FACTURE;
        invoice.issueDate = LocalDateTime.of(2026, 10, 20, 10, 5);
        invoice.terminalId = "C01";
        invoice.ticketNumber = "T1";
        invoice.customerName = "SARL Durand";
        invoice.totalIncludingTax = new BigDecimal("12.00");
        return invoice;
    }

    /**
     * Builds the service over a mocked renderer and journal.
     *
     * @return the service
     */
    private InvoiceDuplicateService service() {
        InvoiceDuplicateService service = new InvoiceDuplicateService();
        service.pdfReportWriter = mock(PdfReportWriter.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        when(service.pdfReportWriter.write(any(), any())).thenReturn(new byte[]{9});
        return service;
    }

    /**
     * Runs a reprint of a number, the referential answering an invoice.
     *
     * @param service the service
     * @param number the number
     * @param found the invoice found, or null
     * @return the PDF
     */
    @SuppressWarnings("unchecked")
    private byte[] reprint(InvoiceDuplicateService service, String number, Invoice found) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Object> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find(eq("documentNumber"), any(Object[].class))).thenReturn(query);
            return service.duplicate(number, "pt");
        }
    }

    /**
     * A first reprint is the duplicate N° 1 of an invoice printed once; the
     * next one the N° 2; each is rendered over the issue day and journaled.
     */
    @Test
    void aReprintIsCountedAndJournaled() {
        InvoiceDuplicateService service = service();
        Invoice invoice = invoice();
        TechnicalEvent recorded = new TechnicalEvent();
        recorded.terminalId = "NODE";
        when(service.technicalEventService.record(any(), any(), any(), any(), any(), any())).thenReturn(recorded);
        assertArrayEquals(new byte[]{9}, reprint(service, "F1", invoice));
        assertEquals(2, invoice.printCount);
        ArgumentCaptor<ReportData> data = ArgumentCaptor.forClass(ReportData.class);
        ArgumentCaptor<ReportCriteria> criteria = ArgumentCaptor.forClass(ReportCriteria.class);
        verify(service.pdfReportWriter).write(data.capture(), criteria.capture());
        assertEquals("FACTURE N° F1 — DUPLICATA N° 1", data.getValue().definition.label());
        assertEquals(LocalDate.of(2026, 10, 20), criteria.getValue().reference);
        assertEquals("pt", criteria.getValue().language);
        verify(service.technicalEventService).record(TechnicalEvent.EventType.DUPLICATA_PRINTED,
                "Duplicata 1 du document F1 réimprimé du back-office", null, invoice.ticketNumber, null, "F1");
        assertEquals("C01", recorded.terminalId);
        reprint(service, "F1", invoice);
        assertEquals(3, invoice.printCount);
    }

    /**
     * The reprint rule both routes share (BO-04-01-53, BO-06-04-03): a
     * document that reached the node unprinted still counts its original, so
     * its first reprint is the duplicate N° 1; the event keeps the node's
     * terminal when the document names no register, and takes the register's
     * when it does.
     */
    @Test
    void theReprintRuleCountsTheOriginalAndNamesTheRegister() {
        InvoiceDuplicateService service = service();
        TechnicalEvent anonymous = new TechnicalEvent();
        anonymous.terminalId = "NODE";
        TechnicalEvent named = new TechnicalEvent();
        named.terminalId = "NODE";
        when(service.technicalEventService.record(any(), any(), any(), any(), any(), any()))
                .thenReturn(anonymous, named);
        Invoice unprinted = new Invoice();
        unprinted.documentNumber = "F7";
        unprinted.ticketNumber = "C04-00000007";
        unprinted.printCount = 0;
        assertEquals(1, service.countReprint(unprinted));
        assertEquals(2, unprinted.printCount);
        assertEquals("NODE", anonymous.terminalId);
        unprinted.terminalId = "C04";
        assertEquals(2, service.countReprint(unprinted));
        assertEquals("C04", named.terminalId);
        verify(service.technicalEventService).record(TechnicalEvent.EventType.DUPLICATA_PRINTED,
                "Duplicata 2 du document F7 réimprimé du back-office", null, "C04-00000007", null, "F7");
    }

    /**
     * An unknown number and a missing one render nothing and journal nothing.
     */
    @Test
    void anUnknownDocumentRendersNothing() {
        InvoiceDuplicateService service = service();
        assertNull(reprint(service, "F9", null));
        assertNull(reprint(service, null, invoice()));
        verify(service.technicalEventService, never()).record(any(), any(), any(), any(), any(), any());
    }

    /**
     * The document: its code, requirement and family, the header with the
     * account number and the address, the lines without the cancelled ones,
     * an unknown code and rate reading blank.
     */
    @Test
    void theDocumentStatesTheInvoice() {
        Invoice invoice = invoice();
        invoice.customerAccountNumber = "C1";
        invoice.customerAddress.streetLine1 = "1 rue Haute";
        invoice.customerAddress.city = "Paris";
        invoice.ticket = new Ticket();
        TicketLine sold = new TicketLine();
        sold.ean = "301";
        sold.productLabel = "Pain";
        sold.quantity = BigDecimal.ONE;
        sold.unitPrice = new BigDecimal("1.20");
        sold.vatRate = new BigDecimal("0.055");
        sold.totalPrice = new BigDecimal("1.20");
        TicketLine bare = new TicketLine();
        TicketLine cancelled = new TicketLine();
        cancelled.cancelled = true;
        invoice.ticket.lines = List.of(sold, bare, cancelled);
        InvoiceDuplicateService.Document document = new InvoiceDuplicateService.Document(invoice, 2);
        assertEquals("duplicata-F1", document.code());
        assertEquals("BO-06-04-03", document.requirement());
        assertEquals(ReportFamily.CUSTOMERS, document.family());
        assertEquals(6, document.columns().size());
        List<String> preamble = document.preamble(new ReportCriteria());
        assertEquals(4, preamble.size());
        assertEquals("DUPLICATA — émis le 20/10/2026 10:05 sur la caisse C01, ticket T1", preamble.get(0));
        assertEquals("Client : SARL Durand (n° C1)", preamble.get(1));
        assertEquals("Adresse : 1 rue Haute Paris", preamble.get(2));
        assertTrue(preamble.get(3).startsWith("Total HT "), preamble.get(3));
        List<List<Object>> rows = document.rows(new ReportCriteria());
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("301", "Pain", BigDecimal.ONE, new BigDecimal("1.20"), new BigDecimal("5.500"),
                new BigDecimal("1.20")), rows.get(0));
        assertEquals("", rows.get(1).get(0));
        assertNull(rows.get(1).get(4));
    }

    /**
     * A document without account, address or ticket states the customer alone
     * and no line.
     */
    @Test
    void aBareDocument() {
        Invoice invoice = invoice();
        invoice.customerAddress = null;
        InvoiceDuplicateService.Document document = new InvoiceDuplicateService.Document(invoice, 1);
        List<String> preamble = document.preamble(new ReportCriteria());
        assertEquals(3, preamble.size());
        assertEquals("Client : SARL Durand", preamble.get(1));
        assertTrue(document.rows(new ReportCriteria()).isEmpty());
        invoice.customerAddress = new com.intermarche.edge.domain.store.Address();
        assertEquals(3, document.preamble(new ReportCriteria()).size());
    }

    /**
     * The duplicate printed for a Dutch back office speaks Dutch — its title
     * and duplicate mention, its header, its customer and its totals — its
     * amounts written as in French (BO-06-01-02).
     */
    @Test
    void theDuplicateIsPrintedInTheOperatorsLanguage() {
        Invoice invoice = invoice();
        invoice.customerAccountNumber = "C1";
        invoice.totalExcludingTax = new BigDecimal("10.00");
        invoice.totalVat = new BigDecimal("2.00");
        InvoiceDuplicateService.Document document = new InvoiceDuplicateService.Document(invoice, 1);
        ReportCriteria criteria = new ReportCriteria();
        criteria.reference = LocalDate.of(2026, 10, 20);
        criteria.language = "nl";
        String pdf = new String(new PdfReportWriter().write(new ReportData(document, document.rows(criteria)), criteria),
                java.nio.charset.Charset.forName("windows-1252"));
        com.intermarche.edge.ui.admin.BackOfficeMessages dutch = com.intermarche.edge.ui.admin.BackOfficeMessages.in("nl");
        assertTrue(pdf.contains("(" + dutch.text("FACTURE") + " nr. F1 — DUPLICAAT nr. 1)"), pdf);
        assertTrue(pdf.contains("(DUPLICAAT — uitgegeven op 20/10/2026 10:05 aan kassa C01, ticket T1)"), pdf);
        assertTrue(pdf.contains("(Klant: SARL Durand \\(nr. C1\\))"), pdf);
        assertTrue(pdf.contains("(Totaal excl. btw 10,00") && pdf.contains(" — btw 2,00") && pdf.contains(" — incl. btw 12,00"), pdf);
        assertTrue(!pdf.contains("DUPLICATA") && !pdf.contains("Client :"));
    }
}
