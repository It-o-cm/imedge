package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.ProductType;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductCsvResource}.
 * <p>
 * The resource is the {@link Product}-specific concretion of the abstract
 * {@link ImporterCsvResource}: for a chunk it bulk-fetches the products the
 * file names, then for each line creates a product or, on an existing one,
 * compares checksums through {@code changedFrom} to decide the update. Its
 * finders resolve to {@link PanacheEntityBase} under plain {@code mvn test}
 * and are intercepted with {@link org.mockito.Mockito#mockStatic} inside a
 * try-with-resources block; {@code Panache.getEntityManager()} is stubbed the
 * same way so {@code persist} is observable, and
 * {@link ProductFamily#fileUnderNomenclature} is stubbed as a static so the
 * filing side-call is verifiable. Private helpers are reached by reflection so
 * both arms of every guard carry absolute expected values. No database and no
 * Quarkus context is booted.
 */
class ProductCsvResourceTest {

    /** The exact JPQL fragment issued by {@code processChunkWithFallback}. */
    private static final String LIST_QUERY = "ean IN ?1";
    /** The exact JPQL fragment issued by {@code findEntityForLine}. */
    private static final String FIND_QUERY = "ean";

    /**
     * Wraps a string as a UTF-8 input stream.
     *
     * @param content the CSV content
     * @return the input stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the canonical 9-column product CSV row used across the line tests.
     *
     * @return a fresh parts array
     *         {EAN, Name, Description, Brand, Weight, Volume, Type, Unit, Active}
     */
    private String[] fullParts() {
        return new String[]{"111", "Milk", "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true"};
    }

    /**
     * Invokes the private {@code computeIncomingChecksum} helper by reflection.
     *
     * @param resource the resource under test
     * @param data     the parsed line
     * @param existing the product the row would update
     * @return the checksum of the incoming state
     * @throws Exception on reflection failure
     */
    private static int incomingChecksum(ProductCsvResource resource, ImporterCsvResource.LineData data,
                                        Product existing) throws Exception {
        Method method = ProductCsvResource.class.getDeclaredMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        method.setAccessible(true);
        return (Integer) method.invoke(resource, data, existing);
    }

    /**
     * {@code importProducts} forwards the stream to the base importer with the
     * 9-column contract; a header-only body produces a zero-count JSON with a
     * 200 status.
     */
    @Test
    void importProductsDelegatesToBaseImporterWithNineColumns() {
        ProductCsvResource resource = new ProductCsvResource();
        Response response = resource.importProducts(stream("EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0,\"updatedCount\":0}", response.getEntity());
    }

    /**
     * {@code processChunkWithFallback} short-circuits to an empty map when the
     * chunk holds no lines (the {@code parsedLines.isEmpty} true arm).
     */
    @Test
    void processChunkWithFallbackReturnsEmptyMapForEmptyChunk() {
        ProductCsvResource resource = new ProductCsvResource();
        Map<String, Object> context = resource.processChunkWithFallback(new ArrayList<>(), new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
        assertTrue(context.isEmpty());
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches and indexes existing
     * products by EAN for a populated chunk (parsedLines non-empty arm,
     * targetEans non-empty arm, loop entered).
     */
    @Test
    void processChunkWithFallbackIndexesExistingProducts() {
        ProductCsvResource resource = new ProductCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(1, fullParts()));
        Set<String> eans = new HashSet<>();
        eans.add("111");
        Product product = new Product();
        product.ean = "111";
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(LIST_QUERY, eans)).thenReturn(List.of(product));
            Map<String, Object> context = resource.processChunkWithFallback(lines, eans, counters, errors);
            assertEquals(1, context.size());
            assertSame(product, context.get("111"));
        }
    }

    /**
     * {@code processChunkWithFallback} queries but indexes nothing when no
     * existing products match (targetEans non-empty arm, loop-not-entered arm).
     */
    @Test
    void processChunkWithFallbackHandlesNoExistingProducts() {
        ProductCsvResource resource = new ProductCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(1, fullParts()));
        Set<String> eans = new HashSet<>();
        eans.add("111");
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(LIST_QUERY, eans)).thenReturn(List.of());
            Map<String, Object> context = resource.processChunkWithFallback(lines, eans, counters, errors);
            assertTrue(context.isEmpty());
        }
    }

    /**
     * {@code processChunkWithFallback} with an empty EAN set skips the product
     * query entirely (the {@code !targetEans.isEmpty} false arm) and returns an
     * empty context.
     */
    @Test
    void processChunkWithFallbackSkipsQueryForEmptyEans() {
        ProductCsvResource resource = new ProductCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(1, fullParts()));
        Map<String, Object> context = resource.processChunkWithFallback(lines, new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
        assertTrue(context.isEmpty());
    }

    /**
     * {@code feedProduct} lands a blank PLU cell as NULL — never "" — and
     * reads the VARIABLE_WEIGHT marker (blank-PLU true arm, marker declared
     * arm): the plu column is unique, and a feed holding several products
     * without a PLU must not collide on the empty string.
     */
    @Test
    void processLineLogicNullifiesBlankPluAndReadsVariableWeight() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = weighLine(1, new String[]{"111", "Milk", "Fresh milk",
                "BrandX", "1.500", "2.000", "unit", "kg", "true", "", "true"});
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            assertNull(captor.getValue().plu);
            assertTrue(captor.getValue().variableWeight);
        }
    }

    /**
     * {@code feedProduct} keeps a filled PLU verbatim (blank-PLU false arm)
     * and a false VARIABLE_WEIGHT cell lands false.
     */
    @Test
    void processLineLogicKeepsFilledPluAndFalseVariableWeight() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = weighLine(1, new String[]{"111", "Milk", "Fresh milk",
                "BrandX", "1.500", "2.000", "unit", "kg", "true", "4020", "false"});
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            assertEquals("4020", captor.getValue().plu);
            assertFalse(captor.getValue().variableWeight);
        }
    }

    /**
     * {@code computeIncomingChecksum} mirrors the VARIABLE_WEIGHT touch rule:
     * an absent column reads the existing product's value (existing arm), a
     * declared column reads the cell (declared arm) — so only a real change
     * flips the checksum.
     */
    @Test
    void computeIncomingChecksumMirrorsVariableWeightTouchRule() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product existing = new Product();
        existing.ean = "111";
        existing.variableWeight = true;
        int absent = incomingChecksum(resource, line(1, fullParts()), existing);
        int declaredTrue = incomingChecksum(resource, weighLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true", "", "true"}), existing);
        int declaredFalse = incomingChecksum(resource, weighLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true", "", "false"}), existing);
        assertEquals(absent, declaredTrue);
        assertNotEquals(declaredTrue, declaredFalse);
    }

    /**
     * {@code processLineLogic} creates a fresh product when the context map
     * lacks the line's EAN ({@code product == null} true arm), populating every
     * field via {@code feedProduct}, incrementing the created counter and
     * persisting through the entity manager.
     */
    @Test
    void processLineLogicCreatesNewProductWhenAbsent() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = line(1, fullParts());
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            Product persisted = captor.getValue();
            assertEquals("111", persisted.ean);
            assertEquals("Milk", persisted.name);
            assertEquals("Fresh milk", persisted.description);
            assertEquals("BrandX", persisted.brand);
            assertEquals(new BigDecimal("1.500"), persisted.referenceWeight);
            assertEquals(new BigDecimal("2.000"), persisted.referenceVolume);
            assertEquals(ProductType.UNIT, persisted.productType);
            assertEquals("kg", persisted.unitName);
            assertTrue(persisted.active);
        }
    }

    /**
     * {@code processLineLogic} updates an existing product when the stored
     * checksum differs from the incoming one ({@code product == null} false arm,
     * checksum-mismatch true arm): it re-reads the entity fresh by id, re-feeds
     * it and increments the updated counter.
     */
    @Test
    void processLineLogicUpdatesExistingProductWhenChecksumDiffers() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = line(1, fullParts());
        Product existing = new Product();
        existing.id = 42L;
        existing.ean = "111";
        existing.checksum = incomingChecksum(resource, data, existing) + 1;
        Map<String, Object> context = new HashMap<>();
        context.put("111", existing);
        Product fresh = new Product();
        fresh.ean = "111";
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(42L)).thenReturn(fresh);
            resource.processLineLogic(data, context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Milk", fresh.name);
            assertEquals("Fresh milk", fresh.description);
            assertEquals("BrandX", fresh.brand);
            assertEquals(new BigDecimal("1.500"), fresh.referenceWeight);
            assertEquals(new BigDecimal("2.000"), fresh.referenceVolume);
            assertEquals(ProductType.UNIT, fresh.productType);
            assertEquals("kg", fresh.unitName);
            assertTrue(fresh.active);
        }
    }

    /**
     * {@code processLineLogic} leaves the counters untouched and re-reads
     * nothing when the existing product already matches the incoming checksum
     * (checksum-mismatch false arm).
     */
    @Test
    void processLineLogicSkipsUpdateWhenChecksumMatches() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = line(1, fullParts());
        Product existing = new Product();
        existing.id = 42L;
        existing.ean = "111";
        existing.checksum = incomingChecksum(resource, data, existing);
        Map<String, Object> context = new HashMap<>();
        context.put("111", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(data, context, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * {@code feedProduct} merges the generic ATTRIBUTES column (BO-02-03-18)
     * into the open attribute map: it poses each {@code code=value} pair — the
     * well-known behavioural codes (BO-02-03-06/09/11/21/22/25) and an
     * unknown Gestion-Commerciale code alike — and skips a pair with an empty
     * code ({@code =orphan}) and a token with no {@code =} ({@code NOEQ}).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void feedProductMergesGenericAttributesColumn() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        String[] cells = {"111", "Milk", "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true",
                "MEAL_VOUCHER_ELIGIBLE=true;DISCOUNT_FORBIDDEN=true;RECALL=true;PRICE_TO_ENTER=true;"
                        + "QUANTITY_TO_ENTER=true;BULKY=true;FOO=bar;=orphan;NOEQ"};
        feedProduct(resource, attrLine(1, cells), product);
        assertEquals("true", product.attributes.get("MEAL_VOUCHER_ELIGIBLE"));
        assertEquals("true", product.attributes.get("DISCOUNT_FORBIDDEN"));
        assertEquals("true", product.attributes.get("RECALL"));
        assertEquals("true", product.attributes.get("PRICE_TO_ENTER"));
        assertEquals("true", product.attributes.get("QUANTITY_TO_ENTER"));
        assertEquals("true", product.attributes.get("BULKY"));
        assertEquals("bar", product.attributes.get("FOO"));
        assertFalse(product.attributes.containsKey(""));
        assertFalse(product.attributes.containsKey("NOEQ"));
        assertEquals(7, product.attributes.size());
    }

    /**
     * {@code feedProduct} on a DECLARED but EMPTY ATTRIBUTES cell poses nothing
     * and clears nothing (the {@code isEmpty} true leg): a fiche-set attribute
     * survives an import that carries an empty attributes cell.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void feedProductBlankAttributesCellChangesNothing() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        product.attributes.put("MEAL_VOUCHER_ELIGIBLE", "true");
        String[] cells = {"111", "Milk", "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true", ""};
        feedProduct(resource, attrLine(1, cells), product);
        assertEquals(1, product.attributes.size());
        assertEquals("true", product.attributes.get("MEAL_VOUCHER_ELIGIBLE"));
    }

    /**
     * {@code feedProduct} on a header that DECLARES ATTRIBUTES but a row that
     * carries no such cell (a short row) reads the value as null and poses
     * nothing (the {@code raw == null} leg of the guard): the map is left alone.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void feedProductNullAttributesCellChangesNothing() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        product.attributes.put("BULKY", "true");
        String[] cells = {"111", "Milk", "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true"};
        feedProduct(resource, attrLine(1, cells), product);
        assertEquals(1, product.attributes.size());
        assertEquals("true", product.attributes.get("BULKY"));
    }

    /**
     * {@code computeIncomingChecksum} folds the incoming attributes
     * (BO-02-03-18): an absent ATTRIBUTES column reads the existing map (so the
     * checksum is unchanged), a declared column that changes an attribute flips
     * it — the two arms of the touch rule mirrored for the map.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void computeIncomingChecksumFoldsAttributesColumn() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product existing = new Product();
        existing.ean = "111";
        existing.attributes.put("MEAL_VOUCHER_ELIGIBLE", "true");
        int absent = incomingChecksum(resource, line(1, fullParts()), existing);
        int declaredSame = incomingChecksum(resource, attrLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true",
                "MEAL_VOUCHER_ELIGIBLE=true"}), existing);
        int declaredChanged = incomingChecksum(resource, attrLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true",
                "DISCOUNT_FORBIDDEN=true"}), existing);
        assertEquals(absent, declaredSame);
        assertNotEquals(absent, declaredChanged);
    }

    /**
     * {@code incomingAttributes} tolerates a null attribute map (the defensive
     * {@code existing.attributes == null} true arm): it substitutes an empty
     * map, so the incoming checksum equals that of a product carrying an empty
     * map.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void computeIncomingChecksumToleratesNullAttributeMap() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product nullMap = new Product();
        nullMap.ean = "111";
        nullMap.attributes = null;
        Product emptyMap = new Product();
        emptyMap.ean = "111";
        assertEquals(incomingChecksum(resource, line(1, fullParts()), emptyMap),
                incomingChecksum(resource, line(1, fullParts()), nullMap));
    }

    /**
     * {@code findEntityForLine} performs the EAN lookup used by the 1-by-1
     * fallback and returns the first matching product.
     */
    @Test
    void findEntityForLineLooksUpProductByEan() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = line(1, fullParts());
        Product product = new Product();
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find(FIND_QUERY, "111")).thenReturn(query);
            when(query.firstResult()).thenReturn(product);
            assertSame(product, resource.findEntityForLine(data));
        }
    }

    /**
     * {@code safeParseProductType} resolves a known enum constant
     * case-insensitively (both guards false, {@code valueOf} succeeds).
     */
    @Test
    void safeParseProductTypeResolvesKnownConstant() {
        ProductCsvResource resource = new ProductCsvResource();
        assertEquals(ProductType.WEIGHT, resource.safeParseProductType(line(1, new String[]{"111", null, null, null, null, null, " weight "}), "PRODUCT_TYPE"));
    }

    /**
     * {@code safeParseProductType} returns null for an unknown value, exercising
     * the {@link IllegalArgumentException} catch arm (both guards false,
     * {@code valueOf} throws).
     */
    @Test
    void safeParseProductTypeReturnsNullForUnknownValue() {
        ProductCsvResource resource = new ProductCsvResource();
        assertNull(resource.safeParseProductType(line(1, new String[]{"111", null, null, null, null, null, "GAS"}), "PRODUCT_TYPE"));
    }

    /**
     * {@code safeParseProductType} returns null when the line has no cell for the column
     * (the {@code index >= parts.length} true arm).
     */
    @Test
    void safeParseProductTypeReturnsNullForOutOfBoundsIndex() {
        ProductCsvResource resource = new ProductCsvResource();
        assertNull(resource.safeParseProductType(line(1, new String[]{"111"}), "PRODUCT_TYPE"));
    }

    /**
     * {@code safeParseProductType} returns null when the cell trims to empty
     * (index in bounds, {@code val.isEmpty} true arm).
     */
    @Test
    void safeParseProductTypeReturnsNullForBlankValue() {
        ProductCsvResource resource = new ProductCsvResource();
        assertNull(resource.safeParseProductType(line(1, new String[]{"111", null, null, null, null, null, "   "}), "PRODUCT_TYPE"));
    }

    /**
     * {@code processLineLogic} defaults {@code active} to false and leaves
     * optional columns null when they are blank, confirming the create path
     * routes blanks through the parent safe-parse helpers.
     */
    @Test
    void processLineLogicCreatesProductWithBlankOptionalColumns() {
        ProductCsvResource resource = new ProductCsvResource();
        String[] parts = {"222", "Bread", "", "", "", "", "", "", ""};
        ImporterCsvResource.LineData data = line(1, parts);
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            Product persisted = captor.getValue();
            assertEquals("222", persisted.ean);
            assertEquals("Bread", persisted.name);
            assertEquals("", persisted.description);
            assertEquals("", persisted.brand);
            assertNull(persisted.referenceWeight);
            assertNull(persisted.referenceVolume);
            assertNull(persisted.productType);
            assertEquals("", persisted.unitName);
            assertFalse(persisted.active);
        }
    }
    /** Header names of the imported feed, in the cell order of the fixtures. */
    private static final String[] TEST_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND", "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE"};

    /**
     * Builds a header-bound row from positional fixture cells: the header
     * maps TEST_HEADER onto the cell positions and the first name is the
     * key column.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData line(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) header.put(TEST_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, TEST_HEADER[0]);
    }

    /** The 9 required columns plus the OPTIONAL generic ATTRIBUTES column at index 9. */
    private static final String[] ATTR_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND",
            "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE", "ATTRIBUTES"};

    /**
     * Builds a line under the ATTRIBUTES-bearing header. A 9-cell row still
     * DECLARES the column (so {@code has} is true) but carries no cell for it
     * ({@code get} returns null), which is exactly the short-row case.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells
     * @return the parsed line bound to the ATTRIBUTES header
     */
    private static ImporterCsvResource.LineData attrLine(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ATTR_HEADER.length; i++) header.put(ATTR_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, ATTR_HEADER[0]);
    }

    /**
     * Invokes the private {@code feedProduct} helper by reflection.
     *
     * @param resource the resource under test
     * @param data the parsed line
     * @param product the product to populate
     * @throws Exception on reflection failure
     */
    private static void feedProduct(ProductCsvResource resource, ImporterCsvResource.LineData data,
                                    Product product) throws Exception {
        Method method = ProductCsvResource.class.getDeclaredMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        method.setAccessible(true);
        method.invoke(resource, data, product);
    }

    /**
     * Header including the optional CHECKOUT_LABEL and INTERNAL_CODE columns
     * appended after the canonical nine.
     */
    private static final String[] EXTRAS_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND",
            "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE",
            "CHECKOUT_LABEL", "INTERNAL_CODE"};

    /**
     * Header including the optional register-only PLU and VARIABLE_WEIGHT
     * columns appended after the canonical nine.
     */
    private static final String[] WEIGH_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND",
            "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE",
            "PLU", "VARIABLE_WEIGHT"};

    /**
     * Builds a header-bound row declaring the optional PLU and
     * VARIABLE_WEIGHT columns.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData weighLine(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < WEIGH_HEADER.length; i++) header.put(WEIGH_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, WEIGH_HEADER[0]);
    }

    /**
     * Builds a header-bound row declaring the optional CHECKOUT_LABEL and
     * INTERNAL_CODE columns.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData lineWithExtras(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < EXTRAS_HEADER.length; i++) header.put(EXTRAS_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, EXTRAS_HEADER[0]);
    }

    /**
     * BO-02-03-02/04: {@code feedProduct} applies the optional CHECKOUT_LABEL and
     * INTERNAL_CODE columns when the header declares them (the {@code has} true
     * arms).
     */
    @Test
    void processLineLogicAppliesCheckoutAndInternalCodeColumns() {
        ProductCsvResource resource = new ProductCsvResource();
        String[] cells = {"333", "Melon", "", "", "", "", "", "", "true", "MELON JAUNE", "INT-42"};
        ImporterCsvResource.LineData data = lineWithExtras(1, cells);
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            Product persisted = captor.getValue();
            assertEquals("MELON JAUNE", persisted.checkoutLabel);
            assertEquals("INT-42", persisted.internalCode);
        }
    }

    /**
     * BO-02-03-04: the internal code is unique, so an empty cell leaves the
     * article WITHOUT a code (null, never an empty string two articles would
     * share), and a padded code is trimmed; the checksum reads an empty, a
     * blank and a missing cell the same way.
     *
     * @throws Exception on reflective access to the checksum
     */
    @Test
    void anEmptyInternalCodeCellStoresNoCode() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(lineWithExtras(1, new String[] {"333", "Melon", "", "", "", "", "", "", "true",
                    "", " "}), new HashMap<>(), new int[] {0, 0});
            resource.processLineLogic(lineWithExtras(2, new String[] {"334", "Melon", "", "", "", "", "", "", "true",
                    "", " INT-7 "}), new HashMap<>(), new int[] {0, 0});
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em, org.mockito.Mockito.times(2)).persist(captor.capture());
            assertNull(captor.getAllValues().get(0).internalCode);
            assertEquals("INT-7", captor.getAllValues().get(1).internalCode);
        }
        Product existing = new Product();
        existing.ean = "333";
        existing.name = "Melon";
        existing.active = true;
        int blank = incomingChecksum(resource, lineWithExtras(1, new String[] {"333", "Melon", "", "", "", "", "", "",
                "true", "", " "}), existing);
        int empty = incomingChecksum(resource, lineWithExtras(1, new String[] {"333", "Melon", "", "", "", "", "", "",
                "true", "", ""}), existing);
        assertEquals(blank, empty);
        int missing = incomingChecksum(resource, lineWithExtras(1, new String[] {"333", "Melon", "", "", "", "", "", "",
                "true", ""}), existing);
        assertEquals(blank, missing);
    }

    /**
     * BO-02-03-02/04: {@code computeIncomingChecksum} reads CHECKOUT_LABEL and
     * INTERNAL_CODE from the CSV when the header declares them (the {@code has}
     * true arms), so a change in either shifts the checksum.
     */
    @Test
    void incomingChecksumReadsCheckoutAndInternalCodeFromCsv() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product existing = new Product();
        String[] plain = {"333", "Melon", "", "", "", "", "", "", "true", "", ""};
        String[] withLabel = {"333", "Melon", "", "", "", "", "", "", "true", "MELON JAUNE", ""};
        String[] withCode = {"333", "Melon", "", "", "", "", "", "", "true", "", "INT-42"};
        int base = incomingChecksum(resource, lineWithExtras(1, plain), existing);
        int labelled = incomingChecksum(resource, lineWithExtras(1, withLabel), existing);
        int coded = incomingChecksum(resource, lineWithExtras(1, withCode), existing);
        assertNotEquals(base, labelled);
        assertNotEquals(base, coded);
    }

    /**
     * Header including the optional register-only ICON and FORBIDDEN_TO_SALE
     * columns appended after the canonical nine.
     */
    private static final String[] ICON_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND",
            "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE",
            "ICON", "FORBIDDEN_TO_SALE"};

    /**
     * Builds a header-bound row declaring the optional ICON and
     * FORBIDDEN_TO_SALE columns.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData iconLine(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ICON_HEADER.length; i++) header.put(ICON_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, ICON_HEADER[0]);
    }

    /**
     * {@code feedProduct} lands a null PLU cell as NULL through the
     * {@code plu == null} leg of the blank-PLU guard (L221): the PLU column
     * is declared ({@code has} true arm) but the row carries a null cell, so
     * {@code safeGet} returns null and the first leg of the OR short-circuits
     * to the null assignment without reaching {@code isEmpty}.
     */
    @Test
    void processLineLogicNullifiesNullPluCell() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = weighLine(1, new String[]{"111", "Milk", "Fresh milk",
                "BrandX", "1.500", "2.000", "unit", "kg", "true", null, "true"});
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            assertNull(captor.getValue().plu);
        }
    }

    /**
     * {@code feedProduct} applies the optional ICON and FORBIDDEN_TO_SALE
     * columns when the header declares them (the {@code has} true arms at L223
     * and L226): the icon lands verbatim and the forbidden-to-sale flag is
     * parsed from the cell.
     */
    @Test
    void processLineLogicAppliesIconAndForbiddenToSaleColumns() {
        ProductCsvResource resource = new ProductCsvResource();
        String[] cells = {"444", "Cig", "", "", "", "", "", "", "true", "cig.png", "true"};
        ImporterCsvResource.LineData data = iconLine(1, cells);
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            Product persisted = captor.getValue();
            assertEquals("cig.png", persisted.icon);
            assertTrue(persisted.forbiddenToSale);
        }
    }

    /**
     * {@code computeIncomingChecksum} reads FORBIDDEN_TO_SALE from the CSV when
     * the header declares it (the {@code has} true arm at L255), so a row that
     * forbids sale shifts the checksum away from an existing product that
     * permits it.
     */
    @Test
    void incomingChecksumReadsForbiddenToSaleFromCsv() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product existing = new Product();
        existing.forbiddenToSale = false;
        String[] permitted = {"444", "Cig", "", "", "", "", "", "", "true", "cig.png", "false"};
        String[] forbidden = {"444", "Cig", "", "", "", "", "", "", "true", "cig.png", "true"};
        int permittedChecksum = incomingChecksum(resource, iconLine(1, permitted), existing);
        int forbiddenChecksum = incomingChecksum(resource, iconLine(1, forbidden), existing);
        assertNotEquals(permittedChecksum, forbiddenChecksum);
    }

    /**
     * Header including the optional EGALIM_REGIME column appended after the
     * canonical nine.
     */
    private static final String[] EGALIM_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND",
            "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE",
            "EGALIM_REGIME"};

    /**
     * Builds a line under the EGALIM_REGIME-bearing header. A 9-cell row still
     * DECLARES the column ({@code has} is true) but carries no cell for it
     * ({@code get} returns null), which is the short-row case.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells
     * @return the parsed line bound to the EGALIM_REGIME header
     */
    private static ImporterCsvResource.LineData egalimLine(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < EGALIM_HEADER.length; i++) header.put(EGALIM_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, EGALIM_HEADER[0]);
    }

    /**
     * {@code feedProduct} classifies the article from a declared
     * EGALIM_REGIME cell, UPPER-CASED: the code is matched against the EGAlim
     * referential and against what the valuation engine holds, and a catalog
     * answering {@code food_34} where the referential says {@code FOOD_34}
     * classifies the article under a category nothing carries.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void feedProductReadsAndNormalisesTheEgalimRegime() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        String[] cells = {"111", "Milk", "", "", "", "", "", "", "true", "  food_34 "};
        feedProduct(resource, egalimLine(1, cells), product);
        assertEquals("FOOD_34", product.egalimRegime);
    }

    /**
     * {@code feedProduct} on a DECLARED but EMPTY cell UNCLASSIFIES the
     * article, and lands it as null rather than as "": that is what a Gestion
     * Commerciale emptying the field means, and "unclassified" must have ONE
     * representation.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void feedProductBlankEgalimCellUnclassifiesTheArticle() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        product.egalimRegime = "FOOD_34";
        String[] cells = {"111", "Milk", "", "", "", "", "", "", "true", "   "};
        feedProduct(resource, egalimLine(1, cells), product);
        assertNull(product.egalimRegime);
    }

    /**
     * {@code feedProduct} on a header that DECLARES the column but a row that
     * carries no such cell reads null and unclassifies too — the
     * {@code raw == null} leg of the guard, which a short row produces.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void feedProductNullEgalimCellUnclassifiesTheArticle() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        product.egalimRegime = "DPH_40";
        String[] cells = {"111", "Milk", "", "", "", "", "", "", "true"};
        feedProduct(resource, egalimLine(1, cells), product);
        assertNull(product.egalimRegime);
    }

    /**
     * {@code feedProduct} on a header WITHOUT the column leaves the
     * classification untouched — the {@code has} false arm, the touch rule
     * every optional column follows: a feed that does not speak of EGAlim does
     * not reclassify.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void feedProductLeavesTheEgalimRegimeAloneWhenTheColumnIsAbsent() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product product = new Product();
        product.egalimRegime = "FOOD_34";
        feedProduct(resource, line(1, fullParts()), product);
        assertEquals("FOOD_34", product.egalimRegime);
    }

    /**
     * {@code computeIncomingChecksum} mirrors the same two arms: an absent
     * column reads the existing classification (so a re-import of an unchanged
     * file stays a no-op), a declared column that reclassifies flips the
     * checksum (so the line is applied rather than skipped).
     *
     * @throws Exception on reflection failure
     */
    @Test
    void computeIncomingChecksumMirrorsTheEgalimTouchRule() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        Product existing = new Product();
        existing.ean = "111";
        existing.egalimRegime = "FOOD_34";
        int absent = incomingChecksum(resource, line(1, fullParts()), existing);
        int declaredSame = incomingChecksum(resource, egalimLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true", "FOOD_34"}), existing);
        int declaredOther = incomingChecksum(resource, egalimLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true", "DPH_40"}), existing);
        int declaredNone = incomingChecksum(resource, egalimLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true", ""}), existing);
        assertEquals(absent, declaredSame);
        assertNotEquals(absent, declaredOther);
        assertNotEquals(absent, declaredNone);
    }

    /**
     * Header including the optional NOMENCLATURE column appended after the
     * canonical nine.
     */
    private static final String[] NOMENCLATURE_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND",
            "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE",
            "NOMENCLATURE"};

    /**
     * Builds a header-bound row declaring the optional NOMENCLATURE column.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData nomenclatureLine(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < NOMENCLATURE_HEADER.length; i++) header.put(NOMENCLATURE_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, NOMENCLATURE_HEADER[0]);
    }

    /**
     * {@code processLineLogic} files the article under the nomenclature node
     * when the feed declares the NOMENCLATURE column (the {@code has} true arm
     * at L219): the filing is applied OUTSIDE the checksum shortcut, so an
     * article moved from one sous-famille to another is re-filed even when
     * nothing else on the row changed.
     */
    @Test
    void processLineLogicFilesArticleUnderNomenclatureWhenColumnDeclared() {
        ProductCsvResource resource = new ProductCsvResource();
        String[] cells = {"555", "Yogurt", "", "", "", "", "", "", "true", "SOUS_FAMILLE_42"};
        ImporterCsvResource.LineData data = nomenclatureLine(1, cells);
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class);
                MockedStatic<com.intermarche.edge.domain.catalog.ProductFamily> families =
                        mockStatic(com.intermarche.edge.domain.catalog.ProductFamily.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            Product persisted = captor.getValue();
            families.verify(() -> com.intermarche.edge.domain.catalog.ProductFamily
                    .fileUnderNomenclature(persisted, "SOUS_FAMILLE_42"));
        }
    }

    /**
     * {@code processLineLogic} leaves the article's filing untouched when the
     * feed does not carry the NOMENCLATURE column (the {@code has} false arm at
     * L219): a feed built before the nomenclature extension keeps its filing.
     */
    @Test
    void processLineLogicLeavesFilingAloneWhenNomenclatureColumnAbsent() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = line(1, fullParts());
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class);
                MockedStatic<com.intermarche.edge.domain.catalog.ProductFamily> families =
                        mockStatic(com.intermarche.edge.domain.catalog.ProductFamily.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            verify(em).persist(org.mockito.ArgumentMatchers.any(Product.class));
            families.verifyNoInteractions();
        }
    }


    /**
     * BO-02-03-10: the commercial management's feed carries the MESSAGE
     * attached to an article, and a feed that declares no such column leaves it
     * null rather than empty — the register raises a warning on a message that
     * exists, and an empty string is a message that does not.
     */
    @Test
    void processLineLogicReadsTheArticleMessage() {
        ProductCsvResource resource = new ProductCsvResource();
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        String[] columns = {"EAN", "NAME", "DESCRIPTION", "BRAND", "REFERENCE_WEIGHT",
                "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE", "INFO_MESSAGE"};
        for (int i = 0; i < columns.length; i++) {
            header.put(columns[i], i);
        }
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, header,
                new String[]{"111", "Milk", "Fresh milk", "BrandX", "1.500", "2.000",
                        "unit", "kg", "true", "VERIFIER LA DATE"}, "EAN");
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            assertEquals("VERIFIER LA DATE", captor.getValue().infoMessage);
        }

        ProductCsvResource silent = new ProductCsvResource();
        ImporterCsvResource.LineData without = weighLine(1, new String[]{"111", "Milk",
                "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true", "", "true"});
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            silent.processLineLogic(without, new HashMap<>(), new int[]{0, 0});
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            assertNull(captor.getValue().infoMessage);
        }
    }

    /** The resource under test. */
    private final ProductCsvResource resource = new ProductCsvResource();

    /**
     * Builds a parsed line over the given ordered column → value cells; the
     * header declares exactly the named columns, which is what
     * {@code LineData.has} keys on.
     *
     * @param cells the column-name → cell-value pairs, in order
     * @return the parsed line, keyed on EAN
     */
    private ImporterCsvResource.LineData line(Map<String, String> cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        String[] parts = new String[cells.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : cells.entrySet()) {
            header.put(entry.getKey(), index);
            parts[index] = entry.getValue();
            index++;
        }
        return new ImporterCsvResource.LineData(1, header, parts, ProductCsvResource.COL_EAN);
    }

    /**
     * Builds a line declaring every column this importer knows, all filled.
     *
     * @return a fully-populated parsed line
     */
    private ImporterCsvResource.LineData fullLine() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_DESCRIPTION, "Whole milk");
        cells.put(ProductCsvResource.COL_BRAND, "DairyCo");
        cells.put(ProductCsvResource.COL_REFERENCE_WEIGHT, "1.000");
        cells.put(ProductCsvResource.COL_REFERENCE_VOLUME, "1.000");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "unit");
        cells.put(ProductCsvResource.COL_UNIT_NAME, "L");
        cells.put(ProductCsvResource.COL_ACTIVE, "true");
        cells.put(ProductCsvResource.COL_PLU, "1234");
        cells.put(ProductCsvResource.COL_ICON, "milk");
        cells.put(ProductCsvResource.COL_FORBIDDEN_TO_SALE, "true");
        cells.put(ProductCsvResource.COL_CHECKOUT_LABEL, "MILK 1L");
        cells.put(ProductCsvResource.COL_INTERNAL_CODE, "INT-1");
        cells.put(ProductCsvResource.COL_VARIABLE_WEIGHT, "true");
        cells.put(ProductCsvResource.COL_ATTRIBUTES, "MEAL_VOUCHER_ELIGIBLE=true;DISCOUNT_FORBIDDEN=false");
        return line(cells);
    }

    /**
     * Builds a line declaring only the required columns, so every optional
     * column reports absent and the checksum falls back to the existing row.
     *
     * @return a required-columns-only parsed line
     */
    private ImporterCsvResource.LineData requiredOnlyLine() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000002");
        cells.put(ProductCsvResource.COL_NAME, "Bread");
        cells.put(ProductCsvResource.COL_DESCRIPTION, "Baguette");
        cells.put(ProductCsvResource.COL_BRAND, "Boulangerie");
        cells.put(ProductCsvResource.COL_REFERENCE_WEIGHT, "0.250");
        cells.put(ProductCsvResource.COL_REFERENCE_VOLUME, "0.000");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "unit");
        cells.put(ProductCsvResource.COL_UNIT_NAME, "pcs");
        cells.put(ProductCsvResource.COL_ACTIVE, "true");
        return line(cells);
    }

    /**
     * Invokes a private method of the resource by reflection.
     *
     * @param name the method name
     * @param types the parameter types
     * @return the accessible method
     * @throws Exception when the method does not exist
     */
    private Method privateMethod(String name, Class<?>... types) throws Exception {
        Method method = ProductCsvResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * {@code importProducts} delegates to the base stream importer with the
     * EAN key and the required columns: a header missing them is rejected
     * with a BAD_REQUEST before any collaborator is touched.
     */
    @Test
    void importProductsDelegatesToTheBaseStreamImporter() {
        ByteArrayInputStream stream = new ByteArrayInputStream(
                "EAN".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importProducts(stream);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    /**
     * The importer names the PRODUCTS engine feed, so the raw file is retained
     * verbatim for the valuation engines.
     */
    @Test
    void importerNamesTheProductsEngineFeed() {
        assertEquals("PRODUCTS", resource.feedCode());
    }

    /**
     * {@code processChunkWithFallback} answers an empty map on an empty chunk
     * without querying (true arm of the emptiness guard).
     */
    @Test
    void processChunkWithFallbackAnswersEmptyOnEmptyChunk() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(), Set.of(), new int[]{0, 0}, new java.util.ArrayList<>());
            assertTrue(context.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * A non-empty chunk that names no key skips the bulk fetch and answers an
     * empty context (false arm of the chunk guard, false arm of the key
     * guard).
     */
    @Test
    void processChunkWithFallbackSkipsFetchWhenNoKey() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(fullLine()), Set.of(), new int[]{0, 0}, new java.util.ArrayList<>());
            assertTrue(context.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * A non-empty chunk with keys bulk-fetches the products and indexes them
     * by their EAN (false arm of the chunk guard, true arm of the key guard,
     * loop body).
     */
    @Test
    void processChunkWithFallbackIndexesTheFetchedProducts() {
        Product existing = new Product();
        existing.ean = "3000000000001";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(eq("ean IN ?1"),
                    org.mockito.ArgumentMatchers.<Object>any()))
                    .thenReturn(List.of(existing));
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(fullLine()), Set.of("3000000000001"),
                    new int[]{0, 0}, new java.util.ArrayList<>());
            assertEquals(1, context.size());
            assertSame(existing, context.get("3000000000001"));
        }
    }

    /**
     * A code the referential does not carry yet creates a product, counted as
     * a creation and persisted (true arm of the novelty guard), with no
     * nomenclature filing when the column is absent (false arm of that guard).
     */
    @Test
    void processLineLogicCreatesAnUnknownProduct() {
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(fullLine(), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(em).persist(captor.capture());
            Product created = (Product) captor.getValue();
            assertEquals("3000000000001", created.ean);
            assertEquals("Milk", created.name);
            assertEquals("1234", created.plu);
        }
    }

    /**
     * A known code whose checksum moved is reloaded and refed, counted as an
     * update (false arm of the novelty guard, true arm of the change guard).
     */
    @Test
    void processLineLogicUpdatesAChangedProduct() {
        Product existing = new Product();
        existing.id = 5L;
        existing.ean = "3000000000001";
        existing.name = "Old milk";
        existing.checksum = 0;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(5L)).thenReturn(existing);
            resource.processLineLogic(fullLine(), entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Milk", existing.name);
        }
    }

    /**
     * A known code whose stored checksum is null is treated as changed and
     * updated (true arm of {@code changedFrom}'s null branch at the call
     * site).
     */
    @Test
    void processLineLogicUpdatesAProductWithNoStoredChecksum() {
        Product existing = new Product();
        existing.id = 6L;
        existing.ean = "3000000000001";
        existing.checksum = null;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(6L)).thenReturn(existing);
            resource.processLineLogic(fullLine(), entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
        }
    }

    /**
     * A known code whose checksum matches the incoming one is left untouched
     * and counted neither way (false arm of the change guard); no reload is
     * issued.
     */
    @Test
    void processLineLogicSkipsAnUnchangedProduct() throws Exception {
        Product existing = new Product();
        existing.id = 7L;
        existing.ean = "3000000000001";
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        int incoming = (int) compute.invoke(resource, fullLine(), existing);
        existing.checksum = incoming;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            resource.processLineLogic(fullLine(), entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            panache.verify(() -> Product.findById(any()), never());
        }
    }

    /**
     * When the feed declares the NOMENCLATURE column, the article is filed
     * under the named node even on the skip path — the filing is outside the
     * checksum shortcut (true arm of the nomenclature guard).
     */
    @Test
    void processLineLogicFilesUnderNomenclatureWhenDeclared() throws Exception {
        Product existing = new Product();
        existing.id = 8L;
        existing.ean = "3000000000001";
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_NOMENCLATURE, "SF01");
        ImporterCsvResource.LineData data = line(cells);
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        existing.checksum = (int) compute.invoke(resource, data, existing);
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.fileUnderNomenclature(existing, "SF01"))
                    .thenReturn(true);
            resource.processLineLogic(data, entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            families.verify(() -> ProductFamily.fileUnderNomenclature(existing, "SF01"));
        }
    }

    /**
     * {@code findEntityForLine} resolves the line's product through the EAN
     * finder.
     */
    @Test
    void findEntityForLineResolvesThroughTheEanFinder() {
        Product existing = new Product();
        existing.ean = "3000000000001";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> Product.find("ean", "3000000000001")).thenReturn(query);
            assertSame(existing, resource.findEntityForLine(fullLine()));
        }
    }

    /**
     * {@code feedProduct} copies every declared column onto the product,
     * including all optional register-only ones (true arm of every
     * {@code has} guard) and the non-empty PLU (false arm of the PLU
     * ternary).
     */
    @Test
    void feedProductPopulatesEveryDeclaredColumn() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Product product = new Product();
        method.invoke(resource, fullLine(), product);
        assertEquals("Milk", product.name);
        assertEquals("Whole milk", product.description);
        assertEquals("DairyCo", product.brand);
        assertEquals(new BigDecimal("1.000"), product.referenceWeight);
        assertEquals(new BigDecimal("1.000"), product.referenceVolume);
        assertEquals(ProductType.UNIT, product.productType);
        assertEquals("L", product.unitName);
        assertTrue(product.active);
        assertEquals("1234", product.plu);
        assertEquals("milk", product.icon);
        assertTrue(product.forbiddenToSale);
        assertEquals("MILK 1L", product.checkoutLabel);
        assertEquals("INT-1", product.internalCode);
        assertTrue(product.variableWeight);
        assertEquals("true", product.attributes.get("MEAL_VOUCHER_ELIGIBLE"));
        assertEquals("false", product.attributes.get("DISCOUNT_FORBIDDEN"));
    }

    /**
     * {@code feedProduct} leaves the optional register-only fields untouched
     * when the feed declares none of their columns (false arm of every
     * {@code has} guard).
     */
    @Test
    void feedProductLeavesOptionalFieldsUntouchedWhenAbsent() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Product product = new Product();
        product.plu = "9999";
        product.icon = "kept";
        product.forbiddenToSale = true;
        product.checkoutLabel = "KEPT";
        product.internalCode = "KEPT-IC";
        product.variableWeight = true;
        product.attributes.put("KEEP", "yes");
        method.invoke(resource, requiredOnlyLine(), product);
        assertEquals("Bread", product.name);
        assertEquals("9999", product.plu);
        assertEquals("kept", product.icon);
        assertTrue(product.forbiddenToSale);
        assertEquals("KEPT", product.checkoutLabel);
        assertEquals("KEPT-IC", product.internalCode);
        assertTrue(product.variableWeight);
        assertEquals("yes", product.attributes.get("KEEP"));
    }

    /**
     * A declared but empty PLU cell lands as null, never the empty string
     * (right operand of the PLU disjunction: null false, isEmpty true).
     */
    @Test
    void feedProductNullsAnEmptyPlu() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_PLU, "");
        Product product = new Product();
        product.plu = "before";
        method.invoke(resource, line(cells), product);
        assertNull(product.plu);
    }

    /**
     * A declared PLU column whose cell is null lands as null (left operand of
     * the PLU disjunction: plu is null).
     */
    @Test
    void feedProductNullsAMissingPluCell() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_PLU, null);
        Product product = new Product();
        product.plu = "before";
        method.invoke(resource, line(cells), product);
        assertNull(product.plu);
    }

    /**
     * {@code mergeAttributes} merges nothing from a null cell (left operand of
     * the emptiness disjunction).
     */
    @Test
    void mergeAttributesIgnoresANullCell() throws Exception {
        Method method = privateMethod("mergeAttributes", Map.class, String.class);
        Map<String, String> target = new HashMap<>();
        target.put("KEEP", "yes");
        method.invoke(resource, target, (String) null);
        assertEquals(1, target.size());
        assertEquals("yes", target.get("KEEP"));
    }

    /**
     * {@code mergeAttributes} merges nothing from an empty cell (right operand
     * of the emptiness disjunction).
     */
    @Test
    void mergeAttributesIgnoresAnEmptyCell() throws Exception {
        Method method = privateMethod("mergeAttributes", Map.class, String.class);
        Map<String, String> target = new HashMap<>();
        target.put("KEEP", "yes");
        method.invoke(resource, target, "");
        assertEquals(1, target.size());
    }

    /**
     * {@code mergeAttributes} poses the well-formed pairs, skips a token with
     * no {@code =} (true arm of the eq guard) and a token with a blank code
     * (true arm of the code guard), covering the false arms via the valid
     * pairs.
     */
    @Test
    void mergeAttributesPosesValidPairsAndSkipsMalformedTokens() throws Exception {
        Method method = privateMethod("mergeAttributes", Map.class, String.class);
        Map<String, String> target = new HashMap<>();
        method.invoke(resource, target, "A=1;novalue;=orphan; B =2");
        assertEquals(2, target.size());
        assertEquals("1", target.get("A"));
        assertEquals("2", target.get("B"));
        assertFalse(target.containsKey("novalue"));
    }

    /**
     * {@code incomingAttributes} starts from an empty map when the product
     * carries no attributes and adds nothing when the column is absent (true
     * arm of the null-attributes ternary, false arm of the column guard).
     */
    @Test
    void incomingAttributesHandlesNullAttributesAndAbsentColumn() throws Exception {
        Method method = privateMethod("incomingAttributes",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        existing.attributes = null;
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(
                resource, requiredOnlyLine(), existing);
        assertTrue(result.isEmpty());
    }

    /**
     * {@code incomingAttributes} copies the product's current attributes and
     * merges the declared cell on top (false arm of the null-attributes
     * ternary, true arm of the column guard).
     */
    @Test
    void incomingAttributesCopiesCurrentAndMergesDeclaredCell() throws Exception {
        Method method = privateMethod("incomingAttributes",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        existing.attributes = new HashMap<>();
        existing.attributes.put("OLD", "1");
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_ATTRIBUTES, "NEW=2");
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(
                resource, line(cells), existing);
        assertEquals(2, result.size());
        assertEquals("1", result.get("OLD"));
        assertEquals("2", result.get("NEW"));
    }

    /**
     * With every optional column declared, {@code computeIncomingChecksum}
     * reproduces the checksum the product would carry after {@code feedProduct}
     * (true arm of every column ternary, false arm of the plu-null ternary).
     */
    @Test
    void computeIncomingChecksumMirrorsFeedProductWhenAllColumnsDeclared() throws Exception {
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        Method feed = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        int incoming = (int) compute.invoke(resource, fullLine(), existing);
        Product expected = new Product();
        feed.invoke(resource, fullLine(), expected);
        expected.ean = "3000000000001";
        assertEquals(expected.getChecksum(), incoming);
    }

    /**
     * With no optional column declared, {@code computeIncomingChecksum} falls
     * back to the existing row's optional fields (false arm of every column
     * ternary) and hashes the empty stored PLU as "" (true arm of the plu-null
     * ternary).
     */
    @Test
    void computeIncomingChecksumFallsBackToExistingWhenColumnsAbsent() throws Exception {
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        existing.plu = null;
        existing.forbiddenToSale = true;
        existing.checkoutLabel = "CL";
        existing.internalCode = "IC";
        existing.variableWeight = true;
        existing.attributes = new HashMap<>();
        existing.attributes.put("K", "V");
        int incoming = (int) compute.invoke(resource, requiredOnlyLine(), existing);
        Product expected = new Product();
        expected.ean = "3000000000002";
        expected.plu = null;
        expected.name = "Bread";
        expected.description = "Baguette";
        expected.brand = "Boulangerie";
        expected.referenceWeight = new BigDecimal("0.250");
        expected.referenceVolume = new BigDecimal("0.000");
        expected.productType = ProductType.UNIT;
        expected.unitName = "pcs";
        expected.active = true;
        expected.forbiddenToSale = true;
        expected.checkoutLabel = "CL";
        expected.internalCode = "IC";
        expected.variableWeight = true;
        expected.attributes = new HashMap<>();
        expected.attributes.put("K", "V");
        assertEquals(expected.getChecksum(), incoming);
    }

    /**
     * {@code safeParseProductType} answers null on an absent column, whose
     * cell reads null (left operand of the disjunction).
     */
    @Test
    void safeParseProductTypeAnswersNullOnAbsentColumn() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        assertNull(resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }

    /**
     * {@code safeParseProductType} answers null on an empty cell (right operand
     * of the disjunction).
     */
    @Test
    void safeParseProductTypeAnswersNullOnEmptyCell() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "");
        assertNull(resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }

    /**
     * {@code safeParseProductType} upper-cases and resolves a well-formed cell
     * (the nominal arm).
     */
    @Test
    void safeParseProductTypeResolvesAWellFormedCell() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "weight");
        assertEquals(ProductType.WEIGHT,
                resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }

    /**
     * {@code safeParseProductType} answers null on an unknown value (the catch
     * arm).
     */
    @Test
    void safeParseProductTypeAnswersNullOnUnknownValue() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "BOGUS");
        assertNull(resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }

    /**
     * Builds a required-columns line extended with lot-20 columns.
     *
     * @param extra the extra column → cell pairs
     * @return the parsed line
     */
    private ImporterCsvResource.LineData lot20Line(Map<String, String> extra) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000009");
        cells.put(ProductCsvResource.COL_NAME, "Tuyau");
        cells.put(ProductCsvResource.COL_DESCRIPTION, "Tuyau d'arrosage");
        cells.put(ProductCsvResource.COL_BRAND, "Jardin");
        cells.put(ProductCsvResource.COL_REFERENCE_WEIGHT, "0.100");
        cells.put(ProductCsvResource.COL_REFERENCE_VOLUME, "0.000");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "unit");
        cells.put(ProductCsvResource.COL_UNIT_NAME, "pcs");
        cells.put(ProductCsvResource.COL_ACTIVE, "true");
        cells.putAll(extra);
        return line(cells);
    }

    /**
     * The lot-20 columns land on the article: the sale codes replace the
     * article's (its own EAN and blanks dropped), the checkout mode, the unit
     * code, the VAT regime and the exemption pose their attributes, and a known
     * unit code gives its symbol to the article (BO-02-03-03, -05, -17, -21 to
     * -27).
     */
    @Test
    void feedProductCarriesTheLot20Columns() throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(ProductCsvResource.COL_SALE_CODES, " 3000000000010 ;;3000000000009;3000000000011");
        extra.put(ProductCsvResource.COL_CHECKOUT_MODE, " decimal_quantity_to_enter ");
        extra.put(ProductCsvResource.COL_UNIT_CODE, "4");
        extra.put(ProductCsvResource.COL_VAT_NUMBER, " 2 ");
        extra.put(ProductCsvResource.COL_VAT_EXEMPT, "true");
        ImporterCsvResource.LineData data = lot20Line(extra);
        com.intermarche.edge.domain.catalog.MeasureUnit metre = new com.intermarche.edge.domain.catalog.MeasureUnit();
        metre.code = "4";
        metre.symbol = "m";
        @SuppressWarnings("unchecked")
        PanacheQuery<com.intermarche.edge.domain.catalog.MeasureUnit> unit = mock(PanacheQuery.class,
                invocation -> "firstResult".equals(invocation.getMethod().getName()) ? metre : null);
        Product product = new Product();
        product.ean = "3000000000009";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.catalog.MeasureUnit.find("code", "4")).thenReturn(unit);
            feedProduct(resource, data, product);
        }
        assertEquals(Set.of("3000000000010", "3000000000011"), product.saleCodes);
        assertEquals("DECIMAL_QUANTITY_TO_ENTER", product.attributes.get("CHECKOUT_MODE"));
        assertEquals("4", product.attributes.get("UNIT_CODE"));
        assertEquals("2", product.attributes.get("VAT_NUMBER"));
        assertEquals("true", product.attributes.get("VAT_EXEMPT"));
        assertEquals("m", product.unitName);
    }

    /**
     * Empty lot-20 cells withdraw the attributes and the sale codes, and an
     * unknown unit code leaves the line's own unit label (BO-02-03-03, -17).
     */
    @Test
    void feedProductWithdrawsOnEmptyLot20Cells() throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(ProductCsvResource.COL_SALE_CODES, "");
        extra.put(ProductCsvResource.COL_CHECKOUT_MODE, " ");
        extra.put(ProductCsvResource.COL_UNIT_CODE, "Z");
        extra.put(ProductCsvResource.COL_VAT_NUMBER, "");
        ImporterCsvResource.LineData data = lot20Line(extra);
        @SuppressWarnings("unchecked")
        PanacheQuery<com.intermarche.edge.domain.catalog.MeasureUnit> none = mock(PanacheQuery.class);
        Product product = new Product();
        product.ean = "3000000000009";
        product.saleCodes.add("OLD");
        product.attributes.put("CHECKOUT_MODE", "WEIGHED");
        product.attributes.put("UNIT_CODE", "2");
        product.attributes.put("VAT_NUMBER", "1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.catalog.MeasureUnit.find("code", "Z")).thenReturn(none);
            feedProduct(resource, data, product);
        }
        assertTrue(product.saleCodes.isEmpty());
        assertNull(product.attributes.get("CHECKOUT_MODE"));
        assertEquals("Z", product.attributes.get("UNIT_CODE"));
        assertNull(product.attributes.get("VAT_NUMBER"));
        assertEquals("pcs", product.unitName);
    }

    /**
     * The incoming checksum mirrors the lot-20 columns: the incoming sale codes
     * and attributes when the feed declares them, the existing ones when it does
     * not — an unchanged row therefore hashes as the entity does.
     */
    @Test
    void incomingChecksumMirrorsTheLot20Columns() throws Exception {
        Product existing = new Product();
        existing.ean = "3000000000009";
        existing.name = "Tuyau";
        existing.description = "Tuyau d'arrosage";
        existing.brand = "Jardin";
        existing.referenceWeight = new BigDecimal("0.100");
        existing.referenceVolume = new BigDecimal("0.000");
        existing.productType = ProductType.UNIT;
        existing.unitName = "pcs";
        existing.active = true;
        existing.saleCodes.add("C1");
        existing.attributes.put("CHECKOUT_MODE", "WEIGHED");
        assertEquals(existing.getChecksum(), incomingChecksum(resource, lot20Line(Map.of()), existing));
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(ProductCsvResource.COL_SALE_CODES, "C1");
        extra.put(ProductCsvResource.COL_CHECKOUT_MODE, "weighed");
        assertEquals(existing.getChecksum(), incomingChecksum(resource, lot20Line(extra), existing));
        extra.put(ProductCsvResource.COL_SALE_CODES, "C2");
        assertNotEquals(existing.getChecksum(), incomingChecksum(resource, lot20Line(extra), existing));
    }

    /**
     * A declared lot-20 column whose cell is missing reads as empty: no sale
     * code, no mode.
     */
    @Test
    void feedProductReadsMissingLot20CellsAsEmpty() throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(ProductCsvResource.COL_SALE_CODES, null);
        extra.put(ProductCsvResource.COL_CHECKOUT_MODE, null);
        Product product = new Product();
        product.ean = "3000000000009";
        product.saleCodes.add("OLD");
        product.attributes.put("CHECKOUT_MODE", "WEIGHED");
        feedProduct(resource, lot20Line(extra), product);
        assertTrue(product.saleCodes.isEmpty());
        assertNull(product.attributes.get("CHECKOUT_MODE"));
    }
}
