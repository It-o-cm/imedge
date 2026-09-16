package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.sync.EngineFeed;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EngineFeedService}: the catalog contract (order,
 * lookup), the pending-delivery snapshot, the store upsert with its
 * SHA-256 version short-circuit, the acknowledgement and error
 * recorders, and the hash helper. Pure unit —
 * the entity statics are intercepted with {@code mockStatic}, the rows are
 * Mockito mocks whose public fields are driven directly.
 */
class EngineFeedServiceTest {

    /** The service under test — stateless, one instance suffices. */
    private final EngineFeedService service = new EngineFeedService();

    /**
     * The catalog ships shared referentials before engine-specific feeds,
     * offers last (delivery-order doctrine).
     */
    @Test
    void catalogOrdersSharedBeforeSpecific() {
        assertEquals("STORES", EngineFeedService.CATALOG.get(0).code());
        assertEquals("OFFERS", EngineFeedService.CATALOG.get(EngineFeedService.CATALOG.size() - 1).code());
        assertTrue(indexOf("PRODUCTS") < indexOf("OFFERS"));
        assertTrue(indexOf("FAMILIES") < indexOf("PRICES"));
    }

    /**
     * Returns the catalog index of a feed code.
     *
     * @param code the feed code
     * @return the index in the catalog
     */
    private int indexOf(String code) {
        for (int i = 0; i < EngineFeedService.CATALOG.size(); i++) {
            if (EngineFeedService.CATALOG.get(i).code().equals(code)) return i;
        }
        return -1;
    }

    /**
     * {@code catalogEntry} resolves a known code (present arm) and yields
     * empty for an unknown one (absent arm).
     */
    @Test
    void catalogEntryResolvesKnownAndRejectsUnknown() {
        assertEquals("/offers/import", EngineFeedService.catalogEntry("OFFERS").orElseThrow().enginePath());
        assertTrue(EngineFeedService.catalogEntry("NOPE").isEmpty());
    }

    /**
     * {@code store} creates the row on first sight (null arm): content,
     * version and timestamp are set and persisted.
     */
    @Test
    void storeCreatesNewFeed() {
        // The created-row arm constructs the entity itself, so the real
        // persist() would need a running container: construction mocking
        // intercepts the new EngineFeed and neutralizes its persist.
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<EngineFeed> constructed =
                     org.mockito.Mockito.mockConstruction(EngineFeed.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> emptyQuery = queryOf(null);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(emptyQuery);
            String version = service.store("OFFERS", "CODE|TYPE\nA|X\n");
            assertEquals(EngineFeedService.sha256("CODE|TYPE\nA|X\n"), version);
            EngineFeed created = constructed.constructed().get(0);
            assertEquals("OFFERS", created.code);
            assertEquals("CODE|TYPE\nA|X\n", created.content);
            assertEquals(version, created.version);
            assertNotNull(created.receivedAt);
            verify(created).persist();
        }
    }

