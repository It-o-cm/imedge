package com.intermarche.pos.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link CashPouch}.
 *
 * <p>The pouch is the step the register cannot take: the money walks to the safe
 * and somebody says so. Its state is asserted on both arms, its formatting on
 * the null and non-null amount, and its two finders through
 * {@code PanacheEntityBase}.
 */
class CashPouchTest {

    /**
     * Builds a pouch of a day's takings.
     *
     * @param amount the amount, as a plain decimal string, null for none
     * @return the pouch
     */
    private CashPouch pouch(String amount) {
        CashPouch pouch = new CashPouch();
        pouch.pouchNumber = "POCH-001";
        pouch.label = "Recettes du jour";
        pouch.accountingDay = LocalDate.of(2026, 9, 15);
        pouch.amount = amount == null ? null : new BigDecimal(amount);
        pouch.terminals = "C01,C02";
        return pouch;
    }

    /**
     * A pouch is declared IN THE SAFE and stays there until it is banked — the
     * two arms of the state.
     */
    @Test
    void apouchIsDeclaredInTheSafe() {
        CashPouch pouch = pouch("1250.00");
        assertEquals(CashPouch.PouchStatus.IN_SAFE, pouch.status);
        assertTrue(pouch.isInSafe());
        pouch.status = CashPouch.PouchStatus.BANKED;
        assertFalse(pouch.isInSafe());
    }

    /**
     * The amount is formatted with two decimals, and a pouch carrying none reads
     * as zero rather than exploding — both arms of the guard.
     */
    @Test
    void theamountIsFormattedOrReadsAsZero() {
        assertEquals("1250.00", pouch("1250").getAmountFormatted());
        assertEquals("0.00", pouch(null).getAmountFormatted());
    }

    /**
     * The available finder asks for the pouches still in the safe, oldest day
     * first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theavailableFinderAsksForThePouchesStillInTheSafe() {
        CashPouch held = pouch("1250.00");
        PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(held));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashPouch.find("status = ?1 order by accountingDay, id",
                    CashPouch.PouchStatus.IN_SAFE)).thenReturn(query);
            assertEquals(List.of(held), CashPouch.inSafe());
        }
    }

    /**
     * The history finder asks for every pouch, newest day first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thehistoryFinderAsksForEveryPouchNewestFirst() {
        CashPouch pouch = pouch("1250.00");
        PanacheQuery<CashPouch> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(pouch));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CashPouch.find("order by accountingDay desc, id desc"))
                    .thenReturn(query);
            assertEquals(List.of(pouch), CashPouch.listNewestFirst());
        }
    }

    /**
     * The checksum hashes the identifying and financial fields.
     */
    @Test
    void thechecksumHashesTheSalientFields() {
        CashPouch pouch = pouch("1250.00");
        int expected = Objects.hash("POCH-001", "Recettes du jour", LocalDate.of(2026, 9, 15),
                new BigDecimal("1250.00"), "C01,C02", CashPouch.PouchStatus.IN_SAFE);
        assertEquals(expected, pouch.getChecksum());
    }
}
