package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.sale.TicketLineValuation;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.util.DateTimeProvider;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GOLDEN MASTER of every document the register prints without a template.
 *
 * <p>Why this exists. The register's layouts are about to move from Java to
 * factory templates, one per document type. That move is only safe if the new
 * layout produces the OLD document character for character — a receipt is read
 * by a customer, kept for a warranty, and audited by a tax inspector, so "it
 * looks about the same" is not a result. This class freezes what each document
 * looks like TODAY, in a reference file per document, and fails on any
 * difference, wherever it comes from.
 *
 * <p>It is deliberately BLIND: it asserts no business rule, names no
 * expectation, and would be worthless as a specification. Its whole value is
 * that it cannot be satisfied by rewriting it — a reference file changes only
 * when a human looks at the diff and decides the new document is the right one.
 *
 * <p>Recording: run with {@code -Dgolden.record=true} to (re)write the
 * reference files from the current behaviour. Never do that to make a red test
 * pass; do it when the diff has been read and accepted.
 *
 * <p>Determinism: the clock is pinned, every amount and identifier is fixed,
 * and the logo directive is whatever the test classpath holds — the same on
 * every run. A document that cannot be made deterministic does not belong
 * here.
 */
class TicketLayoutGoldenMasterTest {

    /** The instant every document is printed at, so no date moves. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 17, 10, 42, 5);

    /** Where the reference files live, relative to the module root. */
    private static final String GOLDEN_DIR = "src/test/resources/golden";

