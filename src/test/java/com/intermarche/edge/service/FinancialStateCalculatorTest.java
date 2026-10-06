package com.intermarche.edge.service;

import com.intermarche.edge.domain.catalog.Nomenclature;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.payment.ForeignCurrencyPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialStateLine;
import com.intermarche.edge.domain.session.FinancialStateLine.StateSection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinancialStateCalculator}.
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database: the calculator is a
 * pure function of the tickets, refunds and configuration it is handed. The
 * nomenclature climb goes through {@code ProductFamily} static finders, mocked
 * with {@link org.mockito.Mockito#mockStatic} in the one test that exercises it.
 * The financial identities of §5.1 are asserted, not commented.
 */
class FinancialStateCalculatorTest {

    /** The calculator under test. */
    private final FinancialStateCalculator calculator = new FinancialStateCalculator();

    /**
     * Builds a bare config with sensible defaults, overridable per test.
     *
     * @param slot the hourly slot width
     * @param level the nomenclature restitution level
     * @param sectors the sector map
     * @param bestSellers the best-sellers depth
     * @param cashierBreakdown whether the cashier breakdown is included
     * @return the config
     */
    private FinancialStateCalculator.Config config(int slot, int level, Map<String, String> sectors,
                                                  int bestSellers, boolean cashierBreakdown) {
        return new FinancialStateCalculator.Config(slot, level, sectors, bestSellers, cashierBreakdown);
    }

    /**
     * Builds a store-scope day X state over a one-day interval.
     *
     * @return the state skeleton
     */
    private FinancialState storeState() {
        FinancialState state = new FinancialState();
        state.stateType = FinancialState.StateType.X;
        state.scope = FinancialState.StateScope.STORE;
        state.periodKind = FinancialState.PeriodKind.DAY;
        state.fromInstant = LocalDateTime.of(2026, 1, 1, 0, 0);
        state.toInstant = LocalDateTime.of(2026, 1, 2, 0, 0);
        return state;
    }

    /**
     * Builds a closed ticket with the given total and closing moment.
     *
     * @param grandTotal the grand total, or null
     * @param closing the closing moment, or null
     * @return the ticket
     */
    private Ticket closed(String grandTotal, LocalDateTime closing) {
        Ticket ticket = new Ticket();
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.grandTotal = grandTotal == null ? null : new BigDecimal(grandTotal);
        ticket.closingDate = closing;
        return ticket;
    }

    /**
     * Builds a goods sale/return line.
     *
     * @param quantity the quantity, or null
     * @param originalUnitPrice the catalog unit price, or null
     * @param unitPrice the unit price, or null
     * @param totalPrice the line total, or null
     * @param vatRate the VAT rate, or null
     * @return the line
     */
    private TicketLine goods(String quantity, String originalUnitPrice, String unitPrice, String totalPrice, String vatRate) {
        TicketLine line = new TicketLine();
        line.quantity = quantity == null ? null : new BigDecimal(quantity);
        line.originalUnitPrice = originalUnitPrice == null ? null : new BigDecimal(originalUnitPrice);
        line.unitPrice = unitPrice == null ? null : new BigDecimal(unitPrice);
        line.totalPrice = totalPrice == null ? null : new BigDecimal(totalPrice);
        line.vatRate = vatRate == null ? null : new BigDecimal(vatRate);
        return line;
    }

    /**
     * Builds a closed refund.
     *
     * @param originalTicketId the original ticket id, or null (no ticket)
     * @param totalAmount the refunded total
     * @param lineQuantities the refunded line quantities
     * @return the refund
     */
    private Refund refund(Long originalTicketId, String totalAmount, String... lineQuantities) {
        Refund refund = new Refund();
        refund.status = Refund.RefundStatus.CLOSED;
        refund.originalTicketId = originalTicketId;
        refund.totalAmount = new BigDecimal(totalAmount);
        for (String quantity : lineQuantities) {
            RefundLine line = new RefundLine();
            line.quantity = new BigDecimal(quantity);
            refund.lines.add(line);
        }
        return refund;
    }

    /**
     * Returns the lines of one section, in rank order.
     *
     * @param state the filled state
     * @param section the section wanted
     * @return the section's lines
     */
    private List<FinancialStateLine> linesOf(FinancialState state, StateSection section) {
        List<FinancialStateLine> result = new ArrayList<>();
        for (FinancialStateLine line : state.lines) {
            if (line.section == section) {
                result.add(line);
            }
        }
        return result;
    }

    /**
     * The financial identities of §5.1 hold over a rich day: gross before
     * negatives, discount as the gross-to-net gap, net after every negative,
     * customers as positive tickets, items by R15, and the four indicators kept
     * out of the turnover.
     */
    @Test
    void fillComputesTheFinancialIdentities() {
        FinancialState state = storeState();
        Ticket t1 = closed("32.00", LocalDateTime.of(2026, 1, 1, 8, 10));
        TicketLine l1 = goods("5", "10.00", "9.00", "45.00", "0.2000");
        l1.ean = "E1";
        TicketLine l2 = goods("1.5", null, "4.00", "6.00", "0.0550");
        l2.plu = "P2";
        TicketLine l3 = goods("-1", null, "5.00", "-5.00", "0.2000");
        l3.ean = "E3";
        TicketLine l4 = goods("1", null, "3.00", "3.00", "0.2000");
        l4.cancelled = true;
        l4.cancelledLast = true;
        TicketLine l5 = goods("1", null, "7.00", null, "0.2000");
        l5.cancelled = true;
        l5.cancelledLast = false;
        TicketLine l6 = goods("1", null, "50.00", "50.00", "0.2000");
        l6.moneyProduct = true;
        TicketLine l7 = goods(null, null, null, "0.00", "0.2000");
        l7.productLabel = "X";
        TicketLine l8 = goods("1", null, null, null, null);
        l8.ean = "E8";
        t1.lines.addAll(List.of(l1, l2, l3, l4, l5, l6, l7, l8));
        Ticket t2 = new Ticket();
        t2.status = Ticket.TicketStatus.CANCELLED;
        t2.grandTotal = new BigDecimal("25.00");
        Ticket t3 = new Ticket();
        t3.status = Ticket.TicketStatus.OPEN;
        Ticket t4 = closed("0.00", LocalDateTime.of(2026, 1, 1, 8, 20));
        t4.lines.add(goods("-1", null, "2.00", "-2.00", "0.2000"));
        List<Refund> refunds = List.of(
                refund(5L, "8.00", "2"),
                refund(null, "4.00", "1"),
                openRefund());
        calculator.fill(state, List.of(t1, t2, t3, t4), refunds,
                config(60, 1, Map.of(), 20, false));
        assertEquals(new BigDecimal("56.0000"), state.grossTurnover);
        assertEquals(new BigDecimal("5.0000"), state.discountTotal);
        assertEquals(new BigDecimal("15.0000"), state.refundWithTicketTotal);
        assertEquals(new BigDecimal("4.0000"), state.refundWithoutTicketTotal);
        assertEquals(new BigDecimal("32.0000"), state.netTurnover);
        assertEquals(state.netTurnover, state.grossTurnover.subtract(state.discountTotal)
                .subtract(state.refundWithTicketTotal).subtract(state.refundWithoutTicketTotal));
        assertEquals(1, state.customerCount);
        assertEquals(2, state.itemCount);
        assertEquals(new BigDecimal("32.00"), state.averageBasket);
        assertEquals(1, state.cancelledLineCount);
        assertEquals(new BigDecimal("3.0000"), state.cancelledLineTotal);
        assertEquals(1, state.endorsedCancelCount);
        assertEquals(new BigDecimal("0.0000"), state.endorsedCancelTotal);
        assertEquals(1, state.abandonedTicketCount);
        assertEquals(new BigDecimal("25.0000"), state.abandonedTicketTotal);
        assertEquals(1, state.giftCardCount);
        assertEquals(new BigDecimal("50.0000"), state.giftCardTotal);
    }

    /**
     * Builds an open refund, which the calculator skips.
     *
     * @return the open refund
     */
    private Refund openRefund() {
        Refund refund = new Refund();
        refund.status = Refund.RefundStatus.OPEN;
        refund.originalTicketId = 9L;
        refund.totalAmount = new BigDecimal("99.00");
        return refund;
    }

    /**
     * The settlement means take the cash on the applied amount and not the
     * tendered one (R11), carry the currency amount in the quantity, and keep TR
     * apart; the VAT lines split tax per rate and sum to the VAT total; the hourly
     * lines run from the first slot to the last with the empty middle slot at zero
     * (R17).
     */
    @Test
    void fillBuildsSettlementVatAndHourlySections() {
        FinancialState state = storeState();
        Ticket t1 = closed("15.50", LocalDateTime.of(2026, 1, 1, 8, 10));
        t1.lines.add(goods("1", null, "10.00", "10.00", "0.2000"));
        t1.lines.add(goods("1", null, "5.50", "5.50", "0.0550"));
        CashPayment cash = new CashPayment(new BigDecimal("12.00"), new BigDecimal("20.00"));
        CardPayment card = new CardPayment(new BigDecimal("3.50"));
        ForeignCurrencyPayment currency = new ForeignCurrencyPayment(new BigDecimal("5.00"));
        currency.foreignAmount = new BigDecimal("6.00");
        currency.currencyCode = "USD";
        t1.payments.addAll(List.of(cash, card, currency));
        Ticket t2 = closed("20.00", LocalDateTime.of(2026, 1, 1, 10, 5));
        t2.lines.add(goods("1", null, "20.00", "20.00", "0.2000"));
        calculator.fill(state, List.of(t1, t2), List.of(), config(60, 1, Map.of(), 20, false));
        List<FinancialStateLine> settlement = linesOf(state, StateSection.SETTLEMENT);
        assertEquals(3, settlement.size());
        assertEquals("CASH", settlement.get(0).code);
        assertEquals(new BigDecimal("12.0000"), settlement.get(0).amount);
        assertEquals("CARD", settlement.get(1).code);
        assertEquals("DEVISE", settlement.get(2).code);
        assertEquals(new BigDecimal("5.0000"), settlement.get(2).amount);
        assertEquals(new BigDecimal("6.0000"), settlement.get(2).quantity);
        List<FinancialStateLine> vat = linesOf(state, StateSection.VAT);
        assertEquals(2, vat.size());
        assertEquals("0.0550", vat.get(0).code);
        assertEquals("0.2000", vat.get(1).code);
        assertEquals(new BigDecimal("30.0000"), vat.get(1).amount);
        assertEquals(new BigDecimal("25.0000"), vat.get(1).amountExcludingTax);
        assertEquals(new BigDecimal("5.0000"), vat.get(1).vatAmount);
        assertEquals(new BigDecimal("5.2867"), state.vatTotal);
        List<FinancialStateLine> hourly = linesOf(state, StateSection.HOURLY);
        assertEquals(3, hourly.size());
        assertEquals("08:00", hourly.get(0).code);
        assertEquals(new BigDecimal("15.5000"), hourly.get(0).amount);
        assertEquals("09:00", hourly.get(1).code);
        assertEquals(new BigDecimal("0.0000"), hourly.get(1).amount);
        assertEquals("10:00", hourly.get(2).code);
        assertEquals(new BigDecimal("20.0000"), hourly.get(2).amount);
    }

    /**
     * The nomenclature content restitutes each line at the configured level and
     * each at its rank-0 sector, an unmapped sector and an unknown family both
     * landing on NON CLASSÉ, never folded into a sector; the food share is the
     * three food sectors over the net turnover (R16). The climb is cached, so a
     * repeated family code resolves once.
     */
    @Test
    void fillClassifiesNomenclatureAndSectors() {
        FinancialState state = storeState();
        Ticket t1 = closed("52.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        t1.lines.add(family(goods("1", null, "12.00", "12.00", "0.2000"), "1201"));
        t1.lines.add(family(goods("1", null, "12.00", "12.00", "0.2000"), "1201"));
        t1.lines.add(family(goods("1", null, "20.00", "20.00", "0.2000"), "5601"));
        t1.lines.add(family(goods("1", null, "8.00", "8.00", "0.2000"), "9999"));
        Nomenclature scheme = mock(Nomenclature.class);
        when(scheme.parentCodeOf("1201")).thenReturn("12");
        when(scheme.parentCodeOf("5601")).thenReturn("56");
        ProductFamily n1201 = node("1201", "Sous-famille A", 1, scheme);
        ProductFamily n12 = node("12", "Rayon frais LS", 0, scheme);
        ProductFamily n5601 = node("5601", null, 1, scheme);
        ProductFamily n56 = node("56", "Rayon hors map", 0, scheme);
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("1201")).thenReturn(n1201);
            families.when(() -> ProductFamily.findByCode("12")).thenReturn(n12);
            families.when(() -> ProductFamily.findByCode("5601")).thenReturn(n5601);
            families.when(() -> ProductFamily.findByCode("56")).thenReturn(n56);
            families.when(() -> ProductFamily.findByCode("9999")).thenReturn(null);
            calculator.fill(state, List.of(t1), List.of(),
                    config(60, 1, Map.of("12", "FRAIS_LS"), 20, false));
        }
        List<FinancialStateLine> nomenclature = linesOf(state, StateSection.NOMENCLATURE);
        assertEquals(3, nomenclature.size());
        assertEquals("1201", nomenclature.get(0).code);
        assertEquals(new BigDecimal("24.0000"), nomenclature.get(0).amount);
        assertEquals("5601", nomenclature.get(1).code);
        assertEquals("NON CLASSÉ", nomenclature.get(2).code);
        assertEquals(new BigDecimal("8.0000"), nomenclature.get(2).amount);
        List<FinancialStateLine> sectors = linesOf(state, StateSection.SECTOR);
        assertEquals(2, sectors.size());
        assertEquals("FRAIS_LS", sectors.get(0).code);
        assertEquals(new BigDecimal("24.0000"), sectors.get(0).amount);
        assertEquals("NON CLASSÉ", sectors.get(1).code);
        assertEquals(new BigDecimal("28.0000"), sectors.get(1).amount);
        assertEquals(new BigDecimal("46.15"), state.foodShare);
    }

    /**
     * Sets a family code on a line.
     *
     * @param line the line
     * @param familyCode the family code
     * @return the line
     */
    private TicketLine family(TicketLine line, String familyCode) {
        line.familyCode = familyCode;
        return line;
    }

    /**
     * Builds a nomenclature node.
     *
     * @param code the node code
     * @param description the node label
     * @param level the node level
     * @param scheme the owning scheme
     * @return the node
     */
    private ProductFamily node(String code, String description, int level, Nomenclature scheme) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.description = description;
        family.level = level;
        family.nomenclature = scheme;
        return family;
    }

    /**
     * The best sellers rank on the quantity then on the amount, both descending,
     * grouping by EAN then PLU then label, and the ranking is cut at the
     * configured depth (R19).
     */
    @Test
    void fillRanksBestSellersAndCapsDepth() {
        FinancialState state = storeState();
        Ticket t1 = closed("65.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        TicketLine a = goods("3", null, "10.00", "30.00", "0.2000");
        a.ean = "E1";
        TicketLine b = goods("5", null, "2.00", "10.00", "0.2000");
        b.ean = "E2";
        TicketLine c = goods("5", null, "4.00", "20.00", "0.2000");
        c.plu = "P3";
        TicketLine d = goods("1", null, "5.00", "5.00", "0.2000");
        d.productLabel = "L4";
        t1.lines.addAll(List.of(a, b, c, d));
        calculator.fill(state, List.of(t1), List.of(), config(60, 1, Map.of(), 2, false));
        List<FinancialStateLine> best = linesOf(state, StateSection.BEST_SELLER);
        assertEquals(2, best.size());
        assertEquals("P3", best.get(0).code);
        assertEquals(new BigDecimal("5.0000"), best.get(0).quantity);
        assertEquals(new BigDecimal("20.0000"), best.get(0).amount);
        assertEquals("E2", best.get(1).code);
        assertEquals(new BigDecimal("10.0000"), best.get(1).amount);
    }

    /**
     * A store state carries a per-cashier breakdown when enabled, one line per
     * cashier with the net turnover and the customer count, a null cashier landing
     * on the dash line; it carries none when disabled.
     */
    @Test
    void fillAddsCashierBreakdownWhenEnabled() {
        FinancialState state = storeState();
        Employee alice = new Employee();
        alice.loginName = "alice";
        Employee bob = new Employee();
        bob.loginName = "bob";
        Ticket t1 = closed("10.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        t1.cashier = alice;
        t1.lines.add(goods("1", null, "10.00", "10.00", "0.2000"));
        Ticket t2 = closed("20.00", LocalDateTime.of(2026, 1, 1, 9, 30));
        t2.cashier = bob;
        t2.lines.add(goods("1", null, "20.00", "20.00", "0.2000"));
        Ticket t3 = closed("0.00", LocalDateTime.of(2026, 1, 1, 9, 45));
        t3.cashier = null;
        t3.lines.add(goods("-1", null, "5.00", "-5.00", "0.2000"));
        calculator.fill(state, List.of(t1, t2, t3), List.of(), config(60, 1, Map.of(), 20, true));
        List<FinancialStateLine> cashiers = linesOf(state, StateSection.CASHIER);
        assertEquals(3, cashiers.size());
        assertEquals("alice", cashiers.get(0).code);
        assertEquals(new BigDecimal("10.0000"), cashiers.get(0).amount);
        assertEquals(BigDecimal.valueOf(1), cashiers.get(0).quantity);
        assertEquals("bob", cashiers.get(1).code);
        assertEquals("—", cashiers.get(2).code);
        assertEquals(new BigDecimal("-5.0000"), cashiers.get(2).amount);
        assertEquals(BigDecimal.valueOf(0), cashiers.get(2).quantity);
    }

    /**
     * A store state carries no cashier breakdown when it is disabled (the R3
     * exception for the floating day), and a cashier-scope state never does.
     */
    @Test
    void fillOmitsCashierBreakdownWhenDisabledOrCashierScope() {
        FinancialState disabled = storeState();
        Ticket t1 = closed("10.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        Employee anonymous = new Employee();
        anonymous.loginName = null;
        t1.cashier = anonymous;
        t1.lines.add(goods("1", null, "10.00", "10.00", "0.2000"));
        calculator.fill(disabled, List.of(t1), List.of(), config(60, 1, Map.of(), 20, false));
        assertTrue(linesOf(disabled, StateSection.CASHIER).isEmpty());
        FinancialState cashierScope = storeState();
        cashierScope.scope = FinancialState.StateScope.CASHIER;
        Ticket t2 = closed("10.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        t2.lines.add(goods("1", null, "10.00", "10.00", "0.2000"));
        calculator.fill(cashierScope, List.of(t2), List.of(), config(60, 1, Map.of(), 20, true));
        assertTrue(linesOf(cashierScope, StateSection.CASHIER).isEmpty());
    }

    /**
     * An empty day leaves every total at zero: no customer makes the average
     * basket zero (R14), a null turnover makes the food share zero (R16), and the
     * hourly section stays empty.
     */
    @Test
    void fillLeavesAnEmptyDayAtZero() {
        FinancialState state = storeState();
        calculator.fill(state, List.of(), List.of(), config(60, 1, Map.of(), 20, false));
        assertEquals(new BigDecimal("0.0000"), state.netTurnover);
        assertEquals(BigDecimal.ZERO, state.averageBasket);
        assertEquals(BigDecimal.ZERO, state.foodShare);
        assertEquals(0, state.customerCount);
        assertTrue(linesOf(state, StateSection.HOURLY).isEmpty());
    }

    /**
     * The abandoned-ticket total takes the grand total when present and the
     * tax-included total otherwise, and zero when neither is set (R9).
     */
    @Test
    void fillTakesAbandonedTotalFromGrandThenIncludingTax() {
        FinancialState state = storeState();
        Ticket withInclusive = new Ticket();
        withInclusive.status = Ticket.TicketStatus.CANCELLED;
        withInclusive.grandTotal = null;
        withInclusive.totalIncludingTax = new BigDecimal("15.00");
        Ticket withNothing = new Ticket();
        withNothing.status = Ticket.TicketStatus.CANCELLED;
        withNothing.grandTotal = null;
        withNothing.totalIncludingTax = null;
        calculator.fill(state, List.of(withInclusive, withNothing), List.of(),
                config(60, 1, Map.of(), 20, false));
        assertEquals(2, state.abandonedTicketCount);
        assertEquals(new BigDecimal("15.0000"), state.abandonedTicketTotal);
    }

    /**
     * A zero or negative slot width falls back to sixty minutes, and the slot
     * moment falls back to the creation date when the ticket never closed (R17).
     */
    @Test
    void fillFallsBackOnSlotWidthAndCreationDate() {
        FinancialState state = storeState();
        Ticket ticket = new Ticket();
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.grandTotal = new BigDecimal("10.00");
        ticket.closingDate = null;
        ticket.creationDate = LocalDateTime.of(2026, 1, 1, 14, 30);
        ticket.lines.add(goods("1", null, "10.00", "10.00", "0.2000"));
        calculator.fill(state, List.of(ticket), List.of(), config(0, 1, Map.of(), 20, false));
        List<FinancialStateLine> hourly = linesOf(state, StateSection.HOURLY);
        assertEquals(1, hourly.size());
        assertEquals("14:00", hourly.get(0).code);
    }

    /**
     * A payment carrying no amount contributes nothing to its mean, a currency
     * payment with no foreign amount leaves the quantity empty, and a ticket with
     * neither closing nor creation date lands in no hourly slot.
     */
    @Test
    void fillHandlesNullPaymentAmountsAndDatelessTickets() {
        FinancialState state = storeState();
        Ticket t1 = closed("5.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        t1.lines.add(goods("1", null, "5.00", "5.00", "0.2000"));
        CashPayment cash = new CashPayment(null, null);
        ForeignCurrencyPayment currency = new ForeignCurrencyPayment(new BigDecimal("5.00"));
        t1.payments.add(cash);
        t1.payments.add(currency);
        Ticket dateless = new Ticket();
        dateless.status = Ticket.TicketStatus.CLOSED;
        dateless.grandTotal = new BigDecimal("1.00");
        dateless.closingDate = null;
        dateless.creationDate = null;
        dateless.lines.add(goods("1", null, "1.00", "1.00", "0.2000"));
        calculator.fill(state, List.of(t1, dateless), List.of(), config(60, 1, Map.of(), 20, false));
        List<FinancialStateLine> settlement = linesOf(state, StateSection.SETTLEMENT);
        assertEquals(2, settlement.size());
        assertEquals("CASH", settlement.get(0).code);
        assertEquals(new BigDecimal("0.0000"), settlement.get(0).amount);
        assertEquals("DEVISE", settlement.get(1).code);
        assertEquals(null, settlement.get(1).quantity);
        List<FinancialStateLine> hourly = linesOf(state, StateSection.HOURLY);
        assertEquals(1, hourly.size());
        assertEquals("09:00", hourly.get(0).code);
    }

    /**
     * A node that belongs to no scheme falls on NON CLASSÉ, and a node whose level
     * is unset stays where it is (the leaf), both resolving to NON CLASSÉ for a
     * sector they have no mapping for (R16).
     */
    @Test
    void fillTreatsSchemelessAndLevellessNodes() {
        FinancialState state = storeState();
        Ticket t1 = closed("30.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        t1.lines.add(family(goods("1", null, "10.00", "10.00", "0.2000"), "NS"));
        t1.lines.add(family(goods("1", null, "20.00", "20.00", "0.2000"), "NL"));
        Nomenclature scheme = mock(Nomenclature.class);
        ProductFamily schemeless = node("NS", "No scheme", 0, null);
        ProductFamily levelless = new ProductFamily();
        levelless.code = "NL";
        levelless.description = "No level";
        levelless.level = null;
        levelless.nomenclature = scheme;
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("NS")).thenReturn(schemeless);
            families.when(() -> ProductFamily.findByCode("NL")).thenReturn(levelless);
            calculator.fill(state, List.of(t1), List.of(), config(60, 1, Map.of(), 20, false));
        }
        List<FinancialStateLine> nomenclature = linesOf(state, StateSection.NOMENCLATURE);
        assertEquals(2, nomenclature.size());
        assertEquals("NL", nomenclature.get(0).code);
        assertEquals("NON CLASSÉ", nomenclature.get(1).code);
        List<FinancialStateLine> sectors = linesOf(state, StateSection.SECTOR);
        assertEquals(1, sectors.size());
        assertEquals("NON CLASSÉ", sectors.get(0).code);
        assertEquals(new BigDecimal("30.0000"), sectors.get(0).amount);
    }

    /**
     * An unknown tender key is kept and printed after every known mean (its rank
     * falling past the fixed order), and a refund carrying no amount adds nothing.
     */
    @Test
    void fillHandlesUnknownTenderAndAmountlessRefund() {
        FinancialState state = storeState();
        Ticket t1 = closed("12.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        t1.lines.add(goods("1", null, "12.00", "12.00", "0.2000"));
        CashPayment cash = new CashPayment(new BigDecimal("10.00"), new BigDecimal("10.00"));
        TicketPayment weird = mock(TicketPayment.class);
        when(weird.getMethodKey()).thenReturn("WEIRD");
        weird.amount = new BigDecimal("2.00");
        t1.payments.add(cash);
        t1.payments.add(weird);
        Refund amountless = new Refund();
        amountless.status = Refund.RefundStatus.CLOSED;
        amountless.originalTicketId = 7L;
        amountless.totalAmount = null;
        calculator.fill(state, List.of(t1), List.of(amountless), config(60, 1, Map.of(), 20, false));
        List<FinancialStateLine> settlement = linesOf(state, StateSection.SETTLEMENT);
        assertEquals(2, settlement.size());
        assertEquals("CASH", settlement.get(0).code);
        assertEquals("WEIRD", settlement.get(1).code);
        assertEquals(new BigDecimal("2.0000"), settlement.get(1).amount);
        assertEquals(new BigDecimal("0.0000"), state.refundWithTicketTotal);
    }

    /**
     * The climb stops where it can go no further: a parent the scheme does not
     * name and a parent code the catalog does not hold both leave the line at the
     * node reached, which then resolves to NON CLASSÉ for a sector it has no
     * mapping for.
     */
    @Test
    void fillClimbStopsWhenParentIsMissing() {
        FinancialState state = storeState();
        Ticket t1 = closed("30.00", LocalDateTime.of(2026, 1, 1, 9, 0));
        t1.lines.add(family(goods("1", null, "10.00", "10.00", "0.2000"), "AA"));
        t1.lines.add(family(goods("1", null, "20.00", "20.00", "0.2000"), "BB"));
        Nomenclature scheme = mock(Nomenclature.class);
        when(scheme.parentCodeOf("AA")).thenReturn(null);
        when(scheme.parentCodeOf("BB")).thenReturn("ZZ");
        ProductFamily nAA = node("AA", "A", 1, scheme);
        ProductFamily nBB = node("BB", "B", 1, scheme);
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("AA")).thenReturn(nAA);
            families.when(() -> ProductFamily.findByCode("BB")).thenReturn(nBB);
            families.when(() -> ProductFamily.findByCode("ZZ")).thenReturn(null);
            calculator.fill(state, List.of(t1), List.of(), config(60, 0, Map.of(), 20, false));
        }
        List<FinancialStateLine> nomenclature = linesOf(state, StateSection.NOMENCLATURE);
        assertEquals(2, nomenclature.size());
        assertEquals("AA", nomenclature.get(0).code);
        assertEquals("BB", nomenclature.get(1).code);
        List<FinancialStateLine> sectors = linesOf(state, StateSection.SECTOR);
        assertEquals(1, sectors.size());
        assertEquals("NON CLASSÉ", sectors.get(0).code);
    }
}
