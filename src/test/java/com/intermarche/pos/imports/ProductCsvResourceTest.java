package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductCsvResource}.
 * <p>
 * The resource is the {@link Product}-specific concretion of the abstract
 * {@link ImporterCsvResource}: it bulk-fetches products for a chunk keyed by
 * EAN, then creates or updates each {@link Product} using a checksum
 * optimisation before persisting through the Panache entity manager. Every
 * collaborator is a Panache active-record static finder which, under plain
 * {@code mvn test}, resolves to {@link PanacheEntityBase}
 * ({@code list}/{@code find}/{@code findById}) — each is intercepted with
 * {@link org.mockito.Mockito#mockStatic} inside a try-with-resources block —
 * and {@code Panache.getEntityManager()} is stubbed the same way so
 * {@code persist} is observable. The private {@code computeIncomingChecksum}
 * helper is reached by reflection so the update-vs-skip checksum arms carry
 * byte-exact expected values; {@code feedProduct} is asserted through the
 * captured persisted entity and the freshly re-read entity. No database and no
 * Quarkus context is booted, every entity field is set directly, and every
 * branch is asserted against absolute expected values.
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
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
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

}
