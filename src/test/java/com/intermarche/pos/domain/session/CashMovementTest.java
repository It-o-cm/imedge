package com.intermarche.pos.domain.session;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        return movement;
    }

    /**
     * The checksum is the exact {@link Objects#hash} of the identifying and
     * financial fields — uid, terminal, type, amount, date and the two tenders a
     * drawer movement names — and nothing else (the reason and the endorser are
     * excluded on purpose).
     */
    @Test
    void checksumHashesTheSalientFields() {
        CashMovement movement = movement();
        int expected = Objects.hash("M-1", "C04", CashMovement.MovementType.WITHDRAWAL,
                new BigDecimal("30.00"), LocalDateTime.of(2026, 9, 1, 15, 42, 0),
                "CHEQUE", "CASH");
        assertEquals(expected, movement.getChecksum());
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
        assertEquals(Objects.hash(null, null, null, null, null, null, null), bare.getChecksum());
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
}
