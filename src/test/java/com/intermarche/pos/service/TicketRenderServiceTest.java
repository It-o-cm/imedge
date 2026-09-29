package com.intermarche.pos.service;

import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.CreditPayment;
import com.intermarche.pos.domain.payment.FidelityPayment;
import com.intermarche.pos.domain.payment.ForeignCurrencyPayment;
import com.intermarche.pos.domain.payment.RoundingPayment;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.sale.TicketLineValuation;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.ui.hardware.ReceiptLogo;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketRenderService}, the store node's own receipt
 * renderer.
 * <p>
 * The renderer touches no printer and writes no row, so the only collaborators
 * are the back-office parameters ({@link PosSettingsService}) and the
 * administered layouts ({@link DocumentTemplateService}), both Mockito mocks
 * assigned to the package-private injection fields. The single Panache static
 * access ({@code TicketLineValuation.list}) is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}, since the
 * entities are not bytecode-enhanced under plain {@code mvn test}. No database
 * and no Quarkus context is booted; every assertion is on the returned text.
 * <p>
 * Two rendering paths are covered. The ADMINISTERED path returns whatever the
 * layout produced and is also the only way to exercise the null-tolerant
 * description helpers ({@code storeData}, {@code saleLines}, {@code saleTotals},
 * {@code saleVatRows}, {@code salePayments}) on a sale carrying nothing — the
 * built-in rendering below them reads {@code ticket.store.name} unconditionally
 * and could not be reached with a null store. The BUILT-IN path covers the
 * logo, the administered header and footer messages, the duplicata banner, the
 * family headings, the cancelled and truncated lines, the EAN, the per-line
 * engine advantage in its four label arms, the global discount, the VAT
 * ventilation switch, the five settlement kinds and the digital-receipt footer.
 */
class TicketRenderServiceTest {

    /** A fixed timestamp used across the receipts. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 10, 30);

    /** The receipt width the renderer is contracted on. */
    private static final int WIDTH = 42;

