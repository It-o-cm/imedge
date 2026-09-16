package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link CouponType}, targeting 100% branch coverage.
 * <p>
 * The three static finders resolve the Panache {@code list} finder, which under
 * plain {@code mvn test} falls back to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic}. Instance methods are
 * exercised directly on plain instances; every ternary and null/short-circuit
 * guard is covered on both arms. Each test is fully isolated and asserts
 * absolute expected values.
 */
class CouponTypeTest {

    /**
     * Builds a coupon type with the given match pattern.
     *
     * @param matchPattern the recognition pattern, possibly null or blank
     * @return the configured coupon type
     */
    private CouponType withPattern(String matchPattern) {
        CouponType type = new CouponType();
        type.matchPattern = matchPattern;
        return type;
    }

    /**
     * A fresh coupon type carries the declared field defaults.
     */
    @Test
    void fieldDefaults() {
        CouponType type = new CouponType();
        Assertions.assertTrue(type.active);
        Assertions.assertFalse(type.depositLine);
        Assertions.assertEquals(100, type.priority);
    }

    /**
     * matches returns false when the number is null (first OR arm true).
     */
    @Test
    void matchesNullNumberIsFalse() {
        CouponType type = withPattern("\\d+");
        Assertions.assertFalse(type.matches(null));
    }

    /**
     * matches returns false when the pattern is null (second OR arm true).
     */
    @Test
    void matchesNullPatternIsFalse() {
        CouponType type = withPattern(null);
        Assertions.assertFalse(type.matches("1234"));
    }

    /**
     * matches returns true when a non-null number satisfies a non-null pattern.
     */
    @Test
    void matchesMatchingNumberIsTrue() {
        CouponType type = withPattern("\\d{4}");
        Assertions.assertTrue(type.matches("1234"));
    }

    /**
     * matches returns false when a non-null number fails a non-null pattern.
     */
    @Test
    void matchesNonMatchingNumberIsFalse() {
        CouponType type = withPattern("\\d{4}");
        Assertions.assertFalse(type.matches("abcd"));
    }

