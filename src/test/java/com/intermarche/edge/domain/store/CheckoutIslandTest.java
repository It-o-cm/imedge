package com.intermarche.edge.domain.store;

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
 * The island's register relation is read from {@link CheckoutPoint} first and
 * from the island's old semicolon list {@code terminalIds} next. Instance
 * methods therefore resolve the Panache finders on both entities, which under
 * plain {@code mvn test} fall back to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code terminals} covers the
 * points-only relation, the null and the blank old list, the empty-entry arm,
 * the trimming arm, and the already-named and new arms of the de-duplication;
 * {@code holds} covers the null arm, the blank arm, the found-through-a-point
 * arm, the found-through-the-old-list arm and the not-found arm;
 * {@code findByCode} covers the null arm, the blank arm and the found arm;
 * {@code findByTerminal} covers the null arm, the blank arm, the empty-list
 * arm, the null-entry arm, the no-match arm and the match arm (through a
 * checkout point and through the old list); {@code listActive} forwards the
 * order.
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
     * Builds an active island holding the given raw old-form terminal list.
     *
     * @param code the island code
     * @param terminalIds the raw semicolon list, or null
     * @return the island
     */
    private CheckoutIsland island(String code, String terminalIds) {
        CheckoutIsland island = island(code, true);
        island.terminalIds = terminalIds;
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
     * An island with no active checkout point and no old list holds no
     * register, whether the old column is null, blank or made of empty entries.
     */
    @Test
    void anIslandWithoutPointsHoldsNone() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT")).thenReturn(List.of());
            assertTrue(island("AVANT", true).terminals().isEmpty());
            assertTrue(island("AVANT", "   ").terminals().isEmpty());
            assertTrue(island("AVANT", ";;;").terminals().isEmpty());
        }
    }

    /**
     * Without checkout points, the old list is split, trimmed, and the empty
     * entries a hand-typed list leaves behind are dropped.
     */
    @Test
    void theOldListIsSplitAndTrimmed() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT")).thenReturn(List.of());
            assertEquals(List.of("POS01", "POS02", "POS03"),
                    island("AVANT", " POS01 ; POS02;;POS03 ; ").terminals());
        }
    }

    /**
     * The checkout points come first and the old list only adds the registers
     * they do not already name.
     */
    @Test
    void theOldListOnlyAddsWhatThePointsDoNotName() {
        CheckoutIsland island = island("AVANT", "POS01;POS03");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT"))
                    .thenReturn(List.of(point("POS01", "AVANT"), point("POS02", "AVANT")));
            assertEquals(List.of("POS01", "POS02", "POS03"), island.terminals());
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
     * {@code holds} recognises a register the old list names when no checkout
     * point ties it to the island.
     */
    @Test
    void holdsRecognisesARegisterOfTheOldList() {
        CheckoutIsland island = island("AVANT", "POS01;POS02");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT")).thenReturn(List.of());
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
        CheckoutIsland island = island("AVANT", "POS01");
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
     * {@code findByTerminal} answers the active island whose checkout point ties
     * the register to it.
     */
    @Test
    void findByTerminalAnswersTheIslandOfTheRegisterPoint() {
        CheckoutIsland other = island("COMPTOIR", true);
        CheckoutIsland wanted = island("AVANT", true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutIsland.list(ACTIVE)).thenReturn(List.of(other, wanted));
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "COMPTOIR"))
                    .thenReturn(List.of(point("POS08", "COMPTOIR")));
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT"))
                    .thenReturn(List.of(point("POS02", "AVANT")));
            assertSame(wanted, CheckoutIsland.findByTerminal("POS02"));
        }
    }

    /**
     * {@code findByTerminal} answers the active island whose old list names the
     * register, skipping a null row and an island that does not list it.
     */
    @Test
    void findByTerminalAnswersTheIslandOfTheOldList() {
        CheckoutIsland other = island("COMPTOIR", "POS08;POS09");
        CheckoutIsland wanted = island("AVANT", "POS01;POS02");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutIsland.list(ACTIVE))
                    .thenReturn(java.util.Arrays.asList(null, other, wanted));
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "COMPTOIR")).thenReturn(List.of());
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "AVANT")).thenReturn(List.of());
            assertSame(wanted, CheckoutIsland.findByTerminal("POS02"));
        }
    }

    /**
     * A register no active island lists belongs to none, and so does every
     * register when the store administers no island at all.
     */
    @Test
    void aRegisterOutsideEveryIslandBelongsToNone() {
        CheckoutIsland other = island("COMPTOIR", "POS08");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutIsland.list(ACTIVE)).thenReturn(List.of(other));
            ms.when(() -> CheckoutPoint.list(BY_ISLAND, "COMPTOIR")).thenReturn(List.of());
            assertNull(CheckoutIsland.findByTerminal("POS01"));
            ms.when(() -> CheckoutIsland.list(ACTIVE)).thenReturn(List.of());
            assertNull(CheckoutIsland.findByTerminal("POS01"));
        }
    }

    /**
     * A null or blank register is answered without touching the database, which
     * is what the absence of any static stubbing here asserts.
     */
    @Test
    void findByTerminalShortCircuitsOnAnUnnamedRegister() {
        assertNull(CheckoutIsland.findByTerminal(null));
        assertNull(CheckoutIsland.findByTerminal("   "));
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
     * sale and its old list empty: a store creating one means to use it.
     */
    @Test
    void aFreshIslandIsInServiceWithSaneDefaults() {
        CheckoutIsland island = new CheckoutIsland();
        assertTrue(island.active);
        assertTrue(island.changeGivingAllowed);
        assertFalse(island.autoPeriodCloseDeclaration);
        assertEquals(PromotionChannel.POINT_OF_SALE, island.promotionChannel);
        assertNull(island.terminalIds);
    }

    /**
     * BO-09-03-01: the hosts attached to the island are read off their list,
     * trimmed, blanks and repeats dropped; an island naming none attaches every
     * host, one naming some attaches only them and never a host without badge.
     */
    @Test
    void theHostsAttachedToTheIsland() {
        CheckoutIsland island = new CheckoutIsland();
        assertTrue(island.hosts().isEmpty());
        assertTrue(island.attaches(null));
        assertTrue(island.attaches("0001"));
        island.hostBadges = " 0001 ; ;0002;0001";
        assertEquals(List.of("0001", "0002"), island.hosts());
        assertTrue(island.attaches(" 0002 "));
        assertFalse(island.attaches("0003"));
        assertFalse(island.attaches(null));
    }
}
