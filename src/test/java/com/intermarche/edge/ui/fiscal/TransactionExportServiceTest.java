package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link TransactionExportService} (BO-05-01-06): every kind of
 * document as a row, the filters the requirement names, the totals by
 * document, by customer and overall.
 */
class TransactionExportServiceTest {

    /** The first day of the searches. */
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    /** The last day of the searches. */
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    /**
     * Builds criteria over the month, with one filter set.
     *
     * @param type the type, or null
     * @param nif the NIF, or null
     * @param number the customer number, or null
     * @param name the customer name, or null
     * @param ticket the ticket number, or null
     * @return the criteria
     */
    private TransactionExportService.Criteria criteria(String type, String nif, String number, String name,
            String ticket) {
        return new TransactionExportService.Criteria(FROM, TO, type, nif, number, name, ticket, null, null, null,
                null);
    }

    /**
     * Stubs a paged query.
     *
     * @param panache the active PanacheEntityBase mock
     * @param query the query text
     * @param rows the rows answered
     * @param params the query parameters
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stub(MockedStatic<PanacheEntityBase> panache, String query, List<?> rows, Object... params) {
        PanacheQuery found = mock(PanacheQuery.class);
        when(found.page(0, TransactionExportService.MAX_ROWS)).thenReturn(found);
        when(found.list()).thenReturn(rows);
        panache.when(() -> PanacheEntityBase.find(query, params)).thenReturn(found);
    }

    /**
     * Stubs the four kinds of documents of the month.
     *
     * @param panache the active PanacheEntityBase mock
     * @param documents the fiscal documents
     * @param tickets the tickets without fiscal document
     * @param invoices the invoices
     * @param refunds the returns without credit note
     */
    private void stubMonth(MockedStatic<PanacheEntityBase> panache, List<?> documents, List<?> tickets,
            List<?> invoices, List<?> refunds) {
        LocalDateTime start = FROM.atStartOfDay();
        LocalDateTime end = TO.plusDays(1).atStartOfDay();
        stub(panache, "issuedAt >= ?1 and issuedAt < ?2 order by issuedAt", documents, start, end);
        stub(panache, "status = ?1 and closingDate >= ?2 and closingDate < ?3 "
                + "and id not in (select f.ticketId from FiscalDocument f where f.ticketId is not null) "
                + "and ticketNumber not in (select i.ticketNumber from Invoice i where i.ticketNumber is not null) "
                + "order by closingDate", tickets, Ticket.TicketStatus.CLOSED, start, end);
        stub(panache, "issueDate >= ?1 and issueDate < ?2 " + TransactionExportService.STATED_ELSEWHERE
                + "order by issueDate", invoices, start, end);
        stub(panache, "creationDate >= ?1 and creationDate < ?2 "
                + "and id not in (select f.refundId from FiscalDocument f where f.refundId is not null) "
                + "order by creationDate", refunds, start, end);
    }

