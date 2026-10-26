package com.intermarche.edge.service.export;

import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.sale.DocumentType;
import com.intermarche.edge.domain.sale.Invoice;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.service.PosSettingsService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboundFlowService} (BO-11-01-02/03/04/08/14/17/21,
 * BO-11-03-01),
 * targeting 100% branch coverage.
 *
 * <p>The day's tickets are supplied by overriding {@code closedTicketsOf} rather
 * than by intercepting a Panache static: the query is one line and is not what
 * these tests are about, while the fold over the tickets is everything they are
 * about. The destination is a real temporary directory, because "the file is
 * written where it was administered" is part of the contract.
 *
 * <p>Branch enumeration, leg by leg. {@code produce}: flow not activated, flow
 * activated with an empty directory, activated with a directory but no ticket,
 * and the nominal case — the three refusals each returning null, which is not a
 * failure. {@code isActivated}: every arm of the switch, each flow asserted on
 * while the others are off. {@code revenueRows}: two VAT rates, a CANCELLED line
 * left out, a null amount folded to zero. {@code cashBookRows}: two payment
 * methods, a null amount, the TOTAL row. {@code familySalesRows}: two islands and
 * two families, a cancelled line left out, a terminal absent from the park, a
 * point with no terminal and a point with no island, a line with no family and no
 * quantity, the label taken from the first line that carries one and not
 * overwritten, and the four legs of the discount refusal (no original price, no
 * charged price, no quantity, a price forced UP). {@code dailyTransactionRows}:
 * the cancelled line KEPT and flagged, a null closing instant, a null cashier and
 * a cashier with no login, a label carrying the separator. {@code periodSummaryRows}:
 * a sale, a non-sale, a second register, a ticket with no total and no number, the
 * number range and the TOTAL row. {@code cashierProductivityRows}: two shifts of
 * one cashier, a ticket attached to no session, a session with no number, a shift
 * still open measured to its last sale, and the three legs of the duration refusal
 * (no opening, no end at all, bounds in the wrong order). {@code tick}: the day
 * already produced (nothing happens), a new day (produced and remembered), and a
 * write failure (NOT remembered, so the next minute retries). {@code tickSafely}:
 * swallows the failure instead of killing the loop.
 *
 * <p>VAT rates are written as the FRACTIONS the referential stores — {@code 0.2000}
 * for twenty percent — because that is what a ticket line carries, and a test
 * written in percent asserts a breakdown nobody will ever produce.
 */
class OutboundFlowServiceTest {

    /** The accounting day every test below produces. */
    private static final LocalDate DAY = LocalDate.of(2026, 3, 17);

    /** The payment codes read during the day, served by the wired service. */
    private final List<com.intermarche.edge.domain.barcode.ScannedBarcode> readings = new java.util.ArrayList<>();

    /** The vignette declarations of the day, served by the wired service. */
    private final List<com.intermarche.edge.domain.session.TechnicalEvent> declarations = new java.util.ArrayList<>();

    /** The engine valuations of the day's lines, served by the wired service. */
    private final List<com.intermarche.edge.domain.sale.TicketLineValuation> valuations = new java.util.ArrayList<>();

    /** The burn registry the wired service asks. */
    private final com.intermarche.edge.service.sync.VoucherBurnService burns =
            mock(com.intermarche.edge.service.sync.VoucherBurnService.class);