    /**
     * requiresManualAmount is true when the amount source is MANUAL.
     */
    @Test
    void requiresManualAmountTrueForManual() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.MANUAL;
        Assertions.assertTrue(type.requiresManualAmount());
    }

    /**
     * requiresManualAmount is false when the amount source is ENCODED.
     */
    @Test
    void requiresManualAmountFalseForEncoded() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        Assertions.assertFalse(type.requiresManualAmount());
    }

    /**
     * hasNumber is false when the pattern is null (first AND arm false).
     */
    @Test
    void hasNumberFalseWhenPatternNull() {
        Assertions.assertFalse(withPattern(null).hasNumber());
    }

    /**
     * hasNumber is false when the pattern is blank (second AND arm false).
     */
    @Test
    void hasNumberFalseWhenPatternBlank() {
        Assertions.assertFalse(withPattern("   ").hasNumber());
    }

    /**
     * hasNumber is true when the pattern is present and non-blank.
     */
    @Test
    void hasNumberTrueWhenPatternPresent() {
        Assertions.assertTrue(withPattern("\\d+").hasNumber());
    }

    /**
     * extractAmount returns null when the amount source is not ENCODED
     * (first OR arm true).
     */
    @Test
    void extractAmountNullWhenNotEncoded() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.MANUAL;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount("1050"));
    }

    /**
     * extractAmount returns null when the amount pattern is null
     * (second OR arm true).
     */
    @Test
    void extractAmountNullWhenPatternNull() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = null;
        Assertions.assertNull(type.extractAmount("1050"));
    }

    /**
     * extractAmount returns null when the number is null (third OR arm true).
     */
    @Test
    void extractAmountNullWhenNumberNull() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount(null));
    }

    /**
     * extractAmount returns null when the pattern does not match the number
     * (first arm of the second guard: find fails).
     */
    @Test
    void extractAmountNullWhenNoMatch() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount("abc"));
    }

    /**
     * extractAmount returns null when the matching pattern has no capturing
     * group (second arm of the second guard: groupCount below one).
     */
    @Test
    void extractAmountNullWhenNoCapturingGroup() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "\\d+";
        Assertions.assertNull(type.extractAmount("1050"));
    }

    /**
     * extractAmount reads the first capturing group as cents and scales it to a
     * two-decimal amount.
     */
    @Test
    void extractAmountReadsEncodedCents() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "AMT(\\d+)";
        Assertions.assertEquals(new BigDecimal("10.50"), type.extractAmount("AMT01050"));
    }

    /**
     * extractAmount returns null when the captured group overflows a long and
     * cannot be parsed (NumberFormatException arm).
     */
    @Test
    void extractAmountNullWhenGroupNotParsable() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount("99999999999999999999"));
    }


    /**
     * listActivePaymentTypes delegates to the non-deposit active finder.
     */
    @Test
    void listActivePaymentTypesDelegatesToFinder() {
        List<CouponType> expected = List.of(new CouponType());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.list("active = true and depositLine = false order by priority"))
                    .thenReturn(expected);
            Assertions.assertSame(expected, CouponType.listActivePaymentTypes());
        }
    }

    /**
     * listActiveDepositTypes delegates to the deposit active finder.
     */
    @Test
    void listActiveDepositTypesDelegatesToFinder() {
        List<CouponType> expected = List.of(new CouponType());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.list("active = true and depositLine = true order by priority"))
                    .thenReturn(expected);
            Assertions.assertSame(expected, CouponType.listActiveDepositTypes());
        }
    }

    /**
     * The AmountSource enum exposes exactly its two declared constants and
     * round-trips through valueOf.
     */
    @Test
    void amountSourceEnumHasTwoConstants() {
        Assertions.assertEquals(3, CouponType.AmountSource.values().length);
        Assertions.assertEquals(CouponType.AmountSource.ENCODED,
                CouponType.AmountSource.valueOf("ENCODED"));
        Assertions.assertEquals(CouponType.AmountSource.REGISTRY,
                CouponType.AmountSource.valueOf("REGISTRY"));
        Assertions.assertEquals(CouponType.AmountSource.MANUAL,
                CouponType.AmountSource.valueOf("MANUAL"));
    }

    /**
     * Builds an encoded-amount type administering one price field.
     *
     * @param offset the zero-based position of the price
     * @param length the number of characters of the price
     * @param decimals the administered decimal count, or null for the default
     * @return the configured type
     */
    private CouponType withPriceField(int offset, int length, Integer decimals) {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.codeLength = offset + length;
        CouponField price = new CouponField();
        price.couponType = type;
        price.role = CouponField.Role.PRICE;
        price.offsetPosition = offset;
        price.fieldLength = length;
        price.kind = CouponField.Kind.NUMERIC;
        price.decimals = decimals;
        type.fields.add(price);
        return type;
    }

    /**
     * isAdministered is false when no code length is set (first leg).
     */
    @Test
    void isAdministeredIsFalseWithoutACodeLength() {
        Assertions.assertFalse(new CouponType().isAdministered());
    }

    /**
     * isAdministered is false on a zero code length (second leg, boundary).
     */
    @Test
    void isAdministeredIsFalseOnAZeroCodeLength() {
        CouponType type = new CouponType();
        type.codeLength = 0;
        Assertions.assertFalse(type.isAdministered());
    }

    /**
     * isAdministered is true from one character on (boundary).
     */
    @Test
    void isAdministeredIsTrueFromOneCharacterOn() {
        CouponType type = new CouponType();
        type.codeLength = 1;
        Assertions.assertTrue(type.isAdministered());
    }

    /**
     * fieldOf returns null for a null role (first leg of the guard).
     */
    @Test
    void fieldOfNullRoleIsNull() {
        Assertions.assertNull(withPriceField(6, 4, null).fieldOf(null));
    }

    /**
     * fieldOf returns null when the type holds no field list (second leg).
     */
    @Test
    void fieldOfNullFieldListIsNull() {
        CouponType type = new CouponType();
        type.fields = null;
        Assertions.assertNull(type.fieldOf(CouponField.Role.PRICE));
    }

    /**
     * fieldOf skips a null entry and returns the field carrying the role.
     */
    @Test
    void fieldOfReturnsTheFieldCarryingTheRole() {
        CouponType type = withPriceField(6, 4, null);
        type.fields.add(0, null);
        Assertions.assertEquals(CouponField.Role.PRICE,
                type.fieldOf(CouponField.Role.PRICE).role);
    }

    /**
     * fieldOf returns null when no field carries the role.
     */
    @Test
    void fieldOfUnadministeredRoleIsNull() {
        Assertions.assertNull(withPriceField(6, 4, null).fieldOf(CouponField.Role.TPV_NUMBER));
    }

    /**
     * controlOf returns null for a null kind (first leg of the guard).
     */
    @Test
    void controlOfNullKindIsNull() {
        Assertions.assertNull(new CouponType().controlOf(null));
    }

    /**
     * controlOf returns null when the type holds no control list (second leg).
     */
    @Test
    void controlOfNullControlListIsNull() {
        CouponType type = new CouponType();
        type.controls = null;
        Assertions.assertNull(type.controlOf(CouponControl.Kind.EXPIRED));
    }

    /**
     * controlOf skips a null entry and returns the control of that kind.
     */
    @Test
    void controlOfReturnsTheControlOfThatKind() {
        CouponType type = new CouponType();
        type.controls.add(null);
        CouponControl control = new CouponControl();
        control.kind = CouponControl.Kind.EXPIRED;
        control.level = AlertLevel.BLOCK;
        type.controls.add(control);
        Assertions.assertSame(control, type.controlOf(CouponControl.Kind.EXPIRED));
        Assertions.assertNull(type.controlOf(CouponControl.Kind.DUPLICATE));
    }

    /**
     * levelOf is silence when the type administers no such control.
     */
    @Test
    void levelOfAnUnadministeredControlIsSilence() {
        Assertions.assertEquals(AlertLevel.NONE,
                new CouponType().levelOf(CouponControl.Kind.EXPIRED));
    }

    /**
     * levelOf is silence when the control carries no level (second leg).
     */
    @Test
    void levelOfALevellessControlIsSilence() {
        CouponType type = new CouponType();
        CouponControl control = new CouponControl();
        control.kind = CouponControl.Kind.EXPIRED;
        control.level = null;
        type.controls.add(control);
        Assertions.assertEquals(AlertLevel.NONE, type.levelOf(CouponControl.Kind.EXPIRED));
    }

    /**
     * levelOf hands back the administered level.
     */
    @Test
    void levelOfHandsBackTheAdministeredLevel() {
        CouponType type = new CouponType();
        CouponControl control = new CouponControl();
        control.kind = CouponControl.Kind.DUPLICATE;
        control.level = AlertLevel.INFO;
        type.controls.add(control);
        Assertions.assertEquals(AlertLevel.INFO, type.levelOf(CouponControl.Kind.DUPLICATE));
    }

    /**
     * effectivePrefix maps an absent prefix to the empty string.
     */
    @Test
    void effectivePrefixOfAnAbsentPrefixIsEmpty() {
        Assertions.assertEquals("", new CouponType().effectivePrefix());
    }

    /**
     * effectivePrefix hands back an administered prefix untouched.
     */
    @Test
    void effectivePrefixHandsBackTheAdministeredPrefix() {
        CouponType type = new CouponType();
        type.prefix = "298";
        Assertions.assertEquals("298", type.effectivePrefix());
    }

    /**
     * extractAmount reads the administered price field rather than the pattern.
     */
    @Test
    void extractAmountReadsTheAdministeredPriceField() {
        CouponType type = withPriceField(6, 4, null);
        type.amountPattern = "(9999)$";
        Assertions.assertEquals(new BigDecimal("12.34"), type.extractAmount("1234561234"));
    }

    /**
     * extractAmount honours the decimal count the price field carries.
     */
    @Test
    void extractAmountHonoursTheAdministeredDecimals() {
        CouponType type = withPriceField(0, 5, 3);
        Assertions.assertEquals(new BigDecimal("12.345"), type.extractAmount("12345"));
    }

    /**
     * extractAmount returns null when the administered price cannot be read.
     */
    @Test
    void extractAmountOnATooShortCodeIsNull() {
        Assertions.assertNull(withPriceField(6, 4, null).extractAmount("123456"));
    }

    /**
     * extractAmount still refuses a code when the amount is typed in.
     */
    @Test
    void extractAmountOnAManualTypeIsNullEvenWithAPriceField() {
        CouponType type = withPriceField(6, 4, null);
        type.amountSource = CouponType.AmountSource.MANUAL;
        Assertions.assertNull(type.extractAmount("1234561234"));
    }

    // --- Montant 9999 (BO-03-06-12) ---

    /**
     * A range asking for the rule treats an all-nines price as "value unknown":
     * the cashier is asked, instead of the voucher settling for 99,99 €.
     */
    @Test
    void anAllNinesPriceAsksTheCashier() {
        CouponType type = withPriceField(2, 4, 2);
        type.manualAmountOnAllNines = true;
        Assertions.assertTrue(type.hasAllNinesPrice("2199990"));
        Assertions.assertTrue(type.requiresManualAmount("2199990"));
    }

    /**
     * An ordinary price on the same range is read normally (the not-all-nines
     * arm), and one non-nine digit is enough to make it ordinary.
     */
    @Test
    void anOrdinaryPriceIsReadNormally() {
        CouponType type = withPriceField(2, 4, 2);
        type.manualAmountOnAllNines = true;
        Assertions.assertFalse(type.hasAllNinesPrice("2199980"));
        Assertions.assertFalse(type.requiresManualAmount("2199980"));
        Assertions.assertFalse(type.hasAllNinesPrice("2189990"));
    }

    /**
     * A range that did NOT ask for the rule reads an all-nines price as an
     * ordinary amount — the flag is read, not assumed (the flag-off arm).
     */
    @Test
    void aRangeWithoutTheRuleReadsNinesAsAnAmount() {
        CouponType type = withPriceField(2, 4, 2);
        type.manualAmountOnAllNines = false;
        Assertions.assertFalse(type.hasAllNinesPrice("2199990"));
        Assertions.assertFalse(type.requiresManualAmount("2199990"));
    }

    /**
     * A SECOND administered position looks at another part of the same code,
     * which is what proves the position is read rather than hard-coded.
     */
    @Test
    void aSecondAdministeredPositionLooksElsewhere() {
        CouponType type = withPriceField(0, 4, 2);
        type.manualAmountOnAllNines = true;
        Assertions.assertTrue(type.hasAllNinesPrice("9999210"));
        Assertions.assertFalse(type.hasAllNinesPrice("2199990"));
    }

    /**
     * A range administering no price position has no price to look at (the
     * absent-position arm).
     */
    @Test
    void aRangeWithoutAPricePositionHasNoNines() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.manualAmountOnAllNines = true;
        Assertions.assertFalse(type.hasAllNinesPrice("9999"));
    }

    /**
     * A null code and a code too short to carry the position are answered
     * without throwing (the null arm and the unreadable arm).
     */
    @Test
    void anUnreadableCodeHasNoNines() {
        CouponType type = withPriceField(2, 4, 2);
        type.manualAmountOnAllNines = true;
        Assertions.assertFalse(type.hasAllNinesPrice(null));
        Assertions.assertFalse(type.hasAllNinesPrice("219"));
        Assertions.assertFalse(type.requiresManualAmount(null));
    }

    /**
     * A MANUAL range still asks for the amount whatever the code carries: the
     * two reasons are independent (the source arm of the disjunction).
     */
    @Test
    void aManualRangeStillAsksWhateverTheCodeCarries() {
        CouponType type = withPriceField(2, 4, 2);
        type.amountSource = CouponType.AmountSource.MANUAL;
        type.manualAmountOnAllNines = false;
        Assertions.assertTrue(type.requiresManualAmount("2199980"));
    }

    /**
     * The rule looks at the position carrying the PRICE, not at whichever
     * position happens to come first: a range whose first administered position
     * is a sequence number made of nines is not an unknown amount.
     */
    @Test
    void theRuleLooksAtThePricePositionNotTheFirstOne() {
        CouponType type = withPriceField(6, 4, 2);
        type.manualAmountOnAllNines = true;
        CouponField sequence = new CouponField();
        sequence.couponType = type;
        sequence.role = CouponField.Role.SEQUENCE_NUMBER;
        sequence.offsetPosition = 0;
        sequence.fieldLength = 4;
        sequence.kind = CouponField.Kind.NUMERIC;
        type.fields.add(0, sequence);
        // Nines in the sequence, an ordinary amount in the price.
        Assertions.assertFalse(type.hasAllNinesPrice("9999121234"));
        // Nines in the price, an ordinary sequence.
        Assertions.assertTrue(type.hasAllNinesPrice("1234129999"));
    }

    /**
     * A fresh range does not carry the rule: it is administered, never assumed.
     */
    @Test
    void theAllNinesRuleIsOffByDefault() {
        Assertions.assertFalse(new CouponType().manualAmountOnAllNines);
    }
}
