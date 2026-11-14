package com.intermarche.edge.domain.sale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SalesChannel} (BO-07-01-01, -02): labels, the third
 * parties' channels, the tolerant name reading and the store's channel of a
 * ticket carrying none.
 */
class SalesChannelTest {

    /**
     * Every channel carries its French label; only the store's lanes are not
     * a third party's.
     */
    @Test
    void theChannelsAndTheirLabels() {
        assertEquals("Magasin", SalesChannel.STORE.getLabel());
        assertEquals("E-commerce", SalesChannel.ECOMMERCE.getLabel());
        assertEquals("Mobilité", SalesChannel.MOBILITY.getLabel());
        assertFalse(SalesChannel.STORE.isThirdParty());
        assertTrue(SalesChannel.ECOMMERCE.isThirdParty());
        assertTrue(SalesChannel.MOBILITY.isThirdParty());
    }

    /**
     * A name is read trimmed and case-insensitively; null and unknown read as
     * none.
     */
    @Test
    void aNameIsReadTolerantly() {
        assertEquals(SalesChannel.MOBILITY, SalesChannel.fromName(" mobility "));
        assertEquals(SalesChannel.ECOMMERCE, SalesChannel.fromName("ECOMMERCE"));
        assertNull(SalesChannel.fromName("WEB"));
        assertNull(SalesChannel.fromName(null));
    }

    /**
     * A ticket carrying no channel is a sale of the store's lanes.
     */
    @Test
    void aTicketWithoutChannelIsTheStores() {
        assertEquals(SalesChannel.STORE, SalesChannel.of(null));
        assertEquals(SalesChannel.ECOMMERCE, SalesChannel.of(SalesChannel.ECOMMERCE));
    }
}
