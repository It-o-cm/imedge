package com.intermarche.edge.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StoredValue}.
 * <p>
 * The entity holds the registry's two contracts. The NUMBER DISCRIMINANT
 * ({@code isRegistryNumber}) decides, on a bare scanned string, whether the
 * register must ask the registry for a live balance or fall back on the
 * historical encoded-voucher path — so both its false arms and its exact
 * shape matter: a code one digit short, or bearing another prefix, must NOT
 * be taken for an instrument. The FINDER is the single door to a balance;
 * it is intercepted here with {@link org.mockito.Mockito#mockStatic} on
 * {@link PanacheEntityBase}, since entities are un-enhanced under plain
 * {@code mvn test}.
 * <p>
 * Note the discriminant is a SHAPE test only: it says "this looks like a
 * registry number", never "this instrument exists" — that second question
 * belongs to {@code findByNumber}, and conflating the two would let a forged
 * number pay.
 */
class StoredValueTest {

    /** A well-formed credit-note number (297 + 12 digits). */
    private static final String CREDIT_NOTE = "297000000000001";

    /** A well-formed gift-card number (296 + 12 digits). */
    private static final String GIFT_CARD = "296000000000042";

    // --------------------------------------------------
    // isRegistryNumber
    // --------------------------------------------------

    /**
     * A credit-note number is recognised: prefix 297 and twelve digits.
     */
    @Test
    void creditNoteNumberIsARegistryNumber() {
        assertTrue(StoredValue.isRegistryNumber(CREDIT_NOTE));
    }

    /**
     * A gift-card number is recognised too — the two instruments share the
     * registry and differ only by their prefix.
     */
    @Test
    void giftCardNumberIsARegistryNumber() {
        assertTrue(StoredValue.isRegistryNumber(GIFT_CARD));
    }

    /**
     * A NULL code is not a registry number (first leg of the guard): the
     * discriminant is called on whatever the scanner produced, including
     * nothing at all.
     */
    @Test
    void nullIsNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber(null));
    }

    /**
     * An EMPTY code is not one either.
     */
    @Test
    void emptyStringIsNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber(""));
    }

    /**
     * A HISTORICAL encoded voucher (prefix 50) is NOT a registry number: its
     * value is encoded on the paper and replayable, which is precisely what
     * the registry exists to replace. Taking it for an instrument would send
     * the register looking for a balance that does not exist.
     */
    @Test
    void historicalEncodedVoucherIsNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber("500000000000001"));
    }

    /**
     * A number ONE DIGIT SHORT is refused: the shape is exact, and a
     * truncated scan must fall through rather than query the registry with a
     * mangled identifier.
     */
    @Test
    void tooShortNumberIsNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber("29700000000001"));
    }

    /**
     * A number ONE DIGIT TOO LONG is refused as well — the pattern is
     * anchored at both ends, so a scanner that appended a check digit does
     * not silently address another row.
     */
    @Test
    void tooLongNumberIsNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber("2970000000000012"));
    }

    /**
     * NON-DIGIT characters are refused: the twelve trailing characters are
     * the registry row id, zero-padded, and nothing else.
     */
    @Test
    void nonDigitCharactersMakeItNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber("29700000000000X"));
        assertFalse(StoredValue.isRegistryNumber("297 00000000001"));
    }

    /**
     * A number carrying the right prefix in the MIDDLE is refused: the match
     * is on the whole string, so an EAN that happens to contain 297 is not
     * mistaken for an instrument.
     */
    @Test
    void aPrefixInTheMiddleIsNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber("1297000000000001"));
    }

    /**
     * Another 29x prefix is refused: only 296 and 297 belong to the registry,
     * and a neighbouring range must stay available for other uses.
     */
    @Test
    void aNeighbouringPrefixIsNotARegistryNumber() {
        assertFalse(StoredValue.isRegistryNumber("295000000000001"));
        assertFalse(StoredValue.isRegistryNumber("298000000000001"));
    }

    /**
     * The number built the way the register builds it — prefix plus the
     * zero-padded row id — is recognised. This is the round trip that
     * matters: what issuance writes, scanning must recognise.
     */
    @Test
    void theNumberBuiltFromARowIdIsRecognised() {
        String issued = StoredValue.GIFT_CARD_PREFIX + String.format("%012d", 42L);
        assertEquals("296000000000042", issued);
        assertTrue(StoredValue.isRegistryNumber(issued));
    }

    // --------------------------------------------------
    // findByNumber
    // --------------------------------------------------

    /**
     * The finder asks the registry BY NUMBER and hands back the instrument:
     * the single door through which a balance is read.
     */
    @Test
    void findByNumberReturnsTheInstrument() {
        StoredValue instrument = new StoredValue();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<StoredValue> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(instrument);
            panache.when(() -> StoredValue.find("number", CREDIT_NOTE)).thenReturn(query);

            assertSame(instrument, StoredValue.findByNumber(CREDIT_NOTE));
        }
    }

    /**
     * A number the registry never issued yields NULL — a forged or mistyped
     * number is simply unknown, which the payment path turns into its own
     * refusal message.
     */
    @Test
    void findByNumberReturnsNullWhenNeverIssued() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<StoredValue> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> StoredValue.find("number", "297999999999999")).thenReturn(query);

            assertNull(StoredValue.findByNumber("297999999999999"));
        }
    }

    // --------------------------------------------------
    // state and defaults
    // --------------------------------------------------

    /**
     * A fresh instrument is ACTIVE by default: an issued note or card is
     * redeemable the moment it exists, and nothing has to remember to arm it.
     */
    @Test
    void freshInstrumentIsActive() {
        StoredValue instrument = new StoredValue();
        assertEquals(StoredValue.Status.ACTIVE, instrument.status);
        assertNull(instrument.exhaustedAt);
        assertNull(instrument.lastRedeemedTicketId);
    }

    /**
     * A CREDIT NOTE is stamped with the refund that issued it, and carries no
     * issuing ticket — the two provenance fields are exclusive, which is what
     * lets an auditor trace a balance back to its origin.
     */
    @Test
    void aCreditNoteIsStampedWithItsRefund() {
        StoredValue note = new StoredValue();
        note.kind = StoredValue.Kind.CREDIT_NOTE;
        note.issuingRefundId = 12L;
        note.initialAmount = new BigDecimal("9.99");
        note.balance = new BigDecimal("9.99");
        note.issuedAt = LocalDateTime.of(2026, 8, 14, 10, 0);

        assertEquals(StoredValue.Kind.CREDIT_NOTE, note.kind);
        assertEquals(12L, note.issuingRefundId);
        assertNull(note.issuingTicketId);
    }

    /**
     * A GIFT CARD is stamped with the sale that issued it, and carries no
     * issuing refund.
     */
    @Test
    void aGiftCardIsStampedWithItsSale() {
        StoredValue card = new StoredValue();
        card.kind = StoredValue.Kind.GIFT_CARD;
        card.issuingTicketId = 5L;
        card.initialAmount = new BigDecimal("25.00");
        card.balance = new BigDecimal("25.00");

        assertEquals(StoredValue.Kind.GIFT_CARD, card.kind);
        assertEquals(5L, card.issuingTicketId);
        assertNull(card.issuingRefundId);
    }

    /**
     * An issued instrument starts with balance EQUAL to its face value: the
     * two fields diverge only as redemptions happen, and their gap is what an
     * audit reads as "already spent".
     */
    @Test
    void anIssuedInstrumentStartsAtItsFaceValue() {
        StoredValue card = new StoredValue();
        card.initialAmount = new BigDecimal("25.00");
        card.balance = card.initialAmount;
        assertEquals(0, card.initialAmount.compareTo(card.balance));
    }

    /**
     * The registry knows exactly TWO kinds and TWO statuses: a credit note
     * and a gift card, redeemable or dead. Any third value would need a
     * decision everywhere a balance is read.
     */
    @Test
    void theRegistryHasTwoKindsAndTwoStatuses() {
        assertEquals(2, StoredValue.Kind.values().length);
        assertEquals(2, StoredValue.Status.values().length);
        assertEquals(StoredValue.Kind.CREDIT_NOTE, StoredValue.Kind.valueOf("CREDIT_NOTE"));
        assertEquals(StoredValue.Kind.GIFT_CARD, StoredValue.Kind.valueOf("GIFT_CARD"));
        assertEquals(StoredValue.Status.ACTIVE, StoredValue.Status.valueOf("ACTIVE"));
        assertEquals(StoredValue.Status.EXHAUSTED, StoredValue.Status.valueOf("EXHAUSTED"));
    }

    /**
     * The two prefixes are DISTINCT and three characters long: the register
     * builds numbers from them and the scan chain routes on them, so a
     * collision would make a card indistinguishable from a note.
     */
    @Test
    void thePrefixesAreDistinctAndThreeCharacters() {
        assertEquals("297", StoredValue.CREDIT_NOTE_PREFIX);
        assertEquals("296", StoredValue.GIFT_CARD_PREFIX);
        assertNotEquals(StoredValue.CREDIT_NOTE_PREFIX, StoredValue.GIFT_CARD_PREFIX);
    }
}
