package com.intermarche.pos.domain.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PaymentTypes}.
 * <p>
 * Branch enumeration (100%): {@code forKey} covers the known-key arm (a class
 * is returned) and the unknown-key arm (null). {@code keys} exposes every
 * discriminator key in display order.
 */
class PaymentTypesTest {

    /**
     * A known key resolves to its entity class (known arm).
     */
    @Test
    void knownKeyResolvesToClass() {
        assertEquals(CardPayment.class, PaymentTypes.forKey("CARD"));
    }

    /**
     * An unknown key resolves to null (unknown arm).
     */
    @Test
    void unknownKeyResolvesToNull() {
        assertNull(PaymentTypes.forKey("BITCOIN"));
    }

    /**
     * The key set carries every known method in display order — the six original
     * ones, then customer credit, the legal cash rounding, foreign currency and
     * backup monetics, and finally the Step-6 tenders (TRD card, Resto Flash,
     * supplier gift card, SOGEC bon) and the deposit recovery of an order
     * recalled at the till (LC-06-03-07), each added at the end so the journal
     * filter of a shop that bookmarked its order does not shuffle under it.
     */
    @Test
    void keysAreTheKnownMethodsInDisplayOrder() {
        assertEquals("[CASH, CARD, CHEQUE, VOUCHER, FIDELITY, TR, CREDIT, ARRONDI, DEVISE, SECOURS, TRD, RESTOFLASH, GIFTCARD, SOGEC, ACOMPTE]",
                PaymentTypes.keys().toString());
        assertTrue(PaymentTypes.keys().contains("TR"));
    }

    /**
     * The deposit-recovery key resolves to its entity class (LC-06-03-07).
     */
    @Test
    void depositRecoveryKeyResolvesToClass() {
        assertEquals(DepositPayment.class, PaymentTypes.forKey("ACOMPTE"));
    }
}
