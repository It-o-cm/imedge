package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ProductFamilyCsvResource}.
 * <p>
 * The resource is the {@link ProductFamily}-specific concretion of the abstract
 * {@link ImporterCsvResource}: for a chunk it bulk-fetches parent families
 * (keyed by code), products (keyed by EAN) and sub-families (keyed by code)
 * into a context map, then for each line rebuilds the product and sub-family
 * links from the CSV row — the file being authoritative — before persisting a
 * new family or comparing checksums on an existing one. Every collaborator is a
 * Panache active-record static finder which, under plain {@code mvn test},
 * resolves to {@link PanacheEntityBase} ({@code list}/{@code find}) and is
 * intercepted with {@link org.mockito.Mockito#mockStatic} inside a
 * try-with-resources block; {@code Panache.getEntityManager()} is stubbed the
 * same way so {@code persist} is observable. The private
 * {@code computeIncomingChecksum} helper is reached by reflection so the
 * update-vs-skip checksum arms carry byte-exact expected values. No database
 * and no Quarkus context is booted, every entity field is set directly, and
 * every branch is asserted against absolute expected values.
 */
class ProductFamilyCsvResourceTest {

    /** Context-map key under which the pre-fetched product map is stored. */
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";
    /** Context-map key under which the pre-fetched sub-family map is stored. */
    private static final String CTX_SUB_FAMILIES = "__CTX_SUB_FAMILIES__";
    /** The exact JPQL fragment issued for families and sub-families. */
    private static final String CODE_QUERY = "code IN ?1";
    /** The exact JPQL fragment issued for products. */
    private static final String EAN_QUERY = "ean IN ?1";
    /** The exact JPQL fragment issued by {@code findEntityForLine}. */
    private static final String FIND_QUERY = "code";

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
     * Builds a 5-column family CSV row.
     *
     * @param code    the family code (column 0)
     * @param desc    the description (column 1)
     * @param flags   the flags (column 2)
     * @param eans    the comma-separated product EANs (column 3)
     * @param subs    the comma-separated sub-family codes (column 4)
     * @return a fresh parts array {code, description, flags, eans, subs}
     */
    private String[] parts(String code, String desc, String flags, String eans, String subs) {
        return new String[]{code, desc, flags, eans, subs};
    }

    /**
     * Builds a {@link Product} with the given EAN.
     *
     * @param ean the product EAN
     * @return the product
     */
    private Product product(String ean) {
        Product p = new Product();
        p.ean = ean;
        return p;
    }

    /**
     * Builds a {@link ProductFamily} with the given code.
     *
     * @param code the family code
     * @return the family
     */
    private ProductFamily family(String code) {
        ProductFamily f = new ProductFamily();
        f.code = code;
        return f;
    }

    /**
     * Invokes the private {@code computeIncomingChecksum} helper by reflection.
     *
     * @param resource the resource under test
     * @param data     the parsed line
     * @return the checksum of the incoming CSV data
     * @throws Exception on reflection failure
     */
    private static int incomingChecksum(ProductFamilyCsvResource resource, ImporterCsvResource.LineData data) throws Exception {
        Method method = ProductFamilyCsvResource.class.getDeclaredMethod("computeIncomingChecksum", ImporterCsvResource.LineData.class);
        method.setAccessible(true);
        return (Integer) method.invoke(resource, data);
    }

    /**
     * {@code importProductFamilies} forwards the stream to the base importer
     * with the 5-column contract; a header-only body produces a zero-count JSON
     * with a 200 status.
     */
    @Test
    void importProductFamiliesDelegatesToBaseImporterWithFiveColumns() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        Response response = resource.importProductFamilies(stream("CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * {@code processChunkWithFallback} short-circuits to an empty map when the
     * chunk holds no lines (the {@code parsedLines.isEmpty} true arm).
     */
    @Test
    void processChunkWithFallbackReturnsEmptyMapForEmptyChunk() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        Map<String, Object> context = resource.processChunkWithFallback(new ArrayList<>(), new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
        assertTrue(context.isEmpty());
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches and indexes existing
     * families, products and sub-families for a populated chunk (parsedLines
     * non-empty arm, both child-set non-empty arms, every loop entered).
     */
    @Test
    void processChunkWithFallbackIndexesFamiliesProductsAndSubFamilies() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(1, parts("F1", "Fruits", "ORGANIC", "111,222", "S1,S2")));
        Set<String> targetCodes = new HashSet<>();
        targetCodes.add("F1");
        Set<String> eans = new HashSet<>();
        eans.add("111");
        eans.add("222");
        Set<String> subCodes = new HashSet<>();
        subCodes.add("S1");
        subCodes.add("S2");
        ProductFamily f1 = family("F1");
        Product p111 = product("111");
        Product p222 = product("222");
        ProductFamily s1 = family("S1");
        ProductFamily s2 = family("S2");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.list(CODE_QUERY, targetCodes)).thenReturn(List.of(f1));
            // The EAN lookup is BATCHED (productsByEan): the IN clause receives a
            // List built by iterating the set, not the set itself.
            panache.when(() -> Product.list(EAN_QUERY, new ArrayList<>(eans)))
                    .thenReturn(List.of(p111, p222));
            panache.when(() -> ProductFamily.list(CODE_QUERY, subCodes)).thenReturn(List.of(s1, s2));
            Map<String, Object> context = resource.processChunkWithFallback(lines, targetCodes, new int[]{0, 0}, new ArrayList<>());
            assertSame(f1, context.get("F1"));
            @SuppressWarnings("unchecked")
            Map<String, Product> productMap = (Map<String, Product>) context.get(CTX_PRODUCTS);
            assertEquals(2, productMap.size());
            assertSame(p111, productMap.get("111"));
            assertSame(p222, productMap.get("222"));
            @SuppressWarnings("unchecked")
            Map<String, ProductFamily> subFamilyMap = (Map<String, ProductFamily>) context.get(CTX_SUB_FAMILIES);
            assertEquals(2, subFamilyMap.size());
            assertSame(s1, subFamilyMap.get("S1"));
            assertSame(s2, subFamilyMap.get("S2"));
        }
    }

    /**
     * {@code processChunkWithFallback} skips the product and sub-family queries
     * entirely when the row references no children (both {@code !isEmpty} false
     * arms) and indexes nothing when no parent family matches (family loop
     * not entered): the context then holds only the two empty auxiliary maps.
     */
    @Test
    void processChunkWithFallbackSkipsChildQueriesWhenNoChildCodes() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(1, parts("F1", "Fruits", "ORGANIC", "", "")));
        Set<String> targetCodes = new HashSet<>();
        targetCodes.add("F1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.list(CODE_QUERY, targetCodes)).thenReturn(List.of());
            Map<String, Object> context = resource.processChunkWithFallback(lines, targetCodes, new int[]{0, 0}, new ArrayList<>());
            assertEquals(2, context.size());
            assertFalse(context.containsKey("F1"));
            @SuppressWarnings("unchecked")
            Map<String, Product> productMap = (Map<String, Product>) context.get(CTX_PRODUCTS);
            assertTrue(productMap.isEmpty());
            @SuppressWarnings("unchecked")
            Map<String, ProductFamily> subFamilyMap = (Map<String, ProductFamily>) context.get(CTX_SUB_FAMILIES);
            assertTrue(subFamilyMap.isEmpty());
        }
    }

    /**
     * {@code processLineLogic} creates a fresh family when the context lacks the
     * line's code ({@code family == null} true arm) in chunk mode (both
     * auxiliary maps present): it re-links the requested products and
     * sub-families, increments the created counter and persists through the
     * entity manager.
     */
    @Test
    void processLineLogicCreatesFamilyLinkingProductsAndSubFamilies() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Fruits", "ORGANIC", "111,222", "S1,S2"));
        Product p111 = product("111");
        Product p222 = product("222");
        ProductFamily s1 = family("S1");
        ProductFamily s2 = family("S2");
        Map<String, Product> productMap = new HashMap<>();
        productMap.put("111", p111);
        productMap.put("222", p222);
        Map<String, ProductFamily> subFamilyMap = new HashMap<>();
        subFamilyMap.put("S1", s1);
        subFamilyMap.put("S2", s2);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, productMap);
        context.put(CTX_SUB_FAMILIES, subFamilyMap);
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<ProductFamily> captor = ArgumentCaptor.forClass(ProductFamily.class);
            verify(em).persist(captor.capture());
            ProductFamily persisted = captor.getValue();
            assertEquals("F1", persisted.code);
            assertEquals("Fruits", persisted.description);
            assertEquals("ORGANIC", persisted.flags);
            assertEquals(2, persisted.products.size());
            assertTrue(persisted.products.contains(p111));
            assertTrue(persisted.products.contains(p222));
            assertEquals(2, persisted.productFamilies.size());
            assertTrue(persisted.productFamilies.contains(s1));
            assertTrue(persisted.productFamilies.contains(s2));
        }
    }

    /**
     * {@code processLineLogic} updates an existing family when the stored
     * checksum differs from the incoming one ({@code family == null} false arm,
     * checksum-mismatch true arm). With no child codes and no auxiliary maps,
     * both {@code retrieveProducts} and {@code retrieveSubProductFamilies} take
     * the {@code map == null && requestedCodes.isEmpty} short-circuit arm and
     * skip their lookups; the description and flags are still overwritten.
     */
    @Test
    void processLineLogicUpdatesExistingFamilyWhenChecksumDiffers() throws Exception {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Veggies", "SEASONAL", "", ""));
        ProductFamily existing = family("F1");
        existing.description = "Old";
        existing.flags = "OLD";
        existing.checksum = incomingChecksum(resource, data) + 1;
        Map<String, Object> context = new HashMap<>();
        context.put("F1", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(data, context, counters);
        assertEquals(0, counters[0]);
        assertEquals(1, counters[1]);
        assertEquals("Veggies", existing.description);
        assertEquals("SEASONAL", existing.flags);
        assertTrue(existing.products.isEmpty());
        assertTrue(existing.productFamilies.isEmpty());
    }

    /**
     * {@code processLineLogic} leaves the counters untouched when the existing
     * family already matches the incoming checksum (checksum-mismatch false arm).
     */
    @Test
    void processLineLogicSkipsUpdateWhenChecksumMatches() throws Exception {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Veggies", "SEASONAL", "", ""));
        ProductFamily existing = family("F1");
        existing.checksum = incomingChecksum(resource, data);
        Map<String, Object> context = new HashMap<>();
        context.put("F1", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(data, context, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * {@code processLineLogic} (through {@code retrieveProducts} and
     * {@code retrieveSubProductFamilies}) performs a fresh DB lookup when the
     * auxiliary maps are absent but child codes are present (both compound
     * {@code map == null && !requestedCodes.isEmpty} true arms), then creates
     * the family with the freshly looked-up links.
     */
    @Test
    void processLineLogicLooksUpChildrenFromDbInFallbackMode() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Fruits", "ORGANIC", "111", "S1"));
        Product p111 = product("111");
        ProductFamily s1 = family("S1");
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Panache> panacheEm = mockStatic(Panache.class)) {
            panache.when(() -> Product.list(EAN_QUERY, new ArrayList<>(List.of("111")))).thenReturn(List.of(p111));
            panache.when(() -> ProductFamily.list(CODE_QUERY, new ArrayList<>(List.of("S1")))).thenReturn(List.of(s1));
            EntityManager em = mock(EntityManager.class);
            panacheEm.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<ProductFamily> captor = ArgumentCaptor.forClass(ProductFamily.class);
            verify(em).persist(captor.capture());
            ProductFamily persisted = captor.getValue();
            assertEquals(1, persisted.products.size());
            assertTrue(persisted.products.contains(p111));
            assertEquals(1, persisted.productFamilies.size());
            assertTrue(persisted.productFamilies.contains(s1));
        }
    }

    /**
     * {@code prepareProductFamily} throws when a referenced product EAN is
     * absent from the map ({@code p != null} false arm), triggering the
     * rollback/fallback mechanism.
     */
    @Test
    void processLineLogicThrowsWhenProductEanMissing() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Fruits", "ORGANIC", "999", ""));
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, context, counters));
        assertEquals("Product EAN '999' not found.", ex.getMessage());
    }

    /**
     * {@code linkSubFamilies} throws when a referenced sub-family code is absent
     * from the map ({@code sub != null} false arm).
     */
    @Test
    void processLineLogicThrowsWhenSubFamilyMissing() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Fruits", "ORGANIC", "", "S9"));
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, context, counters));
        assertEquals("SubFamily code 'S9' not found.", ex.getMessage());
    }

    /**
     * {@code linkSubFamilies} throws when a family lists itself as a sub-family
     * ({@code sub != null} true arm, {@code sub.code.equals(family.code)} true
     * arm).
     */
    @Test
    void processLineLogicThrowsOnSelfReference() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Fruits", "ORGANIC", "", "F1"));
        ProductFamily selfRef = family("F1");
        Map<String, ProductFamily> subFamilyMap = new HashMap<>();
        subFamilyMap.put("F1", selfRef);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, subFamilyMap);
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, context, counters));
        assertEquals("Family 'F1' cannot contain itself.", ex.getMessage());
    }

    /**
     * {@code findEntityForLine} performs the code lookup used by the 1-by-1
     * fallback and returns the first matching family.
     */
    @Test
    void findEntityForLineLooksUpFamilyByCode() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        ImporterCsvResource.LineData data = line(1, parts("F1", "Fruits", "ORGANIC", "", ""));
        ProductFamily f1 = family("F1");
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.find(FIND_QUERY, "F1")).thenReturn(query);
            when(query.firstResult()).thenReturn(f1);
            assertSame(f1, resource.findEntityForLine(data));
        }
    }
    /**
     * {@code processChunkWithFallback}, through {@code productsByEan}, flushes a
     * full batch mid-iteration when a single row references exactly
     * {@code EAN_BATCH} (500) distinct EANs: the {@code batch.size() == EAN_BATCH}
     * true arm fires once, the accumulated batch is fetched and cleared, and the
     * trailing {@code fetchBatch} runs on the now-empty batch (so a single
     * {@code Product.list} query is issued). The looked-up product lands in the
     * context product map, proving the mid-loop flush actually queried the batch.
     */
    @Test
    void processChunkWithFallbackFlushesFullEanBatch() {
        ProductFamilyCsvResource resource = new ProductFamilyCsvResource();
        List<String> eanList = new ArrayList<>();
        for (int i = 0; i < 500; i++) eanList.add(String.format("E%04d", i));
        String eans = String.join(",", eanList);
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(1, parts("F1", "Fruits", "ORGANIC", eans, "")));
        Set<String> targetCodes = new HashSet<>();
        targetCodes.add("F1");
        Product p0 = product("E0000");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.list(CODE_QUERY, targetCodes)).thenReturn(List.of());
            panache.when(() -> Product.list(eq(EAN_QUERY), anyList())).thenReturn(List.of(p0));
            Map<String, Object> context = resource.processChunkWithFallback(lines, targetCodes, new int[]{0, 0}, new ArrayList<>());
            @SuppressWarnings("unchecked")
            Map<String, Product> productMap = (Map<String, Product>) context.get(CTX_PRODUCTS);
            assertEquals(1, productMap.size());
            assertSame(p0, productMap.get("E0000"));
        }
    }

    /** Header names of the imported feed, in the cell order of the fixtures. */
    private static final String[] TEST_HEADER = {"CODE", "DESCRIPTION", "FLAGS", "PRODUCT_EANS", "SUBFAMILY_CODES"};

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

}
