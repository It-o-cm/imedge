package com.intermarche.edge.domain.catalog;

import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ArticleVat} (BO-02-03-26, -27): the precedence of the
 * article's exemption, the article's own regime, the price's regime and the
 * default rate, every leg exercised.
 */
class ArticleVatTest {

    /** The register's default rate in these tests. */
    private static final BigDecimal DEFAULT = new BigDecimal("0.20");

    /**
     * Builds a price under a regime.
     *
     * @param regime the regime, or null
     * @return the price
     */
    private Price price(VatRate regime) {
        Price price = new Price();
        price.vat = regime;
        return price;
    }

    /**
     * Builds a regime.
     *
     * @param number the number
     * @param rate the rate
     * @param exempt whether it is an exemption
     * @return the regime
     */
    private VatRate regime(int number, String rate, boolean exempt) {
        VatRate regime = new VatRate(number, new BigDecimal(rate), "R");
        regime.exempt = exempt;
        return regime;
    }

    /**
     * Without a price the default rate answers; a price without a regime too;
     * a price's regime gives its rate, and its exemption when it is one.
     */
    @Test
    void thePriceRegimeAnswersWhenTheArticleNamesNone() {
        Product product = new Product();
        ArticleVat.Resolution none = ArticleVat.resolve(product, null, DEFAULT);
        assertEquals(DEFAULT, none.rate());
        assertFalse(none.exempt());
        assertEquals(DEFAULT, ArticleVat.resolve(product, price(null), DEFAULT).rate());
        ArticleVat.Resolution reduced = ArticleVat.resolve(product, price(regime(2, "0.055", false)), DEFAULT);
        assertEquals(new BigDecimal("0.055"), reduced.rate());
        assertFalse(reduced.exempt());
        ArticleVat.Resolution exempt = ArticleVat.resolve(product, price(regime(9, "0", true)), DEFAULT);
        assertEquals(BigDecimal.ZERO, exempt.rate());
        assertTrue(exempt.exempt());
    }

    /**
     * The article's own regime outranks the price's; an own regime the
     * referential does not know leaves the price's in place.
     */
    @Test
    void theArticleRegimeOutranksThePrice() {
        Product product = new Product();
        product.attributes.put(ProductAttributeCatalog.VAT_NUMBER, "3");
        Product unknown = new Product();
        unknown.attributes.put(ProductAttributeCatalog.VAT_NUMBER, "8");
        @SuppressWarnings("unchecked")
        PanacheQuery<VatRate> found = mock(PanacheQuery.class,
                invocation -> "firstResult".equals(invocation.getMethod().getName()) ? regime(3, "0.10", false) : null);
        @SuppressWarnings("unchecked")
        PanacheQuery<VatRate> missing = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.find("number", 3)).thenReturn(found);
            panache.when(() -> VatRate.find("number", 8)).thenReturn(missing);
            assertEquals(new BigDecimal("0.10"),
                    ArticleVat.resolve(product, price(regime(1, "0.20", true)), DEFAULT).rate());
            assertFalse(ArticleVat.resolve(product, null, DEFAULT).exempt());
            assertEquals(new BigDecimal("0.20"),
                    ArticleVat.resolve(unknown, price(regime(1, "0.20", false)), DEFAULT).rate());
        }
    }

    /**
     * The article's exemption flag outranks every regime: rate zero, exempt.
     */
    @Test
    void theExemptionFlagOutranksEverything() {
        Product product = new Product();
        product.attributes.put(ProductAttributeCatalog.VAT_EXEMPT, "true");
        ArticleVat.Resolution resolution = ArticleVat.resolve(product, price(regime(1, "0.20", false)), DEFAULT);
        assertEquals(BigDecimal.ZERO, resolution.rate());
        assertTrue(resolution.exempt());
    }
}