    /**
     * {@code store} on an unchanged content returns the same version
     * without touching the row (short-circuit arm).
     */
    @Test
    void storeSkipsUnchangedContent() {
        EngineFeed existing = mock(EngineFeed.class);
        existing.code = "OFFERS";
        existing.version = EngineFeedService.sha256("SAME");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> existingQuery = queryOf(existing);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(existingQuery);
            String version = service.store("OFFERS", "SAME");
            assertEquals(existing.version, version);
            verify(existing, never()).persist();
        }
    }

    /**
     * {@code store} on a changed content refreshes the row (changed arm):
     * new content, new version, new timestamp, persisted.
     */
    @Test
    void storeRefreshesChangedContent() {
        EngineFeed existing = mock(EngineFeed.class);
        existing.code = "OFFERS";
        existing.version = EngineFeedService.sha256("OLD");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> existingQuery = queryOf(existing);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(existingQuery);
            String version = service.store("OFFERS", "NEW");
            assertEquals(EngineFeedService.sha256("NEW"), version);
            assertEquals("NEW", existing.content);
            assertEquals(version, existing.version);
            assertNotNull(existing.receivedAt);
            verify(existing).persist();
        }
    }

    /**
     * {@code markApplied} records the acknowledged version, stamps the
     * time and clears the error (found arm); an unknown code is a no-op
     * (null arm).
     */
    @Test
    void markAppliedRecordsAcknowledgement() {
        EngineFeed feed = mock(EngineFeed.class);
        feed.code = "OFFERS";
        feed.lastError = "old error";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> feedQuery = queryOf(feed);
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> absentQuery = queryOf(null);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(feedQuery);
            mocked.when(() -> EngineFeed.find("code", "NOPE")).thenReturn(absentQuery);
            service.markApplied("OFFERS", "v1");
            assertEquals("v1", feed.appliedVersion);
            assertNotNull(feed.appliedAt);
            assertNull(feed.lastError);
            verify(feed).persist();
            service.markApplied("NOPE", "v1");
        }
    }

    /**
     * {@code markError} records the message, truncated to the column size
     * (long arm), and ignores an unknown code (null arm).
     */
    @Test
    void markErrorRecordsTruncatedMessage() {
        EngineFeed feed = mock(EngineFeed.class);
        feed.code = "OFFERS";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> feedQuery = queryOf(feed);
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> absentQuery = queryOf(null);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(feedQuery);
            mocked.when(() -> EngineFeed.find("code", "NOPE")).thenReturn(absentQuery);
            service.markError("OFFERS", "x".repeat(600));
            assertEquals(500, feed.lastError.length());
            verify(feed).persist();
            service.markError("NOPE", "err");
        }
    }

    /**
     * {@code markError} on a null message stores null verbatim: the
     * {@code error != null} guard takes its false leg, short-circuiting the
     * length test and skipping truncation (null arm of the compound).
     */
    @Test
    void markErrorAcceptsNullMessage() {
        EngineFeed feed = mock(EngineFeed.class);
        feed.code = "OFFERS";
        feed.lastError = "old error";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> feedQuery = queryOf(feed);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(feedQuery);
            service.markError("OFFERS", null);
            assertNull(feed.lastError);
            verify(feed).persist();
        }
    }

    /**
     * {@code markError} on a short message stores it unchanged: the
     * {@code error != null} guard is true but {@code length() > 500} takes
     * its false leg, so no truncation occurs (short arm of the compound).
     */
    @Test
    void markErrorKeepsShortMessage() {
        EngineFeed feed = mock(EngineFeed.class);
        feed.code = "OFFERS";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> feedQuery = queryOf(feed);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(feedQuery);
            service.markError("OFFERS", "short");
            assertEquals("short", feed.lastError);
            verify(feed).persist();
        }
    }

    /**
     * {@code pendingFeeds} snapshots the feeds awaiting delivery in
     * catalog order, skipping absent rows (null arm) and acknowledged ones
     * (up-to-date arm), and carries code, engine path, content, version
     * and last error into each detached snapshot.
     */
    @Test
    void pendingFeedsSnapshotsUnacknowledgedInCatalogOrder() {
        EngineFeed stores = mock(EngineFeed.class);
        stores.code = "STORES";
        stores.content = "CONTENT-STORES";
        stores.version = "v1";
        stores.appliedVersion = "v1";
        EngineFeed products = mock(EngineFeed.class);
        products.code = "PRODUCTS";
        products.content = "CONTENT-PRODUCTS";
        products.version = "v2";
        products.appliedVersion = "v1";
        products.lastError = "old error";
        EngineFeed offers = mock(EngineFeed.class);
        offers.code = "OFFERS";
        offers.content = "CONTENT-OFFERS";
        offers.version = "v9";
        offers.appliedVersion = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            for (EngineFeedService.FeedDef def : EngineFeedService.CATALOG) {
                io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> emptyQuery = queryOf(null);
                mocked.when(() -> EngineFeed.find("code", def.code())).thenReturn(emptyQuery);
            }
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> storesQuery = queryOf(stores);
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> productsQuery = queryOf(products);
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> offersQuery = queryOf(offers);
            mocked.when(() -> EngineFeed.find("code", "STORES")).thenReturn(storesQuery);
            mocked.when(() -> EngineFeed.find("code", "PRODUCTS")).thenReturn(productsQuery);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(offersQuery);
            List<EngineFeedService.PendingFeed> pending = service.pendingFeeds();
            assertEquals(2, pending.size());
            assertEquals("PRODUCTS", pending.get(0).code());
            assertEquals("/products/import", pending.get(0).enginePath());
            assertEquals("CONTENT-PRODUCTS", pending.get(0).content());
            assertEquals("v2", pending.get(0).version());
            assertEquals("old error", pending.get(0).lastError());
            assertEquals("OFFERS", pending.get(1).code());
        }
    }

    /**
     * {@code feedStates} projects the stored feeds in catalog order, skipping
     * absent codes (null arm) and flagging the applied state from the
     * version/appliedVersion equality: an acknowledged feed (equal arm, no
     * error, both timestamps) and a lagging feed (unequal arm, an error, no
     * applied timestamp). Content is never carried.
     */
    @Test
    void feedStatesProjectsStoredFeedsInCatalogOrder() {
        EngineFeed products = mock(EngineFeed.class);
        products.code = "PRODUCTS";
        products.version = "v2v2v2v2v2v2v2";
        products.appliedVersion = "v2v2v2v2v2v2v2";
        products.lastError = null;
        products.receivedAt = java.time.LocalDateTime.of(2026, 9, 11, 8, 0);
        products.appliedAt = java.time.LocalDateTime.of(2026, 9, 11, 8, 1);
        EngineFeed offers = mock(EngineFeed.class);
        offers.code = "OFFERS";
        offers.version = "vNEWvNEWvNEW";
        offers.appliedVersion = "vOLD";
        offers.lastError = "boom";
        offers.receivedAt = java.time.LocalDateTime.of(2026, 9, 11, 9, 0);
        offers.appliedAt = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            for (EngineFeedService.FeedDef def : EngineFeedService.CATALOG) {
                io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> absentQuery = queryOf(null);
                mocked.when(() -> EngineFeed.find("code", def.code())).thenReturn(absentQuery);
            }
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> productsQuery = queryOf(products);
            io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> offersQuery = queryOf(offers);
            mocked.when(() -> EngineFeed.find("code", "PRODUCTS")).thenReturn(productsQuery);
            mocked.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(offersQuery);
            List<EngineFeedService.FeedState> states = service.feedStates();
            assertEquals(2, states.size());
            EngineFeedService.FeedState first = states.get(0);
            assertEquals("PRODUCTS", first.code());
            assertEquals("v2v2v2v2v2v2v2", first.version());
            assertEquals("v2v2v2v2v2v2v2", first.appliedVersion());
            assertTrue(first.applied());
            assertNull(first.lastError());
            assertEquals(java.time.LocalDateTime.of(2026, 9, 11, 8, 0), first.receivedAt());
            assertEquals(java.time.LocalDateTime.of(2026, 9, 11, 8, 1), first.appliedAt());
            EngineFeedService.FeedState second = states.get(1);
            assertEquals("OFFERS", second.code());
            assertFalse(second.applied());
            assertEquals("boom", second.lastError());
            assertNull(second.appliedAt());
        }
    }

    /**
     * {@code sha256} produces the well-known digest of an empty string.
     */
    @Test
    void sha256MatchesKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                EngineFeedService.sha256(""));
    }

    /**
     * Builds a mocked Panache query yielding one first result.
     *
     * @param result the entity the query returns, or null
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private static io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> queryOf(EngineFeed result) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<EngineFeed> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        org.mockito.Mockito.when(query.firstResult()).thenReturn(result);
        return query;
    }
}
