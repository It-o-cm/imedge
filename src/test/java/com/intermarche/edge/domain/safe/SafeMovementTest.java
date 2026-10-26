package com.intermarche.edge.domain.safe;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link SafeMovement}.
 *
 * <p>The row of the ledger the whole safe is read from. The sign is asserted on
 * both directions and on the kindless row, the VAT total on its null and empty
 * arms, and {@code isCorrectable} on all four combinations of its compound guard
 * — pending or validated, crossed with an open, a closed and an absent period.
 */
class SafeMovementTest {

    /**
     * Builds a movement of a kind and an amount.
     *
     * @param type the kind, possibly null
     * @param amount the amount, as a plain decimal string, null for none
     * @return the movement
     */
    private SafeMovement movement(SafeMovementType type, String amount) {
        SafeMovement movement = new SafeMovement();
        movement.type = type;
        movement.amount = amount == null ? null : new BigDecimal(amount);
        movement.label = "Fournitures";
        movement.movementDate = LocalDate.of(2026, 9, 15);
        return movement;
    }

    /**
     * Builds a period in a given state.
     *
     * @param open true for an open period
     * @return the period
     */
    private AccountingPeriod period(boolean open) {
        AccountingPeriod period = new AccountingPeriod();
        period.status = open
                ? AccountingPeriod.PeriodStatus.OPEN : AccountingPeriod.PeriodStatus.CLOSED;
        return period;
    }

    /**
     * A movement is entered PENDING, with nothing validated.
     */
    @Test
    void amovementIsEnteredPending() {
        SafeMovement movement = movement(SafeMovementType.EXPENSE, "40.00");
        assertEquals(SafeMovement.MovementStatus.PENDING, movement.status);
        assertFalse(movement.isValidated());
    }

    /**
     * A debit subtracts and a credit adds — the two arms of the sign.
     */
    @Test
    void adebitSubtractsAndACreditAdds() {
        assertEquals(new BigDecimal("-40.00"),
                movement(SafeMovementType.EXPENSE, "40.00").signedAmount());
        assertEquals(new BigDecimal("40.00"),
                movement(SafeMovementType.RECEIPT, "40.00").signedAmount());
    }

    /**
     * A row carrying NO amount or NO kind is worth nothing and does not explode
     * — the two null arms of the sign.
     */
    @Test
    void arowWithoutAmountOrKindIsWorthNothing() {
        assertEquals(BigDecimal.ZERO, movement(SafeMovementType.EXPENSE, null).signedAmount());
        assertEquals(new BigDecimal("40.00"), movement(null, "40.00").signedAmount());
    }

    /**
     * The signed amount is what the journal prints.
     */
    @Test
    void thesignedAmountIsWhatTheJournalPrints() {
        assertEquals("-40.00",
                movement(SafeMovementType.EXPENSE, "40.00").getSignedAmountFormatted());
        assertEquals("40.00", movement(SafeMovementType.RECEIPT, "40").getSignedAmountFormatted());
    }

    /**
     * The VAT total sums the lines, tax included; the VAT amount sums their
     * VAT alone, a line without VAT and a movement without lines counting zero.
     */
    @Test
    void thevatTotalSumsTheLines() {
        SafeMovement movement = movement(SafeMovementType.EXPENSE, "120.00");
        movement.vatLines = new ArrayList<>(List.of(
                new SafeVatLine(new BigDecimal("20.00"), new BigDecimal("50.00"),
                        new BigDecimal("10.00")),
                new SafeVatLine(new BigDecimal("5.50"), new BigDecimal("55.00"),
                        new BigDecimal("5.00"))));
        assertEquals(new BigDecimal("120.00"), movement.vatTotal());
        assertEquals(new BigDecimal("15.00"), movement.vatAmount());
        movement.vatLines.add(new SafeVatLine(BigDecimal.ZERO, BigDecimal.ONE, null));
        assertEquals(new BigDecimal("15.00"), movement.vatAmount());
        movement.vatLines = null;
        assertEquals(BigDecimal.ZERO, movement.vatAmount());
    }

    /**
     * A movement bearing no VAT totals zero, whether the list is empty or null —
     * both arms of the guard.
     */
    @Test
    void amovementWithoutVatTotalsZero() {
        SafeMovement empty = movement(SafeMovementType.POUCH, "300.00");
        assertEquals(BigDecimal.ZERO, empty.vatTotal());
        empty.vatLines = null;
        assertEquals(BigDecimal.ZERO, empty.vatTotal());
    }

    /**
     * A pending movement of an open period may still be corrected, and so may
     * one attached to NO period — the true arms of both legs.
     */
    @Test
    void apendingMovementOfAnOpenPeriodIsCorrectable() {
        SafeMovement movement = movement(SafeMovementType.EXPENSE, "40.00");
        movement.period = period(true);
        assertTrue(movement.isCorrectable());
        movement.period = null;
        assertTrue(movement.isCorrectable());
    }

    /**
     * A VALIDATED movement is frozen by the pointing, even in an open period.
     */
    @Test
    void avalidatedMovementIsFrozenByThePointing() {
        SafeMovement movement = movement(SafeMovementType.EXPENSE, "40.00");
        movement.period = period(true);
        movement.status = SafeMovement.MovementStatus.VALIDATED;
        assertTrue(movement.isValidated());
        assertFalse(movement.isCorrectable());
    }

    /**
     * A movement of a CLOSED period is frozen by the closure, even if nobody
     * ever pointed it — the second leg alone.
     */
    @Test
    void amovementOfAClosedPeriodIsFrozenByTheClosure() {
        SafeMovement movement = movement(SafeMovementType.EXPENSE, "40.00");
        movement.period = period(false);
        assertEquals(SafeMovement.MovementStatus.PENDING, movement.status);
        assertFalse(movement.isCorrectable());
    }

    /**
     * The kind label is empty on a row carrying no kind — both arms.
     */
    @Test
    void thekindLabelIsEmptyWithoutAKind() {
        assertEquals("Dépense", movement(SafeMovementType.EXPENSE, "40.00").getTypeLabel());
        assertEquals("", movement(null, "40.00").getTypeLabel());
    }

    /**
     * The checksum hashes the identifying and financial fields, the VAT lines
     * counted rather than hashed — both arms of that guard.
     */
    @Test
    void thechecksumHashesTheSalientFields() {
        SafeMovement movement = movement(SafeMovementType.EXPENSE, "40.00");
        movement.reference = "FACT-12";
        int expected = Objects.hash(SafeMovementType.EXPENSE, new BigDecimal("40.00"),
                "Fournitures", LocalDate.of(2026, 9, 15), SafeMovement.MovementStatus.PENDING,
                "FACT-12", 0);
        assertEquals(expected, movement.getChecksum());
        movement.vatLines = null;
        assertEquals(expected, movement.getChecksum());
    }
}
