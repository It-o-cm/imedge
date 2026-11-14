package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.AccountCustomer;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.service.NodeEchelonService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceListReport}: the invoices and delivery notes of
 * the period with their customer, read off the account or the document, the
 * two document options, the preamble naming the point of sale, the reprint
 * link and the metadata.
 */
class InvoiceListReportTest {

    /**
     * Builds an invoice.
     *
     * @param number the number
     * @param type the type, possibly null
     * @return the invoice
     */
    private Invoice invoice(String number, DocumentType type) {
        Invoice invoice = new Invoice();
        invoice.documentNumber = number;
        invoice.documentType = type;
        invoice.issueDate = LocalDateTime.of(2026, 10, 20, 10, 0);
        invoice.totalIncludingTax = new BigDecimal("12.00");
        invoice.ticketNumber = "T1";
        return invoice;
    }

    /**
     * Builds the report.
     *
     * @param stub the stub
     * @param pdv the point of sale, possibly null
     * @return the report
     */
    private InvoiceListReport report(QueryStub stub, String pdv) {
        InvoiceListReport report = new InvoiceListReport();
        report.entityManager = stub.entityManager;
        report.nodeEchelonService = mock(NodeEchelonService.class);
        when(report.nodeEchelonService.currentPdv()).thenReturn(pdv);
        return report;
    }

    /**
     * Every document of the period: a customer account with its names and
     * contacts, an account without them falling back on the document and its
     * ticket, no account at all, no ticket either.
     */
    @Test
    void everyDocumentWithItsCustomer() {
        Invoice full = invoice("F1", DocumentType.FACTURE);
        full.customer = new AccountCustomer();
        full.customer.firstName = "Jean";
        full.customer.lastName = "Dupont";
        full.customer.email = "jean@exemple.fr";
        full.customer.phone = "0102030405";
        full.customerAccountNumber = "C1";
        Invoice bare = invoice("L1", DocumentType.BON_LIVRAISON);
        bare.customer = new AccountCustomer();
        bare.customerName = "SARL Durand";
        bare.ticket = new Ticket();
        bare.ticket.customerEmail = "durand@exemple.fr";
        Invoice anonymous = invoice("F2", null);
        Invoice ticketless = invoice("F3", DocumentType.FACTURE);
        ticketless.ticket = new Ticket();
        QueryStub stub = new QueryStub().answer("from Invoice i", List.of(full, bare, anonymous, ticketless));
        List<List<Object>> rows = report(stub, "1234").rows(new ReportCriteria());
        assertEquals(4, rows.size());
        assertEquals(Arrays.asList("1234", "F1", "FACTURE", "Jean", "Dupont", "C1", new BigDecimal("12.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, "T1", full.issueDate, "0102030405", "jean@exemple.fr"), rows.get(0));
        List<Object> notes = rows.get(1);
        assertEquals("BON DE LIVRAISON", notes.get(2));
        assertEquals("", notes.get(3));
        assertEquals("SARL Durand", notes.get(4));
        assertEquals("", notes.get(5));
        assertEquals("", notes.get(11));
        assertEquals("durand@exemple.fr", notes.get(12));
        List<Object> none = rows.get(2);
        assertEquals("", none.get(2));
        assertEquals("", none.get(4));
        assertEquals("", none.get(12));
        assertEquals("", rows.get(3).get(12));
        assertFalse(stub.parameters.containsKey("type"));
    }

    /**
     * The invoices option and the delivery notes option restrict the query to
     * their type; an unknown point of sale reads blank.
     */
    @Test
    void theOptionsRestrictTheType() {
        ReportCriteria invoices = new ReportCriteria();
        invoices.option = "FACTURE";
        QueryStub stub = new QueryStub();
        assertTrue(report(stub, null).rows(invoices).isEmpty());
        assertEquals(DocumentType.FACTURE, stub.parameters.get("type"));
        ReportCriteria notes = new ReportCriteria();
        notes.option = "BON_LIVRAISON";
        QueryStub other = new QueryStub();
        report(other, null).rows(notes);
        assertEquals(DocumentType.BON_LIVRAISON, other.parameters.get("type"));
        InvoiceListReport unnamed = new InvoiceListReport();
        assertTrue(unnamed.preamble(new ReportCriteria()).get(0).endsWith("— point de vente "));
    }

    /**
     * The preamble dates the report and names the point of sale; each row
     * links to its duplicate.
     */
    @Test
    void preambleAndReprint() {
        InvoiceListReport report = report(new QueryStub(), "1234");
        String preamble = report.preamble(new ReportCriteria()).get(0);
        assertTrue(preamble.startsWith("Rapport édité le "), preamble);
        assertTrue(preamble.endsWith(" — point de vente 1234"), preamble);
        assertEquals("/admin/reports/invoices/duplicate?number=F+1%2F2",
                report.rowLink(Arrays.asList("1234", "F 1/2")));
        assertEquals("Réimprimer (duplicata)", report.rowLinkLabel());
    }

    /**
     * The metadata: identity, columns, options and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        InvoiceListReport report = new InvoiceListReport();
        assertEquals("invoices", report.code());
        assertEquals("BO-06-03-05", report.requirement());
        assertEquals(13, report.columns().size());
        assertEquals("Documents", report.optionLabel());
        assertEquals(3, report.optionChoices().size());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND), report.filters());
        assertFalse(report.label().isEmpty());
        assertFalse(report.family().toString().isEmpty());
    }
}
