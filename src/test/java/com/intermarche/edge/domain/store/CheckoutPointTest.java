package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CheckoutPoint}, targeting 100% branch coverage.
 * <p>
 * The static finders resolve the Panache finders, which under plain
 * {@code mvn test} fall back to {@link PanacheEntityBase}, intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code findByTerminal} covers the
 * null, the blank and the found arm; {@code listByIsland} covers the null, the
 * blank and the resolving arm; {@code countByIsland} covers the null, the blank
 * and the counting arm; {@code listAllOrdered} forwards; the defaults assert the
 * sane starting state of a fresh point.
 */
class CheckoutPointTest {

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param result the row the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<CheckoutPoint> query(CheckoutPoint result) {
        PanacheQuery<CheckoutPoint> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * {@code findByTerminal} answers the row for a terminal, trimming it, and
     * short-circuits a null or blank identifier without a query.
     */
    @Test
    void findByTerminalResolvesOrShortCircuits() {
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = "POS01";
        PanacheQuery<CheckoutPoint> found = query(point);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(found);
            assertSame(point, CheckoutPoint.findByTerminal("  POS01  "));
        }
        assertNull(CheckoutPoint.findByTerminal(null));
        assertNull(CheckoutPoint.findByTerminal("   "));
    }

    /**
     * {@code listByIsland} resolves the active points of an island, and answers
     * an empty list for a null or blank code without a query.
     */
    @Test
    void listByIslandResolvesOrShortCircuits() {
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list("islandCode = ?1 and active = true order by terminalId", "AVANT"))
                    .thenReturn(List.of(point));
            assertEquals(List.of(point), CheckoutPoint.listByIsland(" AVANT "));
        }
        assertTrue(CheckoutPoint.listByIsland(null).isEmpty());
        assertTrue(CheckoutPoint.listByIsland("   ").isEmpty());
    }

    /**
     * {@code countByIsland} counts the points of an island, and answers zero for
     * a null or blank code without a query.
     */
    @Test
    void countByIslandCountsOrShortCircuits() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.count("islandCode = ?1", "AVANT")).thenReturn(3L);
            assertEquals(3L, CheckoutPoint.countByIsland(" AVANT "));
        }
        assertEquals(0L, CheckoutPoint.countByIsland(null));
        assertEquals(0L, CheckoutPoint.countByIsland("   "));
    }

    /**
     * {@code listAllOrdered} forwards the administered order verbatim.
     */
    @Test
    void listAllOrderedForwardsTheOrder() {
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list("order by terminalId")).thenReturn(List.of(point));
            assertEquals(List.of(point), CheckoutPoint.listAllOrdered());
        }
    }

    /**
     * A fresh checkout point is active, a manned till with no display: the sane
     * starting state before administration.
     */
    @Test
    void aFreshPointHasSaneDefaults() {
        CheckoutPoint point = new CheckoutPoint();
        assertTrue(point.active);
        assertEquals(TpvType.CASH_REGISTER, point.tpvType);
        assertEquals(DisplayType.NONE, point.cashierDisplayType);
        assertEquals(DisplayType.NONE, point.customerDisplayType);
    }
}
