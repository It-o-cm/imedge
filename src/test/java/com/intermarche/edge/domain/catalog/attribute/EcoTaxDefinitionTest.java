package com.intermarche.edge.domain.catalog.attribute;

import com.intermarche.edge.domain.catalog.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link EcoTaxDefinition} (BO-10-06-05, BO-10-06-06): the
 * parsing of the administered list, the built-in eco-tax always present, and
 * the amounts an article bears.
 */
class EcoTaxDefinitionTest {

    /**
     * Builds an article carrying attribute values.
     *
     * @param pairs the attribute codes and values, alternately
     * @return the article
     */
    private Product product(String... pairs) {
        Product product = new Product();
        product.attributes = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            product.attributes.put(pairs[i], pairs[i + 1]);
        }
        return product;
    }

    /** A null or blank list is the built-in eco-tax alone, printed under the register's wording. */
    @Test
    void anEmptyListIsTheBuiltInEcoTax() {
        List<EcoTaxDefinition> expected = List.of(new EcoTaxDefinition(ProductAttributeCatalog.ECO_TAX, null, true));
        assertEquals(expected, EcoTaxDefinition.parse(null));
        assertEquals(expected, EcoTaxDefinition.parse(" ; "));
    }

    /** Five declared eco-taxes and more, with labels and print choices, the built-in one first. */
    @Test
    void declaredEcoTaxesCarryTheirLabelAndPrintChoice() {
        List<EcoTaxDefinition> parsed = EcoTaxDefinition.parse(
                "DEEE=Eco DEEE;DEA=;PILES>noprint; PNEUS = Eco pneus >NOPRINT;EMB;DEEE=Again");
        assertEquals(List.of(
                new EcoTaxDefinition(ProductAttributeCatalog.ECO_TAX, null, true),
                new EcoTaxDefinition("DEEE", "Eco DEEE", true),
                new EcoTaxDefinition("DEA", null, true),
                new EcoTaxDefinition("PILES", null, false),
                new EcoTaxDefinition("PNEUS", "Eco pneus", false),
                new EcoTaxDefinition("EMB", null, true)), parsed);
    }

    /** The built-in attribute, when listed, takes the administered label and print choice at its own rank. */
    @Test
    void theBuiltInEcoTaxCanBeAdministered() {
        assertEquals(List.of(new EcoTaxDefinition("DEEE", null, true),
                        new EcoTaxDefinition(ProductAttributeCatalog.ECO_TAX, "Eco", false)),
                EcoTaxDefinition.parse("DEEE;ECO_TAX=Eco>NOPRINT"));
    }

    /** The amounts an article bears, one eco-tax and all of them, unreadable values being zero. */
    @Test
    void amountsAreReadFromTheArticle() {
        Product product = product("ECO_TAX", "0,30", "DEEE", "1.20", "DEA", "n/a");
        List<EcoTaxDefinition> definitions = EcoTaxDefinition.parse("DEEE;DEA");
        assertEquals(new BigDecimal("1.20"), definitions.get(1).unitAmount(product));
        assertEquals(new BigDecimal("1.50"), EcoTaxDefinition.totalUnitAmount(product, definitions));
        assertEquals(BigDecimal.ZERO, EcoTaxDefinition.totalUnitAmount(null, definitions));
    }

    /** No definitions handed over is the built-in eco-tax; handed over, they stand. */
    @Test
    void orBuiltInReplacesOnlyAMissingList() {
        List<EcoTaxDefinition> builtIn = EcoTaxDefinition.parse(null);
        assertEquals(builtIn, EcoTaxDefinition.orBuiltIn(null));
        assertEquals(builtIn, EcoTaxDefinition.orBuiltIn(List.of()));
        List<EcoTaxDefinition> administered = List.of(new EcoTaxDefinition("DEEE", null, true));
        assertSame(administered, EcoTaxDefinition.orBuiltIn(administered));
    }
}
