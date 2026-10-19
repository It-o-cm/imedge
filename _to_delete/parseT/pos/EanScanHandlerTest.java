package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EanScanHandler}.
 * <p>
 * The handler is a {@code @Priority(2)} link of the scan chain: it recognizes
 * an 8-to-13 digit catalog EAN, looks up the active {@link Product}, and — the
 * snapshot moment of a sale line — freezes its resolved price and VAT onto a
 * unit line. A product forbidden to sell is refused with the cashier error; a
 * product with no current {@link Price} falls back to a zero price and the
 * injected default VAT rate. Every collaborator is a Mockito mock: the
 * {@link PosState} whose public {@code ticket} sub-state is itself a
 * {@link TicketState} mock, and the two Panache finders. Because plain
 * {@code mvn test} leaves entities un-enhanced, {@code Product.find} resolves
 * to {@link PanacheEntityBase} (neutralized with {@link org.mockito.Mockito#mockStatic})
 * and {@code Price.findCurrentPrice}, declared on {@link Price} itself, is
 * neutralized on its own class. The {@code defaultVatRate} config field is a
 * package-private collaborator set directly. Six decision points, twelve
 * branches, are exercised by six isolated cases.
 */
class EanScanHandlerTest {

    /** A well-formed catalog EAN fed to the handler. */
    private static final String CODE = "3017620422003";

    /** The product's stored name, upper-cased onto the ticket line. */
    private static final String NAME = "coca cola";

    /** The injected fallback VAT rate used when no current price exists. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /**
     * Builds a handler wired with the fallback VAT rate.
     *
     * @return a ready-to-test handler
     */
    private EanScanHandler newHandler() {
        return newHandler(false);
    }

    /**
     * Builds a handler wired with the fallback VAT rate and an age-gate
     * collaborator answering the given verdict.
     * <p>
     * The gate is a MANDATORY collaborator, never an optional one: the
     * handler asks it before every line, and a silently absent gate would
     * mean age-restricted goods pass unchecked. The mock therefore replaces
     * it in tests rather than the handler tolerating its absence.
     *
     * @param parksTheScan true to simulate a restricted product parking the
     *        scan behind the ID-check prompt
     * @return a ready-to-test handler
     */
    private EanScanHandler newHandler(boolean parksTheScan) {
        EanScanHandler handler = new EanScanHandler();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        handler.i18n = TestMessages.french();
        handler.defaultVatRate = DEFAULT_VAT;
        handler.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        when(handler.ticketService.suspendForAgeCheck(any(), any(), any(), any(), any()))
                .thenReturn(parksTheScan);
        // Back-office parameters: the EAN13 check-digit control defaults to
        // OFF (mock's false), the pre-existing behavior every other case relies on.
        handler.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is the supplied
     * mock.
     *
     * @param ticket the ticket mailbox mock
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket) {
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        // Registering an article ALWAYS reads the line back: the eligible tenders are
        // snapshotted on it ({@code LC-09-01-11} to {@code -18}), so the list is no
        // longer optional the way it was when only a gift card or a discount ban
        // reached into it. A mocked ticket starts with a real, empty list, and a test
        // that asserts on the line seeds it with one.
        if (ticket.items == null) {
            ticket.items = new java.util.ArrayList<>(
                    java.util.List.of(new TicketState.TicketItem()));
        }
        return state;
    }

    /**
     * Builds a {@link Product} with the given fields, addressable by its id.
     *
     * @param forbidden the forbidden-to-sale flag
     * @return the assembled product
     */
    private Product newProduct(boolean forbidden) {
        Product p = new Product();
        p.id = 42L;
        p.name = NAME;
        p.forbiddenToSale = forbidden;
        return p;
    }

    /**
     * Wires {@code Product.find(...)} on the given static mock to yield the
     * supplied product through a mock {@link PanacheQuery}.
     *
     * @param panache the open {@link PanacheEntityBase} static mock
     * @param product the product the finder must return (may be null)
     */
    @SuppressWarnings("unchecked")
    private void stubProductFind(MockedStatic<PanacheEntityBase> panache, Product product) {
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(product);
        panache.when(() -> Product.find("ean = ?1 and active = true", CODE)).thenReturn(query);
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * touching the state or the ticket, leaving the flag set.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verifyNoInteractions(ticket);
    }

    /**
     * A code that is not an 8-to-13 digit EAN is not recognized: no catalog
     * lookup happens and the context stays unhandled for the next link.
     */
    @Test
    void nonEanCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("ABC123", state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
    }

    /**
     * A well-formed EAN matching no active product leaves the context
     * unhandled and the ticket untouched.
     */
    @Test
    void unknownEanLeavesContextUnhandled() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, null);
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
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
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("PRODUIT INTERDIT À LA VENTE");
        verify(ticket, never()).addItem(eq(CODE), isNull(), eq(NAME.toUpperCase()),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
    }

    /**
     * A sellable product with a current price freezes that price and its real
     * VAT rate onto an upper-cased unit line and consumes the context.
     */
    @Test
    void productWithCurrentPriceAddsSnapshotLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("1.5000");
        price.vat = new VatRate(1, new BigDecimal("0.0550"), "Taux normal");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(CODE), isNull(), eq("COCA COLA"),
                eq(new BigDecimal("1.5000")), eq(BigDecimal.ONE), eq(new BigDecimal("0.0550")));
        verify(ticket, never()).setError("PRODUIT INTERDIT À LA VENTE");
    }

    /**
     * A sellable product with no current price falls back to a zero price and
     * the injected default VAT rate, still adding the unit line.
     */
    @Test
    void productWithoutCurrentPriceUsesZeroAndDefaultVat() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(CODE), isNull(), eq("COCA COLA"),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
        verify(ticket, never()).setError("PRODUIT INTERDIT À LA VENTE");
    }

    /**
     * An AGE-RESTRICTED product PARKS the scan: the gate answers true, so the
     * context is consumed and NO line is created — the article only appears
     * once the ID check is confirmed, and the confirmation replays this very
     * code through the chain.
     */
    @Test
    void ageRestrictedProductParksTheScanAndAddsNoLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(handler.ticketService).suspendForAgeCheck(state, p, "SCAN", CODE, null);
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
        verify(ticket, never()).setError(any());
    }

    /**
     * The gate is asked with the SCAN kind and no quantity: a scanned line is
     * always a unit, and the kind is what tells the confirmation to replay it
     * through the recognition chain rather than through a direct add.
     */
    @Test
    void ageGateIsAskedWithScanKindAndNoQuantity() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            handler.handle(ctx);
        }
        verify(handler.ticketService).suspendForAgeCheck(state, p, "SCAN", CODE, null);
        assertTrue(ctx.handled);
    }

    /**
     * A FORBIDDEN product is refused BEFORE the age gate is even consulted:
     * an unsellable article never parks a scan.
     */
    @Test
    void forbiddenProductNeverReachesTheAgeGate() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(true);
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            handler.handle(ctx);
        }
        verify(handler.ticketService, never())
                .suspendForAgeCheck(any(), any(), any(), any(), any());
    }

    /**
     * A GIFT CARD is flagged as a money product on the freshly added line:
     * the line carries VALUE, not goods. The flag is what later excludes it
     * from discounts, gestures and valuation, and forbids its refund.
     */
    @Test
    void giftCardLineIsFlaggedAsMoneyProduct() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.giftCardAmount = new BigDecimal("25.00");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(added.moneyProduct);
    }

    /**
     * An ORDINARY product leaves the line unflagged (null giftCardAmount
     * arm): only instruments carry value.
     */
    @Test
    void ordinaryLineIsNotFlaggedAsMoneyProduct() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.giftCardAmount = null;
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertFalse(added.moneyProduct);
    }

    /**
     * BO-10-02-21: with the check-digit control ON, a 13-digit EAN whose key
     * is wrong is refused before any lookup — the ticket carries the invalid
     * error, the context is consumed and no catalog read happens.
     */
    @Test
    void invalidEan13RefusedWhenCheckDigitEnabled() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        EanScanHandler handler = newHandler();
        when(handler.posSettingsService.ean13CheckDigitEnabled()).thenReturn(true);
        ScanContext ctx = new ScanContext("3017620422000", state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(ticket).setError("CODE EAN INVALIDE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * BO-10-02-21: with the control ON, a 13-digit EAN whose key is CORRECT
     * passes the gate and proceeds to the catalog lookup (here an unknown
     * product leaves the context unhandled).
     */
    @Test
    void validEan13PassesCheckDigitGate() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        EanScanHandler handler = newHandler();
        when(handler.posSettingsService.ean13CheckDigitEnabled()).thenReturn(true);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, null);
            handler.handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError("CODE EAN INVALIDE");
    }

    /**
     * BO-10-02-21: with the control ON, a non-13-digit EAN (e.g. an EAN8)
     * skips the check-digit gate entirely — the key control targets EAN13
     * only — and proceeds to the lookup.
     */
    @Test
    void shortEanSkipsCheckDigitGateWhenEnabled() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        EanScanHandler handler = newHandler();
        when(handler.posSettingsService.ean13CheckDigitEnabled()).thenReturn(true);
        ScanContext ctx = new ScanContext("12345678", state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Product.find("ean = ?1 and active = true", "12345678")).thenReturn(query);
            handler.handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError("CODE EAN INVALIDE");
    }

    /**
     * BO-02-03-11: a RECALLED product is refused at scan with the recall error,
     * before the age gate and without a line — the recall behaves exactly like
     * forbidden-to-sale.
     */
    @Test
    void recalledProductRefusedAtScan() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.RECALL, "true");
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ARTICLE EN RETRAIT/RAPPEL");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
        verify(handler.ticketService, never())
                .suspendForAgeCheck(any(), any(), any(), any(), any());
    }

    /**
     * BO-02-03-26/27: a VAT-EXEMPT product overrides the catalog VAT rate to 0
     * on the line, while the price (the amount due) is unchanged — invariance of
     * the amount due, only the VAT breakdown differs.
     */
    @Test
    void vatExemptProductVentilatesAtZeroRate() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.VAT_EXEMPT, "true");
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("1.5000");
        price.vat = new VatRate(1, new BigDecimal("0.0550"), "Taux normal");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        verify(ticket).addItem(eq(CODE), isNull(), eq("COCA COLA"),
                eq(new BigDecimal("1.5000")), eq(BigDecimal.ONE), eq(BigDecimal.ZERO));
    }

    /**
     * BO-02-03-02: the line label is the checkout label when the article carries
     * one, upper-cased, instead of the commercial name.
     */
    @Test
    void checkoutLabelIsUsedAsLineLabel() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.checkoutLabel = "Promo Cola";
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        verify(ticket).addItem(eq(CODE), isNull(), eq("PROMO COLA"),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
    }

    /**
     * BO-02-03-09: a DISCOUNT-FORBIDDEN product flags the freshly added line so
     * the price gestures later refuse it — the snapshot rides on the line.
     */
    @Test
    void discountForbiddenIsSnapshotOntoTheLine() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(added.discountForbidden);
    }

    /**
     * A plain EAN names no lot, so an article under lot recall has its recalled lots
     * listed and the line is rung all the same: the cashier reads the lot printed on
     * the pack ({@code LC-02-03-13}).
     */
    @Test
    void aScannedEanListsTheRecalledLots() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123;L456");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setNotice("PRODUIT EN RAPPEL - LOTS : L123, L456");
    }

    /**
     * An article under no lot recall says nothing — the arm that keeps the message off
     * every ordinary sale.
     */
    @Test
    void aScannedEanWithoutALotRecallSaysNothing() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        verify(ticket, never()).setNotice(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * LC-02-03-01/03: an article the referential does not fully carry PARKS the
     * add behind the entry prompt — {@code suspendForEntry} answers true, so the
     * context is consumed and NO line is created (the true arm of the entry
     * suspension, distinct from the age gate).
     */
    @Test
    void entrySuspensionParksTheScanAndAddsNoLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(false);
        when(handler.ticketService.suspendForEntry(state, p, null)).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(handler.ticketService).suspendForEntry(state, p, null);
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
        verify(ticket, never()).setError(any());
    }

    /**
     * A sellable product whose current price names NO regime — its {@code vat}
     * is null, so {@code vatRate()} is null — freezes the price but falls back
     * to the injected default VAT rate: the arm of the
     * {@code price != null && price.vatRate() != null} guard where the first leg
     * holds and the second does not.
     */
    @Test
    void productWithPriceButNoVatRateUsesDefaultVat() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("2.3000");
        price.vat = null;
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(CODE), isNull(), eq("COCA COLA"),
                eq(new BigDecimal("2.3000")), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
        verify(ticket, never()).setError("PRODUIT INTERDIT À LA VENTE");
    }

    /**
     * BO-03-01-18: an article the store took out of THIS register is refused
     * here, with no line rung; one the scope allows is rung as before (both
     * arms, plus the absent-service arm outside CDI).
     */
    @Test
    void anArticleOutOfThisRegistersScopeIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        EanScanHandler handler = newHandler();
        handler.articleScopeService =
                mock(com.intermarche.pos.ui.ticket.ArticleScopeService.class);
        when(handler.articleScopeService.soldHere(p)).thenReturn(false);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ARTICLE NON VENDU SUR CETTE CAISSE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());

        EanScanHandler allowed = newHandler();
        allowed.articleScopeService =
                mock(com.intermarche.pos.ui.ticket.ArticleScopeService.class);
        when(allowed.articleScopeService.soldHere(p)).thenReturn(true);
        ScanContext second = new ScanContext(CODE, newState(ticket));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            allowed.handle(second);
        }
        assertTrue(second.handled);
        verify(ticket).addItem(eq(CODE), isNull(), eq("COCA COLA"),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
    }

    /**
     * BO-03-06-44: a DEMATERIALISED article is rung first and the register then
     * ASKS for its activation code, naming the awaited line and snapshotting
     * the provider the referential declared. The line is added — the customer
     * is paying for it — and the demand rides on the message band.
     */
    @Test
    void aDematerialisedArticleAsksForItsActivationCode() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        added.uid = "ligne-1";
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .ACTIVATION_REQUIRED, "true");
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .ACTIVATION_PROVIDER, "TRANSACTIS");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        assertEquals("TRANSACTIS", added.activationProvider);
        assertEquals("ligne-1", ticket.pendingActivationUid);
        verify(ticket).setWarning("SCANNEZ LE CODE D'ACTIVATION");
    }

    /**
     * An ORDINARY article asks for nothing: no wait is opened and the line
     * carries no provider (the other arm of the guard).
     */
    @Test
    void anOrdinaryArticleOpensNoActivationWait() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        added.uid = "ligne-1";
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        assertNull(added.activationProvider);
        assertNull(ticket.pendingActivationUid);
        verify(ticket, never()).setWarning(any());
    }
}
