package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EngineFeed}.
 * <p>
 * The entity is a Panache active record retaining one verbatim feed file per
 * code; its single static finder resolves to {@link PanacheEntityBase} under
 * plain {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic} inside a try-with-resources block. No
 * database and no Quarkus context is booted. The finder carries no branch, so
 * coverage is completed by exercising the found and not-found results and the
 * read/write of every public field. Each test is fully isolated and asserts
 * absolute expected values.
 */
class EngineFeedTest {

    /**
     * Every public field accepts and returns its assigned value.
     */
    @Test
    void fieldsAreReadWrite() {
        EngineFeed feed = new EngineFeed();
        LocalDateTime received = LocalDateTime.of(2026, 9, 26, 8, 0);
        LocalDateTime applied = LocalDateTime.of(2026, 9, 26, 8, 5);
        feed.code = "PRODUCTS";
        feed.content = "header\nrow1\nrow2";
        feed.version = "abc123";
        feed.receivedAt = received;
        feed.appliedVersion = "abc122";
        feed.appliedAt = applied;
        feed.lastError = "engine unreachable";
        Assertions.assertEquals("PRODUCTS", feed.code);
        Assertions.assertEquals("header\nrow1\nrow2", feed.content);
        Assertions.assertEquals("abc123", feed.version);
        Assertions.assertEquals(received, feed.receivedAt);
        Assertions.assertEquals("abc122", feed.appliedVersion);
        Assertions.assertEquals(applied, feed.appliedAt);
        Assertions.assertEquals("engine unreachable", feed.lastError);
    }

    /**
     * findByCode queries the feed row carrying the given code and returns the
     * finder's first result.
     */
    @Test
    void findByCodeReturnsTheQueriedFeed() {
        EngineFeed expected = new EngineFeed();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<EngineFeed> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> EngineFeed.find("code", "PRODUCTS")).thenReturn(query);
            Assertions.assertSame(expected, EngineFeed.findByCode("PRODUCTS"));
        }
    }

    /**
     * findByCode propagates a null first result when this node never received the
     * feed.
     */
    @Test
    void findByCodeReturnsNullWhenNeverReceived() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<EngineFeed> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> EngineFeed.find("code", "OFFERS")).thenReturn(query);
            Assertions.assertNull(EngineFeed.findByCode("OFFERS"));
        }
    }
}
