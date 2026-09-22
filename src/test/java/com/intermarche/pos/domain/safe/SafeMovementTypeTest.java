package com.intermarche.pos.domain.safe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link SafeMovementType}.
 *
 * <p>The direction belongs to the KIND and not to the row, which is what stops a
 * mis-keyed minus turning an expense into a receipt. Every constant is therefore
 * asserted on its three properties — direction, VAT-bearing, offered for entry —
 * one by one rather than by a rule the enum could quietly stop following.
 */
class SafeMovementTypeTest {

    /**
     * The eight kinds the safe knows, and nothing else.
     */
    @Test
    void theSafeKnowsEightKindsOfMovement() {
        assertEquals(8, SafeMovementType.values().length);
    }

    /**
     * The three kinds that take money out of the safe.
     */
    @Test
    void threeKindsTakeMoneyOut() {
        assertTrue(SafeMovementType.EXPENSE.isDebit());
        assertTrue(SafeMovementType.BANK_DEPOSIT.isDebit());
        assertFalse(SafeMovementType.EXPENSE.isCredit());
        assertFalse(SafeMovementType.BANK_DEPOSIT.isCredit());
    }

    /**
     * The kinds that bring money in.
     */
    @Test
    void theOtherKindsBringMoneyIn() {
        assertTrue(SafeMovementType.RECEIPT.isCredit());
        assertTrue(SafeMovementType.AUTOMATIC_RECEIPT.isCredit());
        assertTrue(SafeMovementType.COIN_DELIVERY.isCredit());
        assertTrue(SafeMovementType.POUCH.isCredit());
        assertTrue(SafeMovementType.INVENTORY_ADJUSTMENT.isCredit());
        assertTrue(SafeMovementType.OPENING_BALANCE.isCredit());
        assertFalse(SafeMovementType.RECEIPT.isDebit());
    }

    /**
     * Only the kinds that state a purchase or a sale bear VAT: a pouch carries
     * takings whose VAT the tickets already state, and declaring it again here
     * would count it twice.
     */
    @Test
    void onlyThePurchasesAndSalesBearVat() {
        assertTrue(SafeMovementType.EXPENSE.isVatBearing());
        assertTrue(SafeMovementType.RECEIPT.isVatBearing());
        assertTrue(SafeMovementType.AUTOMATIC_RECEIPT.isVatBearing());
        assertFalse(SafeMovementType.POUCH.isVatBearing());
        assertFalse(SafeMovementType.BANK_DEPOSIT.isVatBearing());
        assertFalse(SafeMovementType.COIN_DELIVERY.isVatBearing());
        assertFalse(SafeMovementType.INVENTORY_ADJUSTMENT.isVatBearing());
        assertFalse(SafeMovementType.OPENING_BALANCE.isVatBearing());
    }

    /**
     * Only the expense and the hand-entered receipt are offered on the movement
     * screen: the integrated receipt bears VAT and is still not typed by anybody.
     */
    @Test
    void onlyTwoKindsAreOfferedForEntry() {
        assertTrue(SafeMovementType.EXPENSE.isManual());
        assertTrue(SafeMovementType.RECEIPT.isManual());
        assertFalse(SafeMovementType.AUTOMATIC_RECEIPT.isManual());
        assertFalse(SafeMovementType.POUCH.isManual());
        assertFalse(SafeMovementType.BANK_DEPOSIT.isManual());
        assertFalse(SafeMovementType.COIN_DELIVERY.isManual());
        assertFalse(SafeMovementType.INVENTORY_ADJUSTMENT.isManual());
        assertFalse(SafeMovementType.OPENING_BALANCE.isManual());
    }

    /**
     * Every kind carries an operator-facing label.
     */
    @Test
    void everyKindCarriesALabel() {
        assertEquals("Dépense", SafeMovementType.EXPENSE.getLabel());
        assertEquals("Remise au coffre", SafeMovementType.POUCH.getLabel());
        for (SafeMovementType type : SafeMovementType.values()) {
            assertFalse(type.getLabel() == null || type.getLabel().isBlank());
        }
    }

    /**
     * The name lookup resolves a stored name, padding tolerated, and answers
     * null on a null or unknown one — the three legs of its guard.
     */
    @Test
    void theNameLookupResolvesAStoredNameAndNothingElse() {
        assertSame(SafeMovementType.EXPENSE, SafeMovementType.fromName("EXPENSE"));
        assertSame(SafeMovementType.EXPENSE, SafeMovementType.fromName("  EXPENSE "));
        assertNull(SafeMovementType.fromName(null));
        assertNull(SafeMovementType.fromName("DONATION"));
    }
}