    /**
     * Builds a renderer with its two collaborators mocked at the defaults every
     * case relies on: VAT ventilation on, no EAN on paper, entry line order.
     *
     * @return a ready-to-use renderer
     */
    private TicketRenderService newService() {
        TicketRenderService service = new TicketRenderService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.vatBreakdownEnabled()).thenReturn(true);
        service.documentTemplateService = mock(DocumentTemplateService.class);
        return service;
    }

    /**
     * Builds a cashier with a printable identity.
     *
     * @return a cashier whose {@code getFullName} yields "Jean Dupont"
     */
    private Employee cashier() {
        Employee cashier = new Employee();
        cashier.firstName = "Jean";
        cashier.lastName = "Dupont";
        return cashier;
    }

    /**
     * Builds a store carrying a name, a street, a postal code and a city.
     *
     * @return a store whose city is "LYON"
     */
    private Store store() {
        Store store = new Store();
        store.name = "MAGASIN LYON";
        store.siret = "12345678900017";
        Address address = new Address();
        address.streetLine1 = "1 rue du Test";
        address.postalCode = "69000";
        address.city = "LYON";
        store.address = address;
        return store;
    }

    /**
     * Builds a sale with the common header, totals and empty collections.
     *
     * @param digitalKey the digital receipt key, or null
     * @return the configured sale
     */
    private Ticket ticket(String digitalKey) {
        Ticket ticket = new Ticket();
        ticket.id = 1L;
        ticket.ticketNumber = "C04-00000001";
        ticket.creationDate = NOW;
        ticket.store = store();
        ticket.cashier = cashier();
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.digitalKey = digitalKey;
        ticket.lines = new ArrayList<>();
        ticket.payments = new ArrayList<>();
        ticket.fidelityLines = new ArrayList<>();
        return ticket;
    }

    /**
     * Builds a sale line.
     *
     * @param uid the stable line uid
     * @param label the product label
     * @param quantity the quantity
     * @param unitPrice the unit price
     * @param totalPrice the line total
     * @return the configured line
     */
    private TicketLine line(String uid, String label, String quantity, String unitPrice,
            String totalPrice) {
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
     * Builds a per-line engine valuation trace.
     *
     * @param uid the valued line uid
     * @param localTotal the register-local total
     * @param valuedTotal the engine-valued total
     * @param advantageLabel the advantage label, or null
     * @param offerLabel the offer label, or null
     * @return the configured valuation
     */
    private TicketLineValuation valuation(String uid, String localTotal, String valuedTotal,
            String advantageLabel, String offerLabel) {
        TicketLineValuation valuation = new TicketLineValuation();
        valuation.lineUid = uid;
        valuation.localTotal = new BigDecimal(localTotal);
        valuation.valuedTotal = new BigDecimal(valuedTotal);
        valuation.advantageLabel = advantageLabel;
        valuation.offerLabel = offerLabel;
        return valuation;
    }

    /**
     * Renders a sale with the valuation lookup stubbed, the logo forced to the
     * given directive, and the administered layout left to whatever the caller
     * stubbed.
     *
     * @param service the renderer under test
     * @param ticket the sale to render
     * @param valuations the traces the lookup answers
     * @param logoDirective the directive {@link ReceiptLogo} answers
     * @param duplicata whether the DUPLICATA banner is asked for
     * @param duplicataNumber the duplicata rank
     * @return the rendered text
     */
    private String render(TicketRenderService service, Ticket ticket,
            List<TicketLineValuation> valuations, String logoDirective,
            boolean duplicata, int duplicataNumber) {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedStatic<ReceiptLogo> logo = mockStatic(ReceiptLogo.class)) {
            logo.when(ReceiptLogo::directive).thenReturn(logoDirective);
            mocked.when(() -> TicketLineValuation.list("ticket.id", 1L)).thenReturn(valuations);
            return service.renderTicket(ticket, duplicata, duplicataNumber);
        }
    }

    /**
     * Renders a plain sale with no logo, no duplicata and no valuation trace.
     *
     * @param service the renderer under test
     * @param ticket the sale to render
     * @return the rendered text
     */
    private String render(TicketRenderService service, Ticket ticket) {
        return render(service, ticket, new ArrayList<>(), "", false, 0);
    }

    // --------------------------------------------------
    // The administered path (BO-03-03)
    // --------------------------------------------------

    /**
     * An administered layout wins: what it produced is returned verbatim and the
     * built-in rendering below is never reached (the {@code administered != null}
     * true arm).
     */
    @Test
    void anAdministeredLayoutIsReturnedVerbatim() {
        TicketRenderService service = newService();
        when(service.documentTemplateService.render(
                eq(DocumentTemplate.DocumentType.SALE_RECEIPT), any())).thenReturn("MISE EN PAGE");
        assertEquals("MISE EN PAGE", render(service, ticket(null)));
    }

    /**
     * A shop that administers no layout gets the built-in receipt (the
     * {@code administered != null} false arm): the mocked template service
     * answers null, and the text carries the built-in courtesy line.
     */
    @Test
    void anUnadministeredSaleFallsBackOnTheBuiltInReceipt() {
        TicketRenderService service = newService();
        when(service.documentTemplateService.render(any(), any())).thenReturn(null);
        assertTrue(render(service, ticket(null)).contains("MERCI DE VOTRE VISITE"));
    }

    /**
     * A renderer built by hand with no template service at all renders its own
     * receipt (the {@code documentTemplateService == null} true arm of
     * {@code administeredLayout}).
     */
    @Test
    void aRendererWithoutATemplateServiceRendersItsOwnReceipt() {
        TicketRenderService service = newService();
        service.documentTemplateService = null;
        assertTrue(render(service, ticket(null)).contains("MERCI DE VOTRE VISITE"));
    }

    /**
     * A sale carrying NOTHING — no store, no cashier, no lines, no payments, no
     * totals, no date — is still fully described for a layout: every null arm of
     * {@code storeData}, {@code saleLines}, {@code saleTotals},
     * {@code saleVatRows} and {@code salePayments}, plus the two null arms of the
     * document dates. This is the shape a node may hold after a partial push, and
     * a layout must not be the thing that fails the rendering.
     */
    @Test
    void aSaleCarryingNothingIsStillDescribedForALayout() {
        TicketRenderService service = newService();
        when(service.documentTemplateService.render(any(), any())).thenReturn("MISE EN PAGE");
        Ticket ticket = new Ticket();
        ticket.id = 1L;
        ticket.ticketNumber = null;
        ticket.creationDate = null;
        ticket.store = null;
        ticket.cashier = null;
        ticket.terminalId = null;
        ticket.fidelityCard = null;
        ticket.digitalKey = null;
        ticket.totalIncludingTax = null;
        ticket.totalVat = null;
        ticket.globalDiscountApplied = null;
        ticket.lines = null;
        ticket.payments = null;
        assertEquals("MISE EN PAGE", render(service, ticket));
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service.documentTemplateService).render(
                eq(DocumentTemplate.DocumentType.SALE_RECEIPT), captor.capture());
        Map<String, Object> data = captor.getValue();
        assertEquals("", data.get("terminal"));
        assertEquals("", data.get("operator"));
        assertEquals("", data.get("date"));
        assertEquals("", data.get("time"));
        Map<String, Object> store = (Map<String, Object>) data.get("store");
        assertEquals("", store.get("name"));
        assertEquals("", store.get("street"));
        assertEquals("", store.get("postalCode"));
        assertEquals("", store.get("city"));
        assertEquals("", store.get("siret"));
        assertEquals(0, ((List<?>) data.get("lines")).size());
        assertEquals(0, ((List<?>) data.get("payments")).size());
        assertEquals(0, ((List<?>) data.get("vatRows")).size());
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("0,00", totals.get("excludingTax"));
        assertEquals("0,00", totals.get("vat"));
        assertEquals("0,00", totals.get("includingTax"));
        assertEquals("", totals.get("discount"));
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("", document.get("number"));
        assertEquals("", document.get("digitalPath"));
    }

    /**
     * A store whose ADDRESS is null is described with empty street, postal code
     * and city while its name and siret still print (the {@code address == null}
     * true arm of {@code storeData}, three times).
     */
    @Test
    void aStoreWithoutAnAddressIsDescribedWithEmptyAddressFields() {
        TicketRenderService service = newService();
        when(service.documentTemplateService.render(any(), any())).thenReturn("MISE EN PAGE");
        Ticket ticket = ticket(null);
        ticket.store.address = null;
        render(service, ticket);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service.documentTemplateService).render(any(), captor.capture());
        Map<String, Object> store = (Map<String, Object>) captor.getValue().get("store");
        assertEquals("MAGASIN LYON", store.get("name"));
        assertEquals("12345678900017", store.get("siret"));
        assertEquals("", store.get("street"));
        assertEquals("", store.get("postalCode"));
        assertEquals("", store.get("city"));
    }

    /**
     * A described sale states its lines, its per-rate ventilation and its
     * settlements, the rounding carried with the sign the paper reads rather
     * than the one the ledger stores (the non-null arms of {@code saleLines},
     * {@code saleVatRows} and {@code salePayments}, and both arms of the
     * rounding ternary). A cancelled line is described no more than it prints,
     * and a line carrying no label, no quantity, no unit price and no rate
     * covers those null arms of the line description.
     */
    @Test
    void aDescribedSaleStatesItsLinesVentilationAndSettlements() {
        TicketRenderService service = newService();
        when(service.documentTemplateService.render(any(), any())).thenReturn("MISE EN PAGE");
        Ticket ticket = ticket("KEY");
        ticket.globalDiscountApplied = new BigDecimal("1.50");
        TicketLine sold = line("U1", "PAIN", "1", "2.00", "2.00");
        sold.ean = "3000000000001";
        sold.unitName = "kg";
        TicketLine cancelled = line("U2", "LAIT", "1", "1.00", "1.00");
        cancelled.cancelled = true;
        TicketLine bare = new TicketLine();
        bare.lineUid = "U3";
        bare.productLabel = null;
        bare.quantity = null;
        bare.unitPrice = null;
        // A null total is NOT set here: the per-rate ventilation merges the line
        // totals and cannot take a null one — the same limit as on the register,
        // where this description helper was extracted from.
        bare.totalPrice = new BigDecimal("0.00");
        bare.vatRate = null;
        ticket.lines.add(sold);
        ticket.lines.add(cancelled);
        ticket.lines.add(bare);
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        ticket.payments.add(new RoundingPayment(new BigDecimal("0.02")));
        render(service, ticket);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service.documentTemplateService).render(any(), captor.capture());
        Map<String, Object> data = captor.getValue();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertEquals(2, lines.size());
        assertEquals("PAIN", lines.get(0).get("label"));
        assertEquals("3000000000001", lines.get(0).get("ean"));
        assertEquals("kg", lines.get(0).get("unit"));
        assertEquals("2,00", lines.get(0).get("total"));
        assertEquals("", lines.get(1).get("label"));
        assertEquals("", lines.get(1).get("quantity"));
        assertEquals("", lines.get(1).get("unitPrice"));
        assertEquals("", lines.get(1).get("vatRate"));
        assertEquals("0,00", lines.get(1).get("total"));
        List<Map<String, Object>> payments = (List<Map<String, Object>>) data.get("payments");
        assertEquals("12,00", payments.get(0).get("amount"));
        assertEquals("-0,02", payments.get(1).get("amount"));
        // Two rates: the sold line's 20 %, and the 0 % the rateless line lands on.
        assertEquals(2, ((List<?>) data.get("vatRows")).size());
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        assertEquals("1,50", totals.get("discount"));
        Map<String, Object> document = (Map<String, Object>) data.get("document");
        assertEquals("/t/1/KEY", document.get("digitalPath"));
    }

    /**
     * A settlement carrying no amount at all is described as zero rather than
     * failing (the {@code payment.amount == null} true arm of
     * {@code salePayments}).
     */
    @Test
    void aSettlementWithoutAnAmountIsDescribedAsZero() {
        TicketRenderService service = newService();
        when(service.documentTemplateService.render(any(), any())).thenReturn("MISE EN PAGE");
        Ticket ticket = ticket(null);
        CashPayment empty = new CashPayment(new BigDecimal("1.00"), new BigDecimal("1.00"));
        empty.amount = null;
        ticket.payments.add(empty);
        render(service, ticket);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service.documentTemplateService).render(any(), captor.capture());
        List<Map<String, Object>> payments =
                (List<Map<String, Object>>) captor.getValue().get("payments");
        assertEquals("0,00", payments.get(0).get("amount"));
    }

    // --------------------------------------------------
    // The built-in receipt — header
    // --------------------------------------------------

    /**
     * The logo prints as its own line above the chain name when the till carries
     * one (the {@code !logo.isEmpty()} true arm).
     */
    @Test
    void theLogoDirectivePrintsAboveTheChainNameWhenPresent() {
        TicketRenderService service = newService();
        String out = render(service, ticket(null), new ArrayList<>(), "[[LOGO abcd]]", false, 0);
        assertTrue(out.startsWith("[[LOGO abcd]]\n"));
    }

    /**
     * A till with no printable logo prints no logo line, the centred chain name
     * being the first line (the {@code !logo.isEmpty()} false arm).
     */
    @Test
    void noLogoLineIsPrintedWhenTheTillCarriesNone() {
        TicketRenderService service = newService();
        String out = render(service, ticket(null));
        assertFalse(out.contains("[[LOGO"));
        assertTrue(out.startsWith(" ".repeat((WIDTH - "INTERMARCHE".length()) / 2) + "INTERMARCHE\n"));
    }

    /**
     * An administered header message prints under the store address
     * (BO-03-08-03), and the two ways of having none — null and blank — print
     * nothing, which covers both arms of its guard.
     */
    @Test
    void theAdministeredHeaderMessagePrintsOnlyWhenItSaysSomething() {
        TicketRenderService present = newService();
        when(present.posSettingsService.ticketHeaderMessage()).thenReturn("BIENVENUE");
        assertTrue(render(present, ticket(null)).contains("BIENVENUE"));
        TicketRenderService blank = newService();
        when(blank.posSettingsService.ticketHeaderMessage()).thenReturn("   ");
        assertFalse(render(blank, ticket(null)).contains("   \n"));
        TicketRenderService absent = newService();
        when(absent.posSettingsService.ticketHeaderMessage()).thenReturn(null);
        assertTrue(render(absent, ticket(null)).contains("LYON"));
    }

    /**
     * A duplicata carries its banner and its rank (the {@code duplicata} true
     * arm); an original carries neither (its false arm).
     */
    @Test
    void aDuplicataCarriesItsNumberedBanner() {
        TicketRenderService service = newService();
        assertTrue(render(service, ticket(null), new ArrayList<>(), "", true, 3)
                .contains("*** DUPLICATA N°3 ***"));
        assertFalse(render(newService(), ticket(null)).contains("DUPLICATA"));
    }

    /**
     * A store name longer than the receipt width is printed unpadded rather than
     * shifted off the paper (the {@code text.length() >= width} true arm of
     * {@code center}).
     */
    @Test
    void aNameWiderThanTheReceiptIsPrintedUnpadded() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.store.name = "M".repeat(WIDTH + 5);
        assertTrue(render(service, ticket).contains("\n" + "M".repeat(WIDTH + 5) + "\n"));
    }

    // --------------------------------------------------
    // The built-in receipt — lines
    // --------------------------------------------------

    /**
     * A cancelled article never prints (BO-04-01-16), a label longer than twenty
     * characters is truncated, a shorter one is not, an article sold by a unit of
     * measure states that unit and one sold by the piece does not — the arms of
     * the label ternary and of the unit ternary in one pass.
     */
    @Test
    void cancelledTruncatedAndUnitBearingLinesPrintAsContracted() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        TicketLine longLabel = line("U1", "JAMBON SUPERIEUR SANS COUENNE", "1", "2.00", "2.00");
        longLabel.unitName = " kg ";
        TicketLine shortLabel = line("U2", "PAIN", "2", "1.00", "2.00");
        shortLabel.unitName = "  ";
        TicketLine cancelled = line("U3", "LAIT", "1", "1.00", "1.00");
        cancelled.cancelled = true;
        ticket.lines.add(longLabel);
        ticket.lines.add(shortLabel);
        ticket.lines.add(cancelled);
        String out = render(service, ticket);
        assertTrue(out.contains("JAMBON SUPERIEUR SAN"));
        assertFalse(out.contains("JAMBON SUPERIEUR SANS COUENNE"));
        assertTrue(out.contains("1,00 kg x"));
        assertTrue(out.contains("PAIN"));
        assertFalse(out.contains("LAIT"));
    }

    /**
     * The EAN prints under the label only when the back office asks for it and
     * the line carries one (LC-02-04-04): all three arms of the guard — asked and
     * carried, asked and absent, not asked.
     */
    @Test
    void theEanPrintsOnlyWhenAskedForAndCarried() {
        TicketRenderService asked = newService();
        when(asked.posSettingsService.showEan()).thenReturn(true);
        Ticket carried = ticket(null);
        TicketLine withEan = line("U1", "PAIN", "1", "2.00", "2.00");
        withEan.ean = "3000000000001";
        TicketLine withoutEan = line("U2", "LAIT", "1", "1.00", "1.00");
        withoutEan.ean = null;
        TicketLine emptyEan = line("U3", "OEUF", "1", "1.00", "1.00");
        emptyEan.ean = "";
        carried.lines.add(withEan);
        carried.lines.add(withoutEan);
        carried.lines.add(emptyEan);
        String shown = render(asked, carried);
        assertTrue(shown.contains("  3000000000001\n"));
        TicketRenderService notAsked = newService();
        when(notAsked.posSettingsService.showEan()).thenReturn(false);
        Ticket other = ticket(null);
        TicketLine line = line("U1", "PAIN", "1", "2.00", "2.00");
        line.ean = "3000000000001";
        other.lines.add(line);
        assertFalse(render(notAsked, other).contains("  3000000000001\n"));
    }

    /**
     * The articles print under their family heading when the FAMILY order is
     * administered (LC-08-01-07): the heading prints the first time a family
     * appears and NOT for a second consecutive line of the same one, so
     * BOULANGERIE appears exactly once though two lines carry it.
     */
    @Test
    void theArticlesPrintUnderTheirFamilyHeadingWhenAdministered() {
        TicketRenderService service = newService();
        when(service.posSettingsService.ticketLineOrder()).thenReturn("FAMILY");
        Ticket ticket = ticket(null);
        TicketLine a = line("U1", "PAIN", "1", "2.00", "2.00");
        a.familyLabel = "BOULANGERIE";
        TicketLine b = line("U2", "BAGUETTE", "1", "1.00", "1.00");
        b.familyLabel = "BOULANGERIE";
        TicketLine c = line("U3", "LAIT", "1", "1.00", "1.00");
        c.familyLabel = "CREMERIE";
        ticket.lines.add(a);
        ticket.lines.add(b);
        ticket.lines.add(c);
        String out = render(service, ticket);
        assertTrue(out.contains("BOULANGERIE"));
        assertTrue(out.contains("CREMERIE"));
        assertEquals(out.indexOf("BOULANGERIE"), out.lastIndexOf("BOULANGERIE"));
    }

    // --------------------------------------------------
    // The built-in receipt — engine advantages
    // --------------------------------------------------

    /**
     * A line the engine revalued prints its advantage as a signed delta under the
     * article, named by the advantage label when there is one, by the offer label
     * when there is not, and "AVANTAGE" when there is neither — the three arms of
     * the label choice, plus the no-delta arm on a line the engine left alone and
     * the no-trace arm on a line it never saw.
     */
    @Test
    void anEngineAdvantagePrintsAsASignedDeltaUnderItsArticle() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        ticket.lines.add(line("U2", "LAIT", "1", "1.00", "1.00"));
        ticket.lines.add(line("U3", "OEUF", "1", "3.00", "3.00"));
        ticket.lines.add(line("U4", "BEURRE", "1", "4.00", "4.00"));
        ticket.lines.add(line("U5", "SUCRE", "1", "5.00", "5.00"));
        List<TicketLineValuation> traces = new ArrayList<>();
        traces.add(valuation("U1", "2.00", "1.50", "REMISE FIDELITE", "OFFRE"));
        traces.add(valuation("U2", "1.00", "0.80", null, "OFFRE DU JOUR"));
        traces.add(valuation("U3", "3.00", "2.00", null, null));
        traces.add(valuation("U4", "4.00", "4.00", "SANS EFFET", null));
        String out = render(service, ticket, traces, "", false, 0);
        assertTrue(out.contains("REMISE FIDELITE"));
        assertTrue(out.contains("-0,50 E"));
        assertTrue(out.contains("OFFRE DU JOUR"));
        assertTrue(out.contains("-0,20 E"));
        assertTrue(out.contains("AVANTAGE"));
        assertTrue(out.contains("-1,00 E"));
        assertFalse(out.contains("SANS EFFET"));
    }

    /**
     * An advantage label longer than twenty-six characters is truncated so the
     * delta stays on the line (the truncation true arm); a shorter one is left
     * alone (its false arm, covered above).
     */
    @Test
    void anOverlongAdvantageLabelIsTruncated() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        List<TicketLineValuation> traces = new ArrayList<>();
        traces.add(valuation("U1", "2.00", "1.50",
                "REMISE EXCEPTIONNELLE DE FIN DE SEMAINE", null));
        String out = render(service, ticket, traces, "", false, 0);
        assertTrue(out.contains("REMISE EXCEPTIONNELLE DE F"));
        assertFalse(out.contains("REMISE EXCEPTIONNELLE DE FIN"));
    }

    // --------------------------------------------------
    // The built-in receipt — totals
    // --------------------------------------------------

    /**
     * A ticket-wide discount prints as a negative line above the total when one
     * was applied (the {@code globalDiscountApplied != null} true arm); a sale
     * without one prints no such line (its false arm).
     */
    @Test
    void aTicketWideDiscountPrintsAboveTheTotal() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.globalDiscountApplied = new BigDecimal("1.50");
        assertTrue(render(service, ticket).contains("REMISE TICKET"));
        assertFalse(render(newService(), ticket(null)).contains("REMISE TICKET"));
    }

    /**
     * The per-rate VAT ventilation prints only when the back office asks for it
     * (BO-10-06-04), and a cancelled article is outside it exactly as it is
     * outside the totals.
     */
    @Test
    void theVatVentilationPrintsOnlyWhenAskedFor() {
        TicketRenderService asked = newService();
        Ticket ticket = ticket(null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        TicketLine cancelled = line("U2", "LAIT", "1", "10.00", "10.00");
        cancelled.vatRate = new BigDecimal("0.0550");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        String shown = render(asked, ticket);
        assertTrue(shown.contains("TVA 20"));
        assertFalse(shown.contains("TVA 5,5"));
        TicketRenderService notAsked = newService();
        when(notAsked.posSettingsService.vatBreakdownEnabled()).thenReturn(false);
        assertFalse(render(notAsked, ticket).contains("  TVA 20"));
    }

    // --------------------------------------------------
    // The built-in receipt — settlements
    // --------------------------------------------------

    /**
     * A plain settlement prints its kind and its amount, the kind being the
     * payment class name stripped of its suffix.
     */
    @Test
    void aPlainSettlementPrintsItsKindAndAmount() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("12.00")));
        String out = render(service, ticket);
        assertTrue(out.contains("CASH"));
        assertTrue(out.contains("12,00 E"));
    }

    /**
     * A foreign settlement states the three figures the customer and the accounts
     * both need — what was handed over, what it was worth, and the rate that
     * connects them (LC-07-14-05). A second one with no rate and no currency code
     * covers the empty arms of both ternaries.
     */
    @Test
    void aForeignSettlementStatesAmountCurrencyAndRate() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ForeignCurrencyPayment chf = new ForeignCurrencyPayment(new BigDecimal("10.00"));
        chf.currencyCode = "CHF";
        chf.foreignAmount = new BigDecimal("9.50");
        chf.exchangeRate = new BigDecimal("1.0531");
        ForeignCurrencyPayment noRate = new ForeignCurrencyPayment(new BigDecimal("2.00"));
        noRate.currencyCode = null;
        noRate.foreignAmount = new BigDecimal("2.00");
        noRate.exchangeRate = null;
        ticket.payments.add(chf);
        ticket.payments.add(noRate);
        String out = render(service, ticket);
        assertTrue(out.contains("DEVISE CHF"));
        assertTrue(out.contains("9,50 CHF  TAUX 1.0531"));
        assertTrue(out.contains("  2,00   TAUX \n"));
    }

    /**
     * A rounding prints with the OPPOSITE sign of the stored ledger amount
     * (LC-07-03-06): a ledger {@code +0,02} reads on paper as {@code -0,02},
     * because on paper it is the adjustment to what the customer hands over.
     */
    @Test
    void aRoundingPrintsWithTheInvertedSign() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.payments.add(new RoundingPayment(new BigDecimal("0.02")));
        String out = render(service, ticket);
        assertTrue(out.contains("ARRONDI"));
        assertTrue(out.contains("-0,02 E"));
    }

    /**
     * A credit settlement names its debtor under its own line — sale day, account
     * number, account name — because a month later this receipt is what the
     * account is reconciled against (LC-07-09-06). A second credit with a null
     * number and a null name covers the empty-string arms of both ternaries.
     */
    @Test
    void aCreditSettlementNamesItsDebtor() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        CreditPayment full = new CreditPayment(new BigDecimal("10.00"));
        full.accountNumber = "CPT-42";
        full.accountName = "MAIRIE DE LYON";
        CreditPayment blank = new CreditPayment(new BigDecimal("2.00"));
        blank.accountNumber = null;
        blank.accountName = null;
        ticket.payments.add(full);
        ticket.payments.add(blank);
        String out = render(service, ticket);
        assertTrue(out.contains("CREDIT"));
        assertTrue(out.contains("02/08/2026  COMPTE CPT-42"));
        assertTrue(out.contains("MAIRIE DE LYON"));
        assertTrue(out.contains("02/08/2026  COMPTE \n"));
    }

    // --------------------------------------------------
    // The built-in receipt — footer
    // --------------------------------------------------

    /**
     * The online receipt link prints when the sale carries a digital key (the
     * {@code digitalKey != null} true arm) and nothing prints when it does not
     * (its false arm).
     */
    @Test
    void theOnlineReceiptLinkPrintsOnlyWithADigitalKey() {
        TicketRenderService service = newService();
        assertTrue(render(service, ticket("KEY")).contains("/t/1/KEY"));
        assertFalse(render(newService(), ticket(null)).contains("Votre ticket en ligne"));
    }

    /**
     * An administered footer message prints after the courtesy line
     * (BO-03-08-05), and the two ways of having none — null and blank — print
     * nothing.
     */
    @Test
    void theAdministeredFooterMessagePrintsOnlyWhenItSaysSomething() {
        TicketRenderService present = newService();
        when(present.posSettingsService.ticketFooterMessage()).thenReturn("A BIENTOT CHEZ NOUS");
        assertTrue(render(present, ticket(null)).contains("A BIENTOT CHEZ NOUS"));
        TicketRenderService blank = newService();
        when(blank.posSettingsService.ticketFooterMessage()).thenReturn("   ");
        assertTrue(render(blank, ticket(null)).contains("A BIENTOT"));
        TicketRenderService absent = newService();
        when(absent.posSettingsService.ticketFooterMessage()).thenReturn(null);
        assertTrue(render(absent, ticket(null)).contains("A BIENTOT"));
    }

    /**
     * The ticket number closes the receipt as a barcode directive: it is what a
     * return, a duplicate or a claim is looked up by, and typing it back by hand
     * is where the mistakes happen.
     */
    @Test
    void theTicketNumberClosesTheReceiptAsABarcode() {
        TicketRenderService service = newService();
        assertTrue(render(service, ticket(null)).endsWith("[[BARCODE C04-00000001]]\n"));
    }

    /**
     * A label and a value that together exceed the receipt width are separated by
     * a single space rather than a negative one (the {@code space < 1} true arm of
     * {@code formatLine}).
     */
    @Test
    void anOverlongLabelAndValuePairKeepsOneSeparatingSpace() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        List<TicketLineValuation> traces = new ArrayList<>();
        traces.add(valuation("U1", "2.00", "-12345676.90", "AVANTAGE SUR VINGT-SIX CA", null));
        String out = render(service, ticket, traces, "", false, 0);
        assertTrue(out.contains("  AVANTAGE SUR VINGT-SIX CA -12345678,90 E\n"));
    }

    // --------------------------------------------------
    // The loyalty zone (BO-03-03-25 / -29 / -30 / -31 / -32)
    // --------------------------------------------------

    /**
     * BO-03-03-25: the node prints the loyalty section from what the selling
     * register froze on the row. It used to be absent here by construction, so
     * a duplicata issued at the welcome desk came out shorter than the paper the
     * customer got at the till.
     */
    @Test
    void thenodePrintsTheLoyaltySectionFrozenOnTheSale() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        Ticket ticket = ticket(null);
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("1.03");
        ticket.fidelityLines.add(
                new TicketFidelityLine("SOCLE", "Cagnotte socle", new BigDecimal("0.28")));
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        String out = render(service, ticket);
        assertTrue(out.contains("CAGNOTTE DU JOUR"));
        assertTrue(out.contains("+1,03 E"));
        assertTrue(out.contains("Cagnotte socle"));
        assertTrue(out.contains("+0,28 E"));
    }

    /**
     * The three legs that silence the section, each on its own: the advantages
     * disabled, no frozen earn at all, and an earn of zero.
     */
    @Test
    void thethreeLegsThatSilenceTheLoyaltySection() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(false);
        Ticket disabled = ticket(null);
        disabled.fidelityEarnTotal = new BigDecimal("1.03");
        disabled.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        assertFalse(render(service, disabled).contains("CAGNOTTE DU JOUR"));
        TicketRenderService enabled = newService();
        when(enabled.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        Ticket nothing = ticket(null);
        nothing.fidelityEarnTotal = null;
        nothing.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        assertFalse(render(enabled, nothing).contains("CAGNOTTE DU JOUR"));
        Ticket zero = ticket(null);
        zero.fidelityEarnTotal = BigDecimal.ZERO;
        zero.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        assertFalse(render(enabled, zero).contains("CAGNOTTE DU JOUR"));
    }

    /**
     * The zone an administered layout reads on the node is the one the register
     * builds: same keys, same figures, built from the same columns.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thezoneAlayoutReadsIsTheRegistersOwn() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("1.03");
        ticket.fidelityAvailableBalance = new BigDecimal("42.30");
        ticket.fidelityLines.add(
                new TicketFidelityLine("SOCLE", "Cagnotte socle", new BigDecimal("0.28")));
        ticket.payments.add(new FidelityPayment(new BigDecimal("3.00")));
        java.util.Map<String, Object> zone = (java.util.Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("2990000000019", zone.get("card"));
        assertEquals(true, zone.get("present"));
        assertEquals("1,03", zone.get("earnTotal"));
        assertEquals("42,30", zone.get("availableBalance"));
        assertEquals("3,00", zone.get("usedAmount"));
        assertEquals(false, zone.get("unavailable"));
        assertEquals("", zone.get("message"));
        assertEquals(1, ((List<?>) zone.get("lines")).size());
    }

    /**
     * A sale carrying no card, no frozen figure, no payment list and no
     * collection at all still describes itself — the four null arms of that
     * zone, which a sale ingested from an older register presents together.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thezoneOfAbareSaleStillDescribesItself() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.fidelityLines = null;
        ticket.payments = null;
        java.util.Map<String, Object> zone = (java.util.Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("", zone.get("card"));
        assertEquals(false, zone.get("present"));
        assertEquals("", zone.get("earnTotal"));
        assertEquals("", zone.get("availableBalance"));
        assertEquals("0,00", zone.get("usedAmount"));
        assertTrue(((List<?>) zone.get("lines")).isEmpty());
    }

    /**
     * BO-03-03-25: the loyalty section groups its advantage lines by advantage
     * type, orders the groups by the received advantageTypeOrder (never anything
     * else), prints the stored type label as the header — falling back to the
     * type code when the label is null — and appends any untyped line flat after
     * the groups.
     */
    @Test
    void theLoyaltySectionGroupsByAdvantageTypeAndOrders() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        Ticket ticket = ticket(null);
        ticket.fidelityEarnTotal = new BigDecimal("1.03");
        ticket.fidelityLines.add(new TicketFidelityLine("R2", "Bonus soir", new BigDecimal("0.20"),
                "B", "Bonus", 2, null, null));
        ticket.fidelityLines.add(new TicketFidelityLine("R1", "Avantage jour", new BigDecimal("0.30"),
                "A", "Avantages", 1, null, null));
        ticket.fidelityLines.add(new TicketFidelityLine("R3", "Sans ordre", new BigDecimal("0.10"),
                "Z", null, null, null, null));
        ticket.fidelityLines.add(new TicketFidelityLine("R4", "Ligne libre", new BigDecimal("0.05")));
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        String out = render(service, ticket);
        int avantages = out.indexOf("Avantages");
        int bonus = out.indexOf("Bonus");
        int untypedKey = out.indexOf("Z");
        int libre = out.indexOf("Ligne libre");
        assertTrue(avantages >= 0 && bonus >= 0 && untypedKey >= 0 && libre >= 0);
        assertTrue(avantages < bonus, "order 1 group before order 2 group");
        assertTrue(bonus < untypedKey, "ordered groups before the unordered (MAX) group");
        assertTrue(untypedKey < libre, "typed groups before the untyped flat tail");
        assertTrue(out.contains("Avantage jour"));
        assertTrue(out.contains("Sans ordre"));
    }

    /**
     * The grouping tolerates a null loyalty-line collection with a frozen earn —
     * a sale ingested from an older register — printing the header alone and no
     * advantage line (the null arm of the collection guard).
     */
    @Test
    void theLoyaltySectionToleratesNullLinesWithAnEarn() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        Ticket ticket = ticket(null);
        ticket.fidelityEarnTotal = new BigDecimal("1.03");
        ticket.fidelityLines = null;
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        String out = render(service, ticket);
        assertTrue(out.contains("CAGNOTTE DU JOUR"));
    }

    /**
     * The projected balance, when the ingested ticket carries one, is printed
     * under the administered label and marked PREVISIONNEL (imfid response B);
     * a ticket without one prints neither (both arms of the guard).
     */
    @Test
    void theProjectedBalanceIsPrintedUnderTheAdministeredLabelWhenCarried() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        when(service.posSettingsService.fidelityProjectedLabel()).thenReturn("CAGNOTTE PREVISIONNELLE");
        Ticket carried = ticket(null);
        carried.fidelityEarnTotal = new BigDecimal("1.03");
        carried.fidelityProjectedAfterEarn = new BigDecimal("42.58");
        carried.fidelityLines.add(new TicketFidelityLine("SOCLE", "Cagnotte socle", new BigDecimal("0.28")));
        carried.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        String out = render(service, carried);
        assertTrue(out.contains("CAGNOTTE PREVISIONNELLE"));
        assertTrue(out.contains("42,58 E"));
        assertTrue(out.contains("(PREVISIONNEL)"));
        TicketRenderService bare = newService();
        when(bare.posSettingsService.fidelityAdvantagesEnabled()).thenReturn(true);
        Ticket noProjection = ticket(null);
        noProjection.fidelityEarnTotal = new BigDecimal("1.03");
        noProjection.fidelityProjectedAfterEarn = null;
        noProjection.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        assertFalse(render(bare, noProjection).contains("(PREVISIONNEL)"));
    }

    /**
     * The administered layout reads the advantage grouping and the projected /
     * after balances too — present here (non-blank arms), so a store template
     * groups and forecasts identically to the register.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thelayoutZoneCarriesTheAdvantageGroupingAndBalances() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityProjectedLabel()).thenReturn("CAGNOTTE PREVISIONNELLE");
        Ticket ticket = ticket(null);
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("1.03");
        ticket.fidelityProjectedAfterEarn = new BigDecimal("42.58");
        ticket.fidelityBalanceAfter = new BigDecimal("39.58");
        ticket.fidelityAnonymousProjection = true;
        ticket.fidelityLines.add(new TicketFidelityLine("R1", "Avantage jour", new BigDecimal("0.30"),
                "A", "Avantages", 1, "CAT", "Categorie"));
        java.util.Map<String, Object> zone = (java.util.Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("42,58", zone.get("projectedAfterEarn"));
        assertEquals("CAGNOTTE PREVISIONNELLE", zone.get("projectedLabel"));
        assertEquals("39,58", zone.get("balanceAfter"));
        assertEquals(true, zone.get("anonymousProjection"));
        List<java.util.Map<String, Object>> lines = (List<java.util.Map<String, Object>>) zone.get("lines");
        assertEquals("A", lines.get(0).get("advantageType"));
        assertEquals("Avantages", lines.get(0).get("advantageTypeLabel"));
        assertEquals("1", lines.get(0).get("advantageTypeOrder"));
        assertEquals("CAT", lines.get(0).get("advantageCategory"));
        assertEquals("Categorie", lines.get(0).get("advantageCategoryLabel"));
    }

    /**
     * The administered layout leaves the balances and the grouping blank when the
     * ingested ticket carries none — the null arms of every new field, as a sale
     * from an older register presents them.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thelayoutZoneLeavesTheNewFieldsBlankWhenAbsent() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityProjectedLabel()).thenReturn("CAGNOTTE PREVISIONNELLE");
        Ticket ticket = ticket(null);
        ticket.fidelityEarnTotal = new BigDecimal("1.03");
        ticket.fidelityProjectedAfterEarn = null;
        ticket.fidelityBalanceAfter = null;
        ticket.fidelityLines.add(new TicketFidelityLine("R4", "Ligne libre", new BigDecimal("0.05")));
        java.util.Map<String, Object> zone = (java.util.Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("", zone.get("projectedAfterEarn"));
        assertEquals("", zone.get("balanceAfter"));
        assertEquals(false, zone.get("anonymousProjection"));
        List<java.util.Map<String, Object>> lines = (List<java.util.Map<String, Object>>) zone.get("lines");
        assertEquals("", lines.get(0).get("advantageType"));
        assertEquals("", lines.get(0).get("advantageTypeOrder"));
    }

    /**
     * BO-03-03-29 and BO-03-03-30: a sale made while the programme was silent
     * carries the administered message — the holder's one with its {@code
     * {carte}} token replaced, the non-holder's other one — and a shop that
     * clears the parameter says nothing, on both the blank and the null arm.
     */
    @Test
    @SuppressWarnings("unchecked")
    void asilentProgrammeIsStatedOnThePaper() {
        TicketRenderService service = newService();
        when(service.posSettingsService.fidelityOfflineMessage())
                .thenReturn("CARTE {carte} INDISPONIBLE", "  ", null);
        when(service.posSettingsService.fidelityOfflineMessageNoCard())
                .thenReturn("PRESENTEZ VOTRE CARTE");
        Ticket holder = ticket(null);
        holder.fidelityCard = "2990000000019";
        holder.fidelityUnavailable = true;
        assertEquals("CARTE 2990000000019 INDISPONIBLE", message(service, holder));
        assertEquals("", message(service, holder));
        assertEquals("", message(service, holder));
        Ticket stranger = ticket(null);
        stranger.fidelityUnavailable = true;
        assertEquals("PRESENTEZ VOTRE CARTE", message(service, stranger));
    }

    /**
     * A card presented but BLANK is described as not present (the {@code
     * !fidelityCard.isBlank()} false arm of the loyalty presence flag, reached
     * with a non-null card): the field carries a value, so the first guard
     * passes, but a blank value is no card at all.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ablankCardIsDescribedAsNotPresent() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.fidelityCard = "   ";
        java.util.Map<String, Object> zone = (java.util.Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals(false, zone.get("present"));
        assertEquals("   ", zone.get("card"));
    }

    /**
     * A loyalty settlement carrying NO amount is skipped by the used-amount tally
     * rather than failing it (the {@code payment.amount != null} false arm of
     * {@code loyaltyPaid}, on a payment that is a {@code FidelityPayment}): the
     * kind matches but the figure is absent, so nothing is added and the used
     * amount stays zero.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aloyaltySettlementWithoutAnAmountIsNotTallied() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        FidelityPayment noAmount = new FidelityPayment(new BigDecimal("3.00"));
        noAmount.amount = null;
        ticket.payments.add(noAmount);
        java.util.Map<String, Object> zone = (java.util.Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity");
        assertEquals("0,00", zone.get("usedAmount"));
    }

    /**
     * A described line carrying NO total is stated with an empty total rather than
     * failing (the {@code line.totalPrice == null} true arm of {@code saleLines}):
     * the per-rate ventilation now tolerates the same null, so the description
     * survives a line a partial push left without a total.
     */
    @Test
    @SuppressWarnings("unchecked")
    void adescribedLineWithoutATotalIsStatedEmpty() {
        TicketRenderService service = newService();
        when(service.documentTemplateService.render(any(), any())).thenReturn("MISE EN PAGE");
        Ticket ticket = ticket(null);
        TicketLine bare = new TicketLine();
        bare.lineUid = "U1";
        bare.productLabel = "PAIN";
        bare.quantity = new BigDecimal("1");
        bare.unitPrice = new BigDecimal("2.00");
        bare.totalPrice = null;
        bare.vatRate = new BigDecimal("0.2000");
        ticket.lines.add(bare);
        java.util.Map<String, Object> data = service.saleDocumentData(ticket, false, 0);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertEquals("", lines.get(0).get("total"));
    }

    /**
     * A label and value pair whose combined width EXCEEDS the receipt is separated
     * by a single forced space rather than a negative one (the {@code space < 1}
     * true arm of {@code formatLine}): a twenty-six-character advantage label and
     * a nine-figure delta together overrun the width, and the line still renders.
     */
    @Test
    void anExcessivelyWideLabelAndValuePairForcesASingleSpace() {
        TicketRenderService service = newService();
        Ticket ticket = ticket(null);
        ticket.lines.add(line("U1", "PAIN", "1", "2.00", "2.00"));
        List<TicketLineValuation> traces = new ArrayList<>();
        traces.add(valuation("U1", "2.00", "-123456787.90",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ", null));
        String out = render(service, ticket, traces, "", false, 0);
        assertTrue(out.contains("  ABCDEFGHIJKLMNOPQRSTUVWXYZ -123456789,90 E\n"));
    }

    /**
     * Reads the message of a sale's loyalty zone.
     *
     * @param service the renderer under test
     * @param ticket the sale to describe
     * @return the resolved message
     */
    @SuppressWarnings("unchecked")
    private String message(TicketRenderService service, Ticket ticket) {
        return (String) ((java.util.Map<String, Object>)
                service.saleDocumentData(ticket, false, 0).get("fidelity")).get("message");
    }
}
