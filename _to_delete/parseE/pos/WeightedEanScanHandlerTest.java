package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link WeightedEanScanHandler}.
 * <p>
 * The handler is a {@code @Priority(1)} link decoding in-store weighted EAN13
 * stickers laid out as {@code PP AAAAA VVVVV K}: a 2-digit prefix classifying
 * the sticker as price-embedded or weight-embedded, a 5-digit article code
 * matched against the product PLU (leading zeros stripped), a 5-digit embedded
 * value (cents or grams) and a verified EAN13 check digit. Prefixes it does not
 * own, non-2x codes and codes with a bad checksum fall through untouched; a
 * missing or forbidden article raises the cashier error; a price-embedded
 * sticker seen twice is refused; a weight of zero grams is invalid. The VAT and
 * unit price come from the current catalog {@link Price}, falling back to a zero
 * price and the injected default VAT when none exists. Every collaborator is a
 * Mockito mock — the {@link PosState} whose public {@code ticket} sub-state is a
 * {@link TicketState} mock, and the two Panache finders neutralized on their own
 * declaring classes with {@link org.mockito.Mockito#mockStatic}. Config fields
 * are package-private collaborators set directly.
 */
class WeightedEanScanHandlerTest {

    /** A valid price-embedded sticker: prefix 21, article 01234, 150 cents. */
    private static final String PRICE_CODE = "2101234001509";

    /** A valid price-embedded sticker whose article is all zeros (strips to "0"). */
    private static final String PRICE_ZERO_ARTICLE_CODE = "2100000001507";

    /** A valid weight-embedded sticker: prefix 23, article 01234, 500 grams. */
    private static final String WEIGHT_CODE = "2301234005006";

    /** A valid weight-embedded sticker carrying zero grams (invalid weight). */
    private static final String WEIGHT_ZERO_CODE = "2301234000001";

    /** A 2x code whose prefix (29) belongs to neither prefix list. */
    private static final String OTHER_PREFIX_CODE = "2901234001505";

    /** The stripped article code shared by the well-formed stickers. */
    private static final String ARTICLE = "1234";

    /** The product's stored name, upper-cased onto the ticket line. */
    private static final String NAME = "banane";

    /** The product's EAN carried by weight-embedded lines. */
    private static final String EAN = "3560070000000";

    /** The injected fallback VAT rate used when no current price exists. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /**
     * Builds a handler wired with the two prefix lists and the fallback VAT.
     *
     * @return a ready-to-test handler
     */
    private WeightedEanScanHandler newHandler() {
        WeightedEanScanHandler handler = new WeightedEanScanHandler();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        handler.i18n = TestMessages.french();
        handler.pricePrefixes = Arrays.asList("21", "22");
        handler.weightPrefixes = Arrays.asList("23", "24", "25", "26");
        handler.defaultVatRate = DEFAULT_VAT;
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is the supplied
     * mock, seeding the ticket's transient scanned-codes set.
     *
     * @param ticket the ticket mailbox mock
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket) {
        ticket.scannedStickerCodes = new HashSet<>();
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        // Registering a weighed line ALWAYS reads it back now: the eligible tenders are
        // snapshotted on it ({@code LC-09-01-11} to {@code -18}). A mocked ticket starts
        // with a real, empty list; a test that asserts on the line seeds its own.
        if (ticket.items == null) {
            ticket.items = new java.util.ArrayList<>(
                    java.util.List.of(new TicketState.TicketItem()));
        }
        return state;
    }

    /**
     * Builds a {@link Product} with the given forbidden flag, addressable by id.
     *
     * @param forbidden the forbidden-to-sale flag
     * @return the assembled product
     */
    private Product newProduct(boolean forbidden) {
        Product p = new Product();
        p.id = 42L;
        p.name = NAME;
        p.ean = EAN;
        p.forbiddenToSale = forbidden;
        return p;
    }

    /**
     * Builds a current {@link Price} with the given tax-inclusive price and VAT.
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
     * An already-handled context short-circuits: the handler returns before
     * touching the state or the ticket, leaving the flag set.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verify(ticket, never()).setError(any());
    }

    /**
     * A null code is not a 2x sticker: the handler returns and the context
     * stays unhandled for the next link.
     */
    @Test
    void nullCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(null, state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A code that is not a 13-digit 2x EAN is not recognized: no lookup happens
     * and the context stays unhandled.
     */
    @Test
    void nonWeightedEanCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("3017620422003", state);
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A 2x code whose prefix belongs to neither the price nor the weight list
     * is left for the next handler: the context stays unhandled.
     */
    @Test
    void unrecognizedPrefixIsNotConsumed() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(OTHER_PREFIX_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A recognized prefix but an invalid EAN13 checksum falls through to the
     * generic handlers: the context stays unhandled and no lookup happens.
     */
    @Test
    void invalidChecksumFallsThrough() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("2101234001500", state);
        try (MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A valid sticker whose article matches no active product raises the cashier
     * error and consumes the context; the all-zero article strips to "0".
     */
    @Test
    void unknownArticleSetsErrorAndConsumes() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(PRICE_ZERO_ARTICLE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu("0")).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ARTICLE BALANCE INTROUVABLE (0)");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A product flagged forbidden to sell is refused: the ticket carries the
     * cashier error, the context is consumed and no line is created.
     */
    @Test
    void forbiddenProductSetsErrorAndAddsNoLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(true);
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("PRODUIT INTERDIT À LA VENTE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A price-embedded sticker with a current price creates a single line of
     * quantity one at the embedded total, carrying no EAN/PLU so it never
     * merges, freezing the catalog VAT and consuming the context.
     */
    @Test
    void priceEmbeddedAddsLocalLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        // The handler flags the LAST ADDED line as price-embedded (surcharge
        // trio toward the engine), so the mock must materialize the add: a
        // real item lands in a real list, like EanScanHandler's money flag.
        ticket.items = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product p = newProduct(false);
        Price price = newPrice("9.99", "0.055");
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        // The line carries the product EAN like every other (doctrine of
        // 14/08: a line without an EAN does not exist) at the sticker total.
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(BigDecimal.valueOf(150, 2)), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
        assertTrue(ticket.items.get(0).priceEmbedded);
        verify(ticket, never()).setError(any());
    }

    /**
     * On a price-embedded sticker (EXP-26, LC-02-05-01) the WEIGHT is deduced the
     * inverse way — read total (1,50 €) divided by the referential price per kilo
     * (4,00 €/kg) = 0,375 kg — and carried with the price/kg for display
     * (price-non-null, priceIncludingTax-positive true arm).
     */
    @Test
    void priceEmbeddedDeducesWeightFromCatalogUnitPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product p = newProduct(false);
        Price price = newPrice("4.00", "0.055");
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        TicketState.TicketItem line = ticket.items.get(0);
        assertTrue(line.hasEmbeddedWeight());
        assertEquals(0, new BigDecimal("0.375").compareTo(line.embeddedWeight));
        assertEquals(0, new BigDecimal("4.00").compareTo(line.embeddedUnitPrice));
    }

    /**
     * A price-embedded sticker whose article has no catalog price (EXP-26)
     * deduces NO weight — the price-null false arm — so the line stands at the
     * sticker total with no weight detail.
     */
    @Test
    void priceEmbeddedWithoutCatalogPriceDeducesNoWeight() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        TicketState.TicketItem line = ticket.items.get(0);
        assertTrue(line.priceEmbedded);
        assertFalse(line.hasEmbeddedWeight());
    }

    /**
     * The same price-embedded sticker scanned twice is refused: the second scan
     * raises the duplicate-sticker error, consumes the context and adds no line.
     */
    @Test
    void priceEmbeddedDuplicateStickerIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.scannedStickerCodes.add(PRICE_CODE);
        Product p = newProduct(false);
        Price price = newPrice("9.99", "0.055");
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ÉTIQUETTE DÉJÀ SCANNÉE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A weight-embedded sticker with a current price creates a weighed line
     * carrying the product EAN and article PLU, valued at the catalog price per
     * kilogram, freezing the catalog VAT and consuming the context.
     */
    @Test
    void weightEmbeddedAddsWeighedLineWithPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        Price price = newPrice("2.99", "0.055");
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("2.99")), eq(new BigDecimal("0.500")), eq(new BigDecimal("0.055")));
        verify(ticket, never()).setError(any());
    }

    /**
     * A weight-embedded sticker with no current price falls back to a zero unit
     * price and the injected default VAT rate, still creating the weighed line.
     */
    @Test
    void weightEmbeddedWithoutPriceUsesZeroAndDefaultVat() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(BigDecimal.ZERO), eq(new BigDecimal("0.500")), eq(DEFAULT_VAT));
        verify(ticket, never()).setError(any());
    }

    /**
     * A weight-embedded sticker carrying zero grams is invalid: the ticket
     * carries the weight error, the context is consumed and no line is created.
     */
    @Test
    void weightEmbeddedZeroWeightSetsError() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(WEIGHT_ZERO_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("POIDS INVALIDE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * BO-02-03-11: a RECALLED weighed article is refused with the recall error,
     * before any weighing, and adds no line.
     */
    @Test
    void recalledProductRefusedAtScan() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.RECALL, "true");
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ARTICLE EN RETRAIT/RAPPEL");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * BO-02-03-26/27: a VAT-EXEMPT weighed article ventilates its line at rate 0.
     */
    @Test
    void vatExemptVentilatesAtZeroRate() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.VAT_EXEMPT, "true");
        Price price = newPrice("2.99", "0.055");
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("2.99")), eq(new BigDecimal("0.500")), eq(BigDecimal.ZERO));
    }

    /**
     * BO-02-03-02: the weighed line uses the checkout label when set.
     */
    @Test
    void checkoutLabelUsedAsLineLabel() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.checkoutLabel = "Banane Bio";
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE BIO"),
                eq(BigDecimal.ZERO), eq(new BigDecimal("0.500")), eq(DEFAULT_VAT));
    }

    /**
     * BO-02-03-09: a DISCOUNT-FORBIDDEN weighed article flags the freshly added
     * line so later price gestures refuse it.
     */
    @Test
    void discountForbiddenIsSnapshotOntoTheLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true");
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ticket.items.get(0).discountForbidden);
    }

    /**
     * An in-store weighed label names no lot, so an article under lot recall has its
     * recalled lots listed while the line is rung ({@code LC-02-03-13}); an article
     * under no lot recall says nothing.
     */
    @Test
    void aWeighedLabelListsTheRecalledLots() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123");
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        verify(ticket).setNotice("PRODUIT EN RAPPEL - LOTS : L123");
    }

    /**
     * A weighed label of an article under no lot recall says nothing — the arm that
     * keeps the message off every ordinary weighing.
     */
    @Test
    void aWeighedLabelWithoutALotRecallSaysNothing() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        verify(ticket, never()).setNotice(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * Neutralizes the administered article ranges, so the handler falls back to
     * the two prefix properties these cases are written against
     * (BO-03-06-02 — no range administered on this node).
     *
     * @return the static mock answering no range for any code
     */
    private MockedStatic<ArticleBarcodeRange> noAdministeredRange() {
        MockedStatic<ArticleBarcodeRange> ranges = mockStatic(ArticleBarcodeRange.class);
        ranges.when(() -> ArticleBarcodeRange.findMatching(any())).thenReturn(null);
        return ranges;
    }

    // --- Plages ARTICLE administrées (BO-03-06-02/03/04/05/10) ---

    /**
     * Builds an administered range, its pattern generated from its description
     * exactly as the back office writes it.
     *
     * @param prefix the literal head
     * @param length the total code length
     * @param source what the value segment carries
     * @return the range, pattern generated
     */
    private ArticleBarcodeRange administered(String prefix, int length,
                                             ArticleBarcodeRange.ValueSource source) {
        ArticleBarcodeRange range = new ArticleBarcodeRange();
        range.code = "PLAN_" + prefix;
        range.label = "Plan " + prefix;
        range.prefix = prefix;
        range.codeLength = length;
        range.codeKind = com.intermarche.pos.domain.barcode.CouponField.Kind.NUMERIC;
        range.valueSource = source;
        range.checkDigit = false;
        // The pattern is NOT generated here: the register RECEIVES it with the
        // range (RefApplyService.applyArticleBarcodeRanges copies
        // ArticleBarcodeRangeDto.matchPattern). The fixture therefore states
        // the pattern the node ships for a numeric range with no administered
        // segment, which is what the scan chain actually reads.
        range.matchPattern = "^" + prefix + "\\d{" + (length - prefix.length()) + "}$";
        return range;
    }

    /**
     * Opens a static mock answering the given range for every code.
     *
     * @param range the administered range
     * @return the static mock
     */
    private MockedStatic<ArticleBarcodeRange> administeredRange(ArticleBarcodeRange range) {
        MockedStatic<ArticleBarcodeRange> ranges = mockStatic(ArticleBarcodeRange.class);
        ranges.when(() -> ArticleBarcodeRange.findMatching(any())).thenReturn(range);
        return ranges;
    }

    /**
     * Seeds a ticket mock that materializes the added line, as the
     * price-embedded path reads the last line back.
     *
     * @param ticket the ticket mock
     */
    private void materializeAdds(TicketState ticket) {
        ticket.items = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * An ADMINISTERED price range says where the article and the price sit, and
     * the register reads them there — a 16-character plan with a 3-character
     * prefix, which the hard-coded 2x layout could not have read.
     */
    @Test
    void anAdministeredPriceRangeIsReadWhereItSaysItIs() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        materializeAdds(ticket);
        ArticleBarcodeRange range = administered("297", 16, ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 3;
        range.articleLength = 6;
        range.valuePosition = 9;
        range.valueLength = 7;
        range.valueDecimals = 2;
        ScanContext ctx = new ScanContext("2970012340000275", state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("2.75")), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
        verify(ticket, never()).setError(any());
    }

    /**
     * A SECOND administered layout reads another segment of the very same code,
     * which is what proves the positions are read rather than hard-coded.
     */
    @Test
    void aSecondAdministeredLayoutReadsTheSameCodeDifferently() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        materializeAdds(ticket);
        ArticleBarcodeRange range = administered("297", 16, ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 9;
        range.articleLength = 7;
        range.valuePosition = 3;
        range.valueLength = 6;
        range.valueDecimals = 2;
        ScanContext ctx = new ScanContext("2970012340000275", state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            products.when(() -> Product.findActiveByPlu("275")).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq("275"), eq("BANANE"),
                eq(new BigDecimal("12.34")), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
    }

    /**
     * An ADMINISTERED weight range carries its own decimal count: four decimals
     * here, where the hard-coded plan only ever read grams (BO-03-06-04).
     */
    @Test
    void anAdministeredWeightRangeCarriesItsOwnDecimals() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered("28", 14, ArticleBarcodeRange.ValueSource.WEIGHT);
        range.articlePosition = 2;
        range.articleLength = 6;
        range.valuePosition = 8;
        range.valueLength = 6;
        range.valueDecimals = 4;
        ScanContext ctx = new ScanContext("28001234012500", state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("9.99")), eq(new BigDecimal("1.250")), eq(new BigDecimal("0.055")));
    }

    /**
     * A range administered in FRANCS converts its price through the currency
     * referential at the administered rate (BO-03-06-05).
     */
    @Test
    void anAdministeredFrancRangeIsConvertedAtTheAdministeredRate() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        materializeAdds(ticket);
        ArticleBarcodeRange range = administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valuePosition = 7;
        range.valueLength = 5;
        range.valueDecimals = 2;
        range.currency = com.intermarche.pos.domain.barcode.CouponField.PriceCurrency.FRF;
        com.intermarche.pos.domain.payment.Currency franc =
                new com.intermarche.pos.domain.payment.Currency();
        franc.code = "FRF";
        franc.euroPerUnit = new BigDecimal("0.152449");
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<com.intermarche.pos.domain.payment.Currency> currencies =
                     mockStatic(com.intermarche.pos.domain.payment.Currency.class);
             MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            currencies.when(() -> com.intermarche.pos.domain.payment.Currency.findActiveByCode("FRF"))
                    .thenReturn(franc);
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        // 1,50 franc at 0,152449 euro the franc, rounded as the referential rounds.
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(franc.toEuro(new BigDecimal("1.50"))), eq(BigDecimal.ONE),
                eq(new BigDecimal("0.055")));
    }

    /**
     * A franc range with NO administered rate refuses the code rather than
     * charging francs as euros (the currency-missing arm).
     */
    @Test
    void aFrancRangeWithoutARateRefusesTheCode() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valuePosition = 7;
        range.valueLength = 5;
        range.currency = com.intermarche.pos.domain.barcode.CouponField.PriceCurrency.FRF;
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<com.intermarche.pos.domain.payment.Currency> currencies =
                     mockStatic(com.intermarche.pos.domain.payment.Currency.class);
             MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            currencies.when(() -> com.intermarche.pos.domain.payment.Currency.findActiveByCode("FRF"))
                    .thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("DEVISE FRF NON PARAMÉTRÉE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A range administered WITHOUT a check digit accepts a code whose last
     * character would fail the EAN13 key — the layout decides, not the code.
     */
    @Test
    void anAdministeredRangeWithoutACheckDigitAcceptsAnyKey() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        materializeAdds(ticket);
        ArticleBarcodeRange range = administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valuePosition = 7;
        range.valueLength = 5;
        ScanContext ctx = new ScanContext("2101234001500", state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A range administered WITH a check digit still refuses a bad key, letting
     * the code fall through to the next handler (the check-digit arm).
     */
    @Test
    void anAdministeredRangeWithACheckDigitStillRefusesABadKey() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
        range.checkDigit = true;
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valuePosition = 7;
        range.valueLength = 5;
        ScanContext ctx = new ScanContext("2101234001500", state);
        try (MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A range whose segments cannot be read in the scanned code lets it fall
     * through untouched (the unreadable-segment arm).
     */
    @Test
    void anUnreadableSegmentLetsTheCodeFallThrough() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valuePosition = 40;
        range.valueLength = 5;
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A blank code is refused before any range is consulted.
     */
    @Test
    void aBlankCodeConsultsNoRange() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("   ", state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A range administered WITH a check digit that RECEIVES a code whose EAN13
     * key is valid proceeds to the line — the arm where {@code checkDigit} holds
     * yet {@code !hasValidChecksum} is false, the one a bad-key case never runs.
     */
    @Test
    void anAdministeredRangeWithACheckDigitAcceptsAValidKey() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        materializeAdds(ticket);
        ArticleBarcodeRange range = administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
        range.checkDigit = true;
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valuePosition = 7;
        range.valueLength = 5;
        range.valueDecimals = 2;
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("1.50")), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
        verify(ticket, never()).setError(any());
    }

    /**
     * A range whose ARTICLE segment cannot be read in the scanned code lets it
     * fall through untouched — the {@code articleCode == null} true leg of the
     * {@code articleCode == null || value == null} guard, distinct from the
     * unreadable-value leg an earlier case exercises.
     */
    @Test
    void anUnreadableArticleSegmentLetsTheCodeFallThrough() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 40;
        range.articleLength = 5;
        range.valuePosition = 7;
        range.valueLength = 5;
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A weighed article whose current price names NO regime — its {@code vat} is
     * null, so {@code vatRate()} is null — is ventilated at the injected default
     * VAT rate: the arm of the {@code price != null && price.vatRate() != null}
     * guard where the first leg holds and the second does not.
     */
    @Test
    void weightEmbeddedWithPriceButNoVatRateUsesDefaultVat() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("2.99");
        price.vat = null;
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("2.99")), eq(new BigDecimal("0.500")), eq(DEFAULT_VAT));
        verify(ticket, never()).setError(any());
    }

    /**
     * A range administered for something OTHER than an article line is not this
     * handler's business (BO-03-06-56/-60/-61): the code falls through untouched
     * to the handler that owns that meaning, which is what keeps the chain's
     * disjointness true. The three non-article destinations are all exercised.
     */
    @Test
    void aRangeAdministeredForAnotherPurposeFallsThrough() {
        for (ArticleBarcodeRange.Purpose purpose : new ArticleBarcodeRange.Purpose[] {
                ArticleBarcodeRange.Purpose.REFUND,
                ArticleBarcodeRange.Purpose.TARE,
                ArticleBarcodeRange.Purpose.BALANCE_TICKET }) {
            TicketState ticket = mock(TicketState.class);
            PosState state = newState(ticket);
            ArticleBarcodeRange range =
                    administered("21", 13, ArticleBarcodeRange.ValueSource.PRICE);
            range.articlePosition = 2;
            range.articleLength = 5;
            range.valuePosition = 7;
            range.valueLength = 5;
            range.purpose = purpose;
            ScanContext ctx = new ScanContext(PRICE_CODE, state);
            try (MockedStatic<ArticleBarcodeRange> ranges = administeredRange(range)) {
                newHandler().handle(ctx);
            }
            assertFalse(ctx.handled);
            verify(ticket, never()).setError(any());
            verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * An announced container tare is deducted from the weighed line and consumed
     * (BO-03-06-60): 500 grams of olives in a 250-gram bowl is 250 grams of
     * olives.
     */
    @Test
    void anAnnouncedTareIsDeductedFromTheWeighedLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        org.mockito.Mockito.when(ticket.consumePendingTare())
                .thenReturn(new BigDecimal("0.250"));
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("9.99")), eq(new BigDecimal("0.250")),
                eq(new BigDecimal("0.055")));
    }

    /**
     * A tare at least as heavy as the weighing is refused: a line weighing
     * nothing is not a sale, and charging the customer for the bowl is not one
     * either (BO-03-06-60).
     */
    @Test
    void aTareHeavierThanTheWeighingIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        org.mockito.Mockito.when(ticket.consumePendingTare())
                .thenReturn(new BigDecimal("0.500"));
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<ArticleBarcodeRange> ranges = noAdministeredRange()) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("POIDS INVALIDE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }
}
