package com.intermarche.pos.service.sync;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SyncEndpoints}, the one place that decides where this
 * node's store node lives.
 * <p>
 * These cases came from {@code SyncOutboxServiceTest}, where the address used
 * to be held; they now sit with the code they describe. Branch enumeration:
 * {@code hasStoreUrl} is a two-leg conjunction — absent, present-and-blank,
 * present-and-filled — and {@code storeUrl} has the disabled arm plus both
 * legs of the trailing-slash test, each on a trimmed input since trimming
 * happens before the test.
 */
class SyncEndpointsTest {

    /**
     * No configured URL: the first leg of the conjunction is false, so the
     * synchronization is off.
     */
    @Test
    void anAbsentUrlDisablesTheSynchronization() {
        SyncEndpoints endpoints = new SyncEndpoints();
        endpoints.storeUrl = Optional.empty();
        assertFalse(endpoints.hasStoreUrl());
    }

    /**
     * A present but blank URL: the first leg is true and the second false. A
     * property set to spaces is the shape an emptied configuration line takes,
     * and it must read as "no store node", not as a store node named "   ".
     */
    @Test
    void aBlankUrlDisablesTheSynchronization() {
        SyncEndpoints endpoints = new SyncEndpoints();
        endpoints.storeUrl = Optional.of("   ");
        assertFalse(endpoints.hasStoreUrl());
    }

    /**
     * A present, non-blank URL: both legs true, the synchronization is on.
     */
    @Test
    void aFilledUrlEnablesTheSynchronization() {
        SyncEndpoints endpoints = new SyncEndpoints();
        endpoints.storeUrl = Optional.of("http://store");
        assertTrue(endpoints.hasStoreUrl());
    }

    /**
     * The disabled arm of {@code storeUrl}: an empty string, never null, so no
     * caller has to guard against one.
     */
    @Test
    void anUnconfiguredNodeAnswersTheEmptyString() {
        SyncEndpoints endpoints = new SyncEndpoints();
        endpoints.storeUrl = Optional.empty();
        assertEquals("", endpoints.storeUrl());
    }

    /**
     * The trailing-slash true arm: the slash is stripped, and stripped AFTER
     * trimming — a value copied out of a configuration file with a stray space
     * behind the slash must still lose the slash.
     */
    @Test
    void aTrailingSlashIsStrippedAfterTrimming() {
        SyncEndpoints endpoints = new SyncEndpoints();
        endpoints.storeUrl = Optional.of(" http://store/ ");
        assertEquals("http://store", endpoints.storeUrl());
    }

    /**
     * The trailing-slash false arm: a URL that has none is returned as it
     * stands, trimmed.
     */
    @Test
    void aUrlWithoutATrailingSlashIsKept() {
        SyncEndpoints endpoints = new SyncEndpoints();
        endpoints.storeUrl = Optional.of(" http://store ");
        assertEquals("http://store", endpoints.storeUrl());
    }

    /**
     * The default constructor leaves the address absent, so an instance CDI
     * has not yet injected answers "disabled" instead of raising.
     */
    @Test
    void theDefaultConstructorStartsDisabled() {
        assertFalse(new SyncEndpoints().hasStoreUrl());
        assertEquals("", new SyncEndpoints().storeUrl());
    }

    /**
     * The hand-wiring factory accepts a URL and a null alike, which is what
     * lets a test build the enabled and the disabled case the same way.
     */
    @Test
    void theFactoryAcceptsAUrlAndNullAlike() {
        assertTrue(SyncEndpoints.of("http://store").hasStoreUrl());
        assertEquals("http://store", SyncEndpoints.of("http://store/").storeUrl());
        assertFalse(SyncEndpoints.of(null).hasStoreUrl());
        assertEquals("", SyncEndpoints.of(null).storeUrl());
    }
}
