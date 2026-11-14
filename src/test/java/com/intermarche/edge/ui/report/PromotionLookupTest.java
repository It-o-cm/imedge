package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Promotion;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromotionLookup}: an administered promotion gives its
 * label, type and financing; an unknown one falls back on the line's label, a
 * type read off it, and no upstream financing; a null number finds nothing.
 */
class PromotionLookupTest {

    /**
     * Administered and unknown promotions.
     */
    @Test
    @SuppressWarnings("unchecked")
    void administeredAndUnknownPromotions() {
        Promotion off1 = new Promotion();
        off1.code = "OFF1";
        off1.label = "Lot de 3";
        off1.kind = Promotion.BY_PERCENT;
        off1.upstreamFunded = true;
        PanacheQuery<Object> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(off1);
        Promotion off3 = new Promotion();
        off3.code = "OFF3";
        PanacheQuery<Object> local = mock(PanacheQuery.class);
        when(local.firstResult()).thenReturn(off3);
        PanacheQuery<Object> missing = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "OFF1")).thenReturn(found);
            panache.when(() -> PanacheEntityBase.find("code", "OFF2")).thenReturn(missing);
            panache.when(() -> PanacheEntityBase.find("code", "OFF3")).thenReturn(local);
            PromotionLookup lookup = new PromotionLookup();
            assertEquals("Lot de 3", lookup.label("OFF1", "x"));
            assertEquals(Promotion.BY_PERCENT, lookup.kind("OFF1", null));
            assertTrue(lookup.upstream("OFF1"));
            assertEquals("-20 %", lookup.label("OFF2", "-20 %"));
            assertEquals("", lookup.label("OFF2", null));
            assertEquals(Promotion.BY_PERCENT, lookup.kind("OFF2", "-20 %"));
            assertEquals(Promotion.BY_VALUE, lookup.kind("OFF2", "-2 €"));
            assertEquals(Promotion.BY_VALUE, lookup.kind("OFF2", null));
            assertFalse(lookup.upstream("OFF2"));
            assertFalse(lookup.upstream("OFF3"));
            assertNull(lookup.find(null));
        }
    }
}
