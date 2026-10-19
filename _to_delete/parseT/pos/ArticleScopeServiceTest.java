package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.service.TicketNumberService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArticleScopeService} (BO-03-01-18).
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database: the checkout-point
 * finder is neutralized on ITS OWN declaring class with
 * {@link org.mockito.Mockito#mockStatic}, and the register's own identity comes
 * from a mocked {@link TicketNumberService}.
 *
 * <p>Branch enumeration: a null article; an article nobody scoped; an exclusion
 * naming this register; an exclusion naming this register's island; a register
 * the referential does not describe; a service built without the ticket
 * numbering (outside CDI); and a referential that cannot be reached at all.
 */
class ArticleScopeServiceTest {

    /** The terminal identifier every fixture answers. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service whose register is the fixture's terminal.
     *
     * @return the wired service
     */
    private ArticleScopeService newService() {
        ArticleScopeService service = new ArticleScopeService();
        service.ticketNumberService = mock(TicketNumberService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Builds a product carrying one administered attribute.
     *
     * @param code the attribute code
     * @param value the administered value
     * @return the product
     */
    private Product productWith(String code, String value) {
        Product product = new Product();
        product.attributes = new HashMap<>();
        product.attributes.put(code, value);
        return product;
    }

    /**
     * Builds a checkout point standing on the given island.
     *
     * @param islandCode the island code, or null
     * @return the checkout point
     */
    private CheckoutPoint pointOn(String islandCode) {
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = TERMINAL;
        point.islandCode = islandCode;
        return point;
    }

    /**
     * A null article is sellable: the question is about the referential's
     * scope, and there is nothing to scope.
     */
    @Test
    void aNullArticleIsSellable() {
        assertTrue(newService().soldHere(null));
    }

    /**
     * An article nobody scoped is sold here, which is what every article was
     * before the scope existed.
     */
    @Test
    void anUnscopedArticleIsSoldHere() {
        ArticleScopeService service = newService();
        try (MockedStatic<CheckoutPoint> ms = mockStatic(CheckoutPoint.class)) {
            ms.when(() -> CheckoutPoint.findByTerminal(TERMINAL)).thenReturn(pointOn("AVANT"));
            assertTrue(service.soldHere(new Product()));
        }
    }

    /**
     * An exclusion naming THIS register refuses the article, and one naming
     * another register does not.
     */
    @Test
    void anExclusionNamingThisRegisterRefusesTheArticle() {
        ArticleScopeService service = newService();
        Product here = productWith(ProductAttributeCatalog.DISABLED_TERMINALS, "C04;C07");
        Product elsewhere = productWith(ProductAttributeCatalog.DISABLED_TERMINALS, "C09");
        try (MockedStatic<CheckoutPoint> ms = mockStatic(CheckoutPoint.class)) {
            ms.when(() -> CheckoutPoint.findByTerminal(TERMINAL)).thenReturn(pointOn("AVANT"));
            assertFalse(service.soldHere(here));
            assertTrue(service.soldHere(elsewhere));
        }
    }

    /**
     * An exclusion naming the ISLAND this register stands on refuses the
     * article — the island is read from the distributed checkout point.
     */
    @Test
    void anExclusionNamingThisIslandRefusesTheArticle() {
        ArticleScopeService service = newService();
        Product excluded = productWith(ProductAttributeCatalog.DISABLED_ISLANDS, "LIBRE");
        try (MockedStatic<CheckoutPoint> ms = mockStatic(CheckoutPoint.class)) {
            ms.when(() -> CheckoutPoint.findByTerminal(TERMINAL)).thenReturn(pointOn("LIBRE"));
            assertFalse(service.soldHere(excluded));
        }
        try (MockedStatic<CheckoutPoint> ms = mockStatic(CheckoutPoint.class)) {
            ms.when(() -> CheckoutPoint.findByTerminal(TERMINAL)).thenReturn(pointOn("AVANT"));
            assertTrue(service.soldHere(excluded));
        }
    }

    /**
     * A register the back office does NOT describe keeps selling: a till
     * missing from the referential is a referential problem, not a reason to
     * refuse a customer (the null-point arm).
     */
    @Test
    void aRegisterTheReferentialDoesNotDescribeKeepsSelling() {
        ArticleScopeService service = newService();
        Product excluded = productWith(ProductAttributeCatalog.DISABLED_ISLANDS, "LIBRE");
        try (MockedStatic<CheckoutPoint> ms = mockStatic(CheckoutPoint.class)) {
            ms.when(() -> CheckoutPoint.findByTerminal(TERMINAL)).thenReturn(null);
            assertTrue(service.soldHere(excluded));
        }
    }

    /**
     * A service built outside CDI has no ticket numbering and therefore no
     * terminal: the register exclusion cannot match, and the island one is
     * read from a point nobody can find (the null-service arm).
     */
    @Test
    void withoutTheTicketNumberingNothingIsExcluded() {
        ArticleScopeService service = new ArticleScopeService();
        Product excluded = productWith(ProductAttributeCatalog.DISABLED_TERMINALS, "C04");
        try (MockedStatic<CheckoutPoint> ms = mockStatic(CheckoutPoint.class)) {
            ms.when(() -> CheckoutPoint.findByTerminal(null)).thenReturn(null);
            assertTrue(service.soldHere(excluded));
        }
    }

    /**
     * A referential this service cannot reach is not an answer the customer
     * should pay for: the article stays sellable (the catch arm — no static
     * mock here, so the finder raises).
     */
    @Test
    void anUnreachableReferentialKeepsTheArticleSellable() {
        ArticleScopeService service = newService();
        Product excluded = productWith(ProductAttributeCatalog.DISABLED_TERMINALS, "C04");
        assertTrue(service.soldHere(excluded));
    }
}