    /**
     * Builds the service, with the given activation and destination, serving the
     * given tickets for every day asked.
     *
     * @param directory the administered destination, possibly empty
     * @param revenue whether the revenue flow is activated
     * @param cashBook whether the cash-book flow is activated
     * @param tickets the closed tickets the day holds
     * @return the service under test
     */
    private OutboundFlowService service(String directory, boolean revenue, boolean cashBook,
            List<Ticket> tickets) {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.exportDirectory()).thenReturn(directory);
        when(settings.exportRevenueEnabled()).thenReturn(revenue);
        when(settings.exportCashBookEnabled()).thenReturn(cashBook);
        when(settings.accountingCutoverHour()).thenReturn(0);
        return wire(settings, tickets, List.of());
    }

    /**
     * Builds the service with EXACTLY ONE flow activated, the given tickets and
     * the given park.
     *
     * <p>One flow at a time on purpose: a fold is asserted on the file it wrote,
     * and a directory holding six files is a test that passes on the wrong one.
     *
     * @param directory the administered destination
     * @param flow the only flow activated
     * @param tickets the closed tickets the day holds
     * @param park the checkout points of the park
     * @return the service under test
     */
    private OutboundFlowService serviceFor(String directory, OutboundFlow flow,
            List<Ticket> tickets, List<CheckoutPoint> park) {
        return wire(settings(directory, flow, ""), tickets, tickets, List.of(), List.of(), park);
    }

    /**
     * Wires a service over the given settings, tickets and park, bypassing the
     * two database reads.
     *
     * @param settings the administered settings
     * @param tickets the closed tickets served for every day asked
     * @param park the checkout points served
     * @return the service under test
     */
    private OutboundFlowService wire(PosSettingsService settings, List<Ticket> tickets,
            List<CheckoutPoint> park) {
        return wire(settings, tickets, tickets, List.of(), List.of(), park);
    }

    /**
     * Wires a service over everything the eleven flows read, bypassing the five
     * database queries.
     *
     * @param settings the administered settings
     * @param tickets the closed tickets of the day
     * @param monthTickets the closed tickets of the month
     * @param invoices the documents issued during the day
     * @param orders the orders dated in the day
     * @param park the checkout points served
     * @return the service under test
     */
    private OutboundFlowService wire(PosSettingsService settings, List<Ticket> tickets,
            List<Ticket> monthTickets, List<Invoice> invoices, List<CustomerOrder> orders,
            List<CheckoutPoint> park) {
        AccountingDay accountingDay = new AccountingDay();
        accountingDay.posSettingsService = settings;
        OutboundFlowService service = new OutboundFlowService() {
            @Override
            List<Ticket> closedTicketsOf(LocalDate day) {
                return tickets;
            }

            @Override
            List<Ticket> closedTicketsOfMonth(LocalDate day) {
                return monthTickets;
            }

            @Override
            List<Invoice> invoicesOf(LocalDate day) {
                return invoices;
            }

            @Override
            List<CustomerOrder> ordersOf(LocalDate day) {
                return orders;
            }

            @Override
            List<CheckoutPoint> checkoutPoints() {
                return park;
            }

            @Override
            java.util.Set<String> planexaExcludedIslands() {
                return java.util.Set.of("DRIVE");
            }

            @Override
            List<com.intermarche.edge.domain.barcode.ScannedBarcode> scannedBarcodesOf(LocalDate day) {
                return readings;
            }

            @Override
            List<com.intermarche.edge.domain.session.TechnicalEvent> vignetteDeclarationsOf(LocalDate day) {
                return declarations;
            }

            @Override
            List<com.intermarche.edge.domain.sale.TicketLineValuation> lineValuationsOf(List<Long> ticketIds) {
                return valuations;
            }
        };
        service.posSettingsService = settings;
        service.accountingDay = accountingDay;
        service.voucherBurnService = burns;
        return service;
    }

    /**
     * Mocks the administered settings with ONE flow activated and the given
     * destination and PDV.
     *
     * @param directory the administered destination
     * @param flow the only flow activated, or null for none
     * @param pdv this node's PDV number
     * @return the settings mock
     */
    private PosSettingsService settings(String directory, OutboundFlow flow, String pdv) {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.exportDirectory()).thenReturn(directory);
        when(settings.accountingCutoverHour()).thenReturn(0);
        when(settings.pdvNumber()).thenReturn(pdv);
        when(settings.exportRevenueEnabled()).thenReturn(flow == OutboundFlow.REVENUE);
        when(settings.exportCashBookEnabled()).thenReturn(flow == OutboundFlow.CASH_BOOK);
        when(settings.exportFamilySalesEnabled()).thenReturn(flow == OutboundFlow.FAMILY_SALES);
        when(settings.exportTransactionsEnabled())
                .thenReturn(flow == OutboundFlow.DAILY_TRANSACTIONS);
        when(settings.exportPeriodSummaryEnabled())
                .thenReturn(flow == OutboundFlow.PERIOD_SUMMARY);
        when(settings.exportProductivityEnabled())
                .thenReturn(flow == OutboundFlow.CASHIER_PRODUCTIVITY);
        when(settings.exportCustomerInvoicesEnabled())
                .thenReturn(flow == OutboundFlow.CUSTOMER_INVOICES);
        when(settings.exportErpInvoicesEnabled()).thenReturn(flow == OutboundFlow.ERP_INVOICES);
        when(settings.exportFidelityVatEnabled()).thenReturn(flow == OutboundFlow.FIDELITY_VAT);
        when(settings.exportOrderVatEnabled()).thenReturn(flow == OutboundFlow.ORDER_VAT);
        when(settings.exportPanelsEnabled()).thenReturn(flow == OutboundFlow.PANELS);
        when(settings.planexaExportEnabled()).thenReturn(flow == OutboundFlow.PLANEXA);
        when(settings.exportGs1PaymentsEnabled()).thenReturn(flow == OutboundFlow.GS1_PAYMENTS);
        when(settings.exportVignettesEnabled()).thenReturn(flow == OutboundFlow.VIGNETTES);
        when(settings.invoiceExportEcoTax()).thenReturn(true);
        when(settings.exportUpstreamOffers()).thenReturn(List.of());
        when(settings.vignetteAmountStep()).thenReturn(new BigDecimal("10.00"));
        when(settings.vignettePerStep()).thenReturn(1);
        return settings;
    }

    /**
     * Builds an invoice.
     *
     * @param number the document number, possibly null
     * @param type the document type, possibly null
     * @param issuedAt the issue instant, possibly null
     * @param excludingTax the tax-excluded total, possibly null
     * @param includingTax the tax-included total, possibly null
     * @param vat the tax, possibly null
     * @param ecoTax the eco-tax, possibly null
     * @return the document
     */
    private Invoice invoice(String number, DocumentType type, LocalDateTime issuedAt,
            String excludingTax, String includingTax, String vat, String ecoTax) {
        Invoice invoice = new Invoice();
        invoice.documentNumber = number;
        invoice.documentType = type;
        invoice.issueDate = issuedAt;
        invoice.totalExcludingTax = excludingTax == null ? null : new BigDecimal(excludingTax);
        invoice.totalIncludingTax = includingTax == null ? null : new BigDecimal(includingTax);
        invoice.totalVat = vat == null ? null : new BigDecimal(vat);
        invoice.totalEcoTax = ecoTax == null ? null : new BigDecimal(ecoTax);
        return invoice;
    }

    /**
     * Builds an order carrying one line per rate given.
     *
     * @param code the order code
     * @param totalAmount the order total
     * @param rateAndTotal the rate and tax-included total of each line, in pairs;
     *     a null rate stands for a line that carries none
     * @return the order
     */
    private CustomerOrder order(String code, String totalAmount, String... rateAndTotal) {
        CustomerOrder customerOrder = new CustomerOrder();
        customerOrder.code = code;
        customerOrder.totalAmount = new BigDecimal(totalAmount);
        for (int index = 0; index < rateAndTotal.length; index += 2) {
            CustomerOrder.OrderLine line = new CustomerOrder.OrderLine();
            line.vatRate = rateAndTotal[index] == null ? null
                    : new BigDecimal(rateAndTotal[index]);
            line.totalPrice = rateAndTotal[index + 1] == null ? null
                    : new BigDecimal(rateAndTotal[index + 1]);
            customerOrder.lines.add(line);
        }
        return customerOrder;
    }

    /**
     * Builds a checkout point of the park.
     *
     * @param terminalId the register identifier, possibly null
     * @param islandCode the island it belongs to, possibly null
     * @return the point
     */
    private CheckoutPoint point(String terminalId, String islandCode) {
        CheckoutPoint checkoutPoint = new CheckoutPoint();
        checkoutPoint.terminalId = terminalId;
        checkoutPoint.islandCode = islandCode;
        return checkoutPoint;
    }

    /**
     * Builds a sale line carrying a family, a quantity and a price history.
     *
     * @param familyCode the family code, possibly null
     * @param familyLabel the family label, possibly null
     * @param quantity the quantity, possibly null
     * @param unitPrice the price charged, possibly null
     * @param originalUnitPrice the price before any modification, possibly null
     * @param total the tax-included line total
     * @return the line, at 20% VAT — the fraction the referential stores — and
     * not cancelled
     */
    private TicketLine saleLine(String familyCode, String familyLabel, String quantity,
            String unitPrice, String originalUnitPrice, String total) {
        TicketLine line = line("0.2000", total, false);
        line.familyCode = familyCode;
        line.familyLabel = familyLabel;
        line.quantity = quantity == null ? null : new BigDecimal(quantity);
        line.unitPrice = unitPrice == null ? null : new BigDecimal(unitPrice);
        line.originalUnitPrice = originalUnitPrice == null ? null
                : new BigDecimal(originalUnitPrice);
        return line;
    }

    /**
     * Builds a closed ticket.
     *
     * @param number the ticket number, possibly null
     * @param terminalId the register, possibly null
     * @param itemCount the number of items it holds
     * @param totalIncludingTax the tax-included total, possibly null
     * @param closedAt the closing instant, possibly null
     * @return the ticket
     */
    private Ticket ticket(String number, String terminalId, int itemCount,
            String totalIncludingTax, LocalDateTime closedAt) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = number;
        ticket.terminalId = terminalId;
        ticket.itemCount = itemCount;
        ticket.totalIncludingTax = totalIncludingTax == null ? null
                : new BigDecimal(totalIncludingTax);
        ticket.closingDate = closedAt;
        return ticket;
    }

    /**
     * Builds a cashier.
     *
     * @param loginName the login, possibly null
     * @return the employee
     */
    private Employee cashier(String loginName) {
        Employee employee = new Employee();
        employee.loginName = loginName;
        return employee;
    }

    /**
     * Builds a cash session — a shift.
     *
     * @param sessionNumber the session number, possibly null
     * @param openedAt the opening instant, possibly null
     * @param closedAt the closing instant, possibly null
     * @return the session
     */
    private CashSession session(String sessionNumber, LocalDateTime openedAt,
            LocalDateTime closedAt) {
        CashSession cashSession = new CashSession();
        cashSession.sessionNumber = sessionNumber;
        cashSession.openingDate = openedAt;
        cashSession.closingDate = closedAt;
        return cashSession;
    }

    /**
     * Builds a ticket line.
     *
     * @param rate the VAT rate, possibly null
     * @param total the tax-included total, possibly null
     * @param cancelled whether the line was cancelled
     * @return the line
     */
    private TicketLine line(String rate, String total, boolean cancelled) {
        TicketLine line = new TicketLine();
        line.vatRate = rate == null ? null : new BigDecimal(rate);
        line.totalPrice = total == null ? null : new BigDecimal(total);
        line.cancelled = cancelled;
        return line;
    }

    /**
     * Builds a settlement of the given method and amount.
     *
     * @param method the method key
     * @param amount the amount, possibly null
     * @return the settlement
     */
    private TicketPayment payment(String method, String amount) {
        TicketPayment payment = mock(TicketPayment.class);
        when(payment.getMethodKey()).thenReturn(method);
        payment.amount = amount == null ? null : new BigDecimal(amount);
        return payment;
    }

    /** A flow that is not activated at this echelon produces nothing. */
    @Test
    void produceWritesNothingWhenTheFlowIsNotActivated(@TempDir Path directory) throws IOException {
        OutboundFlowService service = service(directory.toString(), false, false, List.of());
        Assertions.assertNull(service.produce(OutboundFlow.REVENUE, DAY));
        Assertions.assertEquals(0, Files.list(directory).count());
    }

    /** An activated flow with no administered destination produces nothing. */
    @Test
    void produceWritesNothingWithoutADestination() throws IOException {
        OutboundFlowService service = service("", true, true, List.of(new Ticket()));
        Assertions.assertNull(service.produce(OutboundFlow.REVENUE, DAY));
    }

    /** A day without a single closed ticket produces NO file, not an empty one. */
    @Test
    void produceWritesNoFileForADayWithoutASale(@TempDir Path directory) throws IOException {
        OutboundFlowService service = service(directory.toString(), true, true, List.of());
        Assertions.assertNull(service.produce(OutboundFlow.REVENUE, DAY));
        Assertions.assertEquals(0, Files.list(directory).count());
    }

    /**
     * The REVENUE flow folds the day by VAT rate, leaves the cancelled line out,
     * folds a null amount to zero and closes with a TOTAL row.
     */
    @Test
    void produceWritesTheRevenueFlow(@TempDir Path directory) throws IOException {
        Ticket ticket = new Ticket();
        ticket.lines.add(line("0.2000", "12.00", false));
        ticket.lines.add(line("0.0550", "10.00", false));
        ticket.lines.add(line("0.2000", "99.00", true));
        ticket.lines.add(line("0.2000", null, false));
        OutboundFlowService service = service(directory.toString(), true, false, List.of(ticket));

        Path written = service.produce(OutboundFlow.REVENUE, DAY);
        Assertions.assertNotNull(written);
        Assertions.assertEquals("tranca3-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;TAUX_TVA;TOTAL_TTC;TOTAL_HT;TVA;NB_TICKETS", rows.get(0));
        // The cancelled 99.00 is absent: 12.00 at 20% and 10.00 at 5.5%.
        Assertions.assertTrue(rows.stream().anyMatch(r -> r.contains(";12.00;")));
        Assertions.assertTrue(rows.stream().noneMatch(r -> r.contains("99.00")));
        Assertions.assertEquals("2026-03-17;TOTAL;22.00;", rows.get(rows.size() - 1)
                .substring(0, "2026-03-17;TOTAL;22.00;".length()));
    }

    /**
     * The CASH-BOOK flow folds the day by payment method, counts the settlements,
     * folds a null amount to zero and closes with a TOTAL row.
     */
    @Test
    void produceWritesTheCashBookFlow(@TempDir Path directory) throws IOException {
        Ticket ticket = new Ticket();
        ticket.payments.add(payment("CASH", "20.00"));
        ticket.payments.add(payment("CARD", "15.50"));
        ticket.payments.add(payment("CASH", null));
        OutboundFlowService service = service(directory.toString(), false, true, List.of(ticket));

        Path written = service.produce(OutboundFlow.CASH_BOOK, DAY);
        Assertions.assertNotNull(written);
        Assertions.assertEquals("tranfc-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;MOYEN_PAIEMENT;MONTANT;NB_REGLEMENTS", rows.get(0));
        Assertions.assertTrue(rows.contains("2026-03-17;CASH;20.00;2"));
        Assertions.assertTrue(rows.contains("2026-03-17;CARD;15.50;1"));
        Assertions.assertEquals("2026-03-17;TOTAL;35.50;3", rows.get(rows.size() - 1));
    }

    /**
     * BO-07-01-04: the settlements of an integrated transaction are booked on
     * rows of their own, keyed by channel and by the means the system stated —
     * the tender's key when it stated none, or a blank one.
     */
    @Test
    void theCashBookIsolatesTheIntegratedSettlements(@TempDir Path directory) throws IOException {
        Ticket lane = new Ticket();
        lane.payments.add(payment("CARD", "10.00"));
        Ticket web = new Ticket();
        web.salesChannel = com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE;
        TicketPayment paypal = payment("CARD", "30.00");
        paypal.tenderCode = "PAYPAL";
        TicketPayment blank = payment("CARD", "5.00");
        blank.tenderCode = " ";
        web.payments.add(paypal);
        web.payments.add(blank);
        web.payments.add(payment("CASH", "1.00"));
        OutboundFlowService service = service(directory.toString(), false, true, List.of(lane, web));
        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.CASH_BOOK, DAY));
        Assertions.assertTrue(rows.contains("2026-03-17;CARD;10.00;1"));
        Assertions.assertTrue(rows.contains("2026-03-17;ECOMMERCE/PAYPAL;30.00;1"));
        Assertions.assertTrue(rows.contains("2026-03-17;ECOMMERCE/CARD;5.00;1"));
        Assertions.assertTrue(rows.contains("2026-03-17;ECOMMERCE/CASH;1.00;1"));
        Assertions.assertEquals("2026-03-17;TOTAL;46.00;4", rows.get(rows.size() - 1));
    }

    /** Every arm of the activation switch answers its own administered key. */
    @Test
    void isActivatedReadsTheKeyOfEachFlow() {
        for (OutboundFlow activated : OutboundFlow.values()) {
            OutboundFlowService service = serviceFor("/tmp", activated, List.of(), List.of());
            for (OutboundFlow flow : OutboundFlow.values()) {
                Assertions.assertEquals(flow == activated, service.isActivated(flow),
                        "activation of " + flow + " while " + activated + " is on");
            }
        }
    }

    /**
     * The FAMILY-SALES flow folds by island AND family, leaves the cancelled line
     * out, carries the quantity and the line discount, and closes with a TOTAL
     * row.
     */
    @Test
    void produceWritesTheFamilySalesFlow(@TempDir Path directory) throws IOException {
        Ticket first = ticket("T1", "CA01", 2, "24.00", null);
        first.lines.add(saleLine("100", "EPICERIE", "2", "6.00", "7.00", "12.00"));
        first.lines.add(saleLine("200", "FRUITS", "1", "12.00", null, "12.00"));
        first.lines.add(line("0.2000", "99.00", true));
        Ticket second = ticket("T2", "CA02", 1, "12.00", null);
        second.lines.add(saleLine("100", "EPICERIE", "1", "6.00", "6.00", "6.00"));
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.FAMILY_SALES,
                List.of(first, second), List.of(point("CA01", "ILE1"), point("CA02", "ILE2")));

        Path written = service.produce(OutboundFlow.FAMILY_SALES, DAY);
        Assertions.assertNotNull(written);
        Assertions.assertEquals("cafamil-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;CODE_ILOT;CODE_FAMILLE;LIBELLE_FAMILLE;QUANTITE;"
                + "TOTAL_TTC;TOTAL_HT;TVA;REMISES;REMISES_AMONT;NB_TICKETS", rows.get(0));
        // Island 1, family 100: 2 units, 12.00 TTC, a 1.00 x 2 discount, 1 ticket.
        Assertions.assertEquals("2026-03-17;ILE1;100;EPICERIE;2.000;12.00;10.00;2.00;2.00;0.00;1",
                rows.get(1));
        Assertions.assertEquals("2026-03-17;ILE1;200;FRUITS;1.000;12.00;10.00;2.00;0.00;0.00;1",
                rows.get(2));
        // Island 2 is its own cell even though the family is the same.
        Assertions.assertEquals("2026-03-17;ILE2;100;EPICERIE;1.000;6.00;5.00;1.00;0.00;0.00;1",
                rows.get(3));
        Assertions.assertTrue(rows.stream().noneMatch(row -> row.contains("99.00")));
        Assertions.assertEquals("2026-03-17;TOTAL;;;;30.00;25.00;5.00;2.00;0.00;2", rows.get(4));
    }

    /**
     * A register absent from the park, a point with no terminal, a point with no
     * island, a line with no family and a line with no quantity each fold into
     * their own blank rather than into a failure.
     */
    @Test
    void theFamilySalesFlowSurvivesEveryBlank(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA09", 1, "12.00", null);
        ticket.lines.add(saleLine(null, null, null, null, null, "12.00"));
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.FAMILY_SALES,
                List.of(ticket), List.of(point(null, "ILE1"), point("CA01", null)));

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.FAMILY_SALES, DAY));
        Assertions.assertEquals("2026-03-17;;;;0.000;12.00;10.00;2.00;0.00;0.00;1", rows.get(1));
    }

    /**
     * The family label is taken from the first line that carries one and is not
     * overwritten afterwards, so a cell is never labelled by chance.
     */
    @Test
    void theFamilyLabelComesFromTheFirstLineThatCarriesOne(@TempDir Path directory)
            throws IOException {
        Ticket ticket = ticket("T1", "CA01", 2, "24.00", null);
        ticket.lines.add(saleLine("100", null, "1", "12.00", null, "12.00"));
        ticket.lines.add(saleLine("100", "EPICERIE", "1", "12.00", null, "12.00"));
        ticket.lines.add(saleLine("100", "AUTRE", "1", "12.00", null, "12.00"));
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.FAMILY_SALES,
                List.of(ticket), List.of(point("CA01", "ILE1")));

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.FAMILY_SALES, DAY));
        Assertions.assertTrue(rows.get(1).contains(";EPICERIE;"));
        Assertions.assertFalse(rows.get(1).contains("AUTRE"));
    }

    /**
     * A price FORCED UP is not a discount: the saving is negative, and the
     * discount column stays at zero instead of going negative.
     */
    @Test
    void aPriceForcedUpIsNotADiscount(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 1, "12.00", null);
        ticket.lines.add(saleLine("100", "EPICERIE", "1", "12.00", "10.00", "12.00"));
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.FAMILY_SALES,
                List.of(ticket), List.of(point("CA01", "ILE1")));

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.FAMILY_SALES, DAY));
        Assertions.assertEquals("2026-03-17;ILE1;100;EPICERIE;1.000;12.00;10.00;2.00;0.00;0.00;1",
                rows.get(1));
    }

    /**
     * A line missing its charged price, and a line missing its quantity, state no
     * discount either — the two other legs of the refusal.
     */
    @Test
    void aLineMissingAPriceOrAQuantityStatesNoDiscount(@TempDir Path directory)
            throws IOException {
        Ticket ticket = ticket("T1", "CA01", 2, "24.00", null);
        ticket.lines.add(saleLine("100", "EPICERIE", "1", null, "7.00", "12.00"));
        ticket.lines.add(saleLine("200", "FRUITS", null, "6.00", "7.00", "12.00"));
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.FAMILY_SALES,
                List.of(ticket), List.of(point("CA01", "ILE1")));

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.FAMILY_SALES, DAY));
        Assertions.assertTrue(rows.get(1).endsWith(";0.00;0.00;1"));
        Assertions.assertTrue(rows.get(2).endsWith(";0.00;0.00;1"));
    }

    /**
     * The DAILY-TRANSACTIONS flow writes one row per line, cashier and register
     * included, and KEEPS the cancelled line, flagged.
     */
    @Test
    void produceWritesTheTransactionDetailFlow(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 1, "12.00", LocalDateTime.of(2026, 3, 17, 18, 30));
        ticket.cashier = cashier("mdupont");
        TicketLine sold = saleLine("100", "EPICERIE", "2", "6.00", "7.00", "12.00");
        sold.lineNumber = 1;
        sold.ean = "3250390000000";
        sold.plu = "4011";
        sold.productLabel = "CAFE";
        ticket.lines.add(sold);
        TicketLine cancelled = saleLine("100", "EPICERIE", "1", "6.00", null, "6.00");
        cancelled.lineNumber = 2;
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.DAILY_TRANSACTIONS, List.of(ticket), List.of());

        Path written = service.produce(OutboundFlow.DAILY_TRANSACTIONS, DAY);
        Assertions.assertEquals("trandet-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;NUM_TICKET;DATE_HEURE;TERMINAL;CAISSIERE;NUM_LIGNE;EAN;"
                + "PLU;LIBELLE;CODE_FAMILLE;QUANTITE;PRIX_UNITAIRE;PRIX_ORIGINE;REMISE;"
                + "TOTAL_TTC;TAUX_TVA;ANNULEE", rows.get(0));
        Assertions.assertEquals("2026-03-17;T1;2026-03-17T18:30:00;CA01;mdupont;1;3250390000000;"
                + "4011;CAFE;100;2.000;6.00;7.00;2.00;12.00;0.2000;N", rows.get(1));
        Assertions.assertTrue(rows.get(2).endsWith(";O"));
        Assertions.assertEquals(3, rows.size());
    }

    /**
     * A ticket with no closing instant, no number, no register and no cashier
     * still produces its rows: blanks and {@code INCONNU}, never a hole in the
     * file.
     */
    @Test
    void theTransactionDetailFlowSurvivesEveryBlank(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket(null, null, 1, null, null);
        ticket.lines.add(saleLine(null, null, null, null, null, null));
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.DAILY_TRANSACTIONS, List.of(ticket), List.of());

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.DAILY_TRANSACTIONS, DAY));
        Assertions.assertEquals("2026-03-17;;;;INCONNU;0;;;;;0.000;0.00;0.00;0.00;0.00;0.2000;N",
                rows.get(1));
    }

    /**
     * A cashier WITHOUT a login is {@code INCONNU} too — the second leg of the
     * guard, which a null cashier alone does not reach.
     */
    @Test
    void aCashierWithoutALoginIsUnknown(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 1, "12.00", null);
        ticket.cashier = cashier(null);
        ticket.lines.add(saleLine("100", "EPICERIE", "1", "12.00", null, "12.00"));
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.DAILY_TRANSACTIONS, List.of(ticket), List.of());

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.DAILY_TRANSACTIONS, DAY));
        Assertions.assertTrue(rows.get(1).contains(";INCONNU;"));
    }

    /**
     * A label carrying the separator is neutralised rather than shifting every
     * column after it.
     */
    @Test
    void aLabelCarryingTheSeparatorDoesNotShiftTheColumns(@TempDir Path directory)
            throws IOException {
        Ticket ticket = ticket("T1", "CA01", 1, "12.00", null);
        TicketLine line = saleLine("100", "EPICERIE;BIO", "1", "12.00", null, "12.00");
        line.productLabel = "CAFE;MOULU";
        ticket.lines.add(line);
        OutboundFlowService detail = serviceFor(directory.toString(),
                OutboundFlow.DAILY_TRANSACTIONS, List.of(ticket), List.of());
        List<String> rows = Files.readAllLines(detail.produce(OutboundFlow.DAILY_TRANSACTIONS, DAY));
        Assertions.assertTrue(rows.get(1).contains("CAFE MOULU"));
        Assertions.assertEquals(17, rows.get(1).split(";", -1).length);

        OutboundFlowService families = serviceFor(directory.toString(),
                OutboundFlow.FAMILY_SALES, List.of(ticket), List.of(point("CA01", "ILE1")));
        List<String> familyRows = Files.readAllLines(
                families.produce(OutboundFlow.FAMILY_SALES, DAY));
        Assertions.assertTrue(familyRows.get(1).contains("EPICERIE BIO"));
        Assertions.assertEquals(11, familyRows.get(1).split(";", -1).length);
    }

    /**
     * The PERIOD-SUMMARY flow counts sales and non-sales apart, per register, and
     * reports the ticket-number range of each.
     */
    @Test
    void produceWritesTheClosingSynthesisFlow(@TempDir Path directory) throws IOException {
        Ticket sale = ticket("T1", "CA01", 3, "24.00", null);
        Ticket drawerOpening = ticket("T2", "CA01", 0, null, null);
        Ticket lastSale = ticket("T3", "CA01", 1, "6.00", null);
        Ticket otherRegister = ticket("T4", "CA02", 2, "10.00", null);
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.PERIOD_SUMMARY,
                List.of(sale, drawerOpening, lastSale, otherRegister), List.of());

        Path written = service.produce(OutboundFlow.PERIOD_SUMMARY, DAY);
        Assertions.assertEquals("transyn-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;TERMINAL;NB_TICKETS;NB_VENTES;NB_NON_VENTES;"
                + "PREMIER_TICKET;DERNIER_TICKET;TOTAL_TTC", rows.get(0));
        Assertions.assertEquals("2026-03-17;CA01;3;2;1;T1;T3;30.00", rows.get(1));
        Assertions.assertEquals("2026-03-17;CA02;1;1;0;T4;T4;10.00", rows.get(2));
        Assertions.assertEquals("2026-03-17;TOTAL;4;3;1;;;40.00", rows.get(3));
    }

    /** A ticket with neither register nor number still lands in the synthesis. */
    @Test
    void theClosingSynthesisSurvivesEveryBlank(@TempDir Path directory) throws IOException {
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.PERIOD_SUMMARY, List.of(ticket(null, null, 0, null, null)),
                List.of());
        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.PERIOD_SUMMARY, DAY));
        Assertions.assertEquals("2026-03-17;;1;0;1;;;0.00", rows.get(1));
    }

    /**
     * The PRODUCTIVITY flow folds by shift AND cashier, and computes the duration,
     * the hourly rate and the average basket of each.
     */
    @Test
    void produceWritesTheProductivityFlow(@TempDir Path directory) throws IOException {
        CashSession shift = session("S1", LocalDateTime.of(2026, 3, 17, 9, 0),
                LocalDateTime.of(2026, 3, 17, 11, 0));
        Ticket first = ticket("T1", "CA01", 30, "60.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        first.cashier = cashier("mdupont");
        first.session = shift;
        Ticket second = ticket("T2", "CA01", 30, "40.00", LocalDateTime.of(2026, 3, 17, 10, 30));
        second.cashier = cashier("mdupont");
        second.session = shift;
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(first, second), List.of());

        Path written = service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY);
        Assertions.assertEquals("prodcai-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;PRISE_DE_POSTE;CAISSIERE;TERMINAL;NB_TICKETS;"
                + "NB_ARTICLES;TOTAL_TTC;DUREE_MINUTES;ARTICLES_PAR_HEURE;PANIER_MOYEN",
                rows.get(0));
        // 120 minutes, 60 items: 30 an hour, and a 50.00 average basket.
        Assertions.assertEquals("2026-03-17;S1;mdupont;CA01;2;60;100.00;120;30.00;50.00",
                rows.get(1));
        Assertions.assertEquals(2, rows.size());
    }

    /**
     * The same cashier on two shifts is two rows, and a ticket attached to no
     * session is a shift of its own with a blank number.
     */
    @Test
    void productivityIsCountedByShiftAndNotByDay(@TempDir Path directory) throws IOException {
        Ticket morning = ticket("T1", "CA01", 10, "20.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        morning.cashier = cashier("mdupont");
        morning.session = session("S1", LocalDateTime.of(2026, 3, 17, 9, 0),
                LocalDateTime.of(2026, 3, 17, 10, 0));
        Ticket afternoon = ticket("T2", "CA02", 10, "30.00", LocalDateTime.of(2026, 3, 17, 15, 0));
        afternoon.cashier = cashier("mdupont");
        afternoon.session = session("S2", LocalDateTime.of(2026, 3, 17, 14, 0),
                LocalDateTime.of(2026, 3, 17, 15, 0));
        Ticket detached = ticket("T3", "CA03", 5, "10.00", LocalDateTime.of(2026, 3, 17, 16, 0));
        detached.cashier = cashier("mdupont");
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(morning, afternoon, detached),
                List.of());

        List<String> rows = Files.readAllLines(
                service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY));
        Assertions.assertEquals(4, rows.size());
        Assertions.assertEquals("2026-03-17;S1;mdupont;CA01;1;10;20.00;60;10.00;20.00",
                rows.get(1));
        Assertions.assertEquals("2026-03-17;S2;mdupont;CA02;1;10;30.00;60;10.00;30.00",
                rows.get(2));
        // No session: no bound to measure between, so no duration and no rate.
        Assertions.assertEquals("2026-03-17;;mdupont;CA03;1;5;10.00;0;0.00;10.00", rows.get(3));
    }

    /**
     * A shift still OPEN is measured up to its last sale rather than skipped: the
     * accounting day is over even when a session was left open.
     */
    @Test
    void anOpenShiftIsMeasuredUpToItsLastSale(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 20, "40.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        ticket.cashier = cashier("mdupont");
        ticket.session = session("S1", LocalDateTime.of(2026, 3, 17, 9, 0), null);
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(ticket), List.of());

        List<String> rows = Files.readAllLines(
                service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY));
        Assertions.assertEquals("2026-03-17;S1;mdupont;CA01;1;20;40.00;60;20.00;40.00",
                rows.get(1));
    }

    /**
     * A shift with no opening instant, and one whose bounds come out in the wrong
     * order, both report a zero duration — never a negative one the receiving
     * side would divide by.
     */
    @Test
    void aShiftWithoutUsableBoundsReportsNoDuration(@TempDir Path directory) throws IOException {
        Ticket noOpening = ticket("T1", "CA01", 10, "20.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        noOpening.cashier = cashier("mdupont");
        noOpening.session = session("S1", null, LocalDateTime.of(2026, 3, 17, 11, 0));
        Ticket clockSetBack = ticket("T2", "CA02", 10, "20.00", null);
        clockSetBack.cashier = cashier("mlegrand");
        clockSetBack.session = session("S2", LocalDateTime.of(2026, 3, 17, 11, 0),
                LocalDateTime.of(2026, 3, 17, 9, 0));
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(noOpening, clockSetBack), List.of());

        List<String> rows = Files.readAllLines(
                service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY));
        Assertions.assertEquals("2026-03-17;S1;mdupont;CA01;1;10;20.00;0;0.00;20.00", rows.get(1));
        Assertions.assertEquals("2026-03-17;S2;mlegrand;CA02;1;10;20.00;0;0.00;20.00", rows.get(2));
    }

    /**
     * A shift whose session has no closing AND whose tickets have no closing
     * instant has no end at all: the last leg of the duration refusal.
     */
    @Test
    void aShiftWithNoEndAtAllReportsNoDuration(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 10, "20.00", null);
        ticket.cashier = cashier("mdupont");
        ticket.session = session("S1", LocalDateTime.of(2026, 3, 17, 9, 0), null);
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(ticket), List.of());

        List<String> rows = Files.readAllLines(
                service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY));
        Assertions.assertEquals("2026-03-17;S1;mdupont;CA01;1;10;20.00;0;0.00;20.00", rows.get(1));
    }

    /** A ticket with no tax-included total folds to zero rather than failing. */
    @Test
    void aTicketWithoutATotalFoldsToZero(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 10, null, LocalDateTime.of(2026, 3, 17, 10, 0));
        ticket.cashier = cashier("mdupont");
        ticket.session = session("S1", LocalDateTime.of(2026, 3, 17, 9, 0),
                LocalDateTime.of(2026, 3, 17, 10, 0));
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(ticket), List.of());

        List<String> rows = Files.readAllLines(
                service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY));
        Assertions.assertEquals("2026-03-17;S1;mdupont;CA01;1;10;0.00;60;10.00;0.00", rows.get(1));
    }

    /** A shift with no session number at all is keyed on a blank, not on null. */
    @Test
    void aSessionWithoutANumberIsKeyedOnABlank(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 10, "20.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        ticket.cashier = cashier("mdupont");
        ticket.session = session(null, LocalDateTime.of(2026, 3, 17, 9, 0),
                LocalDateTime.of(2026, 3, 17, 10, 0));
        OutboundFlowService service = serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(ticket), List.of());

        List<String> rows = Files.readAllLines(
                service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY));
        Assertions.assertEquals("2026-03-17;;mdupont;CA01;1;10;20.00;60;10.00;20.00", rows.get(1));
    }

    /**
     * The FIDELITY/VAT flow is MONTHLY: its file is stamped {@code yyyyMM}, it
     * reads the month's tickets and it ventilates each settlement over the rates
     * of its own ticket, the last rate absorbing the residue.
     */
    @Test
    void produceWritesTheMonthlyFidelityVatFlow(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 2, "30.00", null);
        ticket.lines.add(line("0.2000", "20.00", false));
        ticket.lines.add(line("0.0550", "10.00", false));
        ticket.payments.add(payment("FIDELITY", "9.00"));
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.FIDELITY_VAT, ""), List.of(), List.of(ticket), List.of(),
                List.of(), List.of());

        Path written = service.produce(OutboundFlow.FIDELITY_VAT, DAY);
        Assertions.assertEquals("fidtva-202603.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("MOIS;TAUX_TVA;REGLEMENT_TTC;REGLEMENT_HT;TVA;NB_TICKETS",
                rows.get(0));
        // 9.00 over a 30.00 basket: 3.00 at 5.5% (10/30), the rest at 20%.
        Assertions.assertEquals("2026-03;0.0550;3.00;2.84;0.16;1", rows.get(1));
        Assertions.assertEquals("2026-03;0.2000;6.00;5.00;1.00;1", rows.get(2));
        Assertions.assertEquals("2026-03;TOTAL;9.00;7.84;1.16;2", rows.get(3));
    }

    /**
     * The residue of an unsplittable ventilation lands on the LAST rate, so the
     * flow sums back exactly to what was settled: 10.00 over three equal thirds is
     * 3.33 + 3.33 + 3.34, never 9.99.
     */
    @Test
    void theFidelityVentilationSumsBackToWhatWasSettled(@TempDir Path directory)
            throws IOException {
        Ticket ticket = ticket("T1", "CA01", 3, "30.00", null);
        ticket.lines.add(line("0.0550", "10.00", false));
        ticket.lines.add(line("0.1000", "10.00", false));
        ticket.lines.add(line("0.2000", "10.00", false));
        ticket.payments.add(payment("FIDELITY", "10.00"));
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.FIDELITY_VAT, ""), List.of(), List.of(ticket), List.of(),
                List.of(), List.of());

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.FIDELITY_VAT, DAY));
        Assertions.assertTrue(rows.get(1).startsWith("2026-03;0.0550;3.33;"));
        Assertions.assertTrue(rows.get(2).startsWith("2026-03;0.1000;3.33;"));
        Assertions.assertTrue(rows.get(3).startsWith("2026-03;0.2000;3.34;"));
        Assertions.assertTrue(rows.get(4).startsWith("2026-03;TOTAL;10.00;"));
    }

    /**
     * A ticket settled without fidelity is skipped, a fidelity payment with no
     * amount counts for nothing, and a settlement on a ticket with NO taxable base
     * lands on the zero rate rather than being dropped.
     */
    @Test
    void theFidelityFlowSkipsWhatItCannotVentilate(@TempDir Path directory) throws IOException {
        Ticket paidInCash = ticket("T1", "CA01", 1, "12.00", null);
        paidInCash.lines.add(line("0.2000", "12.00", false));
        paidInCash.payments.add(payment("CASH", "12.00"));
        Ticket emptyFidelity = ticket("T2", "CA01", 1, "12.00", null);
        emptyFidelity.lines.add(line("0.2000", "12.00", false));
        emptyFidelity.payments.add(payment("FIDELITY", null));
        Ticket noBase = ticket("T3", "CA01", 0, "0.00", null);
        noBase.lines.add(line("0.2000", "5.00", true));
        noBase.payments.add(payment("FIDELITY", "5.00"));
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.FIDELITY_VAT, ""), List.of(),
                List.of(paidInCash, emptyFidelity, noBase), List.of(), List.of(), List.of());

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.FIDELITY_VAT, DAY));
        Assertions.assertEquals("2026-03;0.0000;5.00;5.00;0.00;1", rows.get(1));
        Assertions.assertEquals("2026-03;TOTAL;5.00;5.00;0.00;1", rows.get(2));
        Assertions.assertEquals(3, rows.size());
    }

    /** A month without a single closed ticket produces no monthly file. */
    @Test
    void theMonthlyFlowProducesNothingForAnEmptyMonth(@TempDir Path directory)
            throws IOException {
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.FIDELITY_VAT, ""), List.of(new Ticket()), List.of(), List.of(),
                List.of(), List.of());
        Assertions.assertNull(service.produce(OutboundFlow.FIDELITY_VAT, DAY));
    }

    /**
     * The CUSTOMER-INVOICE flow writes a FLAT file: one H record per document and
     * one L record per billed line, each field at its own offset, the cancelled
     * line left out.
     */
    @Test
    void produceWritesTheCustomerInvoiceFlatFile(@TempDir Path directory) throws IOException {
        Invoice document = invoice("FA-001", DocumentType.FACTURE,
                LocalDateTime.of(2026, 3, 17, 18, 30), "100.00", "120.00", "20.00", "1.50");
        document.terminalId = "CA01";
        document.ticketNumber = "T1";
        document.customerAccountNumber = "C0001";
        document.customerName = "SARL DUPONT";
        document.customerContact = "Marc Dupont";
        document.customerAddress = new com.intermarche.edge.domain.store.Address(
                "1 rue des Lilas", "Batiment B", "92420", "Vaucresson", "FRA", null, null);
        document.customerSiret = "12345678901234";
        document.customerVatNumber = "FR12345678901";
        document.customerTaxId = "999999990";
        document.customerDueDate = LocalDate.of(2026, 4, 30);
        Ticket ticket = ticket("T1", "CA01", 1, "120.00", null);
        TicketLine sold = saleLine("100", "EPICERIE", "2", "60.00", null, "120.00");
        sold.lineNumber = 1;
        sold.ean = "3250390000000";
        sold.plu = "4011";
        sold.productLabel = "CAFE";
        ticket.lines.add(sold);
        TicketLine cancelled = saleLine("100", "EPICERIE", "1", "6.00", null, "6.00");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        com.intermarche.edge.domain.payment.CardPayment card =
                new com.intermarche.edge.domain.payment.CardPayment(new BigDecimal("100.00"));
        card.paymentIndex = 1;
        card.authorizationNumber = "A12345";
        ticket.payments.add(card);
        TicketPayment credit = mock(TicketPayment.class);
        when(credit.getMethodKey()).thenReturn("CREDIT");
        credit.paymentIndex = 2;
        credit.tenderCode = "COMPTE";
        credit.amount = new BigDecimal("20.00");
        ticket.payments.add(credit);
        document.ticket = ticket;
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.CUSTOMER_INVOICES, ""), List.of(), List.of(), List.of(document),
                List.of(), List.of());

        Path written = service.produce(OutboundFlow.CUSTOMER_INVOICES, DAY);
        Assertions.assertEquals("clifact-20260317.dat", written.getFileName().toString());
        List<String> records = Files.readAllLines(written);
        Assertions.assertEquals(1, records.size());
        String header = records.get(0);
        Assertions.assertEquals("H", header.substring(0, 1));
        Assertions.assertEquals("FA-001              ", header.substring(1, 21));
        Assertions.assertEquals("F", header.substring(21, 22));
        Assertions.assertEquals("202603171830", header.substring(22, 34));
        Assertions.assertEquals("CA01      ", header.substring(34, 44));
        // The eco-tax closes the record, in cents, zero-filled on 13 characters.
        Assertions.assertTrue(header.endsWith("0000000000150"));
        Assertions.assertTrue(header.contains("SARL DUPONT"));
        Assertions.assertTrue(header.contains("Vaucresson"));
        Assertions.assertTrue(header.contains("20260430"));
        List<String> lines = Files.readAllLines(directory.resolve("clilfact-20260317.dat"));
        Assertions.assertEquals(1, lines.size());
        String detail = lines.get(0);
        Assertions.assertEquals("L", detail.substring(0, 1));
        Assertions.assertEquals("FA-001              ", detail.substring(1, 21));
        Assertions.assertEquals("00001", detail.substring(21, 26));
        Assertions.assertEquals("3250390000000", detail.substring(26, 39));
        // The rate travels in basis points: 0.2000 is 2000.
        Assertions.assertTrue(detail.contains("002000"));
        Assertions.assertTrue(detail.endsWith("0000000012000"));
        // cliregl.dat: one R record per settlement, the card one with its authorisation.
        List<String> settlements = Files.readAllLines(directory.resolve("cliregl-20260317.dat"));
        Assertions.assertEquals(2, settlements.size());
        Assertions.assertEquals("R" + "FA-001" + " ".repeat(14) + "001CARD" + " ".repeat(8) + " ".repeat(20) + "0000000010000A12345      ",
                settlements.get(0));
        Assertions.assertEquals("R" + "FA-001" + " ".repeat(14) + "002CREDIT" + " ".repeat(6) + "COMPTE" + " ".repeat(14) + "0000000002000"
                + " ".repeat(12), settlements.get(1));
        // clitva.dat: one T record per rate of the billed lines, then the eco-tax record.
        List<String> vat = Files.readAllLines(directory.resolve("clitva-20260317.dat"));
        Assertions.assertEquals(List.of("T" + "FA-001" + " ".repeat(14) + "002000" + "0000000010000"
                + "0000000002000" + "0000000012000", "E" + "FA-001" + " ".repeat(14) + "0000000000150"), vat);
    }

    /**
     * Without the eco-tax administered, clitva.dat holds the VAT records alone;
     * a document whose ticket carries no line states its own totals on a rate
     * of its own.
     */
    @Test
    void theEcoTaxRecordFollowsTheParameter(@TempDir Path directory) throws IOException {
        Invoice document = invoice("FA-009", DocumentType.FACTURE, null, "10.00", "12.00", "2.00", "0.50");
        document.ticket = ticket("T1", "CA01", 0, "12.00", null);
        PosSettingsService settings = settings(directory.toString(), OutboundFlow.CUSTOMER_INVOICES, "");
        when(settings.invoiceExportEcoTax()).thenReturn(false);
        OutboundFlowService service = wire(settings, List.of(), List.of(), List.of(document),
                List.of(), List.of());
        service.produce(OutboundFlow.CUSTOMER_INVOICES, DAY);
        Assertions.assertEquals(List.of("T" + "FA-009" + " ".repeat(14) + "000000" + "0000000001000"
                + "0000000000200" + "0000000001200"),
                Files.readAllLines(directory.resolve("clitva-20260317.dat")));
        Assertions.assertEquals("", Files.readString(directory.resolve("clilfact-20260317.dat")));
        Assertions.assertEquals("", Files.readString(directory.resolve("cliregl-20260317.dat")));
    }

    /**
     * A document whose ticket is no longer attached exports its HEADER alone: a
     * missing header is a missing receivable, which is worse than a header without
     * its lines.
     */
    @Test
    void anInvoiceWithoutItsTicketExportsItsHeaderAlone(@TempDir Path directory)
            throws IOException {
        Invoice document = invoice("FA-002", null, null, null, null, null, null);
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.CUSTOMER_INVOICES, ""), List.of(), List.of(), List.of(document),
                List.of(), List.of());

        List<String> records = Files.readAllLines(
                service.produce(OutboundFlow.CUSTOMER_INVOICES, DAY));
        Assertions.assertEquals(1, records.size());
        Assertions.assertEquals(List.of("T" + "FA-002" + " ".repeat(14) + "000000" + "0".repeat(39),
                "E" + "FA-002" + " ".repeat(14) + "0".repeat(13)),
                Files.readAllLines(directory.resolve("clitva-20260317.dat")));
        Assertions.assertEquals("H", records.get(0).substring(0, 1));
        // No type, no issue instant, no address, no total: blanks and zeros, and
        // every following field still found at its own offset.
        Assertions.assertEquals(" ", records.get(0).substring(21, 22));
        Assertions.assertEquals("            ", records.get(0).substring(22, 34));
        Assertions.assertTrue(records.get(0).endsWith("0000000000000"));
    }

    /** The civility comes from the customer sheet, and its absence is a blank. */
    @Test
    void theCivilityComesFromTheCustomerSheet(@TempDir Path directory) throws IOException {
        Invoice withCustomer = invoice("FA-003", DocumentType.BON_LIVRAISON, null,
                "10.00", "12.00", "2.00", "0.00");
        withCustomer.customer = new com.intermarche.edge.domain.payment.AccountCustomer();
        withCustomer.customer.civility = "MME";
        Invoice withoutCustomer = invoice("FA-004", DocumentType.FACTURE, null,
                "10.00", "12.00", "2.00", "0.00");
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.CUSTOMER_INVOICES, ""), List.of(), List.of(),
                List.of(withCustomer, withoutCustomer), List.of(), List.of());

        List<String> records = Files.readAllLines(
                service.produce(OutboundFlow.CUSTOMER_INVOICES, DAY));
        Assertions.assertEquals("L", records.get(0).substring(21, 22));
        Assertions.assertEquals("MME       ", records.get(0).substring(89, 99));
        Assertions.assertEquals("          ", records.get(1).substring(89, 99));
    }

    /** A day without a single document produces neither invoice file. */
    @Test
    void theInvoiceFlowsProduceNothingForADayWithoutADocument(@TempDir Path directory)
            throws IOException {
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.CUSTOMER_INVOICES, ""), List.of(new Ticket()), List.of(),
                List.of(), List.of(), List.of());
        Assertions.assertNull(service.produce(OutboundFlow.CUSTOMER_INVOICES, DAY));
        OutboundFlowService erp = wire(settings(directory.toString(),
                OutboundFlow.ERP_INVOICES, ""), List.of(new Ticket()), List.of(),
                List.of(), List.of(), List.of());
        Assertions.assertNull(erp.produce(OutboundFlow.ERP_INVOICES, DAY));
    }

    /**
     * The ERP flow writes one row per document, the duplicate flagged, and closes
     * with a TOTAL row counting the documents.
     */
    @Test
    void produceWritesTheErpInvoiceFlow(@TempDir Path directory) throws IOException {
        Invoice first = invoice("FA-001", DocumentType.FACTURE,
                LocalDateTime.of(2026, 3, 17, 18, 30), "100.00", "120.00", "20.00", "1.50");
        first.terminalId = "CA01";
        first.ticketNumber = "T1";
        first.customerAccountNumber = "C0001";
        first.customerName = "SARL DUPONT";
        first.customerSiret = "12345678901234";
        first.customerVatNumber = "FR12345678901";
        first.customerTaxId = "999999990";
        first.customerDueDate = LocalDate.of(2026, 4, 30);
        first.printCount = 2;
        Invoice second = invoice("BL-001", DocumentType.BON_LIVRAISON, null,
                null, null, null, null);
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.ERP_INVOICES, ""), List.of(), List.of(), List.of(first, second),
                List.of(), List.of());

        Path written = service.produce(OutboundFlow.ERP_INVOICES, DAY);
        Assertions.assertEquals("facterp-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;NUM_DOCUMENT;TYPE;DATE_EMISSION;TERMINAL;NUM_TICKET;"
                + "COMPTE_CLIENT;NOM_CLIENT;SIRET;TVA_INTRA;NIF;ECHEANCE;TOTAL_HT;TOTAL_TTC;"
                + "TVA;ECOTAXE;DUPLICATA", rows.get(0));
        Assertions.assertEquals("2026-03-17;FA-001;FACTURE;2026-03-17T18:30:00;CA01;T1;C0001;"
                + "SARL DUPONT;12345678901234;FR12345678901;999999990;2026-04-30;100.00;"
                + "120.00;20.00;1.50;O", rows.get(1));
        // Nothing but its number and type: every other column empty or zero, N on
        // the duplicate flag since it was never printed.
        Assertions.assertEquals("2026-03-17;BL-001;BON_LIVRAISON;;;;;;;;;;0.00;0.00;0.00;0.00;N",
                rows.get(2));
        Assertions.assertEquals("2026-03-17;TOTAL;;;;;;;;;;;100.00;120.00;20.00;1.50;2",
                rows.get(3));
    }

    /**
     * The ORDER-VAT flow ventilates the day's orders by rate, counts the lines and
     * closes with a TOTAL row counting the orders.
     */
    @Test
    void produceWritesTheOrderVatFlow(@TempDir Path directory) throws IOException {
        CustomerOrder first = order("CMD1", "36.00", "0.2000", "24.00", "0.0550", "12.00");
        CustomerOrder second = order("CMD2", "12.00", "0.2000", "12.00");
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.ORDER_VAT, ""), List.of(), List.of(), List.of(),
                List.of(first, second), List.of());

        Path written = service.produce(OutboundFlow.ORDER_VAT, DAY);
        Assertions.assertEquals("tvacmde-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals("JOURNEE;TAUX_TVA;TOTAL_TTC;TOTAL_HT;TVA;NB_LIGNES", rows.get(0));
        Assertions.assertEquals("2026-03-17;0.0550;12.00;11.37;0.63;1", rows.get(1));
        Assertions.assertEquals("2026-03-17;0.2000;36.00;30.00;6.00;2", rows.get(2));
        Assertions.assertEquals("2026-03-17;TOTAL;48.00;41.37;6.63;2", rows.get(3));
    }

    /**
     * An order with NO line detail is counted whole at the zero rate, and a line
     * with no rate or no total folds to zero rather than failing.
     */
    @Test
    void anOrderWithoutLinesIsCountedWholeAtTheZeroRate(@TempDir Path directory)
            throws IOException {
        CustomerOrder bare = order("CMD1", "50.00");
        CustomerOrder incomplete = order("CMD2", "12.00", null, "12.00", "0.2000", null);
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.ORDER_VAT, ""), List.of(), List.of(), List.of(),
                List.of(bare, incomplete), List.of());

        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.ORDER_VAT, DAY));
        // 50.00 of the bare order and 12.00 of the rate-less line, at rate zero.
        Assertions.assertEquals("2026-03-17;0.0000;62.00;62.00;0.00;2", rows.get(1));
        Assertions.assertEquals("2026-03-17;0.2000;0.00;0.00;0.00;1", rows.get(2));
        Assertions.assertEquals("2026-03-17;TOTAL;62.00;62.00;0.00;2", rows.get(3));
    }

    /** A day without an order produces no ventilation. */
    @Test
    void theOrderFlowProducesNothingForADayWithoutAnOrder(@TempDir Path directory)
            throws IOException {
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.ORDER_VAT, ""), List.of(new Ticket()), List.of(), List.of(),
                List.of(), List.of());
        Assertions.assertNull(service.produce(OutboundFlow.ORDER_VAT, DAY));
    }

    /**
     * The PANEL flow writes ONE archive holding the three members the RFP names,
     * each suffixed by the three-digit day of the year, and the archive itself is
     * named after the node's PDV.
     */
    @Test
    void produceWritesThePanelArchive(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 3, "24.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        TicketLine sold = saleLine("100", "EPICERIE", "2", "6.00", null, "12.00");
        sold.ean = "3250390000000";
        ticket.lines.add(sold);
        ticket.lines.add(line("0.2000", "99.00", true));
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.PANELS, "12345"), List.of(ticket), List.of(), List.of(),
                List.of(), List.of());

        Path written = service.produce(OutboundFlow.PANELS, DAY);
        Assertions.assertEquals("MS.12345.076.zip", written.getFileName().toString());
        Map<String, String> members = unzip(written);
        Assertions.assertEquals(List.of("Scannels.076", "Scvenmag.076", "Tlogextr.076"),
                List.copyOf(members.keySet()));
        Assertions.assertTrue(members.get("Scannels.076")
                .contains("2026-03-17;3250390000000;2.000;12.00"));
        // The cancelled line is out of the scan panel and out of the synthesis.
        Assertions.assertFalse(members.get("Scannels.076").contains("99.00"));
        Assertions.assertTrue(members.get("Scvenmag.076")
                .contains("2026-03-17;1;3;12.00;10.00;2.00"));
        Assertions.assertTrue(members.get("Tlogextr.076")
                .contains("2026-03-17;T1;2026-03-17T10:00:00;CA01;3;24.00"));
    }

    /**
     * A node that does not know its PDV falls back on the catalogue's plain name
     * rather than writing the word "null" into a name the other side matches on.
     */
    @Test
    void thePanelArchiveFallsBackWhenThePdvIsUnknown(@TempDir Path directory)
            throws IOException {
        Ticket ticket = ticket("T1", "CA01", 1, "12.00", null);
        ticket.lines.add(saleLine("100", "EPICERIE", "1", "12.00", null, "12.00"));
        OutboundFlowService service = wire(settings(directory.toString(),
                OutboundFlow.PANELS, ""), List.of(ticket), List.of(), List.of(), List.of(),
                List.of());

        Path written = service.produce(OutboundFlow.PANELS, DAY);
        Assertions.assertEquals("MS-20260317.zip", written.getFileName().toString());
        Assertions.assertEquals(3, unzip(written).size());
    }

    /**
     * Reads back the members of an archive.
     *
     * @param archive the archive written by a flow
     * @return the content of each member, keyed by its name, in archive order
     * @throws IOException when the archive cannot be read
     */
    private Map<String, String> unzip(Path archive) throws IOException {
        Map<String, String> members = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                members.put(entry.getName(),
                        new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                entry = zip.getNextEntry();
            }
        }
        return members;
    }

    /**
     * Every flow of the catalogue produces its own file when all are activated and
     * every source has something in it — the monthly one included, and the panel
     * archive under its own name.
     */
    @Test
    void produceAllWritesOneFilePerActivatedFlow(@TempDir Path directory) throws IOException {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.exportDirectory()).thenReturn(directory.toString());
        when(settings.accountingCutoverHour()).thenReturn(0);
        when(settings.pdvNumber()).thenReturn("12345");
        when(settings.exportRevenueEnabled()).thenReturn(true);
        when(settings.exportCashBookEnabled()).thenReturn(true);
        when(settings.exportFamilySalesEnabled()).thenReturn(true);
        when(settings.exportTransactionsEnabled()).thenReturn(true);
        when(settings.exportPeriodSummaryEnabled()).thenReturn(true);
        when(settings.exportProductivityEnabled()).thenReturn(true);
        when(settings.exportCustomerInvoicesEnabled()).thenReturn(true);
        when(settings.exportErpInvoicesEnabled()).thenReturn(true);
        when(settings.exportFidelityVatEnabled()).thenReturn(true);
        when(settings.exportOrderVatEnabled()).thenReturn(true);
        when(settings.exportPanelsEnabled()).thenReturn(true);
        when(settings.planexaExportEnabled()).thenReturn(true);
        when(settings.exportGs1PaymentsEnabled()).thenReturn(true);
        when(settings.exportVignettesEnabled()).thenReturn(true);
        when(settings.vignetteAmountStep()).thenReturn(new BigDecimal("10.00"));
        when(settings.exportUpstreamOffers()).thenReturn(List.of());
        Ticket ticket = ticket("T1", "CA01", 1, "12.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        ticket.cashier = cashier("mdupont");
        ticket.lines.add(saleLine("100", "EPICERIE", "1", "12.00", null, "12.00"));
        ticket.payments.add(payment("FIDELITY", "12.00"));
        OutboundFlowService service = wire(settings, List.of(ticket), List.of(ticket),
                List.of(invoice("FA-001", DocumentType.FACTURE, null, "10.00", "12.00",
                        "2.00", "0.00")),
                List.of(order("CMD1", "12.00", "0.2000", "12.00")),
                List.of(point("CA01", "ILE1")));

        List<Path> written = service.produceAll(DAY);
        Assertions.assertEquals(OutboundFlow.values().length, written.size());
        for (OutboundFlow flow : OutboundFlow.values()) {
            String expected = flow == OutboundFlow.PANELS
                    ? "MS.12345.076.zip"
                    : flow.fileName(DAY);
            Assertions.assertTrue(written.stream().anyMatch(path ->
                            path.getFileName().toString().equals(expected)),
                    "missing file for " + flow);
        }
    }

    /** {@code produceAll} returns only the files it actually wrote. */
    @Test
    void produceAllSkipsTheFlowsThatAreNotActivated(@TempDir Path directory) throws IOException {
        Ticket ticket = new Ticket();
        ticket.lines.add(line("0.2000", "12.00", false));
        OutboundFlowService service = service(directory.toString(), true, false, List.of(ticket));
        List<Path> written = service.produceAll(DAY);
        Assertions.assertEquals(1, written.size());
        Assertions.assertEquals("tranca3-20260317.csv", written.get(0).getFileName().toString());
    }

    /** The loop does nothing when the finished day has already been produced. */
    @Test
    void tickDoesNothingWhenTheDayIsAlreadyProduced(@TempDir Path directory) throws IOException {
        OutboundFlowService service = service(directory.toString(), true, true, List.of(new Ticket()));
        service.lastProducedDay = LocalDate.now().minusDays(1);
        service.tick();
        Assertions.assertEquals(0, Files.list(directory).count());
    }

    /** The loop produces the day that has just ended, and remembers it. */
    @Test
    void tickProducesTheFinishedDayAndRemembersIt(@TempDir Path directory) {
        Ticket ticket = new Ticket();
        ticket.lines.add(line("0.2000", "12.00", false));
        OutboundFlowService service = service(directory.toString(), true, false, List.of(ticket));
        service.tick();
        Assertions.assertEquals(LocalDate.now().minusDays(1), service.lastProducedDay);
    }

    /**
     * A write failure does NOT mark the day as produced, so the next examination
     * tries again — and {@code tickSafely} swallows it rather than letting the
     * scheduler cancel the loop for the rest of the day.
     */
    @Test
    void aWriteFailureIsRetriedAndNeverKillsTheLoop() {
        Ticket ticket = new Ticket();
        ticket.lines.add(line("0.2000", "12.00", false));
        // A destination that cannot be a directory: an existing FILE's child.
        OutboundFlowService service = service("/dev/null/nowhere", true, false, List.of(ticket));
        service.tickSafely();
        Assertions.assertNull(service.lastProducedDay);
    }

    /** The accounting day of the loop follows the administered cut-over hour. */
    @Test
    void theLoopFollowsTheAdministeredCutover() {
        OutboundFlowService service = service("", true, true, List.of());
        Assertions.assertEquals(LocalDate.now(),
                service.accountingDay.of(LocalDateTime.now()));
    }
    /** BO-11-01: incomplete invoice coordinates and type still yield a complete flat header. */
    @Test
    void invoiceExportsTolerateAbsentAddressAndDocumentType() throws Exception {
        OutboundFlowService service = new OutboundFlowService();
        Invoice invoice = invoice("FA-NULL", null, null, null, null, null, null);
        invoice.customerAddress = null;
        var method = OutboundFlowService.class.getDeclaredMethod("invoiceHeader", Invoice.class);
        method.setAccessible(true);
        String header = (String) method.invoke(service, invoice);
        Assertions.assertTrue(header.startsWith("HFA-NULL"));
        Assertions.assertFalse(header.contains("null"));
        method = OutboundFlowService.class.getDeclaredMethod("erpInvoiceRows", LocalDate.class, List.class);
        method.setAccessible(true);
        var rows = (List<?>) method.invoke(service, DAY, List.of(invoice));
        Assertions.assertEquals(3, rows.size());
        Assertions.assertTrue(rows.get(1).toString().contains("FA-NULL"));
        Assertions.assertTrue(rows.get(2).toString().contains(";TOTAL;"));
    }

    /** BO-11-01: fidelity VAT allocation skips missing amounts and treats an absent rate as zero. */
    @Test
    void allocationHandlesMissingLineAmountsAndRates() throws Exception {
        OutboundFlowService service = new OutboundFlowService();
        Ticket ticket = ticket("T1", "C04", 1, "10.00", null);
        TicketLine missing = line("0.2000", "1.00", false);
        missing.totalPrice = null;
        TicketLine zeroRated = line("0.2000", "10.00", false);
        zeroRated.vatRate = null;
        ticket.lines.addAll(List.of(missing, zeroRated));
        var method = OutboundFlowService.class.getDeclaredMethod("ventilate", BigDecimal.class, Ticket.class);
        method.setAccessible(true);
        Assertions.assertEquals(Map.of(BigDecimal.ZERO, new BigDecimal("3.00")), method.invoke(service, new BigDecimal("3.00"), ticket));
        method = OutboundFlowService.class.getDeclaredMethod("basisPoints", BigDecimal.class);
        method.setAccessible(true);
        Assertions.assertEquals(0L, method.invoke(service, (Object) null));
        method = OutboundFlowService.class.getDeclaredMethod("rate", BigDecimal.class);
        method.setAccessible(true);
        Assertions.assertEquals("0.0000", method.invoke(service, (Object) null));
    }

    /** BO-11-03-01: production uses the CDI proxy when available and otherwise retains the local bean. */
    @Test
    void selfResolvesAvailableProxyOrOwnBean() {
        OutboundFlowService service = new OutboundFlowService();
        OutboundFlowService proxy = mock(OutboundFlowService.class);
        var container = mock(io.quarkus.arc.ArcContainer.class);
        io.quarkus.arc.InstanceHandle<OutboundFlowService> handle = mock(io.quarkus.arc.InstanceHandle.class);
        when(container.instance(OutboundFlowService.class)).thenReturn(handle);
        try (var arc = org.mockito.Mockito.mockStatic(io.quarkus.arc.Arc.class)) {
            arc.when(io.quarkus.arc.Arc::container).thenReturn(container);
            Assertions.assertSame(service, service.self());
            when(handle.get()).thenReturn(proxy);
            Assertions.assertSame(proxy, service.self());
        }
    }

    /** BO-11-01: an empty panel day creates no archive and a completed daily pass records produced files once. */
    @Test
    void panelAbsenceAndDailyProductionKeepTheirBoundaries(@TempDir Path directory) throws Exception {
        OutboundFlowService empty = serviceFor(directory.toString(), OutboundFlow.PANELS, List.of(), List.of());
        Assertions.assertNull(empty.produce(OutboundFlow.PANELS, DAY));
        Ticket ticket = ticket("T1", "C04", 1, "12.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        ticket.lines.add(line("0.2000", "12.00", false));
        OutboundFlowService enabled = service(directory.toString(), true, false, List.of(ticket));
        enabled.tick();
        Assertions.assertEquals(enabled.accountingDay.of(LocalDateTime.now()).minusDays(1), enabled.lastProducedDay);
        Assertions.assertTrue(Files.isRegularFile(directory.resolve(OutboundFlow.REVENUE.fileName(enabled.lastProducedDay))));
    }


    /** BO-11-01: a day with no enabled output is still marked complete, preventing an endless retry loop. */
    @Test
    void dailyPassWithNoFilesIsStillCompleted() throws Exception {
        var service = new OutboundFlowService();
        service.accountingDay = mock(AccountingDay.class);
        when(service.accountingDay.of(org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(DAY);
        var proxy = mock(OutboundFlowService.class);
        when(proxy.produceAll(DAY.minusDays(1))).thenReturn(List.of());
        var container = mock(io.quarkus.arc.ArcContainer.class);
        io.quarkus.arc.InstanceHandle<OutboundFlowService> handle = mock(io.quarkus.arc.InstanceHandle.class);
        when(container.instance(OutboundFlowService.class)).thenReturn(handle);
        when(handle.get()).thenReturn(proxy);
        try (var arc = org.mockito.Mockito.mockStatic(io.quarkus.arc.Arc.class)) {
            arc.when(io.quarkus.arc.Arc::container).thenReturn(container);
            service.tick();
            service.tick();
        }
        Assertions.assertEquals(DAY.minusDays(1), service.lastProducedDay);
        org.mockito.Mockito.verify(proxy).produceAll(DAY.minusDays(1));
    }

    /** BO-11: startup installs one daemon worker at the administered cadence without performing an export. */
    @org.junit.jupiter.api.Test
    void startupUsesOneDaemonAtTheAdministeredCadence() {
        var service = new OutboundFlowService();
        service.tickSeconds = 23;
        var executor = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        var factory = org.mockito.ArgumentCaptor.forClass(java.util.concurrent.ThreadFactory.class);
        try (var executors = org.mockito.Mockito.mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(org.mockito.ArgumentMatchers.any(java.util.concurrent.ThreadFactory.class))).thenReturn(executor);
            service.onStart(new io.quarkus.runtime.StartupEvent());
            executors.verify(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(factory.capture()));
            var thread = factory.getValue().newThread(() -> { });
            org.junit.jupiter.api.Assertions.assertEquals("outbound-flows", thread.getName());
            org.junit.jupiter.api.Assertions.assertTrue(thread.isDaemon());
            org.junit.jupiter.api.Assertions.assertEquals(Thread.State.NEW, thread.getState());
            org.mockito.Mockito.verify(executor).scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.eq(23L), org.mockito.ArgumentMatchers.eq(23L), org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    /** BO-11-01-02: an unexpected failure outside the export fold is contained and the next scheduler invocation still runs. */
    @Test
    void schedulerSurvivesAnUnexpectedTickFailure() {
        var service = org.mockito.Mockito.spy(new OutboundFlowService());
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable")).doNothing().when(service).tick();
        Assertions.assertDoesNotThrow(service::tickSafely);
        Assertions.assertDoesNotThrow(service::tickSafely);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2)).tick();
    }

    /** BO-11-01-05: persisted exports share exclusive accounting-day bounds, including the first cutover of the next month. */
    @Test
    void persistenceLoadersUseTheAccountingBoundariesAndBusinessDates() {
        var service = new OutboundFlowService();
        service.accountingDay = new AccountingDay();
        service.accountingDay.posSettingsService = mock(PosSettingsService.class);
        when(service.accountingDay.posSettingsService.accountingCutoverHour()).thenReturn(3);
        var day = LocalDate.of(2026, 12, 17);
        var from = LocalDateTime.of(2026, 12, 17, 3, 0);
        var to = LocalDateTime.of(2026, 12, 18, 3, 0);
        var ticketQuery = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        var invoiceQuery = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        var orderQuery = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        var tickets = List.of(new Ticket());
        var invoices = List.of(new Invoice());
        var orders = List.of(new CustomerOrder());
        when(ticketQuery.list()).thenReturn(tickets);
        when(invoiceQuery.list()).thenReturn(invoices);
        when(orderQuery.list()).thenReturn(orders);
        var points = List.of(new CheckoutPoint());
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class);
             var park = org.mockito.Mockito.mockStatic(CheckoutPoint.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("status = ?1 and closingDate >= ?2 and closingDate < ?3 order by closingDate", Ticket.TicketStatus.CLOSED, from, to)).thenReturn(ticketQuery);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("status = ?1 and closingDate >= ?2 and closingDate < ?3 order by closingDate", Ticket.TicketStatus.CLOSED, LocalDateTime.of(2026, 12, 1, 3, 0), LocalDateTime.of(2027, 1, 1, 3, 0))).thenReturn(ticketQuery);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("issueDate >= ?1 and issueDate < ?2 order by issueDate", from, to)).thenReturn(invoiceQuery);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("orderDate >= ?1 and orderDate < ?2 order by orderDate", from, to)).thenReturn(orderQuery);
            park.when(CheckoutPoint::listAllOrdered).thenReturn(points);
            Assertions.assertSame(tickets, service.closedTicketsOf(day));
            Assertions.assertSame(tickets, service.closedTicketsOfMonth(day));
            Assertions.assertSame(invoices, service.invoicesOf(day));
            Assertions.assertSame(orders, service.ordersOf(day));
            Assertions.assertSame(points, service.checkoutPoints());
        }
    }


    /** LC-01-03-01: construct one ordered transition of the named operator. */
    private com.intermarche.edge.domain.session.TechnicalEvent transition(boolean lock, String time) {
        var event = new com.intermarche.edge.domain.session.TechnicalEvent();
        event.eventDate = LocalDateTime.parse("2026-03-17T" + time);
        event.eventType = lock
                ? com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_LOCKED
                : com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_UNLOCKED;
        return event;
    }

    /** LC-01-03-01: subtract completed, duplicate, initial and unfinished pauses; round only the net duration. */
    @Test
    void productivitySubtractsEachPauseWithoutDoubleCounting(@TempDir Path directory) throws IOException {
        var cases = List.of(
                List.<com.intermarche.edge.domain.session.TechnicalEvent>of(),
                List.of(transition(true, "09:30:00"), transition(false, "09:50:00")),
                List.of(transition(true, "09:30:00"), transition(true, "09:40:00"),
                        transition(false, "09:50:00"), transition(false, "10:00:00")),
                List.of(transition(true, "10:30:00")),
                List.of(transition(false, "10:00:00")),
                List.of(transition(true, "09:00:00"), transition(false, "11:00:00")),
                List.of(transition(true, "09:00:20"), transition(false, "09:00:50")),
                List.of(transition(false, "09:00:00"), transition(false, "10:00:00")));
        long[] expected = {120, 100, 100, 90, 60, 0, 119, 120};
        for (int index = 0; index < cases.size(); index++) {
            Ticket sale = ticket("T1", "CA01", 60, "100.00", LocalDateTime.of(2026, 3, 17, 10, 30));
            sale.cashier = cashier("login-different-from-badge");
            sale.cashier.badgeId = "B007";
            sale.session = session("S1", LocalDateTime.of(2026, 3, 17, 9, 0), LocalDateTime.of(2026, 3, 17, 11, 0));
            var service = org.mockito.Mockito.spy(serviceFor(directory.toString(),
                    OutboundFlow.CASHIER_PRODUCTIVITY, List.of(sale), List.of()));
            org.mockito.Mockito.doReturn(cases.get(index)).when(service).pauseEvents("CA01", "B007",
                    sale.session.openingDate, sale.session.closingDate);
            var row = Files.readAllLines(service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY)).get(1).split(";");
            Assertions.assertEquals(String.valueOf(expected[index]), row[7], "case " + index);
            BigDecimal rate = expected[index] == 0 ? BigDecimal.ZERO.setScale(2)
                    : new BigDecimal("3600").divide(BigDecimal.valueOf(expected[index]), 2, java.math.RoundingMode.HALF_UP);
            Assertions.assertEquals(rate.toPlainString(), row[8], "case " + index);
            Assertions.assertEquals("100.00", row[9]);
        }
    }

    /** LC-01-03-01: missing legacy badges retain elapsed time without guessing pause ownership. */
    @Test
    void productivityWithBlankBadgeDoesNotQueryUnrelatedOperators(@TempDir Path directory) throws IOException {
        Ticket sale = ticket("T1", "CA01", 60, "100.00", LocalDateTime.of(2026, 3, 17, 10, 0));
        sale.cashier = cashier("cashier");
        sale.cashier.badgeId = " ";
        sale.session = session("S1", LocalDateTime.of(2026, 3, 17, 9, 0), null);
        var service = org.mockito.Mockito.spy(serviceFor(directory.toString(),
                OutboundFlow.CASHIER_PRODUCTIVITY, List.of(sale), List.of()));
        var row = Files.readAllLines(service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY)).get(1);
        Assertions.assertTrue(row.contains(";60;60.00;100.00"));
        sale.cashier = null;
        var anonymous = Files.readAllLines(service.produce(OutboundFlow.CASHIER_PRODUCTIVITY, DAY)).get(1);
        Assertions.assertTrue(anonymous.contains(";INCONNU;"));
        Assertions.assertTrue(anonymous.contains(";60;60.00;100.00"));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).pauseEvents(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** LC-01-03-01: the journal query isolates terminal/badge and shift bounds, ordered chronologically. */
    @Test
    void pauseQueryUsesBadgeTerminalAndExactShiftBounds() {
        var service = new OutboundFlowService();
        var from = LocalDateTime.of(2026, 3, 17, 9, 0);
        var to = from.plusHours(2);
        var events = List.of(transition(true, "09:30:00"));
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list(
                    "terminalId = ?1 and operatorBadgeId = ?2 and eventDate >= ?3 and eventDate <= ?4"
                            + " and eventType in (?5, ?6) order by eventDate, id",
                    "CA01", "B007", from, to,
                    com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_LOCKED,
                    com.intermarche.edge.domain.session.TechnicalEvent.EventType.REGISTER_UNLOCKED)).thenReturn(events);
            Assertions.assertSame(events, service.pauseEvents("CA01", "B007", from, to));
        }
    }

    /**
     * BO-03-10-06: the Planexa flow counts per register and per hour slot, the
     * registers of an excluded island left out, a ticket without closing hour
     * and without amount counted in an empty slot.
     */
    @Test
    void produceWritesThePlanexaFlow(@TempDir Path directory) throws IOException {
        Ticket morning = ticket("T1", "CA01", 2, "24.00", LocalDateTime.of(2026, 3, 17, 9, 15));
        Ticket alsoMorning = ticket("T2", "CA01", 1, "6.00", LocalDateTime.of(2026, 3, 17, 9, 45));
        Ticket drive = ticket("T3", "DR01", 5, "50.00", LocalDateTime.of(2026, 3, 17, 9, 30));
        Ticket undated = ticket("T4", "CA02", 0, null, null);
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.PLANEXA,
                List.of(morning, alsoMorning, drive, undated),
                List.of(point("CA01", "AVANT"), point("DR01", "DRIVE")));
        Path written = service.produce(OutboundFlow.PLANEXA, DAY);
        Assertions.assertNotNull(written);
        Assertions.assertEquals("planexa-20260317.csv", written.getFileName().toString());
        List<String> rows = Files.readAllLines(written);
        Assertions.assertEquals(List.of(
                "JOURNEE;TERMINAL;CODE_ILOT;TRANCHE;NB_TICKETS;NB_ARTICLES;TOTAL_TTC",
                "2026-03-17;CA01;AVANT;09:00;2;3;30.00",
                "2026-03-17;CA02;;;1;0;0.00",
                "2026-03-17;TOTAL;;;3;3;30.00"), rows);
    }

    /** BO-03-10-06: the excluded islands are read from the islands flagged so. */
    @Test
    void planexaExcludedIslandsReadsTheFlag() {
        com.intermarche.edge.domain.store.CheckoutIsland drive = new com.intermarche.edge.domain.store.CheckoutIsland();
        drive.code = "DRIVE";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.store.CheckoutIsland.list("planexaExcluded = true"))
                    .thenReturn(List.of(drive));
            Assertions.assertEquals(java.util.Set.of("DRIVE"), new OutboundFlowService().planexaExcludedIslands());
        }
    }

    /**
     * The REVENUE flow states an exempt line under EXO and a third-party sale
     * on a COMPTE_TIERS row without VAT, both counted in the total takings and
     * the third party's VAT kept out of the store's (BO-02-03-26, -43).
     */
    @Test
    void theRevenueFlowSeparatesExemptAndThirdPartyLines(@TempDir Path directory) throws IOException {
        Ticket ticket = new Ticket();
        ticket.lines.add(line("0.2000", "12.00", false));
        TicketLine exempt = line("0.0000", "5.00", false);
        exempt.vatExempt = true;
        ticket.lines.add(exempt);
        TicketLine third = line("0.2000", "6.00", false);
        third.thirdPartySale = true;
        ticket.lines.add(third);
        TicketLine thirdWithoutTotal = line("0.2000", null, false);
        thirdWithoutTotal.thirdPartySale = true;
        ticket.lines.add(thirdWithoutTotal);
        OutboundFlowService service = service(directory.toString(), true, false, List.of(ticket));
        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.REVENUE, DAY));
        Assertions.assertTrue(rows.contains("2026-03-17;EXO;5.00;5.00;0.00;1"), rows.toString());
        Assertions.assertTrue(rows.contains("2026-03-17;COMPTE_TIERS;6.00;6.00;0.00;1"), rows.toString());
        Assertions.assertEquals("2026-03-17;TOTAL;23.00;21.00;2.00;1", rows.get(rows.size() - 1));
    }

    /**
     * The CASH-BOOK flow states the VAT collected for third parties and the
     * takings net of it; a cancelled third-party line collects nothing
     * (BO-02-03-43).
     */
    @Test
    void theCashBookStatesTheThirdPartyVat(@TempDir Path directory) throws IOException {
        Ticket ticket = new Ticket();
        ticket.payments.add(payment("CASH", "18.00"));
        ticket.lines.add(line("0.2000", "12.00", false));
        TicketLine third = line("0.2000", "6.00", false);
        third.thirdPartySale = true;
        ticket.lines.add(third);
        TicketLine cancelled = line("0.2000", "6.00", true);
        cancelled.thirdPartySale = true;
        ticket.lines.add(cancelled);
        OutboundFlowService service = service(directory.toString(), false, true, List.of(ticket));
        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.CASH_BOOK, DAY));
        Assertions.assertTrue(rows.contains("2026-03-17;TOTAL;18.00;1"), rows.toString());
        Assertions.assertTrue(rows.contains("2026-03-17;TVA_COMPTE_TIERS;-1.00;0"), rows.toString());
        Assertions.assertEquals("2026-03-17;TOTAL_HORS_TVA_TIERS;17.00;1", rows.get(rows.size() - 1));
    }

    /**
     * Builds an engine valuation of one line.
     *
     * @param ticketId the ticket's id
     * @param lineUid the line's uid
     * @param amount the advantage allocated, possibly null
     * @param offer the offer label, possibly null
     * @return the valuation
     */
    private com.intermarche.edge.domain.sale.TicketLineValuation valuation(Long ticketId, String lineUid,
            String amount, String offer) {
        com.intermarche.edge.domain.sale.TicketLineValuation valuation =
                new com.intermarche.edge.domain.sale.TicketLineValuation();
        valuation.ticket = new Ticket();
        valuation.ticket.id = ticketId;
        valuation.lineUid = lineUid;
        valuation.advantageAmount = amount == null ? null : new BigDecimal(amount);
        valuation.offerLabel = offer;
        return valuation;
    }

    /**
     * BO-11-01-08: the immediate discounts the upstream refunds are attributed
     * to the family of the line the engine allocated them to — only the offers
     * the store administers, an advantage absent or nil counting nothing, an
     * offer with no label never matching a prefix.
     */
    @Test
    void theUpstreamDiscountsAreAttributedToTheirFamily(@TempDir Path directory) throws IOException {
        Ticket ticket = ticket("T1", "CA01", 2, "24.00", null);
        ticket.id = 5L;
        TicketLine coffee = saleLine("100", "EPICERIE", "1", "12.00", null, "12.00");
        coffee.lineUid = "a";
        TicketLine fruit = saleLine("200", "FRUITS", "1", "12.00", null, "12.00");
        fruit.lineUid = "b";
        ticket.lines.addAll(List.of(coffee, fruit));
        Ticket anonymous = ticket("T2", "CA01", 0, "0.00", null);
        valuations.add(valuation(5L, "a", "-1.50", "AMONT promo"));
        valuations.add(valuation(5L, "a", null, "AMONT promo"));
        valuations.add(valuation(5L, "a", "0.00", "AMONT promo"));
        valuations.add(valuation(5L, "b", "2.00", "LOCAL promo"));
        valuations.add(valuation(5L, "b", "3.00", null));
        PosSettingsService settings = settings(directory.toString(), OutboundFlow.FAMILY_SALES, "");
        when(settings.exportUpstreamOffers()).thenReturn(List.of("CENTRALE", "AMONT"));
        OutboundFlowService service = wire(settings, List.of(ticket, anonymous), List.of(point("CA01", "ILE1")));
        List<String> rows = Files.readAllLines(service.produce(OutboundFlow.FAMILY_SALES, DAY));
        Assertions.assertEquals("2026-03-17;ILE1;100;EPICERIE;1.000;12.00;10.00;2.00;0.00;1.50;1", rows.get(1));
        Assertions.assertEquals("2026-03-17;ILE1;200;FRUITS;1.000;12.00;10.00;2.00;0.00;0.00;1", rows.get(2));
        Assertions.assertEquals("2026-03-17;TOTAL;;;;24.00;20.00;4.00;0.00;1.50;2", rows.get(3));
        when(settings.exportUpstreamOffers()).thenReturn(List.of());
        rows = Files.readAllLines(service.produce(OutboundFlow.FAMILY_SALES, DAY));
        Assertions.assertEquals("2026-03-17;ILE1;200;FRUITS;1.000;12.00;10.00;2.00;0.00;5.00;1", rows.get(2));
    }

    /**
     * Builds a payment code read at a till.
     *
     * @param code the code, possibly null
     * @param found whether the system knew it
     * @return the reading
     */
    private com.intermarche.edge.domain.barcode.ScannedBarcode reading(String code, boolean found) {
        com.intermarche.edge.domain.barcode.ScannedBarcode reading =
                new com.intermarche.edge.domain.barcode.ScannedBarcode();
        reading.terminalId = "CA01";
        reading.code = code;
        reading.found = found;
        reading.typeCode = "COUPON";
        reading.typeLabel = "Coupon GS1";
        reading.ticketNumber = "T1";
        reading.amount = new BigDecimal("2.00");
        reading.scannedAt = LocalDateTime.of(2026, 3, 17, 10, 5);
        return reading;
    }

    /**
     * BO-11-01-18: the GS1 flow lists every code read — GS1 or not, known or
     * not, burned or not, a number the register extracted from a GS1 payload
     * flagged GS1 — then every voucher settled on a closed ticket, then a TOTAL
     * row, every instant to the second.
     */
    @Test
    void produceWritesTheGs1PaymentsFlow(@TempDir Path directory) throws IOException {
        readings.add(reading("(255)3250390000017", true));
        com.intermarche.edge.domain.barcode.ScannedBarcode plain = reading("BA123", false);
        plain.scannedAt = null;
        readings.add(plain);
        com.intermarche.edge.domain.barcode.ScannedBarcode extracted = reading("952600013436712345", true);
        extracted.gs1 = true;
        extracted.scannedAt = LocalDateTime.of(2026, 3, 17, 10, 7, 12, 345000);
        readings.add(extracted);
        when(burns.burnedAmong(java.util.Set.of("(255)3250390000017", "BA123", "952600013436712345")))
                .thenReturn(java.util.Set.of("(255)3250390000017"));
        Ticket ticket = ticket("T1", "CA01", 1, "12.00", LocalDateTime.of(2026, 3, 17, 10, 6));
        com.intermarche.edge.domain.payment.VoucherPayment voucher =
                new com.intermarche.edge.domain.payment.VoucherPayment(new BigDecimal("2.00"), "Coupon GS1",
                        "(255)3250390000017");
        voucher.tenderCode = "COUPON";
        ticket.payments.add(voucher);
        ticket.payments.add(payment("CASH", "10.00"));
        Ticket undated = ticket("T2", "CA01", 1, "1.00", null);
        com.intermarche.edge.domain.payment.VoucherPayment blank =
                new com.intermarche.edge.domain.payment.VoucherPayment(null, null, null);
        undated.payments.add(blank);
        com.intermarche.edge.domain.payment.VoucherPayment gcn =
                new com.intermarche.edge.domain.payment.VoucherPayment(new BigDecimal("1.00"), "Bon enseigne",
                        "952600013436712345");
        undated.payments.add(gcn);
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.GS1_PAYMENTS,
                List.of(ticket, undated), List.of());
        Path written = service.produce(OutboundFlow.GS1_PAYMENTS, DAY);
        Assertions.assertEquals("paigs1-20260317.csv", written.getFileName().toString());
        Assertions.assertEquals(List.of(
                "JOURNEE;SOURCE;TERMINAL;NUM_TICKET;DATE_HEURE;CODE;GS1;TYPE;LIBELLE;MONTANT;CONNU;BRULE",
                "2026-03-17;SCAN;CA01;T1;2026-03-17T10:05:00;(255)3250390000017;O;COUPON;Coupon GS1;2.00;O;O",
                "2026-03-17;SCAN;CA01;T1;;BA123;N;COUPON;Coupon GS1;2.00;N;N",
                "2026-03-17;SCAN;CA01;T1;2026-03-17T10:07:12;952600013436712345;O;COUPON;Coupon GS1;2.00;O;N",
                "2026-03-17;REGLEMENT;CA01;T1;2026-03-17T10:06:00;(255)3250390000017;O;COUPON;Coupon GS1;2.00;O;O",
                "2026-03-17;REGLEMENT;CA01;T2;;;N;;;0.00;O;O",
                "2026-03-17;REGLEMENT;CA01;T2;;952600013436712345;O;;Bon enseigne;1.00;O;O",
                "2026-03-17;TOTAL;;;;3;;;;3.00;;3"), Files.readAllLines(written));
    }

    /** BO-11-01-18: a day without a reading and without a sale writes no GS1 file. */
    @Test
    void theGs1FlowProducesNothingForAnEmptyDay(@TempDir Path directory) throws IOException {
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.GS1_PAYMENTS,
                List.of(), List.of());
        Assertions.assertNull(service.produce(OutboundFlow.GS1_PAYMENTS, DAY));
        readings.add(reading("X", true));
        Assertions.assertNotNull(service.produce(OutboundFlow.GS1_PAYMENTS, DAY));
    }

    /** BO-11-01-18: every GS1 marker is recognised, and a plain code is not GS1. */
    @Test
    void theGs1MarkersAreRecognised() {
        Assertions.assertFalse(OutboundFlowService.isGs1(null));
        Assertions.assertFalse(OutboundFlowService.isGs1(" "));
        Assertions.assertFalse(OutboundFlowService.isGs1("3250390000017"));
        Assertions.assertTrue(OutboundFlowService.isGs1("(01)03250390000017"));
        Assertions.assertTrue(OutboundFlowService.isGs1("0103250390000017\u001d10LOT"));
        Assertions.assertTrue(OutboundFlowService.isGs1("]d20103250390000017"));
        Assertions.assertTrue(OutboundFlowService.isGs1("]Q30103250390000017"));
        Assertions.assertTrue(OutboundFlowService.isGs1("]e00103250390000017"));
        Assertions.assertTrue(OutboundFlowService.isGs1("]C10103250390000017"));
        Assertions.assertTrue(OutboundFlowService.isGs1("https://id.gs1.org/01/03250390000017"));
    }

    /**
     * Builds a vignette declaration.
     *
     * @param terminal the register
     * @param detail the detail, possibly null
     * @return the event
     */
    private com.intermarche.edge.domain.session.TechnicalEvent declaration(String terminal, String detail) {
        com.intermarche.edge.domain.session.TechnicalEvent event =
                new com.intermarche.edge.domain.session.TechnicalEvent();
        event.terminalId = terminal;
        event.detail = detail;
        event.eventType = com.intermarche.edge.domain.session.TechnicalEvent.EventType.VIGNETTES_DECLARED;
        return event;
    }

    /**
     * BO-11-01-20: the vignettes flow states, per register and shift, the
     * tickets, the theoretical vignettes of the formula, the declared ones and
     * the gap — a declaration on a shift with no sale making its own row, an
     * unreadable or negative count counting none, a sale rung on no shift
     * making none.
     */
    @Test
    void produceWritesTheVignettesFlow(@TempDir Path directory) throws IOException {
        Ticket big = ticket("T1", "CA01", 3, "25.00", null);
        big.session = session("S1", null, null);
        big.cashier = cashier("jdupont");
        Ticket small = ticket("T2", "CA01", 1, null, null);
        small.session = session("S1", null, null);
        Ticket orphan = ticket("T3", "CA02", 1, "9.99", null);
        declarations.add(declaration("CA01", "session=S1;count=3"));
        declarations.add(declaration("CA01", "session=S1;count=x"));
        declarations.add(declaration("CA01", "junk;session=S1;count=-4"));
        declarations.add(declaration("CA03", "session=S9;count=5"));
        declarations.add(declaration("CA03", null));
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.VIGNETTES,
                List.of(big, small, orphan), List.of());
        Path written = service.produce(OutboundFlow.VIGNETTES, DAY);
        Assertions.assertEquals("vignett-20260317.csv", written.getFileName().toString());
        Assertions.assertEquals(List.of(
                "JOURNEE;TERMINAL;PRISE_DE_POSTE;CAISSIERE;NB_TICKETS;VIGNETTES_THEORIQUES;VIGNETTES_DECLAREES;ECART",
                "2026-03-17;CA01;S1;jdupont;2;2;3;1",
                "2026-03-17;CA03;;;0;0;0;0",
                "2026-03-17;CA03;S9;;0;0;5;5",
                "2026-03-17;TOTAL;;;2;2;8;6"), Files.readAllLines(written));
    }

    /** BO-11-01-20: a day without a sale and without a declaration writes no vignettes file. */
    @Test
    void theVignettesFlowProducesNothingForAnEmptyDay(@TempDir Path directory) throws IOException {
        OutboundFlowService service = serviceFor(directory.toString(), OutboundFlow.VIGNETTES,
                List.of(), List.of());
        Assertions.assertNull(service.produce(OutboundFlow.VIGNETTES, DAY));
        declarations.add(declaration("CA01", "session=S1;count=1"));
        Assertions.assertNotNull(service.produce(OutboundFlow.VIGNETTES, DAY));
    }

    /** BO-11-01-20: the formula counts whole steps, and nothing without a total, a positive total or a step. */
    @Test
    void theVignetteFormulaCountsWholeSteps() {
        Assertions.assertEquals(4L, OutboundFlowService.vignettesFor(new BigDecimal("29.99"), new BigDecimal("10"), 2));
        Assertions.assertEquals(0L, OutboundFlowService.vignettesFor(null, BigDecimal.TEN, 1));
        Assertions.assertEquals(0L, OutboundFlowService.vignettesFor(new BigDecimal("-5"), BigDecimal.TEN, 1));
        Assertions.assertEquals(0L, OutboundFlowService.vignettesFor(BigDecimal.TEN, BigDecimal.ZERO, 1));
    }

    /** The three new loaders read the day's bounds and the given tickets. */
    @Test
    void theNewLoadersQueryTheirRows() {
        var service = new OutboundFlowService();
        service.accountingDay = new AccountingDay();
        service.accountingDay.posSettingsService = mock(PosSettingsService.class);
        var from = LocalDateTime.of(2026, 3, 17, 0, 0);
        var to = LocalDateTime.of(2026, 3, 18, 0, 0);
        List<com.intermarche.edge.domain.barcode.ScannedBarcode> scans =
                List.of(new com.intermarche.edge.domain.barcode.ScannedBarcode());
        List<com.intermarche.edge.domain.session.TechnicalEvent> events =
                List.of(new com.intermarche.edge.domain.session.TechnicalEvent());
        List<com.intermarche.edge.domain.sale.TicketLineValuation> rows =
                List.of(new com.intermarche.edge.domain.sale.TicketLineValuation());
        try (var panache = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list(
                    "scannedAt >= ?1 and scannedAt < ?2 order by scannedAt desc, id desc", from, to)).thenReturn(scans);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list(
                    "eventType = ?1 and eventDate >= ?2 and eventDate < ?3 order by eventDate, id",
                    com.intermarche.edge.domain.session.TechnicalEvent.EventType.VIGNETTES_DECLARED, from, to))
                    .thenReturn(events);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("ticket.id in ?1",
                    List.of(5L))).thenReturn(rows);
            Assertions.assertSame(scans, service.scannedBarcodesOf(DAY));
            Assertions.assertSame(events, service.vignetteDeclarationsOf(DAY));
            Assertions.assertSame(rows, service.lineValuationsOf(List.of(5L)));
        }
    }
}
