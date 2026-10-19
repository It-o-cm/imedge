package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.VatRate;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArticleInfoResource} (EXP-93).
 * <p>
 * The resource is an article lookup: it renders a form, and when a code is given,
 * the found article or a not-found message. Taking the article is a separate,
 * CONDITIONAL action — it needs an unlocked register and an open session — and both
 * of its guards are covered on each leg. The Qute {@code articleInfo} template is a
 * Mockito mock whose fluent
 * {@code data(...)} chain echoes itself so the returned view is identifiable and
 * the {@code info}/{@code notFound} values can be captured; the two Panache
 * finders and the current-price lookup are neutralized with
 * {@link org.mockito.Mockito#mockStatic}. Each test asserts absolute expected
 * values. {@link ArticleInfoResource.ArticleInfo} is exercised directly for its
 * formatting arms, being a package-visible view.
 */
class ArticleInfoResourceTest {

    /** A scanned/keyed code used across the lookup tests. */
    private static final String CODE = "3560070000000";

    /** The echoing view the fluent {@code data(...)} chain resolves to. */
    private TemplateInstance view;

    /**
     * Builds a resource whose {@code articleInfo} template echoes its fluent
     * {@code data(...)} chain: the first call returns {@link #view} and every
     * further call on it returns itself, so the {@code info}/{@code notFound}
     * links (called on the view) can be verified there.
     *
     * @return the wired resource
     */
    private ArticleInfoResource newResource() {
        ArticleInfoResource resource = new ArticleInfoResource();
        resource.articleInfo = mock(Template.class);
        resource.ticketService = mock(TicketService.class);
        resource.cashSessionService = mock(com.intermarche.pos.service.CashSessionService.class);
        // BO-04-01-38: every lookup is journalised, so the journal is a
        // collaborator of this screen and not an optional one.
        resource.technicalEventService =
                mock(com.intermarche.pos.service.TechnicalEventService.class);
        // A real state: the lock is the register's own gate and a fresh one is locked,
        // which is also the state the take key must not appear in.
        resource.state = new com.intermarche.pos.ui.PosState();
        resource.state.auth.isLocked = false;
        when(resource.cashSessionService.getOpenSession())
                .thenReturn(mock(com.intermarche.pos.domain.session.CashSession.class));
        view = mock(TemplateInstance.class);
        when(resource.articleInfo.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return resource;
    }

    /**
     * Builds an active product with the given identifiers.
     *
     * @param name the sale name
     * @param ean the EAN, or null
     * @param plu the PLU, or null
     * @return the wired product
     */
    private Product product(String name, String ean, String plu) {
        Product p = new Product();
        p.id = 7L;
        p.name = name;
        p.ean = ean;
        p.plu = plu;
        p.brand = "MARQUE";
        p.active = true;
        return p;
    }

    /**
     * Builds a current price with a tax-inclusive amount and a VAT rate.
     *
     * @param incTax the price including tax
     * @param vat the VAT rate
     * @return the wired price
     */
    private Price price(String incTax, String vat) {
        Price pr = new Price();
        pr.priceIncludingTax = new BigDecimal(incTax);
        pr.vat = new VatRate(1, new BigDecimal(vat), "Taux normal");
        return pr;
    }

    /**
     * A blank code shows the form alone: no info, not-found false (both guard
     * legs of the blank check taken as blank).
     */
    @Test
    void infoPageBlankCodeShowsFormOnly() {
        ArticleInfoResource resource = newResource();
        resource.infoPage("  ");
        verify(view).data(eq("info"), isNullArg());
        verify(view).data("notFound", false);
    }

    /**
     * A null code shows the form alone: no info, not-found false.
     */
    @Test
    void infoPageNullCodeShowsFormOnly() {
        ArticleInfoResource resource = newResource();
        resource.infoPage(null);
        verify(view).data(eq("info"), isNullArg());
        verify(view).data("notFound", false);
    }

    /**
     * A code matching an active product by EAN renders its info, not-found false.
     */
    @Test
    void infoPageFoundByEanRendersInfo() {
        ArticleInfoResource resource = newResource();
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(CODE)).thenReturn(product("LAIT", CODE, null));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("2.35", "0.20"));
            resource.infoPage(CODE);
            ArgumentCaptor<Object> info = ArgumentCaptor.forClass(Object.class);
            verify(view).data(eq("info"), info.capture());
            assertNotNull(info.getValue());
            assertEquals("LAIT", ((ArticleInfoResource.ArticleInfo) info.getValue()).getLabel());
            verify(view).data("notFound", false);
        }
    }

    /**
     * A code that matches no EAN falls back to the PLU lookup and renders the
     * product found there (EAN-null arm of the fallback).
     */
    @Test
    void infoPageFallsBackToPluLookup() {
        ArticleInfoResource resource = newResource();
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("42")).thenReturn(null);
            products.when(() -> Product.findActiveByPlu("42")).thenReturn(product("POMME", null, "42"));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("1.00", "0.055"));
            resource.infoPage("42");
            verify(view).data("notFound", false);
        }
    }

    /**
     * A code matching neither EAN nor PLU shows the not-found message with no info.
     */
    @Test
    void infoPageNotFoundShowsMessage() {
        ArticleInfoResource resource = newResource();
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan("999")).thenReturn(null);
            products.when(() -> Product.findActiveByPlu("999")).thenReturn(null);
            resource.infoPage("999");
            verify(view).data(eq("info"), isNullArg());
            verify(view).data("notFound", true);
        }
    }

    /**
     * {@link ArticleInfoResource.ArticleInfo} formats a priced article: price with
     * a French comma and VAT as a percentage; the identifiers pass through.
     */
    @Test
    void articleInfoFormatsAPricedArticle() {
        ArticleInfoResource.ArticleInfo info =
                new ArticleInfoResource.ArticleInfo(product("LAIT", CODE, "12"), price("2.35", "0.20"));
        assertEquals("LAIT", info.getLabel());
        assertEquals(CODE, info.getEan());
        assertEquals("12", info.getPlu());
        assertEquals("MARQUE", info.getBrand());
        assertEquals("2,35", info.getPriceFormatted());
        assertEquals("20 %", info.getVatFormatted());
    }

    /**
     * {@link ArticleInfoResource.ArticleInfo} shows dashes for an unpriced article
     * and blank identifiers (each null/blank arm).
     */
    @Test
    void articleInfoShowsDashesWhenUnpricedAndBlank() {
        ArticleInfoResource.ArticleInfo info =
                new ArticleInfoResource.ArticleInfo(product("DIVERS", "", ""), null);
        assertEquals("—", info.getEan());
        assertEquals("—", info.getPlu());
        assertEquals("—", info.getPriceFormatted());
        assertEquals("—", info.getVatFormatted());
    }

    /**
     * The take key is offered when the register is unlocked AND a session is open:
     * the only state in which a ticket can exist.
     */
    @Test
    void theTakeKeyIsOfferedWhenASaleIsPossible() {
        ArticleInfoResource resource = newResource();
        assertTrue(resource.canTake());
        resource.infoPage(null);
        verify(view).data("canTake", true);
    }

    /**
     * A LOCKED register offers no take key (first leg false): caisse fermée, the
     * screen is the pure consultation it has always been.
     */
    @Test
    void theTakeKeyIsHiddenOnALockedRegister() {
        ArticleInfoResource resource = newResource();
        resource.state.auth.isLocked = true;
        assertFalse(resource.canTake());
        resource.infoPage(null);
        verify(view).data("canTake", false);
    }

    /**
     * With NO open session there is no take key either (second leg false): there is
     * no ticket to add a line to.
     */
    @Test
    void theTakeKeyIsHiddenWithoutAnOpenSession() {
        ArticleInfoResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertFalse(resource.canTake());
        resource.infoPage(null);
        verify(view).data("canTake", false);
    }

    /**
     * Taking the article walks the code through the SCAN ADMISSION — the chain a
     * scanner walks — and lands the cashier back on the sale screen, where the line
     * now is. The code is trimmed on the way.
     */
    @Test
    void takeArticleScansTheCodeAndReturnsToTheSaleScreen() {
        ArticleInfoResource resource = newResource();
        Response response = resource.takeArticle("  3245412  ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).processScan("3245412");
    }

    /**
     * An empty code takes nothing (first leg of the refusal) and comes back to the
     * lookup.
     */
    @Test
    void takeArticleWithAnEmptyCodeTakesNothing() {
        ArticleInfoResource resource = newResource();
        Response response = resource.takeArticle("   ");
        assertEquals("/article-info", response.getLocation().toString());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * A NULL code takes nothing either (the null arm of the trimming ternary).
     */
    @Test
    void takeArticleWithANullCodeTakesNothing() {
        ArticleInfoResource resource = newResource();
        Response response = resource.takeArticle(null);
        assertEquals("/article-info", response.getLocation().toString());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * A code posted straight at the action on a LOCKED register takes nothing
     * (second leg of the refusal): the key was not shown, and the action does not
     * trust that it was not.
     */
    @Test
    void takeArticleIsRefusedWhenNoSaleIsPossible() {
        ArticleInfoResource resource = newResource();
        resource.state.auth.isLocked = true;
        Response response = resource.takeArticle("3245412");
        assertEquals("/article-info", response.getLocation().toString());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * A Mockito nullable-argument matcher for the {@code data("info", null)} call.
     *
     * @return null, registered as an argument matcher
     */
    private static Object isNullArg() {
        return org.mockito.ArgumentMatchers.isNull();
    }

    /**
     * BO-04-01-38: a price CHECK is journalised — found or not, because a code
     * nobody recognises is exactly what a shop looks for afterwards — and the
     * empty form is not a check and writes nothing.
     */
    @Test
    void thePriceCheckIsJournalised() {
        ArticleInfoResource resource = newResource();
        resource.infoPage(null);
        resource.infoPage("   ");
        verify(resource.technicalEventService, never())
                .log(any(), any(), any());

        ArticleInfoResource unknown = newResource();
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan("999")).thenReturn(null);
            products.when(() -> Product.findActiveByPlu("999")).thenReturn(null);
            unknown.infoPage("999");
        }
        verify(unknown.technicalEventService).log(
                eq(com.intermarche.pos.domain.session.TechnicalEvent.EventType.PRICE_CHECKED),
                eq("999 (inconnu)"), any());
    }
}
