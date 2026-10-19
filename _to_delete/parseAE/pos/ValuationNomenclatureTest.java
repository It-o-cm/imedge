package com.intermarche.pos.ui.valuation;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the NOMENCLATURE sent with each basket line (BO-10-03-19).
 *
 * <p>The referential lookups are custom statics of {@link Product} and
 * {@link ProductFamily} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; no database is booted.
 *
 * <p>Branch enumeration: the rule off, the article found by PLU, the article
 * found by EAN, no identifier at all, an unknown article, an article filed
 * under no family, and a family with and without a parent.
 */
class ValuationNomenclatureTest {

    /**
     * Builds a service whose nomenclature rule answers the given value.
     *
     * @param sending whether the shop sends the nomenclature
     * @return a ready-to-test service
     */
    private ValuationService newService(boolean sending) {
        ValuationService service = new ValuationService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.fidelitySendSubFamily()).thenReturn(sending);
        return service;
    }

    /**
     * Builds a cart line carrying the given identifiers.
     *
     * @param ean the article's EAN, or null
     * @param plu the article's PLU, or null
     * @return the line
     */
    private TicketState.TicketItem item(String ean, String plu) {
        TicketState.TicketItem line = mock(TicketState.TicketItem.class);
        line.ean = ean;
        line.plu = plu;
        return line;
    }

    /**
     * Builds a family carrying a code and a parent code.
     *
     * @param code the family's own code
     * @param parent the parent code it answers, possibly null or blank
     * @return the family
     */
    private ProductFamily family(String code, String parent) {
        ProductFamily family = mock(ProductFamily.class);
        family.code = code;
        when(family.parentCode()).thenReturn(parent);
        return family;
    }

    /**
     * BO-10-03-19: the shop that does not send the nomenclature sends nothing,
     * and the referential is not even consulted.
     */
    @Test
    void theRuleOffSendsNothing() {
        ValuationPayloads.ItemDto dto = new ValuationPayloads.ItemDto();
        newService(false).fillNomenclature(dto, item("3017620422003", null));
        assertNull(dto.familyCode);
        assertNull(dto.subFamilyCode);
    }

    /**
     * BO-10-03-19: a weighed article is found by its PLU, and the deepest
     * family is the sub-family while its parent is the family.
     */
    @Test
    void aPluArticleSendsItsFamilyAndSubFamily() {
        ValuationPayloads.ItemDto dto = new ValuationPayloads.ItemDto();
        Product product = mock(Product.class);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            products.when(() -> Product.findByPlu("0100")).thenReturn(product);
            families.when(() -> ProductFamily.findDirectFamily(product))
                    .thenReturn(family("FL-TOM", "FL"));
            newService(true).fillNomenclature(dto, item(null, "0100"));
        }
        assertEquals("FL-TOM", dto.subFamilyCode);
        assertEquals("FL", dto.familyCode);
    }

    /**
     * BO-10-03-19: a scanned article is found by its EAN, and a family with no
     * parent sends its sub-family alone rather than an empty family.
     */
    @Test
    void anEanArticleWithoutAParentSendsTheSubFamilyAlone() {
        ValuationPayloads.ItemDto dto = new ValuationPayloads.ItemDto();
        Product product = mock(Product.class);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            products.when(() -> Product.findByEan("3017620422003")).thenReturn(product);
            families.when(() -> ProductFamily.findDirectFamily(product))
                    .thenReturn(family("EPICERIE", "  "));
            newService(true).fillNomenclature(dto, item("3017620422003", null));
        }
        assertEquals("EPICERIE", dto.subFamilyCode);
        assertNull(dto.familyCode);
    }

    /**
     * BO-10-03-19: a line with no identifier, an article the referential does
     * not know, and an article filed under no family all send nothing rather
     * than an empty code the engine would group under.
     */
    @Test
    void nothingIsSentWhenThereIsNothingToSend() {
        ValuationService service = newService(true);

        ValuationPayloads.ItemDto noIdentifier = new ValuationPayloads.ItemDto();
        service.fillNomenclature(noIdentifier, item(null, null));
        assertNull(noIdentifier.subFamilyCode);

        ValuationPayloads.ItemDto unknown = new ValuationPayloads.ItemDto();
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("3017620422003")).thenReturn(null);
            service.fillNomenclature(unknown, item("3017620422003", null));
        }
        assertNull(unknown.subFamilyCode);

        ValuationPayloads.ItemDto unfiled = new ValuationPayloads.ItemDto();
        Product product = mock(Product.class);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            products.when(() -> Product.findByEan("3017620422003")).thenReturn(product);
            families.when(() -> ProductFamily.findDirectFamily(product)).thenReturn(null);
            service.fillNomenclature(unfiled, item("3017620422003", null));
        }
        assertNull(unfiled.subFamilyCode);
    }
}
