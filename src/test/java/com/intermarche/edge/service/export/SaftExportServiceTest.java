package com.intermarche.edge.service.export;

import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.store.Address;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SaftExportService} (BO-10-06-03): the SAF-T audit
 * file drawn from the documents the registers pushed — header and
 * certification, customers, products and tax table, the sales section and
 * its totals — and its name.
 */
class SaftExportServiceTest {

    /** The first day of the exported period. */
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    /** The last day of the exported period. */
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    /** The instant the file is drawn. */
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 2, 9, 0);

    /**
     * Builds the service with its parameters.
     *
     * @param certificate the administered certification number
     * @return the service under test
     */
    private SaftExportService service(String certificate) {
        SaftExportService service = new SaftExportService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn(certificate);
        service.productCompanyTaxId = "509999999";
        service.productId = "impos/Intermarche";
        service.productVersion = "1.0";
        return service;
    }

    /**
     * Builds a fiscal document.
     *
     * @param type the document type
     * @param number its number
     * @param gross its gross total
     * @param tax its tax total
     * @return the document, issued on 24 October 2026 at C04
     */
    private FiscalDocument document(FiscalDocument.Type type, String number, String gross, String tax) {
        FiscalDocument document = new FiscalDocument();
        document.documentType = type;
        document.number = number;
        document.atcud = "ATC-" + number.charAt(number.length() - 1);
        document.terminalId = "C04";
        document.issuedAt = LocalDateTime.of(2026, 10, 24, 10, 30);
        document.grossTotal = new BigDecimal(gross);
        document.taxTotal = new BigDecimal(tax);
        document.hash = "HASH" + number.charAt(number.length() - 1);
        return document;
    }

    /**
     * Builds a sold line.
     *
     * @param uid its uid
     * @param ean its EAN, or null
     * @param plu its PLU, or null
     * @param label its label
     * @param quantity its quantity
     * @param total its total tax included
     * @param rate its VAT rate, or null
     * @return the line
     */
    private TicketLine line(String uid, String ean, String plu, String label, String quantity,
            String total, String rate) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.ean = ean;
        line.plu = plu;
        line.productLabel = label;
        line.quantity = new BigDecimal(quantity);
        line.totalPrice = new BigDecimal(total);
        line.vatRate = rate == null ? null : new BigDecimal(rate);
        return line;
    }

    /**
     * Draws the file of a store holding the documents given.
     *
     * @param service the service
     * @param stores the stores the node holds
     * @param documents the documents of the period
     * @param tickets the sales, by id 100 and 101
     * @param refund the return of id 200, or null
     * @param original the original line of id 300, or null
     * @return the file
     */
    private String export(SaftExportService service, List<Store> stores, List<FiscalDocument> documents,
            List<Ticket> tickets, Refund refund, TicketLine original) {
        return export(service, stores, documents, tickets, refund, original, List.of());
    }

    /**
     * Draws the file of a store holding the documents given and the VAT
     * regimes the lines name by number (BO-02-02-01).
     *
     * @param service the service
     * @param stores the stores the node holds
     * @param documents the documents of the period
     * @param tickets the sales, by id 100 and 101
     * @param refund the return of id 200, or null
     * @param original the original line of id 300, or null
     * @param regimes the regimes findable by number
     * @return the file
     */
    private String export(SaftExportService service, List<Store> stores, List<FiscalDocument> documents,
            List<Ticket> tickets, Refund refund, TicketLine original,
            List<com.intermarche.edge.domain.catalog.VatRate> regimes) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "issuedAt >= ?1 and issuedAt < ?2 order by documentType, series, rank",
                    FROM.atStartOfDay(), TO.plusDays(1).atStartOfDay())).thenReturn(new ArrayList<>(documents));
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>(stores));
            for (int index = 0; index < tickets.size(); index++) {
                Ticket ticket = tickets.get(index);
                long id = 100L + index;
                panache.when(() -> PanacheEntityBase.findById(id)).thenReturn(ticket);
            }
            panache.when(() -> PanacheEntityBase.findById(200L)).thenReturn(refund);
            panache.when(() -> PanacheEntityBase.findById(300L)).thenReturn(original);
            // BO-02-02-01: the regime of a zero-rated line, by rate or by number:
            // none here unless a test names one.
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<Object> none =
                    org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            panache.when(() -> PanacheEntityBase.find(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.<Object>any())).thenReturn(none);
            for (com.intermarche.edge.domain.catalog.VatRate regime : regimes) {
                @SuppressWarnings("unchecked")
                io.quarkus.hibernate.orm.panache.PanacheQuery<Object> found =
                        org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
                org.mockito.Mockito.when(found.firstResult()).thenReturn(regime);
                panache.when(() -> PanacheEntityBase.find("number", regime.number)).thenReturn(found);
            }
            return service.export(FROM, TO, CREATED);
        }
    }

    /**
     * Builds a Portuguese store with its address.
     *
     * @return the store
     */
    private Store store() {
        Store store = new Store();
        store.name = "Intermarché Porto";
        store.vatNumber = "PT 500 100 200";
        store.address = new Address();
        store.address.streetLine1 = "Rua Central 1";
        store.address.city = "Porto";
        store.address.postalCode = "4000-001";
        return store;
    }

    /**
     * The header names the company, the period, the software and its
     * certification, and the document identifiers the documents carry.
     */
    @Test
    void theHeaderStatesTheCompanyAndTheCertification() {
        FiscalDocument fs = document(FiscalDocument.Type.FS, "FS C04/2026/1", "12.30", "2.30");
        fs.saftDocumentId = 1;
        FiscalDocument other = document(FiscalDocument.Type.FS, "FS C04/2026/2", "1.00", "0.00");
        FiscalDocument nc = document(FiscalDocument.Type.NC, "NC C04/2026/3", "1.00", "0.00");
        nc.saftDocumentId = 2;
        String xml = export(service("PT-123/AT"), List.of(store()), List.of(fs, other, nc), List.of(), null, null);
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<AuditFile xmlns=\"urn:OECD:StandardAuditFile-Tax:PT_1.04_01\">\n  <Header>\n"));
        assertTrue(xml.contains("<AuditFileVersion>1.04_01</AuditFileVersion>"));
        assertTrue(xml.contains("<CompanyID>500100200</CompanyID>"));
        assertTrue(xml.contains("<CompanyName>Intermarché Porto</CompanyName>"));
        assertTrue(xml.contains("<AddressDetail>Rua Central 1</AddressDetail>"));
        assertTrue(xml.contains("<City>Porto</City>"));
        assertTrue(xml.contains("<PostalCode>4000-001</PostalCode>"));
        assertTrue(xml.contains("<FiscalYear>2026</FiscalYear>"));
        assertTrue(xml.contains("<StartDate>2026-10-01</StartDate>"));
        assertTrue(xml.contains("<EndDate>2026-10-31</EndDate>"));
        assertTrue(xml.contains("<DateCreated>2026-11-02</DateCreated>"));
        assertTrue(xml.contains("<ProductCompanyTaxID>509999999</ProductCompanyTaxID>"));
        assertTrue(xml.contains("<SoftwareCertificateNumber>123</SoftwareCertificateNumber>"));
        assertTrue(xml.contains("<HeaderComment>FS=1;NC=2</HeaderComment>"));
        assertTrue(xml.endsWith("</AuditFile>\n"));
    }

    /**
     * BO-07-01-06: an e-commerce document integrated from the platform is
     * stated as integrated, a document this software produced as produced.
     */
    @Test
    void anIntegratedDocumentIsStatedAsSuch() {
        FiscalDocument produced = document(FiscalDocument.Type.FS, "FS ECOM2026/1", "1.00", "0.00");
        FiscalDocument integrated = document(FiscalDocument.Type.FS, "FS WEB2026/7", "2.00", "0.00");
        integrated.sourceBilling = "I";
        String xml = export(service("1"), List.of(store()), List.of(produced, integrated), List.of(), null, null);
        assertTrue(xml.contains("<SourceBilling>P</SourceBilling>"));
        assertTrue(xml.contains("<SourceBilling>I</SourceBilling>"));
    }

    /**
     * Without a store, a certification or document identifiers, the header
     * still holds together: unknown address, certificate 0, no comment.
     */
    @Test
    void anEmptyNodeDrawsAnEmptyFile() {
        String xml = export(service(""), List.of(), List.of(), List.of(), null, null);
        assertTrue(xml.contains("<CompanyID></CompanyID>"));
        assertTrue(xml.contains("<CompanyName></CompanyName>"));
        assertTrue(xml.contains("<AddressDetail>Desconhecido</AddressDetail>"));
        assertTrue(xml.contains("<SoftwareCertificateNumber>0</SoftwareCertificateNumber>"));
        assertFalse(xml.contains("HeaderComment"));
        assertTrue(xml.contains("<NumberOfEntries>0</NumberOfEntries>"));
        assertTrue(xml.contains("<TotalDebit>0.00</TotalDebit>"));
    }

    /**
     * A store with a legal name and no address, or an address with blank
     * parts, states the legal name and the unknown parts.
     */
    @Test
    void missingAddressPartsAreUnknown() {
        Store bare = store();
        bare.legalName = "SOCIEDADE SA";
        bare.address = null;
        String xml = export(service(null), List.of(bare), List.of(), List.of(), null, null);
        assertTrue(xml.contains("<CompanyName>SOCIEDADE SA</CompanyName>"));
        assertTrue(xml.contains("<City>Desconhecido</City>"));
        Store blank = store();
        blank.legalName = " ";
        blank.address.streetLine1 = null;
        blank.address.city = " ";
        blank.address.postalCode = null;
        String other = export(service(null), List.of(blank), List.of(), List.of(), null, null);
        assertTrue(other.contains("<CompanyName>Intermarché Porto</CompanyName>"));
        assertTrue(other.contains("<AddressDetail>Desconhecido</AddressDetail>"));
        assertTrue(other.contains("<PostalCode>Desconhecido</PostalCode>"));
    }

    /**
     * A sale's simplified invoice states the lines it covers, each with its
     * product, net amount and tax, the master files listing the final
     * consumer, the products and the rates once; the totals credit the sales.
     */
    @Test
    void aSimplifiedInvoiceStatesItsCoveredLines() {
        Ticket sale = new Ticket();
        sale.lines.add(line("U1", "5601234567890", null, "Vinho & <Tinto>", "2", "12.30", "0.2300"));
        sale.lines.add(line("U2", " ", "2001", "Pão \"caseiro\"", "1", "1.06", "0.06"));
        sale.lines.add(line("U3", null, " ", "Diversos 'x'", "0", "1.13", "0.1300"));
        TicketLine cancelled = line("U4", "5600000000000", null, "Anulado", "1", "5.00", "0.23");
        cancelled.cancelled = true;
        sale.lines.add(cancelled);
        FiscalDocument fs = document(FiscalDocument.Type.FS, "FS C04/2026/1", "14.49", "2.47");
        fs.ticketId = 100L;
        String xml = export(service("123"), List.of(store()), List.of(fs), List.of(sale), null, null);
        assertTrue(xml.contains("<CustomerID>Consumidor final</CustomerID>"));
        assertTrue(xml.contains("<CustomerTaxID>999999990</CustomerTaxID>"));
        assertTrue(xml.contains("<ProductCode>5601234567890</ProductCode>"));
        assertTrue(xml.contains("<ProductDescription>Vinho &amp; &lt;Tinto&gt;</ProductDescription>"));
        assertTrue(xml.contains("<ProductCode>2001</ProductCode>"));
        assertTrue(xml.contains("<ProductDescription>Pão &quot;caseiro&quot;</ProductDescription>"));
        assertTrue(xml.contains("<ProductCode>DIVERSOS</ProductCode>"));
        assertTrue(xml.contains("<Description>Diversos &apos;x&apos;</Description>"));
        assertFalse(xml.contains("Anulado"));
        assertTrue(xml.contains("<TaxCode>NOR</TaxCode>"));
        assertTrue(xml.contains("<TaxCode>RED</TaxCode>"));
        assertTrue(xml.contains("<TaxCode>INT</TaxCode>"));
        assertTrue(xml.contains("<Description>IVA 23%</Description>"));
        assertTrue(xml.contains("<UnitPrice>5.0000</UnitPrice>"));
        assertTrue(xml.contains("<CreditAmount>10.00</CreditAmount>"));
        assertTrue(xml.contains("<UnitPrice>1.0000</UnitPrice>"));
        assertTrue(xml.contains("<Quantity>0</Quantity>"));
        assertTrue(xml.contains("<InvoiceNo>FS C04/2026/1</InvoiceNo>"));
        assertTrue(xml.contains("<ATCUD>ATC-1</ATCUD>"));
        assertTrue(xml.contains("<InvoiceStatus>N</InvoiceStatus>"));
        assertTrue(xml.contains("<InvoiceStatusDate>2026-10-24T10:30:00</InvoiceStatusDate>"));
        assertTrue(xml.contains("<Hash>HASH1</Hash>"));
        assertTrue(xml.contains("<Period>10</Period>"));
        assertTrue(xml.contains("<InvoiceType>FS</InvoiceType>"));
        assertTrue(xml.contains("<TaxPayable>2.47</TaxPayable>"));
        assertTrue(xml.contains("<NetTotal>12.02</NetTotal>"));
        assertTrue(xml.contains("<GrossTotal>14.49</GrossTotal>"));
        assertTrue(xml.contains("<TotalCredit>12.02</TotalCredit>"));
        assertTrue(xml.contains("<TotalDebit>0.00</TotalDebit>"));
        assertFalse(xml.contains("TaxExemption"));
    }

    /**
     * A credit note issued after a return states the returned lines as debit,
     * with its reference, an exempt line its exemption, and a line whose
     * original is gone the generic code; an invoice naming its customer lists
     * them; a cancelled document is stated cancelled and out of the totals; a
     * receipt is not a sales document; a document whose sale or return the
     * node does not hold states no line.
     */
    @Test
    void creditNotesInvoicesAndCancellationsAreStated() {
        Refund refund = new Refund();
        RefundLine returned = new RefundLine();
        returned.originalLineId = 300L;
        returned.productLabel = "Vinho";
        returned.quantity = new BigDecimal("-1");
        returned.price = new BigDecimal("6.15");
        returned.vatRate = new BigDecimal("0.23");
        RefundLine exempt = new RefundLine();
        exempt.originalLineId = 301L;
        exempt.productLabel = "Livro";
        exempt.quantity = BigDecimal.ONE;
        exempt.price = new BigDecimal("8.00");
        exempt.vatRate = null;
        refund.lines.add(returned);
        refund.lines.add(exempt);
        FiscalDocument nc = document(FiscalDocument.Type.NC, "NC C04/2026/5", "14.15", "1.15");
        nc.refundId = 200L;
        nc.sourceNumber = "FS C04/2026/1";
        FiscalDocument lostReturn = document(FiscalDocument.Type.NC, "NC C04/2026/6", "1.00", "0.00");
        lostReturn.refundId = 999L;
        FiscalDocument cancelling = document(FiscalDocument.Type.NC, "NC C04/2026/7", "2.00", "0.00");
        cancelling.ticketId = 100L;
        FiscalDocument ft = document(FiscalDocument.Type.FT, "FT C04/2026/8", "-3.69", "-0.69");
        ft.customerTaxId = "ES12345678Z";
        ft.customerAddress = "Calle Mayor";
        ft.ticketId = 101L;
        FiscalDocument cancelled = document(FiscalDocument.Type.FS, "FS C04/2026/9", "50.00", "5.00");
        cancelled.cancelled = true;
        FiscalDocument orphan = document(FiscalDocument.Type.FS, "FS C04/2026/4", "1.00", "0.00");
        orphan.ticketId = 102L;
        FiscalDocument receipt = document(FiscalDocument.Type.RC, "RC C04/2026/2", "3.69", "0.00");
        Ticket cancelledSale = new Ticket();
        cancelledSale.lines.add(line("A", "111", null, "Agua", "1", "2.00", "0"));
        Ticket invoiced = new Ticket();
        invoiced.lines.add(line("B", "222", null, "Azeite", "1", "3.69", "0.16"));
        TicketLine original = line("O", "5601234567890", null, "Vinho", "1", "6.15", "0.23");
        String xml = export(service("123"), List.of(store()),
                List.of(nc, lostReturn, cancelling, ft, cancelled, orphan, receipt),
                List.of(cancelledSale, invoiced), refund, original);
        assertTrue(xml.contains("<NumberOfEntries>6</NumberOfEntries>"));
        assertFalse(xml.contains("<InvoiceNo>RC C04/2026/2</InvoiceNo>"));
        assertTrue(xml.contains("<PaymentRefNo>RC C04/2026/2</PaymentRefNo>"));
        assertTrue(xml.contains("<Reference>FS C04/2026/1</Reference>"));
        assertTrue(xml.contains("<DebitAmount>5.00</DebitAmount>"));
        assertTrue(xml.contains("<ProductCode>5601234567890</ProductCode>"));
        assertTrue(xml.contains("<ProductCode>DIVERSOS</ProductCode>"));
        assertTrue(xml.contains("<TaxExemptionReason>Isento</TaxExemptionReason>"));
        assertTrue(xml.contains("<TaxExemptionCode>M99</TaxExemptionCode>"));
        assertTrue(xml.contains("<TaxCode>ISE</TaxCode>"));
        assertTrue(xml.contains("<CustomerID>12345678Z</CustomerID>"));
        assertTrue(xml.contains("<CompanyName>Desconhecido</CompanyName>"));
        assertTrue(xml.contains("<AddressDetail>Calle Mayor</AddressDetail>"));
        assertTrue(xml.contains("<Country>ES</Country>"));
        assertTrue(xml.contains("<InvoiceStatus>A</InvoiceStatus>"));
        assertTrue(xml.contains("<TaxCode>NOR</TaxCode>"));
        assertTrue(xml.contains("<TotalDebit>16.00</TotalDebit>"));
        assertTrue(xml.contains("<TotalCredit>4.00</TotalCredit>"));
        assertTrue(xml.contains("<GrossTotal>3.69</GrossTotal>"));
        assertEquals(2, xml.split("<Reference>", -1).length - 1);
    }

    /**
     * A named customer's own name and address are stated as the document
     * carries them.
     */
    @Test
    void aNamedCustomerKeepsItsName() {
        FiscalDocument ft = document(FiscalDocument.Type.FT, "FT C04/2026/1", "1.00", "0.00");
        ft.customerTaxId = "123456789";
        ft.customerName = "ACME LDA";
        String xml = export(service("1"), List.of(store()), List.of(ft), List.of(), null, null);
        assertTrue(xml.contains("<CompanyName>ACME LDA</CompanyName>"));
        assertTrue(xml.contains("<Country>PT</Country>"));
        assertTrue(xml.contains("<AddressDetail>Desconhecido</AddressDetail>"));
    }

    /**
     * The tax code follows the rate classes, boundaries included, and the
     * escaping, the digits and the product code helpers tolerate null.
     */
    @Test
    void helpersClassifyAndClean() {
        assertEquals("ISE", SaftExportService.taxCode(BigDecimal.ZERO));
        assertEquals("NOR", SaftExportService.taxCode(new BigDecimal("0.16")));
        assertEquals("INT", SaftExportService.taxCode(new BigDecimal("0.09")));
        assertEquals("RED", SaftExportService.taxCode(new BigDecimal("0.04")));
        assertEquals("", SaftExportService.escape(null));
        assertEquals("", SaftExportService.digits(null));
        assertEquals("DIVERSOS", SaftExportService.productCode(new TicketLine()));
    }

    /**
     * The file is named after the company's tax number and the period, a node
     * with no store leaving the number out.
     */
    @Test
    void theFileIsNamedAfterTheCompanyAndThePeriod() {
        SaftExportService service = service("1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(store()));
            assertEquals("SAFT-PT_500100200_2026-10-01_2026-10-31.xml", service.fileName(FROM, TO));
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of());
            assertEquals("SAFT-PT__2026-10-01_2026-10-31.xml", service.fileName(FROM, TO));
        }
    }
    /**
     * BO-02-02-01: a zero-rated line names the exemption its regime declares —
     * its code and its legal ground — rather than the generic pair.
     */
    @Test
    void theExemptionOfTheRegimeIsStated() {
        com.intermarche.edge.domain.catalog.VatRate article9 = new com.intermarche.edge.domain.catalog.VatRate(
                10, BigDecimal.ZERO, "Isento");
        article9.exempt = true;
        article9.exemptionCode = "M07";
        article9.legalMention = "Isento artigo 9.º do CIVA";
        FiscalDocument fs = document(FiscalDocument.Type.FS, "FS C04/2026/1", "2.00", "0.00");
        fs.ticketId = 100L;
        Ticket sale = new Ticket();
        TicketLine exempt = line("A", "111", null, "Livro", "1", "2.00", "0");
        exempt.vatNumber = 10;
        sale.lines.add(exempt);
        String xml = export(service("1"), List.of(store()), List.of(fs), List.of(sale), null, null,
                List.of(article9));
        assertTrue(xml.contains("<TaxExemptionReason>Isento artigo 9.º do CIVA</TaxExemptionReason>"));
        assertTrue(xml.contains("<TaxExemptionCode>M07</TaxExemptionCode>"));
    }

    /**
     * The exemption helpers fall back on the generic pair for a missing regime
     * and for a regime declaring nothing, blank included.
     */
    @Test
    void theExemptionHelpersFallBack() {
        com.intermarche.edge.domain.catalog.VatRate silent = new com.intermarche.edge.domain.catalog.VatRate();
        com.intermarche.edge.domain.catalog.VatRate blank = new com.intermarche.edge.domain.catalog.VatRate();
        blank.legalMention = " ";
        blank.exemptionCode = " ";
        com.intermarche.edge.domain.catalog.VatRate stated = new com.intermarche.edge.domain.catalog.VatRate();
        stated.legalMention = " Isento ";
        stated.exemptionCode = " M16 ";
        assertEquals("Isento", SaftExportService.exemptionReason(null));
        assertEquals("Isento", SaftExportService.exemptionReason(silent));
        assertEquals("Isento", SaftExportService.exemptionReason(blank));
        assertEquals("Isento", SaftExportService.exemptionReason(stated));
        assertEquals("M99", SaftExportService.exemptionCode(null));
        assertEquals("M99", SaftExportService.exemptionCode(silent));
        assertEquals("M99", SaftExportService.exemptionCode(blank));
        assertEquals("M16", SaftExportService.exemptionCode(stated));
    }

    /**
     * The receipts are the payments section: each states the invoice it pays
     * and that invoice's day when the node holds it, its own number and day
     * otherwise, and a cancelled receipt is stated cancelled and out of the
     * total (BO-05-01-04, BO-05-01-05).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theReceiptsAreThePaymentsSection() {
        FiscalDocument invoice = document(FiscalDocument.Type.FT, "FT C04/2026/3", "10.00", "1.00");
        invoice.issuedAt = LocalDateTime.of(2026, 9, 30, 18, 0);
        FiscalDocument paying = document(FiscalDocument.Type.RC, "RC C04/2026/4", "10.00", "0.00");
        paying.sourceNumber = "FT C04/2026/3";
        paying.customerTaxId = "123456789";
        FiscalDocument unlinked = document(FiscalDocument.Type.RC, "RC C04/2026/5", "-4.00", "0.00");
        FiscalDocument lost = document(FiscalDocument.Type.RC, "RC C04/2026/6", "2.00", "0.00");
        lost.sourceNumber = "FT C04/2025/9";
        lost.cancelled = true;
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> found =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(found.firstResult()).thenReturn(invoice);
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> none =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        String xml;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>(List.of(store())));
            panache.when(() -> PanacheEntityBase.find("number", "FT C04/2026/3")).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find("number", "FT C04/2025/9")).thenReturn(none);
            xml = service("123").exportDocuments(List.of(paying, unlinked, lost), FROM, TO, CREATED);
        }
        assertTrue(xml.contains("<Payments>"));
        assertTrue(xml.contains("<NumberOfEntries>3</NumberOfEntries>"));
        assertTrue(xml.contains("<TotalCredit>14.00</TotalCredit>"));
        assertTrue(xml.contains("<OriginatingON>FT C04/2026/3</OriginatingON>"));
        assertTrue(xml.contains("<InvoiceDate>2026-09-30</InvoiceDate>"));
        assertTrue(xml.contains("<OriginatingON>RC C04/2026/5</OriginatingON>"));
        assertTrue(xml.contains("<PaymentStatus>A</PaymentStatus>"));
        assertTrue(xml.contains("<PaymentStatus>N</PaymentStatus>"));
        assertTrue(xml.contains("<CustomerID>123456789</CustomerID>"));
        assertTrue(xml.contains("<NumberOfEntries>0</NumberOfEntries>"));
        assertTrue(xml.indexOf("</SalesInvoices>") < xml.indexOf("<Payments>"));
        assertTrue(xml.indexOf("</Payments>") < xml.indexOf("</SourceDocuments>"));
    }
}
