package com.intermarche.edge.imports;

import com.intermarche.edge.service.sync.EngineFeedService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EngineFeedRelayResource}: the catalog gate (unknown
 * code → 400 naming the known codes), the empty-body gate, the verbatim
 * store on the happy path (code normalized to upper case) and the stream
 * failure. Pure unit — the feed keeper is a Mockito mock.
 */
class EngineFeedRelayResourceTest {

    /** The resource under test. */
    private EngineFeedRelayResource resource;

    /**
     * Wires a fresh resource on a mocked feed keeper and mocked dedicated
     * importers (the delegation table of the unified grammar).
     */
    @BeforeEach
    void setUp() {
        resource = new EngineFeedRelayResource();
        resource.engineFeedService = mock(EngineFeedService.class);
        resource.storeCsvResource = mock(StoreCsvResource.class);
        resource.productCsvResource = mock(ProductCsvResource.class);
        resource.productFamilyCsvResource = mock(ProductFamilyCsvResource.class);
        resource.priceCsvResource = mock(PriceCsvResource.class);
        resource.employeeCsvResource = mock(EmployeeCsvResource.class);
        resource.vatRateCsvResource = mock(VatRateCsvResource.class);
        resource.egalimRegimeCsvResource = mock(EgalimRegimeCsvResource.class);
        resource.accountCustomerCsvResource = mock(AccountCustomerCsvResource.class);
        resource.postalCodeCsvResource = mock(PostalCodeCsvResource.class);
        resource.safeReceiptCsvResource = mock(SafeReceiptCsvResource.class);
        resource.nomenclatureCsvResource = mock(NomenclatureCsvResource.class);
        resource.pdvCsvResource = mock(PdvCsvResource.class);
        resource.integrationRetryService = mock(com.intermarche.edge.service.sync.IntegrationRetryService.class);
        // BO-02-03-33: the fingerprints an article file is stamped with.
        resource.refExportService = mock(com.intermarche.edge.service.sync.store.RefExportService.class);
        when(resource.refExportService.fingerprintOf(java.util.List.of())).thenReturn("EMPTY");
        when(resource.refExportService.publishedFingerprint(any())).thenReturn("before", "after");
    }

    /**
     * Wraps a string as a UTF-8 input stream.
     *
     * @param content the body content
     * @return the input stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * An unknown feed code is rejected with a 400 naming BOTH families of
     * known codes — delegated and sealed (unknown arm), nothing stored.
     */
    @Test
    void unknownCodeRejected() {
        Response response = resource.importFeed("NOPE", null, null, null, null, stream("CODE|X\n"));
        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("OFFERS"));
        assertTrue(String.valueOf(response.getEntity()).contains("EMPLOYEES"));
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * The refusal of a code carrying a quote and a backslash is still valid
     * JSON: a real parser reads the code back in the error message.
     *
     * @throws Exception never: the answer parses
     */
    @Test
    void unknownCodeAnswerIsValidJson() throws Exception {
        Response response = resource.importFeed("NO\"PE\\", null, null, null, null, stream("CODE|X\n"));
        String error = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(String.valueOf(response.getEntity())).get("error").asText();
        assertTrue(error.startsWith("Unknown feed code 'NO\"PE\\' - known codes: "));
    }

