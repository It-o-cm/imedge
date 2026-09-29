package com.intermarche.pos.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CheckoutIsland}, targeting 100% branch coverage.
 * <p>
 * The island's register relation is no longer a semicolon string on the island:
 * it is read from {@link CheckoutPoint}. Instance methods therefore resolve the
 * Panache finders on both entities, which under plain {@code mvn test} fall back
 * to {@link PanacheEntityBase}, so they are intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code terminals} covers the empty
 * and the non-empty relation; {@code holds} covers the null arm, the blank arm,
 * the found arm and the not-found arm; {@code findByCode} covers the null arm,
 * the blank arm and the found arm; {@code findByTerminal} covers the no-point
 * arm, the point-without-island arm, the unknown-island arm, the inactive-island
 * arm and the active-island arm; {@code listActive} forwards the order.
 */
class CheckoutIslandTest {

    /** The finder clause the active island listing uses. */
    private static final String ACTIVE = "active = true order by code";

    /** The finder clause {@code CheckoutPoint.listByIsland} uses. */
    private static final String BY_ISLAND = "islandCode = ?1 and active = true order by terminalId";

    /**
     * Builds an island.
     *
     * @param code the island code
     * @param active whether it is in service
     * @return the island
     */
    private CheckoutIsland island(String code, boolean active) {
        CheckoutIsland island = new CheckoutIsland();
        island.code = code;
        island.label = "Ligne " + code;
        island.active = active;
        return island;
    }

    /**
     * Builds a checkout point.
     *
     * @param terminalId the terminal identifier
     * @param islandCode the island it points at, or null
     * @return the point
     */
    private CheckoutPoint point(String terminalId, String islandCode) {
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = terminalId;
        point.islandCode = islandCode;
        return point;
    }

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param result the row the query resolves, or null
     * @param <T> the row type
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * The attached registers are read from the active checkout points of the
     * island, in the order the relation returns them.
     */
    @Test
    void theAttachedRegistersComeFromTheCheckoutPoints() {
        CheckoutIsland island = island("AVANT", true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT"))
                    .thenReturn(List.of(point("POS01", "AVANT"), point("POS02", "AVANT")));
            assertEquals(List.of("POS01", "POS02"), island.terminals());
        }
    }

    /**
     * An island with no active checkout point holds no register.
     */
    @Test
    void anIslandWithoutPointsHoldsNone() {
        CheckoutIsland island = island("AVANT", true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT")).thenReturn(List.of());
            assertTrue(island.terminals().isEmpty());
        }
    }

    /**
     * {@code holds} recognises an attached register, trimming what it is given,
     * and refuses one the island's points do not list.
     */
    @Test
    void holdsRecognisesAnAttachedRegister() {
        CheckoutIsland island = island("AVANT", true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT"))
                    .thenReturn(List.of(point("POS01", "AVANT"), point("POS02", "AVANT")));
            assertTrue(island.holds("POS01"));
            assertTrue(island.holds("  POS02  "));
            assertFalse(island.holds("POS09"));
        }
    }

    /**
     * A null or blank register belongs to no island (the two legs of the guard),
     * answered without touching the relation.
     */
    @Test
    void anUnnamedRegisterBelongsToNoIsland() {
        CheckoutIsland island = island("AVANT", true);
        assertFalse(island.holds(null));
        assertFalse(island.holds("   "));
    }

    /**
     * {@code findByCode} answers the row for a code, and short-circuits a null or
     * blank code without a query.
     */
    @Test
    void findByCodeResolvesOrShortCircuits() {
        CheckoutIsland island = island("AVANT", true);
        PanacheQuery<CheckoutIsland> islandQuery = query(island);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(islandQuery);
            assertSame(island, CheckoutIsland.findByCode("AVANT"));
        }
        assertNull(CheckoutIsland.findByCode(null));
        assertNull(CheckoutIsland.findByCode("   "));
    }

    /**
     * {@code findByTerminal} answers the active island the register's checkout
     * point points at.
     */
    @Test
    void findByTerminalAnswersTheActiveIslandOfTheRegister() {
        CheckoutIsland island = island("AVANT", true);
        PanacheQuery<CheckoutPoint> pointQuery = query(point("POS02", "AVANT"));
        PanacheQuery<CheckoutIsland> islandQuery = query(island);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.find("terminalId", "POS02")).thenReturn(pointQuery);
            ms.when(() -> CheckoutIsland.find("code", "AVANT")).thenReturn(islandQuery);
            assertSame(island, CheckoutIsland.findByTerminal("POS02"));
        }
    }

    /**
     * A register with no checkout point, or a point tied to no island, belongs to
     * no island (the two early arms).
     */
    @Test
    void findByTerminalIsNullWithoutAPointOrAnIsland() {
        PanacheQuery<CheckoutPoint> noPoint = query(null);
        PanacheQuery<CheckoutPoint> pointNoIsland = query(point("POS02", null));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.find("terminalId", "POS01")).thenReturn(noPoint);
            assertNull(CheckoutIsland.findByTerminal("POS01"));
            ms.when(() -> CheckoutPoint.find("terminalId", "POS02")).thenReturn(pointNoIsland);
            assertNull(CheckoutIsland.findByTerminal("POS02"));
        }
    }

    /**
     * A point pointing at an unknown or a DEACTIVATED island resolves to none:
     * an island rule must stay silent once the island is out of service.
     */
    @Test
    void findByTerminalIsNullForAnUnknownOrInactiveIsland() {
        PanacheQuery<CheckoutPoint> ghostPoint = query(point("POS03", "GHOST"));
        PanacheQuery<CheckoutIsland> ghostIsland = query(null);
        PanacheQuery<CheckoutPoint> oldPoint = query(point("POS04", "OLD"));
        PanacheQuery<CheckoutIsland> oldIsland = query(island("OLD", false));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.find("terminalId", "POS03")).thenReturn(ghostPoint);
            ms.when(() -> CheckoutIsland.find("code", "GHOST")).thenReturn(ghostIsland);
            assertNull(CheckoutIsland.findByTerminal("POS03"));
            ms.when(() -> CheckoutPoint.find("terminalId", "POS04")).thenReturn(oldPoint);
            ms.when(() -> CheckoutIsland.find("code", "OLD")).thenReturn(oldIsland);
            assertNull(CheckoutIsland.findByTerminal("POS04"));
        }
    }

    /**
     * {@code listActive} forwards the administered order verbatim.
     */
    @Test
    void listActiveForwardsTheAdministeredOrder() {
        CheckoutIsland island = island("AVANT", true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutIsland.list(ACTIVE)).thenReturn(List.of(island));
            assertEquals(List.of(island), CheckoutIsland.listActive());
        }
    }

    /**
     * A fresh island is in service and gives change, its channel the point of
     * sale: a store creating one means to use it.
     */
    @Test
    void aFreshIslandIsInServiceWithSaneDefaults() {
        CheckoutIsland island = new CheckoutIsland();
        assertTrue(island.active);
        assertTrue(island.changeGivingAllowed);
        assertFalse(island.autoPeriodCloseDeclaration);
        assertEquals(PromotionChannel.POINT_OF_SALE, island.promotionChannel);
    }
}
