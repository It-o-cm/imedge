package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThirdPartySystem} (BO-07-01-01): a fresh system is an
 * active e-commerce one; it is found by its trimmed token or code, a blank one
 * naming none, and listed by code.
 */
class ThirdPartySystemTest {

    /**
     * A fresh system is an active e-commerce system that has deposited nothing.
     */
    @Test
    void aFreshSystemIsAnActiveEcommerceOne() {
        ThirdPartySystem system = new ThirdPartySystem();
        assertEquals(SalesChannel.ECOMMERCE, system.channel);
        assertTrue(system.active);
        assertEquals(0L, system.orderCount);
    }

    /**
     * The finders trim what they are given and short-circuit on blank or null.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFindersTrimOrShortCircuit() {
        ThirdPartySystem system = new ThirdPartySystem();
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(system);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> ThirdPartySystem.find("token", "abc")).thenReturn(query);
            ms.when(() -> ThirdPartySystem.find("code", "WEB")).thenReturn(query);
            ms.when(() -> ThirdPartySystem.list("order by code")).thenReturn(List.of(system));
            assertSame(system, ThirdPartySystem.findByToken(" abc "));
            assertSame(system, ThirdPartySystem.findByCode(" WEB "));
            assertEquals(List.of(system), ThirdPartySystem.listAllOrdered());
            assertNull(ThirdPartySystem.findByToken(null));
            assertNull(ThirdPartySystem.findByToken(" "));
            assertNull(ThirdPartySystem.findByCode(null));
            assertNull(ThirdPartySystem.findByCode(""));
        }
    }
}