    /**
     * A code owned by a dedicated importer is delegated to it under the
     * unified grammar — the importer's own response comes back verbatim,
     * nothing is sealed here (delegated arm).
     */
    @Test
    void delegatedCodeRoutesToItsImporter() {
        Response delegated = Response.ok("{\"createdCount\":312,\"updatedCount\":0}").build();
        InputStream body = stream("EAN|NAME\n1|A\n");
        when(resource.productCsvResource.importProducts(any(InputStream.class))).thenReturn(delegated);
        Response response = resource.importFeed("products", null, null, null, null, body);
        assertSame(delegated, response);
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * EMPLOYEES delegates too, although it is no engine-catalog code: the
     * unified grammar covers the whole referential, and the secrets never
     * reach the sealed-parcel store.
     */
    @Test
    void employeesCodeDelegatesOutsideTheEngineCatalog() {
        Response delegated = Response.ok("{\"createdCount\":4,\"updatedCount\":0}").build();
        InputStream body = stream("BADGE_ID|LOGIN\n1|a\n");
        when(resource.employeeCsvResource.importEmployees(any(InputStream.class))).thenReturn(delegated);
        Response response = resource.importFeed("EMPLOYEES", null, null, null, null, body);
        assertSame(delegated, response);
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * The CUSTOMERS code delegates to the customer importer AND forwards the
     * import mode, the only delegation that carries one: a full population and
     * an evening delta enter by the same door and are told apart by it.
     */
    @Test
    void customersCodeForwardsTheImportMode() {
        InputStream body = stream("ACCOUNT_NUMBER|COMPANY_NAME\n1|Acme\n");
        Response delegated = Response.ok("{\"createdCount\":1}").build();
        when(resource.accountCustomerCsvResource.importCustomers(any(InputStream.class), eq("full"))).thenReturn(delegated);
        Response response = resource.importFeed("customers", "full", null, null, null, body);
        assertEquals(delegated, response);
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * The NOMENCLATURE code delegates AND carries its three parameters: the
     * published file numbers nodes inside a scheme and never names the scheme,
     * so the caller supplies it. This was the last import with an address of
     * its own; it has none now.
     */
    @Test
    void nomenclatureCodeForwardsItsThreeParameters() {
        InputStream body = stream("SOURCE|NIVEAU|NUMERO|LIBELLE\nX|1|10|Frais\n");
        Response delegated = Response.ok("{\"createdCount\":1}").build();
        when(resource.nomenclatureCsvResource
                .importNomenclature(eq("ITM_FR"), eq("Nomenclature ITM"), eq("ITM"), any(InputStream.class)))
                .thenReturn(delegated);
        Response response = resource.importFeed(
                "NOMENCLATURE", null, "ITM_FR", "Nomenclature ITM", "ITM", body);
        assertEquals(delegated, response);
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * The POSTAL_CODES code delegates too: an importer that had only its own
     * address now answers on the single door like every other.
     */
    @Test
    void postalCodesCodeDelegatesToItsImporter() {
        InputStream body = stream("CODE|PLACE\n75001|Paris\n");
        Response delegated = Response.ok("{\"createdCount\":1}").build();
        when(resource.postalCodeCsvResource.importPostalCodes(any(InputStream.class))).thenReturn(delegated);
        assertEquals(delegated, resource.importFeed("POSTAL_CODES", null, null, null, null, body));
    }

    /**
     * The PDVS code delegates to the point-of-sale importer (BO-02-05-03).
     */
    @Test
    void pdvsCodeDelegatesToItsImporter() {
        InputStream body = stream("PDV_NUMBER|NAME\n07039|Lyon\n");
        Response delegated = Response.ok("{\"createdCount\":1}").build();
        when(resource.pdvCsvResource.importPdvs(any(InputStream.class))).thenReturn(delegated);
        assertEquals(delegated, resource.importFeed("PDVS", null, null, null, null, body));
    }

    /**
     * The SAFE_RECEIPTS code likewise.
     */
    @Test
    void safeReceiptsCodeDelegatesToItsImporter() {
        InputStream body = stream("REFERENCE|AMOUNT\nR1|10\n");
        Response delegated = Response.ok("{\"createdCount\":1}").build();
        when(resource.safeReceiptCsvResource.importReceipts(any(InputStream.class), org.mockito.ArgumentMatchers.isNull())).thenReturn(delegated);
        assertEquals(delegated, resource.importFeed("SAFE_RECEIPTS", null, null, null, null, body));
    }

    /**
     * A null code is rejected like an unknown one (null arm of the
     * normalization).
     */
    @Test
    void nullCodeRejected() {
        Response response = resource.importFeed(null, null, null, null, null, stream("CODE|X\n"));
        assertEquals(400, response.getStatus());
    }

    /**
     * An empty body is rejected with a 400 (blank arm), nothing stored.
     */
    @Test
    void emptyBodyRejected() {
        Response response = resource.importFeed("OFFERS", null, null, null, null, stream("   "));
        assertEquals(400, response.getStatus());
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * A known code stores the body verbatim — the code normalized to upper
     * case — and answers 200 with the stored version (happy arm).
     */
    @Test
    void knownCodeStoresVerbatim() {
        when(resource.engineFeedService.store("OFFERS", "CODE|TYPE\nA|X\n")).thenReturn("deadbeef");
        Response response = resource.importFeed("offers", null, null, null, null, stream("CODE|TYPE\nA|X\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"code\":\"OFFERS\",\"version\":\"deadbeef\"}", response.getEntity());
    }

    /**
     * {@code status} lists the stored feeds in catalog order with their
     * acknowledgement state: an acknowledged feed reads applied=true, a
     * pending one applied=false with its delivery error, and absent
     * catalog codes are omitted.
     */
    @Test
    void statusListsStoredFeedsInCatalogOrder() {
        com.intermarche.edge.domain.sync.EngineFeed products =
            org.mockito.Mockito.mock(com.intermarche.edge.domain.sync.EngineFeed.class);
        products.code = "PRODUCTS";
        products.version = "v2";
        products.appliedVersion = "v1";
        products.lastError = "HTTP 503 \"boom\"";
        com.intermarche.edge.domain.sync.EngineFeed offers =
            org.mockito.Mockito.mock(com.intermarche.edge.domain.sync.EngineFeed.class);
        offers.code = "OFFERS";
        offers.version = "v7";
        offers.appliedVersion = "v7";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                 org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            for (com.intermarche.edge.service.sync.EngineFeedService.FeedDef def :
                     com.intermarche.edge.service.sync.EngineFeedService.CATALOG) {
                com.intermarche.edge.domain.sync.EngineFeed match =
                        def.code().equals("PRODUCTS") ? products
                                : def.code().equals("OFFERS") ? offers : null;
                // Hoisted out of the stubbing: Mockito forbids creating and
                // stubbing another mock while a stubbing is in progress.
                io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed>
                        matchQuery = queryOf(match);
                mocked.when(() -> com.intermarche.edge.domain.sync.EngineFeed.find("code", def.code()))
                        .thenReturn(matchQuery);
            }
            Response response = resource.status();
            assertEquals(200, response.getStatus());
            String body = String.valueOf(response.getEntity());
            assertEquals("[{\"code\":\"PRODUCTS\",\"version\":\"v2\",\"appliedVersion\":\"v1\","
                    + "\"applied\":false,\"lastError\":\"HTTP 503 \\\"boom\\\"\"},"
                    + "{\"code\":\"OFFERS\",\"version\":\"v7\",\"appliedVersion\":\"v7\","
                    + "\"applied\":true,\"lastError\":null}]", body);
        }
    }

    /**
     * A feed never acknowledged by the engine reads appliedVersion=null
     * (the null arm of the appliedVersion ternary) with applied=false, since
     * a stored version can never equal a null acknowledgement.
     */
    @Test
    void statusRendersNullAppliedVersionForNeverAcknowledgedFeed() {
        com.intermarche.edge.domain.sync.EngineFeed products =
            org.mockito.Mockito.mock(com.intermarche.edge.domain.sync.EngineFeed.class);
        products.code = "PRODUCTS";
        products.version = "v5";
        products.appliedVersion = null;
        products.lastError = null;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                 org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            for (com.intermarche.edge.service.sync.EngineFeedService.FeedDef def :
                     com.intermarche.edge.service.sync.EngineFeedService.CATALOG) {
                com.intermarche.edge.domain.sync.EngineFeed match =
                        def.code().equals("PRODUCTS") ? products : null;
                io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed>
                        matchQuery = queryOf(match);
                mocked.when(() -> com.intermarche.edge.domain.sync.EngineFeed.find("code", def.code()))
                        .thenReturn(matchQuery);
            }
            Response response = resource.status();
            assertEquals(200, response.getStatus());
            String body = String.valueOf(response.getEntity());
            assertEquals("[{\"code\":\"PRODUCTS\",\"version\":\"v5\",\"appliedVersion\":null,"
                    + "\"applied\":false,\"lastError\":null}]", body);
        }
    }

    /**
     * Builds a mocked Panache query yielding one first result.
     *
     * @param result the entity the query returns, or null
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private static io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> queryOf(
            com.intermarche.edge.domain.sync.EngineFeed result) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.edge.domain.sync.EngineFeed> query =
                org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * A failing stream answers 500 (IOException arm), nothing stored.
     */
    @Test
    void failingStreamAnswers500() {
        InputStream failing = new InputStream() {
            /**
             * Always fails to simulate an unreadable stream.
             *
             * @return never returns normally
             * @throws IOException always
             */
            @Override
            public int read() throws IOException {
                throw new IOException("disk");
            }
        };
        Response response = resource.importFeed("OFFERS", null, null, null, null, failing);
        assertEquals(500, response.getStatus());
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * BO-08-04-01 to -13: every deposit of a known code is recorded with the
     * integration's answer, and a replay integrates the kept file through the
     * same delegate.
     */
    @Test
    void depositsAreRecordedAndReplayable() {
        Response delegated = Response.ok("{\"createdCount\":1}").build();
        when(resource.productCsvResource.importProducts(any(InputStream.class))).thenReturn(delegated);
        resource.importFeed("PRODUCTS", "m", "s", "l", "e", stream("EAN|NAME\n1|A\n"));
        verify(resource.integrationRetryService).record("PRODUCTS", "m", "s", "l", "e", "EAN|NAME\n1|A\n", delegated);
        assertSame(delegated, resource.integrate("PRODUCTS", null, null, null, null, "EAN|NAME\n1|A\n"));
        assertEquals(400, resource.integrate("OFFERS", null, null, null, null, " ").getStatus());
    }

    /**
     * BO-02-03-33: an article file is stamped with the state it was integrated
     * over and the state it left; over an empty referential its base is empty;
     * a code the registers do not apply from the file is not stamped.
     */
    @Test
    void anArticleFileIsStampedAroundItsIntegration() {
        when(resource.productCsvResource.importProducts(any(InputStream.class))).thenReturn(Response.ok().build());
        when(resource.priceCsvResource.importPrices(any(InputStream.class))).thenReturn(Response.ok().build());
        when(resource.employeeCsvResource.importEmployees(any(InputStream.class))).thenReturn(Response.ok().build());
        resource.integrate("PRODUCTS", null, null, null, null, "EAN|NAME\n1|A\n");
        verify(resource.engineFeedService).stamp("PRODUCTS", "before", "after");
        when(resource.refExportService.publishedFingerprint("PRICES")).thenReturn("EMPTY", "priced");
        resource.integrate("PRICES", null, null, null, null, "EAN|PRICE\n1|2\n");
        verify(resource.engineFeedService).stamp("PRICES", "", "priced");
        resource.integrate("EMPLOYEES", null, null, null, null, "ID|NAME\n1|A\n");
        verify(resource.engineFeedService, never()).stamp(org.mockito.ArgumentMatchers.eq("EMPLOYEES"), any(), any());
    }
}
