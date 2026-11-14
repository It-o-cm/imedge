package com.intermarche.edge.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Promotion}: the finder by number, trimmed and
 * null-safe, the ordered list and the default type.
 */
class PromotionTest {

    /**
     * A null number finds nothing, a number is trimmed, the list is by number,
     * a new promotion is by value and not financed upstream.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFindersAndTheDefaults() {
        Promotion promotion = new Promotion();
        assertEquals(Promotion.BY_VALUE, promotion.kind);
        assertEquals(false, promotion.upstreamFunded);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(promotion);
        List<Promotion> all = List.of(promotion);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "OFF1")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(all);
            assertNull(Promotion.findByCode(null));
            assertSame(promotion, Promotion.findByCode(" OFF1 "));
            assertSame(all, Promotion.listOrdered());
        }
    }
}
