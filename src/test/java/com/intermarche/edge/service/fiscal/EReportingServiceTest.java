package com.intermarche.edge.service.fiscal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.fiscal.EReport;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.setting.ServiceProvider;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * Unit tests of {@link EReportingService} (BO-05-01-03): the periods, the data
 * of a period — consumer sales by day and rate, international sales by
 * invoice, returns deducted, e-invoiced sales left out —, the transmitted
 * document, its transmission and its acknowledgement.
 */
class EReportingServiceTest {

    /** The instant of the tests. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 9, 0);

    /** The first day of the period of the tests. */
    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    /** The last day of the period of the tests. */
    private static final LocalDate END = LocalDate.of(2026, 10, 10);

    /**
     * Builds the service with its collaborators mocked.
     *
     * @return the service
     */
    private EReportingService service() {
        EReportingService service = new EReportingService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.externalRelayService = mock(ExternalRelayService.class);
        service.eInvoicingService = mock(EInvoicingService.class);
        when(service.posSettingsService.fiscalRetrySeconds()).thenReturn(60);
        when(service.posSettingsService.ereportingPeriod()).thenReturn("DECADE");
        when(service.eInvoicingService.read(anyString())).thenAnswer(invocation ->
                new EInvoicingService() {
                    {
                        objectMapper = new ObjectMapper();
                    }
                }.read(invocation.getArgument(0)));
        return service;
    }

