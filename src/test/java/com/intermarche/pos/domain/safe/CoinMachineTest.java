package com.intermarche.pos.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link CoinMachine}.
 *
 * <p>What the software believes and what was counted are two columns, and their
 * difference is the point. The variance is asserted on both arms of the counted
 * guard and on both arms of the declared one, and the three finders are
 * exercised through {@code PanacheEntityBase}, blank and null inputs included.
 */
class CoinMachineTest {

    /**
     * Builds a machine holding an amount.
     *
     * @param declared what the software believes, as a plain decimal string
     * @return the machine, never counted
     */
    private CoinMachine machine(String declared) {
        CoinMachine machine = new CoinMachine();
        machine.code = "MON-01";
        machine.label = "Monnayeur caisse 1";
        machine.terminalId = "C01";
        machine.declaredAmount = declared == null ? null : new BigDecimal(declared);
        return machine;
    }

    /**
     * A machine is declared in service, believed empty and never counted.
     */
    @Test
    void amachineIsDeclaredInServiceAndEmpty() {
        CoinMachine machine = new CoinMachine();
        assertTrue(machine.active);
        assertEquals(BigDecimal.ZERO, machine.declaredAmount);
        assertNull(machine.countedAmount);
        assertNull(machine.countedOn);
        assertFalse(machine.isReconciled());
    }

    /**
     * The variance is what was counted minus what the software believes.
     */
    @Test
    void thevarianceIsCountedMinusDeclared() {
        CoinMachine machine = machine("300.00");
        machine.countedAmount = new BigDecimal("290.00");
        assertEquals(new BigDecimal("-10.00"), machine.variance());
        assertEquals("-10.00", machine.getVarianceFormatted());
        assertFalse(machine.isReconciled());
    }

    /**
     * A machine never counted has no variance and is not reconciled — the null
     * arm of the counted guard.
     */
    @Test
    void amachineNeverCountedHasNoVariance() {
        CoinMachine machine = machine("300.00");
        assertEquals(BigDecimal.ZERO, machine.variance());
        assertFalse(machine.isReconciled());
    }

    /**
     * A machine whose count agrees with the software is reconciled.
     */
    @Test
    void amachineAgreeingWithTheSoftwareIsReconciled() {
        CoinMachine machine = machine("300.00");
        machine.countedAmount = new BigDecimal("300.00");
        assertTrue(machine.isReconciled());
        assertEquals("0.00", machine.getVarianceFormatted());
    }

    /**
     * A machine whose running total was never written reads as zero rather than
     * exploding — the null arm of the declared guard, on the variance and on the
     * formatting.
     */
    @Test
    void amachineWithoutARunningTotalReadsAsZero() {
        CoinMachine machine = machine(null);
        machine.countedAmount = new BigDecimal("40.00");
        assertEquals(new BigDecimal("40.00"), machine.variance());
        assertEquals("0.00", machine.getDeclaredAmountFormatted());
    }

    /**
     * The code finder resolves a machine, padding tolerated, and answers null on
     * a null or blank code — the three legs of its guard.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thecodeFinderResolvesAMachineAndNothingElse() {
        CoinMachine machine = machine("300.00");
        PanacheQuery<CoinMachine> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(machine);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.find("code", "MON-01")).thenReturn(query);
            assertSame(machine, CoinMachine.findByCode("  MON-01 "));
            assertNull(CoinMachine.findByCode(null));
            assertNull(CoinMachine.findByCode("   "));
        }
    }

    /**
     * The register finder asks for the machines of one register, and answers an
     * empty list on a null or blank identifier — the three legs of its guard.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theregisterFinderAsksForTheMachinesOfOneRegister() {
        CoinMachine machine = machine("300.00");
        PanacheQuery<CoinMachine> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(machine));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.find("terminalId = ?1 order by code", "C01"))
                    .thenReturn(query);
            assertEquals(List.of(machine), CoinMachine.ofTerminal(" C01 "));
            assertTrue(CoinMachine.ofTerminal(null).isEmpty());
            assertTrue(CoinMachine.ofTerminal("  ").isEmpty());
        }
    }

    /**
     * The park finder asks for every machine, in code order.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theparkFinderAsksForEveryMachine() {
        CoinMachine machine = machine("300.00");
        PanacheQuery<CoinMachine> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(machine));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinMachine.find("order by code")).thenReturn(query);
            assertEquals(List.of(machine), CoinMachine.listOrdered());
        }
    }

    /**
     * The checksum hashes the identifying and financial fields.
     */
    @Test
    void thechecksumHashesTheSalientFields() {
        CoinMachine machine = machine("300.00");
        machine.countedAmount = new BigDecimal("290.00");
        int expected = Objects.hash("MON-01", "Monnayeur caisse 1", "C01", true,
                new BigDecimal("300.00"), new BigDecimal("290.00"));
        assertEquals(expected, machine.getChecksum());
    }
}