    /**
     * The four kinds become rows in the one vocabulary, credit notes counting
     * negatively, sorted by date, totalled overall and by customer — a row
     * naming no customer counted in the totals only.
     */
    @Test
    void everyKindOfDocumentIsListedAndTotalled() {
        FiscalDocument ft = new FiscalDocument();
        ft.documentType = FiscalDocument.Type.FT;
        ft.number = "FT C04/1";
        ft.issuedAt = LocalDateTime.of(2026, 10, 5, 10, 0);
        ft.ticketId = 7L;
        ft.customerTaxId = "PT123456789";
        ft.customerName = "ACME";
        ft.grossTotal = new BigDecimal("12.30");
        ft.taxTotal = new BigDecimal("2.30");
        FiscalDocument nc = new FiscalDocument();
        nc.documentType = FiscalDocument.Type.NC;
        nc.number = "NC C04/1";
        nc.issuedAt = LocalDateTime.of(2026, 10, 6, 10, 0);
        nc.ticketId = 7L;
        nc.customerTaxId = "PT123456789";
        nc.grossTotal = new BigDecimal("-2.46");
        nc.taxTotal = new BigDecimal("-0.46");
        FiscalDocument fs = new FiscalDocument();
        fs.documentType = FiscalDocument.Type.FS;
        fs.number = "FS C04/9";
        fs.customerTaxId = "999999990";
        fs.ticketId = 99L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C01-00000001";
        ticket.closingDate = LocalDateTime.of(2026, 10, 2, 9, 0);
        ticket.terminalId = "C01";
        ticket.fidelityCard = "4000123";
        ticket.totalExcludingTax = new BigDecimal("10");
        ticket.totalVat = new BigDecimal("2");
        ticket.totalIncludingTax = new BigDecimal("12");
        Ticket bare = new Ticket();
        bare.ticketNumber = "C01-00000002";
        Invoice invoice = new Invoice();
        invoice.documentNumber = "C01-F1";
        invoice.issueDate = LocalDateTime.of(2026, 10, 3, 9, 0);
        invoice.customerAccountNumber = "4000123";
        invoice.customerName = "Dupont";
        invoice.customerVatNumber = "FR12";
        invoice.totalExcludingTax = new BigDecimal("100");
        invoice.totalVat = new BigDecimal("20");
        invoice.totalIncludingTax = new BigDecimal("120");
        Refund refund = new Refund();
        refund.refundNumber = "R1";
        refund.creationDate = LocalDateTime.of(2026, 10, 4, 9, 0);
        refund.originalTicketId = 8L;
        refund.creditAccountNumber = "4000123";
        refund.totalExcludingTax = new BigDecimal("5");
        refund.totalVat = new BigDecimal("1");
        refund.totalAmount = new BigDecimal("6");
        Refund orphan = new Refund();
        orphan.refundNumber = "R2";
        Ticket original = new Ticket();
        original.ticketNumber = "C01-00000008";
        Ticket stated = new Ticket();
        stated.ticketNumber = "C04-00000007";
        TransactionExportService.Result result;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMonth(panache, List.of(ft, nc, fs), List.of(ticket, bare), List.of(invoice), List.of(refund, orphan));
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(stated);
            panache.when(() -> PanacheEntityBase.findById(8L)).thenReturn(original);
            result = new TransactionExportService().search(criteria(null, null, null, null, null));
        }
        assertEquals(8, result.rows.size());
        assertFalse(result.truncated);
        assertNull(result.rows.get(0).issuedAt);
        TransactionExportService.Row ftRow = result.rows.stream().filter(r -> r.number.equals("FT C04/1"))
                .findFirst().orElseThrow();
        assertEquals("C04-00000007", ftRow.ticketNumber);
        assertEquals(new BigDecimal("10.00"), ftRow.totalExcludingTax);
        TransactionExportService.Row ncRow = result.rows.stream().filter(r -> r.type.equals("NC")
                && r.number.startsWith("NC")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("-2.46"), ncRow.totalIncludingTax);
        assertEquals(new BigDecimal("-2.00"), ncRow.totalExcludingTax);
        TransactionExportService.Row fsRow = result.rows.stream().filter(r -> r.number.equals("FS C04/9"))
                .findFirst().orElseThrow();
        assertNull(fsRow.nif);
        assertNull(fsRow.ticketNumber);
        TransactionExportService.Row refundRow = result.rows.stream().filter(r -> r.number.equals("R1"))
                .findFirst().orElseThrow();
        assertEquals("C01-00000008", refundRow.ticketNumber);
        assertEquals(new BigDecimal("-6"), refundRow.totalIncludingTax);
        TransactionExportService.Row invoiceRow = result.rows.stream().filter(r -> r.type.equals("FT")
                && r.number.equals("C01-F1")).findFirst().orElseThrow();
        assertEquals("FR12", invoiceRow.nif);
        assertEquals(new BigDecimal("135.84"), result.totalIncludingTax);
        assertEquals(2, result.customers.size());
        TransactionExportService.CustomerTotal regular = result.customers.get(0);
        assertEquals("4000123", regular.customer);
        assertEquals("Dupont", regular.name);
        assertEquals(3, regular.count);
        assertEquals(new BigDecimal("126"), regular.totalIncludingTax);
        assertEquals("PT123456789", result.customers.get(1).customer);
        assertEquals("ACME", result.customers.get(1).name);
        assertEquals(0, new BigDecimal("9.84").compareTo(result.customers.get(1).totalIncludingTax));
    }

    /**
     * A kind of document hitting the reading ceiling marks the result
     * truncated.
     */
    @Test
    void theReadingCeilingIsSaid() {
        List<Ticket> full = Collections.nCopies(TransactionExportService.MAX_ROWS, new Ticket());
        FiscalDocument document = new FiscalDocument();
        document.documentType = FiscalDocument.Type.FS;
        List<FiscalDocument> documents = Collections.nCopies(TransactionExportService.MAX_ROWS, document);
        List<Invoice> invoices = Collections.nCopies(TransactionExportService.MAX_ROWS, new Invoice());
        List<Refund> refunds = Collections.nCopies(TransactionExportService.MAX_ROWS, new Refund());
        TransactionExportService.Result result;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMonth(panache, documents, List.of(), List.of(), List.of());
            assertTrue(new TransactionExportService().search(criteria("RC", null, null, null, null)).truncated);
            stubMonth(panache, List.of(), List.of(), invoices, List.of());
            assertTrue(new TransactionExportService().search(criteria("RC", null, null, null, null)).truncated);
            stubMonth(panache, List.of(), List.of(), List.of(), refunds);
            assertTrue(new TransactionExportService().search(criteria("RC", null, null, null, null)).truncated);
            stubMonth(panache, List.of(), full, List.of(), List.of());
            result = new TransactionExportService().search(criteria("RC", null, null, null, null));
        }
        assertTrue(result.truncated);
        assertTrue(result.rows.isEmpty());
    }

    /**
     * Every filter keeps what it names and drops the rest: type, NIF spaces
     * and case ignored, customer number, ticket number or its fragment, name
     * fragment, and the bounds with and without VAT.
     */
    @Test
    void everyFilterKeepsWhatItNames() {
        TransactionExportService.Row row = new TransactionExportService.Row();
        row.type = "FT";
        row.nif = "PT 123";
        row.customerNumber = "C-1";
        row.customerName = "Société ACME";
        row.ticketNumber = "T-1";
        row.totalExcludingTax = new BigDecimal("10");
        row.totalIncludingTax = new BigDecimal("12");
        assertTrue(TransactionExportService.matches(row, criteria("FT", "pt123", "c-1", " acme", "T-1")));
        assertTrue(TransactionExportService.matches(row, criteria(null, null, null, null, "-1")));
        assertFalse(TransactionExportService.matches(row, criteria("FS", null, null, null, null)));
        assertFalse(TransactionExportService.matches(row, criteria(null, "PT9", null, null, null)));
        assertFalse(TransactionExportService.matches(row, criteria(null, null, "C-2", null, null)));
        assertFalse(TransactionExportService.matches(row, criteria(null, null, null, "dupont", null)));
        assertFalse(TransactionExportService.matches(row, criteria(null, null, null, null, "T-2")));
        TransactionExportService.Row anonymous = new TransactionExportService.Row();
        assertFalse(TransactionExportService.same("X", null));
        assertFalse(TransactionExportService.contains(null, "x"));
        assertTrue(TransactionExportService.matches(anonymous, criteria(null, null, null, null, null)));
        BigDecimal ten = BigDecimal.TEN;
        assertTrue(TransactionExportService.within(ten, ten, ten));
        assertFalse(TransactionExportService.within(ten, new BigDecimal("11"), null));
        assertFalse(TransactionExportService.within(ten, null, BigDecimal.ONE));
        assertFalse(TransactionExportService.matches(row, new TransactionExportService.Criteria(FROM, TO, null, null,
                null, null, null, new BigDecimal("11"), null, null, null)));
        assertFalse(TransactionExportService.matches(row, new TransactionExportService.Criteria(FROM, TO, null, null,
                null, null, null, null, null, null, BigDecimal.ONE)));
    }

    /**
     * A row states its amounts and its day of issue; its customer
     * key is the number, else the NIF, else the name.
     */
    @Test
    void arowStatesItsAmountsAndItsCustomer() {
        TransactionExportService.Row row = new TransactionExportService.Row();
        assertEquals("", row.getIssued());
        assertEquals("", row.customerKey());
        row.issuedAt = LocalDateTime.of(2026, 10, 5, 10, 0, 7, 500);
        row.totalExcludingTax = new BigDecimal("1000");
        row.totalVat = new BigDecimal("200");
        row.totalIncludingTax = new BigDecimal("1200");
        row.customerName = "Dupont";
        assertEquals("2026-10-05 10:00:07", row.getIssued());
        row.issuedAt = LocalDateTime.of(2026, 10, 5, 10, 0);
        assertEquals("2026-10-05 10:00:00", row.getIssued());
        row.issuedAt = LocalDateTime.of(2026, 10, 5, 10, 0, 7, 500);
        assertEquals("Dupont", row.customerKey());
        row.nif = "FR1";
        assertEquals("FR1", row.customerKey());
        row.customerNumber = "C-1";
        assertEquals("C-1", row.customerKey());
        assertEquals("x", TransactionExportService.firstFilled(" ", "x"));
        row.type = "NC";
        row.number = "R1";
        assertEquals("NC", row.getType());
        assertEquals("R1", row.getNumber());
        TransactionExportService.CustomerTotal total = new TransactionExportService.CustomerTotal();
        assertEquals(BigDecimal.ZERO, total.totalVat);
        TransactionExportService.Result result = new TransactionExportService.Result();
        assertEquals(BigDecimal.ZERO, result.totalIncludingTax);
    }
}
