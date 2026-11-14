package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.sale.TaxRefundForm;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.ui.report.PdfReportWriter;
import com.intermarche.edge.ui.report.ReportCriteria;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportFamily;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaxRefundService} (BO-06-07-03): the issue of an
 * export sale form off a closed ticket — refused without ticket or customer,
 * or for an unknown or open ticket, the existing form given back — its VAT
 * by rate, the refund recorded once, and the printed slip.
 */
class TaxRefundServiceTest {

    /**
     * Stubs a finder on one field.
     *
     * @param panache the static mock
     * @param field the field
     * @param value the value searched
     * @param found the entity found, or null
     */
    @SuppressWarnings("unchecked")
    private void finds(MockedStatic<PanacheEntityBase> panache, String field, String value, Object found) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        panache.when(() -> PanacheEntityBase.find(field, value)).thenReturn(query);
    }

    /**
     * Builds a sold line.
     *
     * @param rate the VAT rate
     * @param total the total
     * @return the line
     */
    private TicketLine line(String rate, String total) {
        TicketLine line = new TicketLine();
        line.vatRate = new BigDecimal(rate);
        line.totalPrice = new BigDecimal(total);
        return line;
    }

    /**
     * Builds a form whose amounts start at zero, as the entity's own.
     *
     * @return the form
     */
    private TaxRefundForm zeroed() {
        TaxRefundForm form = new TaxRefundForm();
        assertEquals(BigDecimal.ZERO, form.sales20);
        return form;
    }

    /**
     * Without ticket or customer the form is refused.
     */
    @Test
    void aFormNeedsATicketAndACustomer() {
        TaxRefundService service = new TaxRefundService();
        assertEquals("Numéro de ticket et nom du client obligatoires.",
                service.issue(null, "John", null, null, null).refusal());
        assertEquals("Numéro de ticket et nom du client obligatoires.",
                service.issue("T1", null, null, null, null).refusal());
    }

    /**
     * A ticket already detaxed gives its form back; an unknown or open ticket
     * is refused.
     */
    @Test
    void existingUnknownAndOpenTickets() {
        TaxRefundService service = new TaxRefundService();
        TaxRefundForm existing = zeroed();
        Ticket open = new Ticket();
        open.status = Ticket.TicketStatus.PARKED;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            finds(panache, "ticketNumber", "T1", existing);
            assertSame(existing, service.issue("T1", "John", null, null, null).form());
            finds(panache, "ticketNumber", "T2", null);
            assertEquals("Ticket inconnu ou non clôturé.", service.issue("T2", "John", null, null, null).refusal());
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Object> none = mock(PanacheQuery.class);
            PanacheQuery<Object> ticket = mock(PanacheQuery.class);
            when(ticket.firstResult()).thenReturn(open);
            panache.when(() -> PanacheEntityBase.find("ticketNumber", "T3")).thenReturn(none, ticket);
            assertEquals("Ticket inconnu ou non clôturé.", service.issue("T3", "John", null, null, null).refusal());
        }
    }

    /**
     * A closed ticket gets its form: numbered by day and ticket, the customer
     * kept, the VAT of the sold lines by rate, the cancelled and returned
     * lines and the other rates left out.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aClosedTicketGetsItsForm() {
        TaxRefundService service = new TaxRefundService();
        Ticket closed = new Ticket();
        closed.status = Ticket.TicketStatus.CLOSED;
        TicketLine cancelled = line("0.2", "50.00");
        cancelled.cancelled = true;
        TicketLine returned = line("0.2", "-30.00");
        returned.returnLine = true;
        closed.lines = List.of(line("0.2", "12.00"), line("0.1", "11.00"), line("0.055", "10.55"),
                line("0.021", "10.21"), line("0", "5.00"), cancelled, returned);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<TaxRefundForm> built = mockConstruction(TaxRefundForm.class, (mock, context) -> {
                 mock.sales210 = BigDecimal.ZERO;
                 mock.vat210 = BigDecimal.ZERO;
                 mock.sales55 = BigDecimal.ZERO;
                 mock.vat55 = BigDecimal.ZERO;
                 mock.sales10 = BigDecimal.ZERO;
                 mock.vat10 = BigDecimal.ZERO;
                 mock.sales20 = BigDecimal.ZERO;
                 mock.vat20 = BigDecimal.ZERO;
             })) {
            PanacheQuery<Object> none = mock(PanacheQuery.class);
            PanacheQuery<Object> ticket = mock(PanacheQuery.class);
            when(ticket.firstResult()).thenReturn(closed);
            panache.when(() -> PanacheEntityBase.find("ticketNumber", "T1")).thenReturn(none, ticket);
            TaxRefundService.Outcome outcome = service.issue("T1", "John Smith", "US", "P123", "admin");
            assertNull(outcome.refusal());
            TaxRefundForm form = built.constructed().get(0);
            assertSame(form, outcome.form());
            assertTrue(form.number.startsWith("BV"));
            assertTrue(form.number.endsWith("-T1"));
            assertEquals("John Smith", form.customerName);
            assertEquals("US", form.customerCountry);
            assertEquals("P123", form.customerDocument);
            assertEquals("admin", form.createdBy);
            assertEquals(new BigDecimal("12.00"), form.sales20);
            assertEquals(new BigDecimal("2.00"), form.vat20);
            assertEquals(new BigDecimal("1.00"), form.vat10);
            assertEquals(new BigDecimal("0.55"), form.vat55);
            assertEquals(new BigDecimal("0.21"), form.vat210);
            assertEquals(new BigDecimal("3.76"), form.refundAmount);
            verify(form).persist();
        }
    }

    /**
     * A refund is recorded once: an unknown form and a form already refunded
     * are refused.
     */
    @Test
    void aRefundIsRecordedOnce() {
        TaxRefundService service = new TaxRefundService();
        TaxRefundForm form = zeroed();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            finds(panache, "number", "BV1", form);
            finds(panache, "number", "BV9", null);
            assertEquals("Bordereau inconnu.", service.refund("BV9", "admin"));
            assertNull(service.refund(" BV1 ", "admin"));
            assertEquals(TaxRefundForm.REFUNDED, form.status);
            assertEquals("admin", form.refundedBy);
            assertTrue(form.refundedAt != null);
            assertEquals("Ce bordereau est déjà remboursé.", service.refund("BV1", "admin"));
        }
    }

    /**
     * The slip of a known form is rendered over its day; an unknown one renders
     * nothing.
     */
    @Test
    void theSlipIsRendered() {
        TaxRefundService service = new TaxRefundService();
        service.pdfReportWriter = mock(PdfReportWriter.class);
        when(service.pdfReportWriter.write(any(), any())).thenReturn(new byte[]{7});
        TaxRefundForm form = zeroed();
        form.number = "BV1";
        form.createdAt = LocalDateTime.of(2026, 10, 20, 10, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            finds(panache, "number", "BV1", form);
            finds(panache, "number", "BV9", null);
            assertArrayEquals(new byte[]{7}, service.slip("BV1", "nl"));
            assertNull(service.slip("BV9", "nl"));
        }
        ArgumentCaptor<ReportData> data = ArgumentCaptor.forClass(ReportData.class);
        ArgumentCaptor<ReportCriteria> criteria = ArgumentCaptor.forClass(ReportCriteria.class);
        verify(service.pdfReportWriter).write(data.capture(), criteria.capture());
        assertEquals("Bordereau de vente à l'exportation N° BV1", data.getValue().definition.label());
        assertEquals(LocalDate.of(2026, 10, 20), criteria.getValue().reference);
        assertEquals("nl", criteria.getValue().language);
    }

    /**
     * The slip states the customer, with or without country and document,
     * the amount to refund, the customs visa, the four rates and the
     * customer's signature.
     */
    @Test
    void theSlipStatesTheForm() {
        TaxRefundForm form = zeroed();
        form.number = "BV1";
        form.ticketNumber = "T1";
        form.customerName = "John Smith";
        form.customerCountry = "US";
        form.customerDocument = "P123";
        form.vat20 = new BigDecimal("2.00");
        TaxRefundService.Slip slip = new TaxRefundService.Slip(form);
        assertEquals("BV1", slip.code());
        assertEquals("BO-06-07-03", slip.requirement());
        assertEquals(ReportFamily.TAX, slip.family());
        assertEquals(3, slip.columns().size());
        assertEquals("Signature du client", slip.signature());
        List<String> preamble = slip.preamble(new ReportCriteria());
        assertEquals(List.of("Client : John Smith", "Pays de résidence : US", "Pièce d'identité : P123"),
                preamble.subList(0, 3));
        assertTrue(preamble.get(3).startsWith("Ticket T1 — montant de TVA à rembourser : "), preamble.get(3));
        assertEquals("Visa des douanes : ____________________", preamble.get(4));
        form.customerCountry = null;
        form.customerDocument = null;
        List<String> bare = slip.preamble(new ReportCriteria());
        assertEquals(3, bare.size());
        assertEquals("Client : John Smith", bare.get(0));
        List<List<Object>> rows = slip.rows(new ReportCriteria());
        assertEquals(4, rows.size());
        assertEquals(Arrays.asList("20 %", BigDecimal.ZERO, new BigDecimal("2.00")), rows.get(3));
    }

    /**
     * The slip printed for a Dutch back office speaks Dutch — title, customer,
     * country, document, amount to refund, customs visa, headings and
     * signature — its amounts written as in French (BO-06-01-02).
     */
    @Test
    void theSlipIsPrintedInTheOperatorsLanguage() {
        TaxRefundForm form = zeroed();
        form.number = "BV1";
        form.ticketNumber = "T1";
        form.customerName = "John Smith";
        form.customerCountry = "US";
        form.customerDocument = "P123";
        form.refundAmount = new BigDecimal("1.56");
        form.vat20 = new BigDecimal("2.00");
        TaxRefundService.Slip slip = new TaxRefundService.Slip(form);
        ReportCriteria criteria = new ReportCriteria();
        criteria.reference = LocalDate.of(2026, 10, 25);
        criteria.language = "nl";
        String pdf = new String(new PdfReportWriter().write(new ReportData(slip, slip.rows(criteria)), criteria),
                java.nio.charset.Charset.forName("windows-1252"));
        assertTrue(pdf.contains("(Exportverkoopborderel nr. BV1)"), pdf);
        assertTrue(pdf.contains("(Klant: John Smith)"), pdf);
        assertTrue(pdf.contains("(Land van verblijf: US)"), pdf);
        assertTrue(pdf.contains("(Identiteitsbewijs: P123)"), pdf);
        assertTrue(pdf.contains("(Ticket T1 — terug te betalen btw-bedrag: 1,56"), pdf);
        assertTrue(pdf.contains("(Visum van de douane: ____________________)"), pdf);
        assertTrue(pdf.contains("Handtekening van de klant : "), pdf);
        assertTrue(pdf.contains("(2,00"), pdf);
        assertTrue(!pdf.contains("Bordereau") && !pdf.contains("Client :"));
    }
}
