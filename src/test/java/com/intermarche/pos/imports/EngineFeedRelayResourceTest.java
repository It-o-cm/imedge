package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.EngineFeedService;
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
        Response response = resource.importFeed("NOPE", stream("CODE|X\n"));
        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("OFFERS"));
        assertTrue(String.valueOf(response.getEntity()).contains("EMPLOYEES"));
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * A code owned by a dedicated importer is delegated to it under the
     * unified grammar — the importer's own response comes back verbatim,
     * nothing is sealed here (delegated arm).
     */
    @Test
    void delegatedCodeRoutesToItsImporter() {
        Response delegated = Response.ok("{\"createdCount\":312, \"updatedCount\":0}").build();
        InputStream body = stream("EAN|NAME\n1|A\n");
        when(resource.productCsvResource.importProducts(body)).thenReturn(delegated);
        Response response = resource.importFeed("products", body);
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
        Response delegated = Response.ok("{\"createdCount\":4, \"updatedCount\":0}").build();
        InputStream body = stream("BADGE_ID|LOGIN\n1|a\n");
        when(resource.employeeCsvResource.importEmployees(body)).thenReturn(delegated);
        Response response = resource.importFeed("EMPLOYEES", body);
        assertSame(delegated, response);
        verify(resource.engineFeedService, never()).store(any(), any());
    }

    /**
     * A null code is rejected like an unknown one (null arm of the
     * normalization).
     */
    @Test
    void nullCodeRejected() {
        Response response = resource.importFeed(null, stream("CODE|X\n"));
        assertEquals(400, response.getStatus());
    }

    /**
     * An empty body is rejected with a 400 (blank arm), nothing stored.
     */
    @Test
    void emptyBodyRejected() {
        Response response = resource.importFeed("OFFERS", stream("   "));
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
        Response response = resource.importFeed("offers", stream("CODE|TYPE\nA|X\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"code\":\"OFFERS\", \"version\":\"deadbeef\"}", response.getEntity());
    }

    /**
     * {@code status} lists the stored feeds in catalog order with their
     * acknowledgement state: an acknowledged feed reads applied=true, a
     * pending one applied=false with its delivery error, and absent
     * catalog codes are omitted.
     */
    @Test
    void statusListsStoredFeedsInCatalogOrder() {
        com.intermarche.pos.domain.sync.EngineFeed products =
            org.mockito.Mockito.mock(com.intermarche.pos.domain.sync.EngineFeed.class);
        products.code = "PRODUCTS";
        products.version = "v2";
        products.appliedVersion = "v1";
        products.lastError = "HTTP 503 \"boom\"";
        com.intermarche.pos.domain.sync.EngineFeed offers =
            org.mockito.Mockito.mock(com.intermarche.pos.domain.sync.EngineFeed.class);
        offers.code = "OFFERS";
        offers.version = "v7";
        offers.appliedVersion = "v7";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                 org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            for (com.intermarche.pos.service.sync.EngineFeedService.FeedDef def :
                     com.intermarche.pos.service.sync.EngineFeedService.CATALOG) {
                com.intermarche.pos.domain.sync.EngineFeed match =
                        def.code().equals("PRODUCTS") ? products
                                : def.code().equals("OFFERS") ? offers : null;
                // Hoisted out of the stubbing: Mockito forbids creating and
                // stubbing another mock while a stubbing is in progress.
                io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sync.EngineFeed>
                        matchQuery = queryOf(match);
                mocked.when(() -> com.intermarche.pos.domain.sync.EngineFeed.find("code", def.code()))
                        .thenReturn(matchQuery);
            }
            Response response = resource.status();
            assertEquals(200, response.getStatus());
            String body = String.valueOf(response.getEntity());
            assertEquals("[{\"code\":\"PRODUCTS\",\"version\":\"v2\",\"appliedVersion\":\"v1\","
                    + "\"applied\":false,\"lastError\":\"HTTP 503 'boom'\"},"
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
        com.intermarche.pos.domain.sync.EngineFeed products =
            org.mockito.Mockito.mock(com.intermarche.pos.domain.sync.EngineFeed.class);
        products.code = "PRODUCTS";
        products.version = "v5";
        products.appliedVersion = null;
        products.lastError = null;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> mocked =
                 org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            for (com.intermarche.pos.service.sync.EngineFeedService.FeedDef def :
                     com.intermarche.pos.service.sync.EngineFeedService.CATALOG) {
                com.intermarche.pos.domain.sync.EngineFeed match =
                        def.code().equals("PRODUCTS") ? products : null;
                io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sync.EngineFeed>
                        matchQuery = queryOf(match);
                mocked.when(() -> com.intermarche.pos.domain.sync.EngineFeed.find("code", def.code()))
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
    private static io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sync.EngineFeed> queryOf(
            com.intermarche.pos.domain.sync.EngineFeed result) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sync.EngineFeed> query =
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
        Response response = resource.importFeed("OFFERS", failing);
        assertEquals(500, response.getStatus());
        verify(resource.engineFeedService, never()).store(any(), any());
    }
}
