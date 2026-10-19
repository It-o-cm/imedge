package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RangePurposeScanHandler} (BO-03-06-60 and -61).
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database: the two Panache
 * finders are neutralized on their declaring classes with
 * {@link org.mockito.Mockito#mockStatic}, the register state and its ticket are
 * mocks, and the administered permissions come from a mocked
 * {@link PosSettingsService}. The operator messages come from the French bundle,
 * so every assertion keeps the literal sentence the register shows (LC-13-06).
 *
 * <p>Branch enumeration: the already-handled short circuit; a null and a blank
 * code; no administered range; a range administered as ARTICLE and one
 * administered as BALANCE_TICKET, both left to the handlers that own them; the
 * check-digit guard, both arms; the tare path with a readable weight, an
 * unreadable one and a zero one; the return path with a price-carrying range and
 * a weight-carrying one, with and without a catalog price, with a zero weight,
 * with each of the two administered permissions refusing, with an unknown
 * article, and with the return mode already on so its restoration is seen both
 * ways.
 */
class RangePurposeScanHandlerTest {

    /** The stripped article code the fixtures name. */
    private static final String ARTICLE = "1234";

    /** The product EAN carried by every line. */
    private static final String EAN = "3560070000000";

    /** The injected fallback VAT rate used when no current price exists. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /** A REFUND code carrying a price: article 01234, 150 cents, check digit 9. */
    private static final String REFUND_PRICE_CODE = "2101234001509";

    /** A REFUND code carrying a weight: article 01234, 500 grams, check digit 2. */
    private static final String REFUND_WEIGHT_CODE = "2101234005002";

    /** A REFUND code whose value segment carries nothing, check digit 7. */
    private static final String REFUND_ZERO_CODE = "2101234000007";

    /** A TARE code carrying 250 grams, check digit 4. */
    private static final String TARE_CODE = "2100000002504";

    /** A TARE code carrying no weight at all, check digit 5. */
    private static final String TARE_ZERO_CODE = "2100000000005";

    /** The 250-gram tare layout with the RIGHT EAN13 check digit. */
    private static final String TARE_GOOD_DIGIT = "2101234002506";

    /** The same layout with a WRONG check digit. */
    private static final String TARE_BAD_DIGIT = "2101234002509";

    /**
     * Builds a handler wired with the French bundle, permissive settings and the
     * fallback VAT.
     *
     * @return a ready-to-test handler
     */
    private RangePurposeScanHandler newHandler() {
        RangePurposeScanHandler handler = new RangePurposeScanHandler();
        handler.i18n = TestMessages.french();
        handler.posSettingsService = mock(PosSettingsService.class);
        when(handler.posSettingsService.returnWithoutOriginAllowed()).thenReturn(true);
        when(handler.posSettingsService.returnInSaleAllowed()).thenReturn(true);
        handler.defaultVatRate = DEFAULT_VAT;
        return handler;
    }

    /**
     * Assembles a mock register state over the given ticket mock, with an empty
     * line list — a return rung on an empty ticket is the ordinary case.
     *
     * @param ticket the ticket mailbox mock
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket) {
        ticket.items = new java.util.ArrayList<>();
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        return state;
    }

    /**
     * Builds an administered range with the given destination, laid out as
     * {@code PP AAAAA VVVVV K} and carrying the pattern the node ships.
     *
     * @param purpose what the register does with a code of the range
     * @param source what the value segment carries
     * @return the administered range
     */
    private ArticleBarcodeRange administered(ArticleBarcodeRange.Purpose purpose,
                                             ArticleBarcodeRange.ValueSource source) {
        ArticleBarcodeRange range = new ArticleBarcodeRange();
        range.code = "PLAN_" + purpose;
        range.label = "Plan " + purpose;
        range.prefix = "21";
        range.codeLength = 13;
        range.codeKind = com.intermarche.pos.domain.barcode.CouponField.Kind.NUMERIC;
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valueSource = source;
        range.valuePosition = 7;
        range.valueLength = 5;
        range.valueDecimals = source == ArticleBarcodeRange.ValueSource.PRICE ? 2 : 3;
        range.checkDigit = false;
        range.purpose = purpose;
        range.matchPattern = "^21\\d{11}$";
        return range;
    }

    /**
     * Opens a static mock answering the given range for every code.
     *
     * @param range the administered range, or null for none
     * @return the static mock
     */
    private MockedStatic<ArticleBarcodeRange> ranges(ArticleBarcodeRange range) {
        MockedStatic<ArticleBarcodeRange> mocked = mockStatic(ArticleBarcodeRange.class);
        mocked.when(() -> ArticleBarcodeRange.findMatching(any())).thenReturn(range);
        return mocked;
    }

    /**
     * Builds a product addressable by the fixtures' id.
     *
     * @return the assembled product
     */
    private Product newProduct() {
        Product product = new Product();
        product.id = 42L;
        product.name = "banane";
        product.ean = EAN;
        return product;
    }

    /**
     * Builds a current price with the given tax-inclusive amount and VAT.
     *
     * @param including the price including tax
     * @param vat the VAT rate
     * @return the assembled price
     */
    private Price newPrice(String including, String vat) {
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal(including);
        price.vat = new VatRate(1, new BigDecimal(vat), "Taux normal");
        return price;
    }

    /**
     * An already-handled context short-circuits, untouched.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
    }

    /**
     * A null and a blank code are not this handler's business, and neither
     * reaches the range finder.
     */
    @Test
    void nullAndBlankCodesAreNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        RangePurposeScanHandler handler = newHandler();
        ScanContext nullCode = new ScanContext(null, state);
        handler.handle(nullCode);
        ScanContext blankCode = new ScanContext("   ", state);
        handler.handle(blankCode);
        assertFalse(nullCode.handled);
        assertFalse(blankCode.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A code no administered range recognizes walks on down the chain.
     */
    @Test
    void anUnadministeredCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(null)) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
    }

    /**
     * An ARTICLE range and a BALANCE_TICKET range are both left alone: the first
     * belongs to {@link WeightedEanScanHandler}, the second to
     * {@link BalanceTicketScanHandler}.
     */
    @Test
    void theRangesOwnedByOtherHandlersAreLeftAlone() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext article = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.ARTICLE,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            newHandler().handle(article);
        }
        ScanContext bag = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.BALANCE_TICKET,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            newHandler().handle(bag);
        }
        assertFalse(article.handled);
        assertFalse(bag.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A range whose check digit is administered refuses a code failing it, and
     * accepts the same layout once the digit is right (both arms of the guard).
     */
    @Test
    void theAdministeredCheckDigitIsHonoured() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.TARE,
                ArticleBarcodeRange.ValueSource.WEIGHT);
        range.checkDigit = true;
        ScanContext wrong = new ScanContext(TARE_BAD_DIGIT, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(wrong);
        }
        assertFalse(wrong.handled);
        ScanContext right = new ScanContext(TARE_GOOD_DIGIT, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(right);
        }
        assertTrue(right.handled);
        assertEquals(new BigDecimal("0.250"), ticket.pendingTareKg);
    }

    /**
     * A TARE code announces the container weight, which waits on the ticket for
     * the next weighed line (BO-03-06-60).
     */
    @Test
    void aTareCodeAnnouncesTheContainerWeight() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(TARE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.TARE,
                        ArticleBarcodeRange.ValueSource.WEIGHT))) {
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        assertEquals(new BigDecimal("0.250"), ticket.pendingTareKg);
        verify(ticket).setNotice("TARE 0.250 KG PRISE EN COMPTE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A tare of zero and a tare whose segment carries no number are refused
     * rather than taken as nothing (both legs of the value guard).
     */
    @Test
    void anUnreadableOrZeroTareIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.TARE,
                ArticleBarcodeRange.ValueSource.WEIGHT);
        ScanContext zero = new ScanContext(TARE_ZERO_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(zero);
        }
        assertTrue(zero.handled);
        assertNull(ticket.pendingTareKg);
        ArticleBarcodeRange unreadable = administered(ArticleBarcodeRange.Purpose.TARE,
                ArticleBarcodeRange.ValueSource.WEIGHT);
        unreadable.valuePosition = 40;
        ScanContext broken = new ScanContext(TARE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(unreadable)) {
            newHandler().handle(broken);
        }
        assertTrue(broken.handled);
        assertNull(ticket.pendingTareKg);
        verify(ticket, org.mockito.Mockito.times(2)).setError("TARE ILLISIBLE");
    }

    /**
     * A REFUND code rings the article it names, at the price it carries, as a
     * return — through the one funnel every entry goes through, with the return
     * mode armed for that entry only (BO-03-06-61).
     */
    @Test
    void aRefundCodeRingsTheArticleBackAtItsOwnPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        state.returnMode = false;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("1.50")), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
        assertFalse(state.returnMode);
    }

    /**
     * A register already in return mode is left in return mode: the arming is a
     * restoration, not a toggle (the other arm of the same guard).
     */
    @Test
    void aRegisterAlreadyInReturnModeStaysInIt() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        state.returnMode = true;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        assertTrue(state.returnMode);
    }

    /**
     * A REFUND range carrying a WEIGHT gives the article back at the price the
     * shop charges for it, for the weight the code states — and with the
     * fallback VAT when the catalog carries no current price (both legs of the
     * price and VAT ternaries).
     */
    @Test
    void aWeightCarryingRefundCodeUsesTheCatalogPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.WEIGHT))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("12.00", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("12.00")), eq(new BigDecimal("0.500")),
                eq(new BigDecimal("0.055")));
    }

    /**
     * Without a catalog price the return falls back to a zero unit price and the
     * injected default VAT.
     */
    @Test
    void aRefundWithoutACatalogPriceFallsBack() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.WEIGHT))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(BigDecimal.ZERO), eq(new BigDecimal("0.500")), eq(DEFAULT_VAT));
    }

    /**
     * A weight-carrying REFUND code stating no weight is refused: a line
     * weighing nothing is not a return.
     */
    @Test
    void aRefundCodeWithoutWeightIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_ZERO_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.WEIGHT))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("12.00", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("POIDS INVALIDE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A store that forbids taking an article back without its origin ticket
     * forbids it here too: the barcode is not a way around the setting
     * (LC-05-02-09/14).
     */
    @Test
    void aStoreForbiddingReturnsWithoutOriginRefusesTheCode() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        RangePurposeScanHandler handler = newHandler();
        when(handler.posSettingsService.returnWithoutOriginAllowed()).thenReturn(false);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.REFUND,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("RETOUR SANS TICKET D'ORIGINE INTERDIT");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A store that forbids a return inside a ticket carrying sales refuses the
     * code on a started ticket, and accepts it on an empty one (both legs of the
     * compound guard).
     */
    @Test
    void aStoreForbiddingReturnsInASaleRefusesOnlyAStartedTicket() {
        TicketState started = mock(TicketState.class);
        PosState startedState = newState(started);
        started.items.add(new TicketState.TicketItem());
        RangePurposeScanHandler handler = newHandler();
        when(handler.posSettingsService.returnInSaleAllowed()).thenReturn(false);
        ScanContext onSale = new ScanContext(REFUND_PRICE_CODE, startedState);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.REFUND,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            handler.handle(onSale);
        }
        assertTrue(onSale.handled);
        verify(started).setError("RETOUR DANS UN TICKET DE VENTE INTERDIT");

        TicketState empty = mock(TicketState.class);
        PosState emptyState = newState(empty);
        ScanContext onEmpty = new ScanContext(REFUND_PRICE_CODE, emptyState);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            handler.handle(onEmpty);
        }
        assertTrue(onEmpty.handled);
        verify(empty).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("1.50")), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
    }

    /**
     * A REFUND code naming no active article raises the cashier error and
     * consumes the code.
     */
    @Test
    void aRefundCodeNamingNoArticleIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ARTICLE BALANCE INTROUVABLE (1234)");
    }

    /**
     * A REFUND range whose segments fall outside the code reads nothing and
     * lets the code walk on, rather than refunding an article nobody named.
     */
    @Test
    void aRefundCodeWhoseSegmentsCannotBeReadIsNotConsumed() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.REFUND,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 40;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }
}
