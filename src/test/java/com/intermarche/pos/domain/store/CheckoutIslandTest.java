package com.intermarche.pos.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link CheckoutIsland}, targeting 100% branch coverage.
 * <p>
 * Instance methods run on plain instances; the two static finders resolve the
 * Panache {@code list} finder, which under plain {@code mvn test} falls back to
 * {@link PanacheEntityBase}, so they are intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code terminals} covers the null
 * arm, the blank arm, the empty-entry arm and the trimming arm;
 * {@code holds} covers the null arm, the blank arm, the found arm and the
 * not-found arm; {@code findByTerminal} covers the null arm, the blank arm, the
 * empty-list arm, the null-entry arm, the no-match arm and the match arm.
 */
class CheckoutIslandTest {

    /** The finder clause the active listing uses. */
    private static final String ACTIVE = "active = true order by code";

    /**
     * Builds an island holding the given raw terminal list.
     *
     * @param code the island code
     * @param terminalIds the raw semicolon list, or null
     * @return the island
     */
    private CheckoutIsland island(String code, String terminalIds) {
        CheckoutIsland island = new CheckoutIsland();
        island.code = code;
        island.label = "Ligne " + code;
        island.terminalIds = terminalIds;
        return island;
    }

    /**
     * The attached registers are split, trimmed, and the empty entries a
     * hand-typed list leaves behind are dropped.
     */
    @Test
    void theAttachedRegistersAreSplitAndTrimmed() {
        assertEquals(List.of("POS01", "POS02", "POS03"),
                island("AVANT", " POS01 ; POS02;;POS03 ; ").terminals());
    }

    /**
     * An island holding no register answers an empty list, whether the column
     * is null or blank (the two legs of the same guard).
     */
    @Test
    void anIslandWithoutRegistersHoldsNone() {
        assertTrue(island("AVANT", null).terminals().isEmpty());
        assertTrue(island("AVANT", "   ").terminals().isEmpty());
        assertTrue(island("AVANT", ";;;").terminals().isEmpty());
    }

    /**
     * {@code holds} recognises an attached register, trimming what it is given,
     * and refuses one the island does not list.
     */
    @Test
    void holdsRecognisesAnAttachedRegister() {
        CheckoutIsland island = island("AVANT", "POS01;POS02");
        assertTrue(island.holds("POS01"));
        assertTrue(island.holds("  POS02  "));
        assertFalse(island.holds("POS09"));
    }

    /**
     * A null or blank register belongs to no island (the two legs of the first
     * guard).
     */
    @Test
    void anUnnamedRegisterBelongsToNoIsland() {
        CheckoutIsland island = island("AVANT", "POS01");
        assertFalse(island.holds(null));
        assertFalse(island.holds("   "));
    }

    /**
     * {@code findByTerminal} answers the active island listing the register,
     * skipping a null row and an island that does not list it.
     */
    @Test
    void findByTerminalAnswersTheIslandOfTheRegister() {
        CheckoutIsland other = island("COMPTOIR", "POS08;POS09");
        CheckoutIsland wanted = island("AVANT", "POS01;POS02");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutIsland.list(ACTIVE))
                    .thenReturn(java.util.Arrays.asList(null, other, wanted));
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
        CheckoutIsland island = island("AVANT", "POS01");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutIsland.list(ACTIVE)).thenReturn(List.of(island));
            assertEquals(List.of(island), CheckoutIsland.listActive());
        }
    }

    /**
     * A fresh island is in service: a store creating one means to use it.
     */
    @Test
    void aFreshIslandIsInService() {
        assertTrue(new CheckoutIsland().active);
    }
}
