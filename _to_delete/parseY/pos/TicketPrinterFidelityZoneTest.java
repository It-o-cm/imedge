package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the fidelity zone of {@link TicketPrinterService} added by the
 * imfid-contracts integration: the advantage-type grouping (imfid response A),
 * the projected balance printed as PREVISIONNEL (response B) and the anonymous
 * projection for a card-less ticket (response C).
 * <p>
 * The printer is built with mocked hardware and parameters, exactly as the
 * golden-master test does, and its {@code renderTicket} output is asserted
 * directly. No {@code @QuarkusTest}, no database.
 */
class TicketPrinterFidelityZoneTest {

    /** A fixed instant so no date moves the rendered text. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 17, 10, 42, 5);

    /**
     * Builds a printer whose hardware and parameters are mocked, with the
     * fidelity advantages enabled and a fixed projected-balance label.
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
        when(service.posSettingsService.fidelityProjectedLabel()).thenReturn("CAGNOTTE PREVISIONNELLE");
        return service;
    }

    /**
     * Renders a ticket with the Panache statics neutralised (plain mvn test
     * does not enhance entities), mirroring the golden-master test: the
     * per-line valuation lookup then returns an empty list.
     *
     * @param service the printer under test
     * @param ticket the sale to render
     * @return the rendered receipt text
     */
    private String render(TicketPrinterService service, Ticket ticket) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            return service.renderTicket(ticket, false, 0);
        }
    }

    /**
     * Builds a minimal but complete sale carrying an empty fidelity zone, ready
     * for a test to fill with advantage lines and projection fields.
     *
     * @return the sale
     */
    private Ticket sale() {
        Ticket ticket = new Ticket();
        ticket.id = 1L;
        ticket.ticketNumber = "C04-00000001";
        ticket.creationDate = NOW;
        Store store = new Store();
        store.name = "INTERMARCHE LYON";
        store.address = new Address();
        ticket.store = store;
        Employee cashier = new Employee();
        cashier.loginName = "jdupont";
        cashier.firstName = "Jean";
        cashier.lastName = "Dupont";
        ticket.cashier = cashier;
        ticket.terminalId = "C04";
        ticket.lines = new ArrayList<>();
        TicketLine line = new TicketLine();
        line.lineUid = "u1";
        line.productLabel = "PAIN COMPLET";
        line.quantity = new BigDecimal("1");
        line.unitPrice = new BigDecimal("2.00");
        line.totalPrice = new BigDecimal("2.00");
        line.vatRate = new BigDecimal("0.2000");
        ticket.lines.add(line);
        ticket.payments = new ArrayList<>();
        ticket.payments.add(new com.intermarche.pos.domain.payment.CashPayment(
                new BigDecimal("2.00"), new BigDecimal("2.00")));
        ticket.fidelityLines = new ArrayList<>();
        ticket.totalIncludingTax = new BigDecimal("2.00");
        ticket.totalExcludingTax = new BigDecimal("1.67");
        ticket.totalVat = new BigDecimal("0.33");
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("3.90");
        return ticket;
    }

    /**
     * Builds one frozen advantage line with its grouping.
     *
     * @param label the printable label
     * @param amount the earn amount
     * @param type the advantage type code
     * @param typeLabel the advantage type label
     * @param order the group print order
     * @return the frozen line
     */
    private TicketFidelityLine advLine(String label, String amount, String type,
            String typeLabel, Integer order) {
        return new TicketFidelityLine("R-" + label, label, new BigDecimal(amount),
                type, typeLabel, order, null, null);
    }

    /**
     * The advantage lines are GROUPED by type and the groups ORDERED by
     * advantageTypeOrder ascending, whatever the arrival order, and the received
     * labels are printed verbatim (imfid response A).
     */
    @Test
    void groupsAdvantagesAndOrdersThemByType() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        // Added ticket-order (20) first, product-order (10) second: the output
        // must still print PRODUCT before TICKET.
        ticket.fidelityLines.add(advLine("Palier 50 euros", "2.40", "TICKET", "Vos avantages ticket", 20));
        ticket.fidelityLines.add(advLine("Boost marque", "1.50", "PRODUCT", "Vos avantages produits", 10));
        String receipt = render(service, ticket);
        assertTrue(receipt.contains("Vos avantages produits"));
        assertTrue(receipt.contains("Vos avantages ticket"));
        assertTrue(receipt.contains("Boost marque"));
        assertTrue(receipt.contains("Palier 50 euros"));
        assertTrue(receipt.indexOf("Vos avantages produits") < receipt.indexOf("Vos avantages ticket"),
                "PRODUCT (order 10) must print before TICKET (order 20)");
    }

    /**
     * When NO line carries an advantage type (legacy rules), the zone falls back
     * to the flat list with no group header (imfid response A last bullet).
     */
    @Test
    void fallsBackToTheFlatListWhenNoAdvantageType() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        ticket.fidelityLines.add(new TicketFidelityLine("SOCLE", "Cagnotte socle", new BigDecimal("0.28")));
        String receipt = render(service, ticket);
        assertTrue(receipt.contains("CAGNOTTE DU JOUR"));
        assertTrue(receipt.contains("Cagnotte socle"));
        assertFalse(receipt.contains("Vos avantages"));
    }

    /**
     * The projected balance prints under the administered label, marked
     * PREVISIONNEL (imfid response B); nothing is credited before ingestion.
     */
    @Test
    void printsTheProjectedBalanceAsPrevisionnel() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        ticket.fidelityLines.add(advLine("Boost marque", "1.50", "PRODUCT", "Vos avantages produits", 10));
        ticket.fidelityProjectedAfterEarn = new BigDecimal("46.20");
        String receipt = render(service, ticket);
        assertTrue(receipt.contains("CAGNOTTE PREVISIONNELLE"));
        assertTrue(receipt.contains("(PREVISIONNEL)"));
        assertTrue(receipt.contains("46,20"));
    }

    /**
     * A card-less ticket flagged as an anonymous projection prints « ce que vous
     * auriez gagné avec la carte » and no cagnotte-du-jour header (imfid
     * response C).
     */
    @Test
    void printsTheAnonymousProjectionPhrase() {
        TicketPrinterService service = newService();
        Ticket ticket = sale();
        ticket.fidelityCard = null;
        ticket.fidelityAnonymousProjection = true;
        String receipt = render(service, ticket);
        assertTrue(receipt.toLowerCase().contains("auriez gagne"));
        assertFalse(receipt.contains("CAGNOTTE DU JOUR"));
    }

    /**
     * Builds one frozen advantage line carrying a CATEGORY and no type, for the
     * grouping of BO-03-03-33.
     *
     * @param label the printable label
     * @param amount the earn amount
     * @param category the advantage category code
     * @param categoryLabel the advantage category label
     * @return the frozen line
     */
    private TicketFidelityLine categoryLine(String label, String amount, String category,
            String categoryLabel) {
        return new TicketFidelityLine("R-" + label, label, new BigDecimal(amount),
                null, null, null, category, categoryLabel);
    }

    /**
     * BO-03-03-32, the three legs of the guard held false one at a time: the
     * shop not asking, a sale carrying no card, and a sale carrying a blank
     * one. None of them names a card.
     */
    @Test
    void theCardIsNotNamedUnlessTheShopAsksAndACardWasPresented() {
        TicketPrinterService silent = newService();
        when(silent.posSettingsService.fidelityPrintCardNumber()).thenReturn(false);
        assertFalse(render(silent, sale()).contains("CARTE FIDELITE"));

        TicketPrinterService noCard = newService();
        when(noCard.posSettingsService.fidelityPrintCardNumber()).thenReturn(true);
        Ticket cardless = sale();
        cardless.fidelityCard = null;
        assertFalse(render(noCard, cardless).contains("CARTE FIDELITE"));

        TicketPrinterService blankCard = newService();
        when(blankCard.posSettingsService.fidelityPrintCardNumber()).thenReturn(true);
        Ticket blank = sale();
        blank.fidelityCard = "   ";
        assertFalse(render(blankCard, blank).contains("CARTE FIDELITE"));
    }

    /**
     * BO-03-03-32, the guard's true arm: the mention and the number come out,
     * and they come out BEFORE the day's earn — the card names the zone the
     * advantages are read in.
     */
    @Test
    void theCardIsNamedWhenTheShopAsksForIt() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityPrintCardNumber()).thenReturn(true);
        String out = render(service, sale());
        assertTrue(out.contains("CARTE FIDELITE"));
        assertTrue(out.contains("2990000000019"));
        assertTrue(out.indexOf("CARTE FIDELITE") < out.indexOf("CAGNOTTE DU JOUR"));
    }

    /**
     * BO-03-03-31 / BO-03-03-34, the guard's true arm: the four figures of the
     * balance zone, each under its own label — what was available, what the day
     * earned, what this sale took on the balance and what is left after it.
     */
    @Test
    void theBalanceZoneStatesTheFourFigures() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket ticket = sale();
        ticket.fidelityAvailableBalance = new BigDecimal("42.30");
        ticket.fidelityBalanceAfter = new BigDecimal("40.20");
        ticket.payments.add(new com.intermarche.pos.domain.payment.FidelityPayment(
                new BigDecimal("3.00")));
        String out = render(service, ticket);
        assertTrue(out.contains("SOLDES CARTE"));
        assertTrue(out.contains("SOLDE DISPONIBLE"));
        assertTrue(out.contains("42,30"));
        assertTrue(out.contains("TOTAL AVANTAGES JOUR"));
        assertTrue(out.contains("MONTANT UTILISE"));
        assertTrue(out.contains("3,00"));
        assertTrue(out.contains("NOUVEAU SOLDE"));
        assertTrue(out.contains("40,20"));
    }

    /**
     * BO-03-03-31, the three legs of the guard held false one at a time: the
     * shop not asking, a sale carrying no card, and an ANONYMOUS projection —
     * that last one has no balance to state, only what a card would have
     * earned, so stating one would be an invention.
     */
    @Test
    void theBalanceZoneStaysOutOnEachLegOfItsGuard() {
        TicketPrinterService silent = newService();
        when(silent.posSettingsService.fidelityPrintBalances()).thenReturn(false);
        Ticket asked = sale();
        asked.fidelityAvailableBalance = new BigDecimal("42.30");
        assertFalse(render(silent, asked).contains("SOLDES CARTE"));

        TicketPrinterService noCard = newService();
        when(noCard.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket cardless = sale();
        cardless.fidelityCard = "  ";
        cardless.fidelityAvailableBalance = new BigDecimal("42.30");
        assertFalse(render(noCard, cardless).contains("SOLDES CARTE"));

        TicketPrinterService anonymous = newService();
        when(anonymous.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket projected = sale();
        projected.fidelityAnonymousProjection = true;
        projected.fidelityAvailableBalance = new BigDecimal("42.30");
        assertFalse(render(anonymous, projected).contains("SOLDES CARTE"));
    }

    /**
     * The four null guards of the balance zone, all false at once: a sale whose
     * balances the programme never answered prints the heading and no figure.
     * A figure the register did not vouch for is left out rather than printed
     * as a zero, because a zero on a receipt is a statement and not a silence.
     */
    @Test
    void theBalanceZonePrintsNoFigureItDoesNotHold() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityPrintBalances()).thenReturn(true);
        Ticket ticket = sale();
        ticket.fidelityEarnTotal = null;
        ticket.fidelityAvailableBalance = null;
        ticket.fidelityBalanceAfter = null;
        String out = render(service, ticket);
        assertFalse(out.contains("SOLDE DISPONIBLE"));
        assertFalse(out.contains("TOTAL AVANTAGES JOUR"));
        assertFalse(out.contains("MONTANT UTILISE"));
        assertFalse(out.contains("NOUVEAU SOLDE"));
    }

    /**
     * BO-03-03-33: with the CATEGORY grouping administered, the headings are the
     * category labels and the advantages come out under them.
     */
    @Test
    void theAdvantagesAreGroupedByCategoryWhenAdministered() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityAdvantageGrouping()).thenReturn("CATEGORY");
        Ticket ticket = sale();
        ticket.fidelityLines.add(categoryLine("SOCLE", "1.00", "ALIM", "ALIMENTAIRE"));
        ticket.fidelityLines.add(categoryLine("BIO", "0.50", "ALIM", "ALIMENTAIRE"));
        ticket.fidelityLines.add(categoryLine("TEXTILE", "0.40", "NONALIM", "NON ALIMENTAIRE"));
        String out = render(service, ticket);
        assertTrue(out.contains("ALIMENTAIRE"));
        assertTrue(out.contains("NON ALIMENTAIRE"));
        assertTrue(out.contains("SOCLE"));
        assertTrue(out.contains("TEXTILE"));
    }

    /**
     * BO-03-03-25, the NONE arm: the shop asking for no grouping gets the flat
     * list, headings and all gone, even though every line carries a type.
     */
    @Test
    void theAdvantagesArePrintedFlatWhenNoGroupingIsAdministered() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityAdvantageGrouping()).thenReturn("NONE");
        Ticket ticket = sale();
        ticket.fidelityLines.add(advLine("SOCLE", "1.00", "REMISE", "REMISES IMMEDIATES", 10));
        String out = render(service, ticket);
        assertTrue(out.contains("SOCLE"));
        assertFalse(out.contains("REMISES IMMEDIATES"));
    }

    /**
     * The CATEGORY grouping over lines that carry no category: the output is the
     * flat list, exactly as for a type nobody sent — a shop that administered a
     * grouping the programme does not fill must still read its advantages.
     */
    @Test
    void theCategoryGroupingFallsFlatWhenNoLineCarriesOne() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityAdvantageGrouping()).thenReturn("CATEGORY");
        Ticket ticket = sale();
        ticket.fidelityLines.add(advLine("SOCLE", "1.00", "REMISE", "REMISES IMMEDIATES", 10));
        String out = render(service, ticket);
        assertTrue(out.contains("SOCLE"));
        assertFalse(out.contains("REMISES IMMEDIATES"));
    }

    /**
     * A grouping value this register does not know falls back to the type,
     * which is the historical print — the false arm of both comparisons in
     * {@code normalizeAdvantageGrouping}.
     */
    @Test
    void anUnknownGroupingFallsBackToTheAdvantageType() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityAdvantageGrouping()).thenReturn("RAYON");
        Ticket ticket = sale();
        ticket.fidelityLines.add(advLine("SOCLE", "1.00", "REMISE", "REMISES IMMEDIATES", 10));
        assertTrue(render(service, ticket).contains("REMISES IMMEDIATES"));
    }

    /**
     * BO-03-03-28, the guard's true arm: a sale carrying no card and no offline
     * message prints the shop's word to a non-holder.
     */
    @Test
    void theNonHolderMessagePrintsOnACardlessSale() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityNonHolderMessage())
                .thenReturn("PENSEZ A VOTRE CARTE");
        Ticket ticket = sale();
        ticket.fidelityCard = null;
        assertTrue(render(service, ticket).contains("PENSEZ A VOTRE CARTE"));
    }

    /**
     * BO-03-03-28, the three legs held false one at a time: a sale carrying a
     * card, a shop administering no text, and a sale already carrying the
     * OFFLINE message — the two say different things and the receipt says one
     * of them, not both.
     */
    @Test
    void theNonHolderMessageStaysOutOnEachLegOfItsGuard() {
        TicketPrinterService withCard = newService();
        when(withCard.posSettingsService.fidelityNonHolderMessage())
                .thenReturn("PENSEZ A VOTRE CARTE");
        assertFalse(render(withCard, sale()).contains("PENSEZ A VOTRE CARTE"));

        TicketPrinterService silent = newService();
        when(silent.posSettingsService.fidelityNonHolderMessage()).thenReturn("   ");
        Ticket cardless = sale();
        cardless.fidelityCard = null;
        assertFalse(render(silent, cardless).contains("PENSEZ A VOTRE CARTE"));

        TicketPrinterService offline = newService();
        when(offline.posSettingsService.fidelityNonHolderMessage())
                .thenReturn("PENSEZ A VOTRE CARTE");
        when(offline.posSettingsService.fidelityOfflineMessageNoCard())
                .thenReturn("FIDELITE INDISPONIBLE");
        Ticket unavailable = sale();
        unavailable.fidelityCard = null;
        unavailable.fidelityUnavailable = true;
        String out = render(offline, unavailable);
        assertTrue(out.contains("FIDELITE INDISPONIBLE"));
        assertFalse(out.contains("PENSEZ A VOTRE CARTE"));
    }

    /**
     * The null arm of the administered non-holder text: a parameter nobody
     * answered prints nothing and fails nothing.
     */
    @Test
    void aMissingNonHolderMessagePrintsNothing() {
        TicketPrinterService service = newService();
        when(service.posSettingsService.fidelityNonHolderMessage()).thenReturn(null);
        Ticket ticket = sale();
        ticket.fidelityCard = null;
        assertFalse(render(service, ticket).isBlank());
    }
}
