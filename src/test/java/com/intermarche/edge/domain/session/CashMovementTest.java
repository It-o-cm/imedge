package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link CashMovement}.
 * <p>
 * The entity is a branch-free data carrier: public fields, a six-value
 * {@link CashMovement.MovementType} enum and a single {@code getChecksum}
 * whose only logic is an {@link Objects#hash} of the salient business fields.
 * The tests pin the checksum to its exact expected value (so a silent
 * constant-hash regression — the RefundLine lesson — would fail), prove it
 * changes when a hashed field changes, and assert the enum's constants and
 * ordering, which drives no DB ordinal used elsewhere but is part of the
 * entity's public contract. No database and no Quarkus context is booted.
 */
class CashMovementTest {

    /**
     * Builds a fully populated movement.
     *
     * @return the movement fixture
     */
    private CashMovement movement() {
        CashMovement movement = new CashMovement();
        movement.movementUid = "M-1";
        movement.terminalId = "C04";
        movement.type = CashMovement.MovementType.WITHDRAWAL;
        movement.amount = new BigDecimal("30.00");
        movement.movementDate = LocalDateTime.of(2026, 9, 1, 15, 42, 0);
        movement.reason = "coffre";
        movement.endorsedBy = "11111111";
        movement.paymentMethod = "CHEQUE";
        movement.transferTo = "CASH";
        movement.orderReference = "CMD-9";
        movement.safeDeposit = true;
        return movement;
    }

    /**
     * The checksum is the exact {@link Objects#hash} of the identifying and
     * financial fields — uid, terminal, type, amount, date, the two tenders a
     * drawer movement names, the order reference (EXP-85) and the safe flag
     * (EXP-87) — and nothing else (the reason and the endorser are excluded on
     * purpose).
     */
    @Test
    void checksumHashesTheSalientFields() {
        CashMovement movement = movement();
        int expected = Objects.hash("M-1", "C04", CashMovement.MovementType.WITHDRAWAL,
                new BigDecimal("30.00"), LocalDateTime.of(2026, 9, 1, 15, 42, 0),
                "CHEQUE", "CASH", "CMD-9", true);
        assertEquals(expected, movement.getChecksum());
    }

    /**
     * A change to the order reference changes the checksum: a down-payment
     * imputed to another order is another movement (EXP-85).
     */
    @Test
    void checksumChangesWithOrderReference() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.orderReference = "CMD-10";
        assertNotEquals(before, movement.getChecksum());
    }

    /**
     * A change to the safe flag changes the checksum: a prélèvement destined to
     * the coffre is not the same movement as a plain one (EXP-87).
     */
    @Test
    void checksumChangesWithSafeDeposit() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.safeDeposit = false;
        assertNotEquals(before, movement.getChecksum());
    }

    /**
     * A change to the withdrawn tender changes the checksum: a withdrawal of the
     * cheques and a withdrawal of the cash are not the same movement, even for the
     * same amount on the same date.
     */
    @Test
    void checksumChangesWithPaymentMethod() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.paymentMethod = "TR";
        assertNotEquals(before, movement.getChecksum());
    }

    /**
     * A change to the transfer destination changes the checksum: where an amount
     * landed is part of what a transfer states.
     */
    @Test
    void checksumChangesWithTransferTo() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.transferTo = "TR";
        assertNotEquals(before, movement.getChecksum());
    }

    /**
     * A change to a hashed field (the amount) changes the checksum: the field
     * is not silently dropped from the hash.
     */
    @Test
    void checksumChangesWithAmount() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.amount = new BigDecimal("31.00");
        assertNotEquals(before, movement.getChecksum());
    }

    /**
     * The safe-deposit flag is false by default and read/write (EXP-87).
     */
    @Test
    void safeDepositFieldDefaultsFalseAndIsReadWrite() {
        CashMovement bare = new CashMovement();
        assertEquals(false, bare.safeDeposit);
        bare.safeDeposit = true;
        assertEquals(true, bare.safeDeposit);
    }

    /**
     * A change to a NON-hashed field (the reason) leaves the checksum
     * unchanged: the checksum tracks business identity, not free text.
     */
    @Test
    void checksumIgnoresReason() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.reason = "autre motif";
        assertEquals(before, movement.getChecksum());
    }

    /**
     * The checksum tolerates null fields (a bare movement) without throwing.
     */
    @Test
    void checksumToleratesNulls() {
        CashMovement bare = new CashMovement();
        assertEquals(Objects.hash(null, null, null, null, null, null, null, null, false), bare.getChecksum());
    }

    /**
     * {@code acompteTotalForOrder} returns ZERO for a null reference without
     * touching the database (guard true arm on the null test).
     */
    @Test
    void acompteTotalForOrderReturnsZeroForNullReference() {
        assertEquals(BigDecimal.ZERO, CashMovement.acompteTotalForOrder(null));
    }

    /**
     * {@code acompteTotalForOrder} returns ZERO for a blank reference without
     * touching the database (guard true arm on the isBlank test).
     */
    @Test
    void acompteTotalForOrderReturnsZeroForBlankReference() {
        assertEquals(BigDecimal.ZERO, CashMovement.acompteTotalForOrder("   "));
    }

    /**
     * {@code acompteTotalForOrder} sums the amounts of the matching down-payment
     * movements, skipping a movement whose amount is null (both arms of the inner
     * null guard), and queries by type and order reference.
     */
    @Test
    void acompteTotalForOrderSumsMatchingMovementsAndSkipsNullAmounts() {
        CashMovement first = new CashMovement();
        first.amount = new BigDecimal("40.00");
        CashMovement nullAmount = new CashMovement();
        nullAmount.amount = null;
        CashMovement second = new CashMovement();
        second.amount = new BigDecimal("15.50");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashMovement.list("type = ?1 and orderReference = ?2",
                    CashMovement.MovementType.CUSTOMER_DEPOSIT, "CMD-9"))
                    .thenReturn(List.of(first, nullAmount, second));
            assertEquals(new BigDecimal("55.50"), CashMovement.acompteTotalForOrder("CMD-9"));
        }
    }

    /**
     * The six movement types are exactly those the campaign enumerates, in a
     * stable declaration order.
     */
    @Test
    void movementTypeEnumeratesTheSixKinds() {
        assertEquals(6, CashMovement.MovementType.values().length);
        assertEquals(CashMovement.MovementType.WITHDRAWAL, CashMovement.MovementType.valueOf("WITHDRAWAL"));
        assertEquals(CashMovement.MovementType.DEPOSIT, CashMovement.MovementType.valueOf("DEPOSIT"));
        assertEquals(CashMovement.MovementType.EXPENSE, CashMovement.MovementType.valueOf("EXPENSE"));
        assertEquals(CashMovement.MovementType.CUSTOMER_DEPOSIT, CashMovement.MovementType.valueOf("CUSTOMER_DEPOSIT"));
        assertEquals(CashMovement.MovementType.DECLARATION, CashMovement.MovementType.valueOf("DECLARATION"));
        assertEquals(CashMovement.MovementType.TRANSFER, CashMovement.MovementType.valueOf("TRANSFER"));
    }

    /**
     * The tender key the register names the cash with is the one the drawer
     * screens and the movements share, so a withdrawal recorded on one side is
     * read on the other.
     */
    @Test
    void cashIsTheSharedTenderKey() {
        assertEquals("CASH", CashMovement.CASH);
    }

    /**
     * {@code acompteTotalForOrder} answers ZERO without a query for a null or a
     * blank order reference (both guard arms).
     */
    @Test
    void acompteTotalForOrderZeroWhenReferenceBlank() {
        assertEquals(0, CashMovement.acompteTotalForOrder(null).compareTo(BigDecimal.ZERO));
        assertEquals(0, CashMovement.acompteTotalForOrder("   ").compareTo(BigDecimal.ZERO));
    }

    /** LC-06-03: deposit totals include each non-null movement once and ignore rows without an amount. */
    @Test
    void orderDepositTotalSumsOnlyAvailableAmounts() {
        CashMovement paid = new CashMovement();
        paid.amount = new BigDecimal("12.50");
        CashMovement corrected = new CashMovement();
        corrected.amount = new BigDecimal("-2.50");
        CashMovement missing = new CashMovement();
        try (var base = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            base.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("type = ?1 and orderReference = ?2", CashMovement.MovementType.CUSTOMER_DEPOSIT, "ORDER")).thenReturn(java.util.List.of(paid, corrected, missing));
            assertEquals(new BigDecimal("10.00"), CashMovement.acompteTotalForOrder("ORDER"));
        }
    }
}
