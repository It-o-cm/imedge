package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PriceKioskResource} (BO-11-02-06), targeting 100%
 * branch coverage: the exposure off, an absent, malformed or undeclared
 * credential, a kiosk with no key of its own, an unknown article, an article
 * with no price or no amount, and each of the three answer formats, offer or
 * not, with labels to escape.
 */
class PriceKioskResourceTest {

    /**
     * Builds the resource with the exposure and format given.
     *
     * @param enabled whether the exposure is on
     * @param format the answer format
     * @return the resource
     */
    private PriceKioskResource resource(boolean enabled, String format) {
        PriceKioskResource resource = new PriceKioskResource();
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.posSettingsService.kioskEnabled()).thenReturn(enabled);
        when(resource.posSettingsService.kioskFormat()).thenReturn(format);
        return resource;
    }

    /**
     * Builds a kiosk provider.
     *
     * @param key its credential, possibly null
     * @return the provider
     */
    private ServiceProvider kiosk(String key) {
        ServiceProvider kiosk = new ServiceProvider();
        kiosk.code = "BORNE";
        kiosk.providerType = ServiceProvider.ProviderType.BORNE_PRIX;
        kiosk.authKey = key;
        return kiosk;
    }

    /**
     * Stubs the declared kiosks, the article and its current price.
     *
     * @param panache the Panache static mock
     * @param product the article the EAN finds, possibly null
     * @param price its current price, possibly null
     */
    @SuppressWarnings("unchecked")
    private void stub(MockedStatic<PanacheEntityBase> panache, Product product, Price price) {
        panache.when(() -> ServiceProvider.list("providerType = ?1 and active = true order by code",
                ServiceProvider.ProviderType.BORNE_PRIX)).thenReturn(List.of(kiosk(null), kiosk("k1")));
        PanacheQuery<Product> products = mock(PanacheQuery.class);
        when(products.firstResult()).thenReturn(product);
        panache.when(() -> Product.find(anyString(), eq("3250390000001"))).thenReturn(products);
        PanacheQuery<Price> prices = mock(PanacheQuery.class);
        when(prices.firstResult()).thenReturn(price);
        panache.when(() -> Price.find(anyString(), eq(1L), any())).thenReturn(prices);
    }

    /**
     * Builds an article and its price.
     *
     * @param amount the tax-included amount, possibly null
     * @param priority the price priority, an offer above zero, possibly null
     * @return the price, its article attached
     */
    private Price price(String amount, Integer priority) {
        Product product = new Product();
        product.id = 1L;
        product.ean = "3250390000001";
        product.name = "CAFE <MOULU>|250G";
        Price price = new Price();
        price.product = product;
        price.priceIncludingTax = amount == null ? null : new BigDecimal(amount);
        price.priority = priority;
        return price;
    }

    /** The exposure off refuses every kiosk, and an undeclared one is refused. */
    @Test
    void onlyDeclaredKiosksAreAnswered() {
        Response off = resource(false, "JSON").price("Bearer k1", "1");
        Assertions.assertEquals(403, off.getStatus());
        Assertions.assertEquals("text/plain;charset=UTF-8", off.getMediaType().toString().replace(" ", ""));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, null, null);
            PriceKioskResource resource = resource(true, "JSON");
            Response undeclared = resource.price(null, "3250390000001");
            Assertions.assertEquals(401, undeclared.getStatus());
            Assertions.assertEquals("Borne de prix non déclarée", undeclared.getEntity());
            Assertions.assertEquals(PriceKioskResource.REFUSAL_TYPE.replace(" ", ""),
                    undeclared.getMediaType().toString().replace(" ", ""));
            Assertions.assertEquals(401, resource.price("Basic k1", "3250390000001").getStatus());
            Assertions.assertEquals(401, resource.price("Bearer other", "3250390000001").getStatus());
            Assertions.assertEquals(404, resource.price("Bearer k1", " 3250390000001 ").getStatus());
        }
    }

    /** An article without a price, or a price without an amount, is unknown to the kiosk. */
    @Test
    void anArticleWithoutAnAmountIsUnknown() {
        Price noAmount = price(null, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, noAmount.product, null);
            Assertions.assertEquals(404, resource(true, "JSON").price("Bearer k1", "3250390000001").getStatus());
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, noAmount.product, noAmount);
            Assertions.assertEquals(404, resource(true, "JSON").price("Bearer k1", "3250390000001").getStatus());
        }
        Assertions.assertEquals(404, resourceAnswering(null).getStatus());
    }

    /**
     * Asks the price of a null EAN to a declared kiosk.
     *
     * @param ean the EAN, possibly null
     * @return the answer
     */
    private Response resourceAnswering(String ean) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, null, null);
            panache.when(() -> Product.find("ean = ?1 and active = true", (Object) null))
                    .thenReturn(mock(PanacheQuery.class));
            return resource(true, "JSON").price("Bearer k1", ean);
        }
    }

    /** The price is answered in the format of the kiosk's protocol, the offer flagged. */
    @Test
    void thePriceIsAnsweredInTheKioskFormat() {
        Price offer = price("1.999", 5);
        Price base = price("2", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, offer.product, offer);
            Response json = resource(true, "JSON").price("Bearer k1", "3250390000001");
            Assertions.assertEquals(200, json.getStatus());
            Assertions.assertEquals(new PriceKioskResource.KioskPrice("3250390000001", "CAFE <MOULU>|250G",
                    new BigDecimal("2.00"), "EUR", true, null, null), json.getEntity());
            Response xml = resource(true, "XML").price("Bearer k1", "3250390000001");
            Assertions.assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><price><ean>3250390000001</ean>"
                    + "<label>CAFE &lt;MOULU&gt;|250G</label><amount>2.00</amount><currency>EUR</currency>"
                    + "<offer>true</offer></price>", xml.getEntity());
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, base.product, base);
            Response text = resource(true, "TEXT").price("Bearer k1", "3250390000001");
            base.priority = 0;
            Assertions.assertEquals(new PriceKioskResource.KioskPrice("3250390000001", "CAFE <MOULU>|250G",
                    new BigDecimal("2.00"), "EUR", false, null, null),
                    resource(true, "JSON").price("Bearer k1", "3250390000001").getEntity());
            Assertions.assertEquals("3250390000001|CAFE <MOULU> 250G|2.00||", text.getEntity());
            base.product.name = null;
            Response blank = resource(true, "XML").price("Bearer k1", "3250390000001");
            Assertions.assertTrue(blank.getEntity().toString().contains("<label></label>"));
            Assertions.assertTrue(blank.getEntity().toString().contains("<offer>false</offer>"));
            Response blankText = resource(true, "TEXT").price("Bearer k1", "3250390000001");
            Assertions.assertEquals("3250390000001||2.00||", blankText.getEntity());
        }
    }

    /**
     * LS-11-01-02: the price per litre the kiosk.enabled parameter promises is
     * answered in the three formats beside the price.
     */
    @Test
    void theUnitPriceIsAnsweredInTheThreeFormats() {
        Price milk = price("2.76", 0);
        milk.product.name = "Lait UHT 1L";
        milk.product.unitName = "L";
        milk.product.referenceVolume = new BigDecimal("1.000");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stub(panache, milk.product, milk);
            Assertions.assertEquals(new PriceKioskResource.KioskPrice("3250390000001", "Lait UHT 1L",
                    new BigDecimal("2.76"), "EUR", false, new BigDecimal("2.76"), "l"),
                    resource(true, "JSON").price("Bearer k1", "3250390000001").getEntity());
            Assertions.assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><price><ean>3250390000001</ean>"
                    + "<label>Lait UHT 1L</label><amount>2.76</amount><currency>EUR</currency>"
                    + "<offer>false</offer><unitPrice unit=\"l\">2.76</unitPrice></price>",
                    resource(true, "XML").price("Bearer k1", "3250390000001").getEntity());
            Assertions.assertEquals("3250390000001|Lait UHT 1L|2.76|2.76|l",
                    resource(true, "TEXT").price("Bearer k1", "3250390000001").getEntity());
        }
    }

    /**
     * LS-11-01-02: the unit price of every kind of article — sold loose or typed
     * by weight (the price is per kilogram already), typed by volume (per litre
     * already), a pack whose unit is a weight or a volume (the price divided by
     * its reference quantity), and nothing for a piece, a missing unit or a
     * missing or null reference.
     */
    @Test
    void theUnitPriceFollowsTheArticleKind() {
        BigDecimal current = new BigDecimal("2.999");
        Product loose = new Product();
        loose.variableWeight = true;
        Assertions.assertEquals(new PriceKioskResource.UnitPrice(new BigDecimal("3.00"), "kg"),
                PriceKioskResource.unitPrice(loose, current));
        Product weighed = new Product();
        weighed.productType = ProductType.WEIGHT;
        Assertions.assertEquals(new PriceKioskResource.UnitPrice(new BigDecimal("3.00"), "kg"),
                PriceKioskResource.unitPrice(weighed, current));
        Product bulkLiquid = new Product();
        bulkLiquid.productType = ProductType.VOLUME;
        Assertions.assertEquals(new PriceKioskResource.UnitPrice(new BigDecimal("3.00"), "l"),
                PriceKioskResource.unitPrice(bulkLiquid, current));
        Product coffee = new Product();
        coffee.productType = ProductType.UNIT;
        coffee.unitName = " kg ";
        coffee.referenceWeight = new BigDecimal("0.500");
        Assertions.assertEquals(new PriceKioskResource.UnitPrice(new BigDecimal("6.00"), "kg"),
                PriceKioskResource.unitPrice(coffee, current));
        Product water = new Product();
        water.unitName = "cl";
        water.referenceVolume = new BigDecimal("1.500");
        Assertions.assertEquals(new PriceKioskResource.UnitPrice(new BigDecimal("2.00"), "l"),
                PriceKioskResource.unitPrice(water, current));
        Product noWeight = new Product();
        noWeight.unitName = "g";
        Assertions.assertNull(PriceKioskResource.unitPrice(noWeight, current));
        noWeight.referenceWeight = BigDecimal.ZERO;
        Assertions.assertNull(PriceKioskResource.unitPrice(noWeight, current));
        Product pan = new Product();
        pan.unitName = "pcs";
        Assertions.assertNull(PriceKioskResource.unitPrice(pan, current));
        Assertions.assertNull(PriceKioskResource.unitPrice(new Product(), current));
    }
}