    /**
     * A day belongs to its decade or its month.
     */
    @Test
    void adayBelongsToItsPeriod() {
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 10)},
                EReportingService.periodOf(LocalDate.of(2026, 2, 10), "DECADE"));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 2, 11), LocalDate.of(2026, 2, 20)},
                EReportingService.periodOf(LocalDate.of(2026, 2, 11), "DECADE"));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 2, 21), LocalDate.of(2026, 2, 28)},
                EReportingService.periodOf(LocalDate.of(2026, 2, 21), "DECADE"));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)},
                EReportingService.periodOf(LocalDate.of(2026, 2, 21), "MONTH"));
        List<LocalDate[]> past = EReportingService.pastPeriods(LocalDate.of(2026, 10, 12), "DECADE", 3);
        assertArrayEquals(new LocalDate[] {START, END}, past.get(0));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 30)}, past.get(1));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 20)}, past.get(2));
    }

    /**
     * Builds a sold line.
     *
     * @param total its total tax included
     * @param rate its rate, null for none
     * @return the line
     */
    private TicketLine line(String total, String rate) {
        TicketLine line = new TicketLine();
        line.totalPrice = total == null ? null : new BigDecimal(total);
        line.vatRate = rate == null ? null : new BigDecimal(rate);
        return line;
    }

    /**
     * Builds a closed ticket.
     *
     * @param id its id
     * @param lines its lines
     * @return the ticket
     */
    private Ticket ticket(long id, TicketLine... lines) {
        Ticket ticket = new Ticket();
        ticket.id = id;
        ticket.closingDate = LocalDateTime.of(2026, 10, 2, 11, 0);
        ticket.lines = new ArrayList<>(List.of(lines));
        return ticket;
    }

    /**
     * Stubs what a period holds.
     *
     * @param panache the active PanacheEntityBase mock
     * @param invoices the invoices
     * @param tickets the closed tickets
     * @param refunds the returns
     * @param stores the stores
     */
    private void stubPeriod(MockedStatic<PanacheEntityBase> panache, List<Invoice> invoices, List<Ticket> tickets,
            List<Refund> refunds, List<Store> stores) {
        LocalDateTime from = START.atStartOfDay();
        LocalDateTime to = END.plusDays(1).atStartOfDay();
        panache.when(() -> PanacheEntityBase.list("issueDate >= ?1 and issueDate < ?2 order by issueDate", from, to))
                .thenReturn(new ArrayList<>(invoices));
        panache.when(() -> PanacheEntityBase.list("status = ?1 and closingDate >= ?2 and closingDate < ?3 "
                + "order by closingDate", Ticket.TicketStatus.CLOSED, from, to)).thenReturn(new ArrayList<>(tickets));
        panache.when(() -> PanacheEntityBase.list("creationDate >= ?1 and creationDate < ?2 order by creationDate",
                from, to)).thenReturn(new ArrayList<>(refunds));
        panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>(stores));
    }

    /**
     * The data of a period: the consumer sales by day and rate, returns
     * deducted, money products and cancelled lines left out; the e-invoiced
     * sales left out; the international sales by invoice, with or without
     * their sale; and the totals.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thedataOfAPeriodIsExtracted() {
        EReportingService service = service();
        Ticket b2c = ticket(1L, line("12.00", "0.20"), line("6.00", null), line(null, "0.2"));
        TicketLine gift = line("50.00", "0");
        gift.moneyProduct = true;
        TicketLine cancelled = line("3.00", "0.2");
        cancelled.cancelled = true;
        b2c.lines.add(gift);
        b2c.lines.add(cancelled);
        Ticket einvoiced = ticket(2L, line("120.00", "0.20"));
        Ticket abroad = ticket(3L, line("24.00", "0.20"));
        Invoice french = new Invoice();
        french.customerSiret = "12345678900012";
        french.ticket = einvoiced;
        Invoice frenchLost = new Invoice();
        frenchLost.customerVatNumber = "FR12123456789";
        Invoice foreign = new Invoice();
        foreign.documentNumber = "C04-F2";
        foreign.issueDate = LocalDateTime.of(2026, 10, 3, 9, 0);
        foreign.customerVatNumber = "BE0123456789";
        foreign.ticket = abroad;
        Invoice foreignLost = new Invoice();
        foreignLost.documentNumber = "C04-F3";
        foreignLost.customerTaxId = "ES12345678Z";
        foreignLost.totalExcludingTax = new BigDecimal("50");
        foreignLost.totalVat = new BigDecimal("10");
        Invoice consumer = new Invoice();
        Refund refund = new Refund();
        refund.creationDate = LocalDateTime.of(2026, 10, 2, 15, 0);
        RefundLine back = new RefundLine();
        back.price = new BigDecimal("6.00");
        back.quantity = new BigDecimal("-1");
        back.vatRate = new BigDecimal("0.20");
        RefundLine unpriced = new RefundLine();
        RefundLine untaxed = new RefundLine();
        untaxed.price = BigDecimal.ONE;
        untaxed.quantity = BigDecimal.ONE;
        RefundLine unquantified = new RefundLine();
        unquantified.price = BigDecimal.ONE;
        refund.lines = new ArrayList<>(List.of(back, unpriced, untaxed, unquantified));
        Store store = new Store();
        store.name = "Intermarché";
        store.siret = "98765432100011";
        store.vatNumber = "FR99987654321";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<EReport> created = mockConstruction(EReport.class)) {
            PanacheQuery<EReport> none = mock(PanacheQuery.class);
            panache.when(() -> PanacheEntityBase.find("periodStart = ?1 and periodEnd = ?2", START, END))
                    .thenReturn(none);
            stubPeriod(panache, List.of(french, frenchLost, foreign, foreignLost, consumer),
                    List.of(b2c, einvoiced, abroad), List.of(refund), List.of(store));
            EReport report = service.extract(START, END, NOW);
            assertSame(created.constructed().get(0), report);
            assertEquals(START, report.periodStart);
            assertEquals(END, report.periodEnd);
            assertEquals(4, report.transactionCount);
            assertEquals(new BigDecimal("80.00"), report.totalExcludingTax);
            assertEquals(new BigDecimal("15.00"), report.totalVat);
            assertEquals(new BigDecimal("95.00"), report.totalIncludingTax);
            assertEquals(EReport.Status.PENDING, report.status);
            String xml = report.payload;
            assertTrue(xml.contains("<Siren>987654321</Siren>"));
            assertTrue(xml.contains("<Periodicity>DECADE</Periodicity>"));
            assertTrue(xml.contains("<DailySummary date=\"2026-10-02\" category=\"TLB1\" scope=\"B2C\""));
            assertTrue(xml.contains("<Tax rate=\"20\">\n        <TaxableAmount>5.00</TaxableAmount>\n"
                    + "        <TaxAmount>1.00</TaxAmount>\n        <TransactionCount>1</TransactionCount>"));
            assertTrue(xml.contains("<Tax rate=\"0\">\n        <TaxableAmount>5.00</TaxableAmount>"));
            assertTrue(xml.contains("number=\"C04-F2\" date=\"2026-10-03\" buyerVat=\"BE0123456789\""));
            assertTrue(xml.contains("number=\"C04-F3\" date=\"\" buyerVat=\"\" buyerTaxId=\"ES12345678Z\""));
            assertTrue(xml.contains("<TransactionCount>4</TransactionCount>"));
            assertTrue(xml.contains("<PaymentsReport/>"));
            verify(report).persist();
        }
    }

    /**
     * An accepted period is never extracted again; a rejected one is, in
     * place, its transmission state reset.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anAcceptedPeriodIsNeverExtractedAgain() {
        EReportingService service = service();
        EReport accepted = new EReport();
        accepted.status = EReport.Status.ACCEPTED;
        EReport rejected = mock(EReport.class);
        rejected.status = EReport.Status.REJECTED;
        rejected.attempts = 3;
        rejected.message = "erreur";
        PanacheQuery<EReport> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(accepted, rejected);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("periodStart = ?1 and periodEnd = ?2", START, END))
                    .thenReturn(query);
            stubPeriod(panache, List.of(), List.of(), List.of(), List.of());
            assertNull(service.extract(START, END, NOW));
            assertSame(rejected, service.extract(START, END, NOW));
        }
        assertEquals(EReport.Status.PENDING, rejected.status);
        assertEquals(0, rejected.attempts);
        assertNull(rejected.message);
        assertTrue(rejected.payload.contains("<Siren></Siren>"));
        assertTrue(rejected.payload.contains("<Name></Name>"));
    }

    /**
     * A foreign operator is told by the prefix of its VAT number or, without
     * one, of its tax number; France, Portugal and the final consumer are not
     * abroad.
     */
    @Test
    void aforeignOperatorIsToldByItsPrefix() {
        assertTrue(EReportingService.international("DE123", null));
        assertFalse(EReportingService.international("FR123", "ES1"));
        assertFalse(EReportingService.international(null, null));
        assertFalse(EReportingService.international("12", " "));
        assertTrue(EReportingService.international("1X", "ES12345678Z"));
        assertFalse(EReportingService.international(" ", "999999990"));
        assertFalse(EReportingService.international(null, "FR123456789"));
        assertFalse(EReportingService.international(null, "123456789"));
        assertFalse(EReportingService.international("D1", null));
        assertFalse(EReportingService.international("123", null));
        assertFalse(EReportingService.international("D12", null));
    }

    /**
     * The tick does nothing while e-reporting is off; on, it extracts the
     * periods over that were never extracted and transmits those due.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thetickExtractsAndTransmits() {
        EReportingService service = spy(service());
        assertEquals(0, service.tick(NOW));
        when(service.posSettingsService.ereportingEnabled()).thenReturn(true);
        PanacheQuery<EReport> missing = mock(PanacheQuery.class);
        PanacheQuery<EReport> present = mock(PanacheQuery.class);
        when(present.firstResult()).thenReturn(new EReport());
        EReport due = new EReport();
        EReport failing = new EReport();
        PanacheQuery<EReport> dues = mock(PanacheQuery.class);
        when(dues.list()).thenReturn(List.of(due, failing));
        doReturn(null).when(service).extract(any(), any(), eq(NOW));
        doReturn(true).when(service).transmit(due, NOW);
        doReturn(false).when(service).transmit(failing, NOW);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("periodStart = ?1 and periodEnd = ?2", START, END))
                    .thenReturn(missing);
            panache.when(() -> PanacheEntityBase.find(eq("periodStart = ?1 and periodEnd = ?2"),
                    eq(LocalDate.of(2026, 9, 21)), eq(LocalDate.of(2026, 9, 30)))).thenReturn(present);
            panache.when(() -> PanacheEntityBase.find(eq("periodStart = ?1 and periodEnd = ?2"),
                    eq(LocalDate.of(2026, 9, 11)), eq(LocalDate.of(2026, 9, 20)))).thenReturn(present);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(List.class), eq(NOW), eq(NOW.toLocalDate())))
                    .thenReturn(dues);
            assertEquals(2, service.tick(NOW));
        }
        verify(service).extract(START, END, NOW);
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
        return provider;
    }

    /**
     * A transmission the platform takes is sent, its acknowledgement applied
     * when the answer carries one; without a platform, unreached or refused,
     * it fails and waits for the delay.
     */
    @Test
    void atransmissionIsSentOrTriedAgain() {
        EReportingService service = service();
        EReport report = mock(EReport.class);
        report.periodStart = START;
        report.periodEnd = END;
        report.payload = "<EReporting/>";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(new ArrayList<>());
            assertFalse(service.transmit(report, NOW));
            assertEquals(EReport.Status.FAILED, report.status);
            assertEquals(EInvoicingService.NO_PLATFORM, report.message);
            when(service.eInvoicingService.platform()).thenReturn(platform());
            when(service.externalRelayService.relay(eq(Optional.of("http://pdp")), eq("POST"), eq("/e-reporting"),
                    anyString(), anyLong(), eq("KEY")))
                    .thenReturn(new ExternalRelayService.RelayResult(false, 0, ""),
                            new ExternalRelayService.RelayResult(true, 500, ""),
                            new ExternalRelayService.RelayResult(true, 102, ""),
                            new ExternalRelayService.RelayResult(true, 202, ""),
                            new ExternalRelayService.RelayResult(true, 200,
                                    "{\"id\":\"ACK-1\",\"status\":\"accepted\",\"message\":\"ok\"}"));
            assertFalse(service.transmit(report, NOW));
            assertFalse(service.transmit(report, NOW));
            assertFalse(service.transmit(report, NOW));
            assertEquals(4, report.attempts);
            assertEquals("Transmission refusée ou impossible (statut 102).", report.message);
            assertEquals(NOW.plusSeconds(60), report.nextAttemptAt);
            assertTrue(service.transmit(report, NOW));
            assertEquals(EReport.Status.SENT, report.status);
            assertEquals("2026-10-01/2026-10-10", report.ackId);
            assertEquals(NOW, report.sentAt);
            assertTrue(service.transmit(report, NOW));
            assertEquals(EReport.Status.ACCEPTED, report.status);
            assertEquals("ACK-1", report.ackId);
            assertEquals("ok", report.message);
        }
    }

    /**
     * An acknowledgement pushed by the platform is applied to its
     * transmission; an unknown transmission or status is refused.
     */
    @Test
    @SuppressWarnings("unchecked")
    void apushedAcknowledgementIsApplied() {
        EReportingService service = service();
        EReport report = mock(EReport.class);
        PanacheQuery<EReport> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(report);
        PanacheQuery<EReport> none = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ackId", "ACK-1")).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find("ackId", "X")).thenReturn(none);
            assertNull(service.receive("ACK-1", "REJECTED", "montants"));
            assertEquals(EReport.Status.REJECTED, report.status);
            assertEquals("montants", report.message);
            assertEquals("Statut inconnu.", service.receive("ACK-1", "LATER", null));
            assertEquals("Statut inconnu.", service.receive("ACK-1", null, null));
            assertEquals("Transmission d'e-reporting inconnue.", service.receive("X", "ACCEPTED", null));
        }
        assertEquals("Transmission d'e-reporting inconnue.", service.receive(null, "ACCEPTED", null));
        verify(report).persist();
    }

    /**
     * The totals of an invoice whose sale the node does not hold give its
     * implied rate; absent totals read as zero at no rate.
     */
    @Test
    void thetotalsOfAnInvoiceImplyItsRate() {
        Invoice invoice = new Invoice();
        invoice.totalExcludingTax = null;
        invoice.totalVat = null;
        com.intermarche.edge.domain.sale.VatBreakdown none = EReportingService.totals(invoice);
        assertEquals(BigDecimal.ZERO, none.getBuckets().get(0).rate);
        invoice.totalExcludingTax = new BigDecimal("100");
        invoice.totalVat = new BigDecimal("5.5");
        assertEquals(new BigDecimal("0.055"), EReportingService.totals(invoice).getBuckets().get(0).rate);
    }

    /**
     * Builds a sold service line (BO-05-01-03).
     *
     * @param total its total tax included
     * @param rate its rate
     * @return the line
     */
    private TicketLine serviceLine(String total, String rate) {
        TicketLine line = line(total, rate);
        line.service = true;
        return line;
    }

    /**
     * Builds a returned line.
     *
     * @param price the unit price returned
     * @param originalLineId the sold line it returns, or null
     * @return the line
     */
    private RefundLine returned(String price, Long originalLineId) {
        RefundLine line = new RefundLine();
        line.price = new BigDecimal(price);
        line.quantity = new BigDecimal("-1");
        line.vatRate = new BigDecimal("0.20");
        line.originalLineId = originalLineId;
        return line;
    }

    /**
     * Builds an invoice to an operator established abroad.
     *
     * @param number its number
     * @param day its day of issue
     * @param ticket the sale it states
     * @return the invoice
     */
    private Invoice abroad(String number, int day, Ticket ticket) {
        Invoice invoice = new Invoice();
        invoice.documentNumber = number;
        invoice.issueDate = LocalDateTime.of(2026, 10, day, 9, 0);
        invoice.customerVatNumber = "DE123456789";
        invoice.ticket = ticket;
        return invoice;
    }

    /**
     * BO-05-01-03: the payment data state the services only — collected by day,
     * scope and rate, tax included, VAT and taxable amount — the return of a
     * service deducted, the return of goods, of an unknown line or of no line
     * left out; the goods stay out. The transactions state goods and services
     * each under its category, an international invoice too, and an
     * international invoice holding nothing still says it was issued.
     */
    @Test
    void theServicesAreStatedAsCollectedPayments() {
        EReportingService service = service();
        TicketLine cancelledService = serviceLine("50.00", "0.20");
        cancelledService.cancelled = true;
        TicketLine giftService = serviceLine("30.00", "0");
        giftService.moneyProduct = true;
        Ticket mixed = ticket(10L, line("4.22", "0.055"), serviceLine("12.00", "0.20"), cancelledService, giftService);
        Ticket servicesOnly = ticket(11L, serviceLine("6.00", "0.20"));
        servicesOnly.closingDate = LocalDateTime.of(2026, 10, 4, 10, 0);
        Ticket abroadMixed = ticket(12L, line("10.00", "0.20"), serviceLine("24.00", "0.20"));
        abroadMixed.closingDate = null;
        Ticket abroadServices = ticket(13L, serviceLine("11.00", "0.10"));
        abroadServices.closingDate = LocalDateTime.of(2026, 10, 6, 18, 0);
        Ticket abroadEmpty = ticket(14L);
        Refund refund = new Refund();
        refund.creationDate = LocalDateTime.of(2026, 10, 2, 16, 0);
        refund.lines = new ArrayList<>(List.of(returned("6.00", 501L), returned("1.20", 502L),
                returned("2.40", 503L), returned("3.60", null)));
        TicketLine soldService = serviceLine("6.00", "0.20");
        TicketLine soldGoods = line("1.20", "0.20");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubPeriod(panache, List.of(abroad("C04-F7", 5, abroadMixed), abroad("C04-F8", 6, abroadServices),
                    abroad("C04-F9", 7, abroadEmpty)), List.of(mixed, servicesOnly), List.of(refund), List.of());
            panache.when(() -> PanacheEntityBase.findById(501L)).thenReturn(soldService);
            panache.when(() -> PanacheEntityBase.findById(502L)).thenReturn(soldGoods);
            EReportingService.Data data = service.collect(START, END);
            String xml = service.payload(data, START, END, NOW);
            String payments = xml.substring(xml.indexOf("  <PaymentsReport>"), xml.indexOf("  <Totals>"));
            assertEquals("  <PaymentsReport>\n"
                    + "    <DailyPayments date=\"2026-10-02\" category=\"TPS1\" scope=\"B2C\" currency=\"EUR\">\n"
                    + "      <Payment rate=\"20\">\n"
                    + "        <AmountIncludingTax>6.00</AmountIncludingTax>\n"
                    + "        <TaxAmount>1.00</TaxAmount>\n"
                    + "        <TaxableAmount>5.00</TaxableAmount>\n"
                    + "      </Payment>\n"
                    + "    </DailyPayments>\n"
                    + "    <DailyPayments date=\"2026-10-04\" category=\"TPS1\" scope=\"B2C\" currency=\"EUR\">\n"
                    + "      <Payment rate=\"20\">\n"
                    + "        <AmountIncludingTax>6.00</AmountIncludingTax>\n"
                    + "        <TaxAmount>1.00</TaxAmount>\n"
                    + "        <TaxableAmount>5.00</TaxableAmount>\n"
                    + "      </Payment>\n"
                    + "    </DailyPayments>\n"
                    + "    <DailyPayments date=\"2026-10-05\" category=\"TPS1\" scope=\"B2B_INTERNATIONAL\" currency=\"EUR\">\n"
                    + "      <Payment rate=\"20\">\n"
                    + "        <AmountIncludingTax>24.00</AmountIncludingTax>\n"
                    + "        <TaxAmount>4.00</TaxAmount>\n"
                    + "        <TaxableAmount>20.00</TaxableAmount>\n"
                    + "      </Payment>\n"
                    + "    </DailyPayments>\n"
                    + "    <DailyPayments date=\"2026-10-06\" category=\"TPS1\" scope=\"B2B_INTERNATIONAL\" currency=\"EUR\">\n"
                    + "      <Payment rate=\"10\">\n"
                    + "        <AmountIncludingTax>11.00</AmountIncludingTax>\n"
                    + "        <TaxAmount>1.00</TaxAmount>\n"
                    + "        <TaxableAmount>10.00</TaxableAmount>\n"
                    + "      </Payment>\n"
                    + "    </DailyPayments>\n"
                    + "  </PaymentsReport>\n", payments);
            assertTrue(xml.contains("<DailySummary date=\"2026-10-02\" category=\"TLB1\" scope=\"B2C\""));
            assertTrue(xml.contains("<DailySummary date=\"2026-10-02\" category=\"TPS1\" scope=\"B2C\""));
            assertFalse(xml.contains("<DailySummary date=\"2026-10-04\" category=\"TLB1\""));
            assertTrue(xml.contains("number=\"C04-F7\" date=\"2026-10-05\" buyerVat=\"DE123456789\" buyerTaxId=\"\" "
                    + "category=\"TLB1\""));
            assertTrue(xml.contains("number=\"C04-F7\" date=\"2026-10-05\" buyerVat=\"DE123456789\" buyerTaxId=\"\" "
                    + "category=\"TPS1\""));
            assertFalse(xml.contains("number=\"C04-F8\" date=\"2026-10-06\" buyerVat=\"DE123456789\" buyerTaxId=\"\" "
                    + "category=\"TLB1\""));
            assertTrue(xml.contains("number=\"C04-F9\" date=\"2026-10-07\" buyerVat=\"DE123456789\" buyerTaxId=\"\" "
                    + "category=\"TLB1\" scope=\"B2B_INTERNATIONAL\">\n    </InternationalInvoice>"));
            assertEquals(6, data.count);
        }
    }
}