    /**
     * Builds a printer whose hardware and parameters are mocked at their
     * defaults, exactly as the behavioural tests do.
     *
     * @return the wired printer
     */
    private TicketPrinterService newService() {
        TicketPrinterService service = new TicketPrinterService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.hardwareService = mock(HardwareService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        when(service.posSettingsService.vatBreakdownEnabled()).thenReturn(true);
        // The currency defaults of the catalogue, which print exactly what the
        // register printed before the symbol became administrable: the symbol
        // E, after the amount (BO-10-07-04/-05/-06).
        when(service.posSettingsService.currencySymbolPrinted()).thenReturn(true);
        when(service.posSettingsService.currencySymbol()).thenReturn("E");
        when(service.posSettingsService.currencySymbolPosition()).thenReturn("RIGHT");
        return service;
    }

    /**
     * Captures the single receipt the printer pushed to the hardware.
     *
     * @param service the printer under test
     * @return the printed content
     */
    private String captureReceipt(TicketPrinterService service) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service.hardwareService).printReceipt(captor.capture());
        return captor.getValue();
    }

    /**
     * Builds the store every document is emitted by.
     *
     * @return the store
     */
    private Store store() {
        Store store = new Store();
        store.name = "INTERMARCHE LYON";
        store.address = new Address();
        store.address.streetLine1 = "12 RUE DE LA REPUBLIQUE";
        store.address.postalCode = "69002";
        store.address.city = "LYON";
        return store;
    }

    /**
     * Builds the cashier every document is emitted by.
     *
     * @return the cashier
     */
    private Employee cashier() {
        Employee cashier = new Employee();
        cashier.loginName = "jdupont";
        cashier.firstName = "Jean";
        cashier.lastName = "Dupont";
        cashier.badgeId = "BADGE-0042";
        return cashier;
    }

    /**
     * Builds a sale of two lines paid in cash, with its store, cashier and
     * totals fixed.
     *
     * @return the sale
     */
    private Ticket sale() {
        Ticket ticket = new Ticket();
        ticket.id = 1L;
        ticket.ticketNumber = "C04-00000001";
        ticket.creationDate = NOW;
        ticket.store = store();
        ticket.cashier = cashier();
        ticket.terminalId = "C04";
        ticket.lines = new ArrayList<>();
        ticket.lines.add(saleLine("u1", "PAIN COMPLET", "1", "2.00", "2.00"));
        ticket.lines.add(saleLine("u2", "SODA 1L", "2", "1.31", "2.62"));
        ticket.payments = new ArrayList<>();
        ticket.payments.add(new com.intermarche.pos.domain.payment.CashPayment(
                new BigDecimal("4.62"), new BigDecimal("5.00")));
        ticket.fidelityLines = new ArrayList<>();
        ticket.totalIncludingTax = new BigDecimal("4.62");
        ticket.totalExcludingTax = new BigDecimal("3.85");
        ticket.totalVat = new BigDecimal("0.77");
        return ticket;
    }

    /**
     * Builds one sold line at the standard rate.
     *
     * @param uid the line uid
     * @param label the product label
     * @param quantity the quantity
     * @param unitPrice the unit price
     * @param totalPrice the line total
     * @return the line
     */
    private TicketLine saleLine(String uid, String label, String quantity,
                                String unitPrice, String totalPrice) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.productLabel = label;
        line.quantity = new BigDecimal(quantity);
        line.unitPrice = new BigDecimal(unitPrice);
        line.totalPrice = new BigDecimal(totalPrice);
        line.vatRate = new BigDecimal("0.2000");
        return line;
    }

    /**
     * The ordinary sale receipt — the document printed thousands of times a
     * day, and the one every other layout is judged against.
     */
    @Test
    void goldenSaleReceipt() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertGolden("sale-receipt", service.renderTicket(sale(), false, 0));
        }
    }

    /**
     * The same sale reprinted: the DUPLICATA banner and its rank.
     */
    @Test
    void goldenSaleReceiptDuplicata() {
        TicketPrinterService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertGolden("sale-receipt-duplicata", service.renderTicket(sale(), true, 2));
        }
    }

    /**
     * A sale carrying an EGAlim correction: the advantage prints net of the
     * reprise and the reprise prints on its own line, which is the layout the
     * legal ceiling produced.
     */
    @Test
    void goldenSaleReceiptWithEgalimCorrection() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        TicketLineValuation valuation = new TicketLineValuation();
        valuation.lineUid = "u2";
        valuation.localTotal = new BigDecimal("1.31");
        valuation.valuedTotal = new BigDecimal("2.62");
        valuation.advantageLabel = "2 ACHETES 1 OFFERT";
        valuation.correctionLabel = "AJUSTEMENT EGALIM";
        valuation.correctionAmount = new BigDecimal("0.42");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> TicketLineValuation.list("ticket.id", 1L))
                    .thenReturn(java.util.List.of(valuation));
            assertGolden("sale-receipt-egalim", service.renderTicket(ticket, false, 0));
        }
    }

    /**
     * A sale made while the loyalty service was silent: the administered
     * message takes the place of the advantages.
     */
    @Test
    void goldenSaleReceiptFidelityOffline() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityOfflineMessage())
                .thenReturn("CARTE {carte} - FIDELITE INDISPONIBLE, VOS AVANTAGES SERONT CREDITES ULTERIEUREMENT");
        Ticket ticket = sale();
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityUnavailable = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertGolden("sale-receipt-fidelity-offline", service.renderTicket(ticket, false, 0));
        }
    }

    /**
     * The training receipt — one of the seven documents that print with no
     * template hook at all.
     */
    @Test
    void goldenTrainingReceipt() {
        TicketPrinterService service = newService();
        PosState state = new PosState();
        state.ticket.items.add(new TicketState.TicketItem(
                "3000000000014", null, "PAIN COMPLET",
                new BigDecimal("2.00"), new BigDecimal("1"), new BigDecimal("0.2000")));
        state.payment.payments.add(new PaymentState.PaymentEntry("CB", new BigDecimal("2.00")));
        service.printTrainingReceipt(state);
        assertGolden("training-receipt", captureReceipt(service));
    }

    /**
     * The operator badge — templateless.
     */
    @Test
    void goldenOperatorBadge() {
        TicketPrinterService service = newService();
        service.printOperatorBadge(cashier());
        assertGolden("operator-badge", captureReceipt(service));
    }

    /**
     * The parked-ticket slip — templateless, and the very document
     * BO-03-03-07 asks to make administrable.
     */
    @Test
    void goldenParkedTicket() {
        TicketPrinterService service = newService();
        service.printParkedTicket(sale());
        assertGolden("parked-ticket", captureReceipt(service));
    }

    /**
     * The loyalty-credit slip — templateless.
     */
    @Test
    void goldenLoyaltyCredit() {
        TicketPrinterService service = newService();
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(NOW);
            service.printLoyaltyCredit(new BigDecimal("3.50"));
        }
        assertGolden("loyalty-credit", captureReceipt(service));
    }

    /**
     * The refund voucher — templateless.
     */
    @Test
    void goldenRefundVoucher() {
        TicketPrinterService service = newService();
        Refund refund = new Refund();
        refund.totalAmount = new BigDecimal("7.90");
        refund.creationDate = NOW;
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(NOW);
            service.printRefundVoucher(refund, "AV-000001", null);
        }
        assertGolden("refund-voucher", captureReceipt(service));
    }

    /**
     * Compares a produced document with its reference, or records the
     * reference when {@code -Dgolden.record=true} is set.
     *
     * <p>A missing reference is a FAILURE and not a silent record: a golden
     * master that writes whatever it is given proves nothing, and the one
     * moment a reference may be created or changed is a moment a human chose.
     *
     * @param name the document's reference name
     * @param produced the document as printed now
     */
    private void assertGolden(String name, String produced) {
        if (Boolean.getBoolean("golden.record")) {
            record(name, produced);
            return;
        }
        String expected = read(name);
        if (expected == null) {
            fail("Référence absente pour '" + name + "'. Relancer avec -Dgolden.record=true "
                    + "APRÈS avoir lu le document produit :\n" + produced);
        }
        if (!expected.equals(produced)) {
            dumpActual(name, produced);
        }
        assertEquals(expected, produced, "Le document '" + name + "' a changé. "
                + "Lire le diff : si le nouveau document est le bon, réenregistrer la référence. "
                + "Le document produit est écrit dans target/golden-actual/" + name + ".txt");
    }

    /**
     * Writes the produced document beside the build output when it differs
     * from its reference.
     *
     * <p>A golden master whose only output is an assertion message forces
     * whoever reads the failure to reconstruct the document by hand. The file
     * dropped here is the exact text that was produced, ready to be diffed
     * against the reference or, once a human has read it, copied over it.
     * Nothing is written when the documents match, and a failure to write is
     * swallowed: the assertion below is what must report, not the dump.
     *
     * @param name the document's reference name
     * @param produced the document as printed now
     */
    private void dumpActual(String name, String produced) {
        try {
            java.nio.file.Path directory = java.nio.file.Paths.get("target", "golden-actual");
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve(name + ".txt"), produced,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            // The assertion that follows is the report; a dump that cannot be
            // written must not replace a document comparison with an I/O error.
        }
    }

    /**
     * Reads a reference from the test classpath.
     *
     * @param name the document's reference name
     * @return the reference content, or null when none is recorded
     */
    private String read(String name) {
        try (InputStream stream = getClass().getResourceAsStream("/golden/" + name + ".txt")) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Référence illisible: " + name, e);
        }
    }

    /**
     * Writes a reference into the test resources.
     *
     * @param name the document's reference name
     * @param produced the document to record
     */
    private void record(String name, String produced) {
        try {
            Path dir = Path.of(GOLDEN_DIR);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".txt"), produced, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Référence non enregistrée: " + name, e);
        }
    }
}
