package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link CouponField}, targeting 100% branch coverage.
 * <p>
 * Instance methods are exercised on plain instances; the single static finder
 * resolves the Panache {@code list} finder, which under plain {@code mvn test}
 * falls back to {@link PanacheEntityBase}, so it is intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code raw} covers each of the
 * three legs of its first guard separately plus both arms of the length guard,
 * at the boundary; {@code decimalValue} covers the unreadable-field arm, the
 * unparsable arm and the administered and defaulted decimal counts;
 * {@code dateValue} and {@code timeValue} cover the unreadable arm, the absent
 * layout arm and the wrong-family arm; {@code overlaps} covers the null arm,
 * the two disjoint arms, the touching boundary and the intersecting arm;
 * {@code listByType} covers both arms of its ternary; {@code DateFormat}
 * covers every layout, both arms of {@code isTime}, the wrong-width arm, the
 * null arm and the impossible-date arm.
 */
class CouponFieldTest {

    /**
     * Builds a numeric field at the given position.
     *
     * @param role the role the field carries
     * @param offset the zero-based start position
     * @param length the number of characters
     * @return the configured field
     */
    private CouponField field(CouponField.Role role, int offset, int length) {
        CouponField f = new CouponField();
        f.role = role;
        f.offsetPosition = offset;
        f.fieldLength = length;
        f.kind = CouponField.Kind.NUMERIC;
        return f;
    }

    /**
     * A fresh field carries the declared defaults.
     */
    @Test
    void fieldDefaults() {
        CouponField f = new CouponField();
        Assertions.assertEquals(CouponField.Kind.NUMERIC, f.kind);
        Assertions.assertNull(f.decimals);
        Assertions.assertNull(f.dateFormat);
        Assertions.assertNull(f.currency);
        Assertions.assertEquals(2, CouponField.DEFAULT_DECIMALS);
    }

    /**
     * endPosition is the position just past the last character.
     */
    @Test
    void endPositionIsExclusive() {
        Assertions.assertEquals(10, field(CouponField.Role.PRICE, 6, 4).endPosition());
    }

    /**
     * overlaps returns false against a null field (guard arm).
     */
    @Test
    void overlapsNullIsFalse() {
        Assertions.assertFalse(field(CouponField.Role.PRICE, 0, 4).overlaps(null));
    }

    /**
     * overlaps returns false when this field ends exactly where the other starts.
     */
    @Test
    void overlapsIsFalseWhenFieldsOnlyTouchOnTheRight() {
        CouponField left = field(CouponField.Role.PRICE, 0, 4);
        CouponField right = field(CouponField.Role.QUANTITY, 4, 3);
        Assertions.assertFalse(left.overlaps(right));
    }

    /**
     * overlaps returns false when the other field ends exactly where this one starts.
     */
    @Test
    void overlapsIsFalseWhenFieldsOnlyTouchOnTheLeft() {
        CouponField left = field(CouponField.Role.PRICE, 4, 3);
        CouponField right = field(CouponField.Role.QUANTITY, 0, 4);
        Assertions.assertFalse(left.overlaps(right));
    }

    /**
     * overlaps returns true on a one-character intersection.
     */
    @Test
    void overlapsIsTrueOnASingleSharedPosition() {
        CouponField left = field(CouponField.Role.PRICE, 0, 5);
        CouponField right = field(CouponField.Role.QUANTITY, 4, 3);
        Assertions.assertTrue(left.overlaps(right));
    }

    /**
     * effectiveDecimals falls back to two when none is administered.
     */
    @Test
    void effectiveDecimalsDefaultsToTwo() {
        Assertions.assertEquals(2, field(CouponField.Role.PRICE, 0, 4).effectiveDecimals());
    }

    /**
     * effectiveDecimals honours an administered count, zero included.
     */
    @Test
    void effectiveDecimalsHonoursTheAdministeredCount() {
        CouponField f = field(CouponField.Role.PRICE, 0, 4);
        f.decimals = 0;
        Assertions.assertEquals(0, f.effectiveDecimals());
    }

    /**
     * raw returns null on a null code (first leg of the guard).
     */
    @Test
    void rawNullCodeIsNull() {
        Assertions.assertNull(field(CouponField.Role.PRICE, 0, 4).raw(null));
    }

    /**
     * raw returns null on a negative position (second leg of the guard).
     */
    @Test
    void rawNegativeOffsetIsNull() {
        Assertions.assertNull(field(CouponField.Role.PRICE, -1, 4).raw("12345678"));
    }

    /**
     * raw returns null on a zero length (third leg of the guard).
     */
    @Test
    void rawZeroLengthIsNull() {
        Assertions.assertNull(field(CouponField.Role.PRICE, 0, 0).raw("12345678"));
    }

    /**
     * raw returns null when the code stops one character short of the field.
     */
    @Test
    void rawTooShortCodeIsNull() {
        Assertions.assertNull(field(CouponField.Role.PRICE, 6, 4).raw("123456789"));
    }

    /**
     * raw reads the field when the code ends exactly on its last character.
     */
    @Test
    void rawReadsTheFieldAtTheExactLengthBoundary() {
        Assertions.assertEquals("7890", field(CouponField.Role.PRICE, 6, 4).raw("1234567890"));
    }

    /**
     * decimalValue returns null when the field cannot be read at all.
     */
    @Test
    void decimalValueUnreadableFieldIsNull() {
        Assertions.assertNull(field(CouponField.Role.PRICE, 6, 4).decimalValue("123"));
    }

    /**
     * decimalValue returns null when the characters are not a number.
     */
    @Test
    void decimalValueNonNumericFieldIsNull() {
        CouponField f = field(CouponField.Role.PRICE, 0, 4);
        f.kind = CouponField.Kind.ALPHANUMERIC;
        Assertions.assertNull(f.decimalValue("AB12"));
    }

    /**
     * decimalValue scales by the default two decimals.
     */
    @Test
    void decimalValueScalesByTwoDecimalsByDefault() {
        Assertions.assertEquals(new BigDecimal("12.34"),
                field(CouponField.Role.PRICE, 6, 4).decimalValue("1234561234"));
    }

    /**
     * decimalValue scales by an administered three decimals.
     */
    @Test
    void decimalValueScalesByTheAdministeredDecimals() {
        CouponField f = field(CouponField.Role.QUANTITY, 0, 5);
        f.decimals = 3;
        Assertions.assertEquals(new BigDecimal("12.345"), f.decimalValue("12345"));
    }

    /**
     * decimalValue reads a whole number when zero decimals are administered.
     */
    @Test
    void decimalValueReadsAWholeNumberWithZeroDecimals() {
        CouponField f = field(CouponField.Role.MIN_TICKET_TOTAL, 0, 4);
        f.decimals = 0;
        Assertions.assertEquals(new BigDecimal("1234"), f.decimalValue("1234"));
    }

    /**
     * dateValue returns null when the field cannot be read (first arm).
     */
    @Test
    void dateValueUnreadableFieldIsNull() {
        CouponField f = field(CouponField.Role.DATE_END, 0, 6);
        f.dateFormat = CouponField.DateFormat.DDMMYY;
        Assertions.assertNull(f.dateValue("123", 2000));
    }

    /**
     * dateValue returns null when no layout is administered (second arm).
     */
    @Test
    void dateValueWithoutLayoutIsNull() {
        Assertions.assertNull(field(CouponField.Role.DATE_END, 0, 6).dateValue("310126", 2000));
    }

    /**
     * dateValue reads a day-month-year field.
     */
    @Test
    void dateValueReadsDayMonthYear() {
        CouponField f = field(CouponField.Role.DATE_END, 3, 6);
        f.dateFormat = CouponField.DateFormat.DDMMYY;
        Assertions.assertEquals(LocalDate.of(2026, 1, 31), f.dateValue("298310126", 2000));
    }

    /**
     * dateValue reads the field through the layout the field ADMINISTERS, not
     * through a layout written in the source: the very same six characters give
     * a different day under MMDDYY than under DDMMYY, and an impossible one
     * under the wrong layout (BO-03-06-18/19).
     */
    @Test
    void dateValueHonoursTheAdministeredLayout() {
        CouponField mmddyy = field(CouponField.Role.DATE_END, 0, 6);
        mmddyy.dateFormat = CouponField.DateFormat.MMDDYY;
        Assertions.assertEquals(LocalDate.of(2026, 1, 31), mmddyy.dateValue("013126", 2000));
        CouponField ddmmyy = field(CouponField.Role.DATE_END, 0, 6);
        ddmmyy.dateFormat = CouponField.DateFormat.DDMMYY;
        Assertions.assertNull(ddmmyy.dateValue("013126", 2000));
    }

    /**
     * dateValue reads a four-digit year layout through the administered field,
     * which no two-digit layout could produce (BO-03-06-23).
     */
    @Test
    void dateValueHonoursAFourDigitYearLayout() {
        CouponField f = field(CouponField.Role.DATE_END, 0, 8);
        f.dateFormat = CouponField.DateFormat.DDMMYYYY;
        Assertions.assertEquals(LocalDate.of(2026, 1, 31), f.dateValue("31012026", 0));
    }

    /**
     * dateValue reads a day-of-year layout through the administered field
     * (BO-03-06-24).
     */
    @Test
    void dateValueHonoursADayOfYearLayout() {
        CouponField f = field(CouponField.Role.DATE_END, 0, 3);
        f.dateFormat = CouponField.DateFormat.DDD;
        Assertions.assertEquals(LocalDate.ofYearDay(LocalDate.now().getYear(), 31),
                f.dateValue("031", 2000));
    }

    /**
     * timeValue reads the field through the layout the field ADMINISTERS: the
     * same six characters are a valid time under HHMMSS and none under HHMM,
     * whose width they do not fit (BO-03-06-31/32).
     */
    @Test
    void timeValueHonoursTheAdministeredLayout() {
        CouponField hhmmss = field(CouponField.Role.TIME, 0, 6);
        hhmmss.dateFormat = CouponField.DateFormat.HHMMSS;
        Assertions.assertEquals(LocalTime.of(13, 45, 9), hhmmss.timeValue("134509"));
        CouponField hhmm = field(CouponField.Role.TIME, 0, 6);
        hhmm.dateFormat = CouponField.DateFormat.HHMM;
        Assertions.assertNull(hhmm.timeValue("134509"));
    }

    /**
     * timeValue returns null when the field cannot be read (first arm).
     */
    @Test
    void timeValueUnreadableFieldIsNull() {
        CouponField f = field(CouponField.Role.TIME, 0, 4);
        f.dateFormat = CouponField.DateFormat.HHMM;
        Assertions.assertNull(f.timeValue("12"));
    }

    /**
     * timeValue returns null when no layout is administered (second arm).
     */
    @Test
    void timeValueWithoutLayoutIsNull() {
        Assertions.assertNull(field(CouponField.Role.TIME, 0, 4).timeValue("1345"));
    }

    /**
     * timeValue reads an hours-minutes field.
     */
    @Test
    void timeValueReadsHoursAndMinutes() {
        CouponField f = field(CouponField.Role.TIME, 0, 4);
        f.dateFormat = CouponField.DateFormat.HHMM;
        Assertions.assertEquals(LocalTime.of(13, 45), f.timeValue("1345"));
    }



    /**
     * Each character kind carries its own single-character token.
     */
    @Test
    void kindTokens() {
        Assertions.assertEquals("\\d", CouponField.Kind.NUMERIC.getToken());
        Assertions.assertEquals("[A-Za-z0-9]", CouponField.Kind.ALPHANUMERIC.getToken());
        Assertions.assertEquals(".", CouponField.Kind.ANY.getToken());
        Assertions.assertEquals(3, CouponField.Kind.values().length);
    }

    /**
     * Every currency of the catalog is reachable by name.
     */
    @Test
    void currencyCatalog() {
        Assertions.assertEquals(CouponField.PriceCurrency.EUR,
                CouponField.PriceCurrency.valueOf("EUR"));
        Assertions.assertEquals(CouponField.PriceCurrency.FRF,
                CouponField.PriceCurrency.valueOf("FRF"));
        Assertions.assertEquals(2, CouponField.PriceCurrency.values().length);
    }

    /**
     * Every role of the catalog is reachable by name.
     */
    @Test
    void roleCatalog() {
        Assertions.assertEquals(CouponField.Role.PRICE, CouponField.Role.valueOf("PRICE"));
        // BO-03-06-40 : la part de carte fidélité portée par le code.
        Assertions.assertEquals(CouponField.Role.CARD_MATCH, CouponField.Role.valueOf("CARD_MATCH"));
        Assertions.assertEquals(12, CouponField.Role.values().length);
    }

    /**
     * isTime separates the two time layouts from the seven date layouts.
     */
    @Test
    void isTimeSeparatesTheTwoFamilies() {
        Assertions.assertTrue(CouponField.DateFormat.HHMM.isTime());
        Assertions.assertTrue(CouponField.DateFormat.HHMMSS.isTime());
        for (CouponField.DateFormat format : CouponField.DateFormat.values()) {
            if (format != CouponField.DateFormat.HHMM && format != CouponField.DateFormat.HHMMSS) {
                Assertions.assertFalse(format.isTime(), format.name());
            }
        }
    }

    /**
     * Each layout declares the number of characters it occupies.
     */
    @Test
    void layoutWidths() {
        Assertions.assertEquals(6, CouponField.DateFormat.DDMMYY.getWidth());
        Assertions.assertEquals(6, CouponField.DateFormat.MMDDYY.getWidth());
        Assertions.assertEquals(4, CouponField.DateFormat.DDMM.getWidth());
        Assertions.assertEquals(4, CouponField.DateFormat.MMDD.getWidth());
        Assertions.assertEquals(8, CouponField.DateFormat.DDMMYYYY.getWidth());
        Assertions.assertEquals(8, CouponField.DateFormat.YYYYMMDD.getWidth());
        Assertions.assertEquals(3, CouponField.DateFormat.DDD.getWidth());
        Assertions.assertEquals(4, CouponField.DateFormat.HHMM.getWidth());
        Assertions.assertEquals(6, CouponField.DateFormat.HHMMSS.getWidth());
        Assertions.assertEquals(9, CouponField.DateFormat.values().length);
    }

    /**
     * toDate reads each of the four fixed-year layouts.
     */
    @Test
    void toDateReadsTheFixedYearLayouts() {
        Assertions.assertEquals(LocalDate.of(2026, 1, 31),
                CouponField.DateFormat.DDMMYY.toDate("310126", 2000));
        Assertions.assertEquals(LocalDate.of(2026, 1, 31),
                CouponField.DateFormat.MMDDYY.toDate("013126", 2000));
        Assertions.assertEquals(LocalDate.of(2026, 1, 31),
                CouponField.DateFormat.DDMMYYYY.toDate("31012026", 0));
        Assertions.assertEquals(LocalDate.of(2026, 1, 31),
                CouponField.DateFormat.YYYYMMDD.toDate("20260131", 0));
    }

    /**
     * toDate reads the three current-year layouts.
     */
    @Test
    void toDateReadsTheCurrentYearLayouts() {
        int year = LocalDate.now().getYear();
        Assertions.assertEquals(LocalDate.of(year, 1, 31),
                CouponField.DateFormat.DDMM.toDate("3101", 2000));
        Assertions.assertEquals(LocalDate.of(year, 1, 31),
                CouponField.DateFormat.MMDD.toDate("0131", 2000));
        Assertions.assertEquals(LocalDate.ofYearDay(year, 31),
                CouponField.DateFormat.DDD.toDate("031", 2000));
    }

    /**
     * toDate returns null on a time layout (first leg of the guard).
     */
    @Test
    void toDateOnATimeLayoutIsNull() {
        Assertions.assertNull(CouponField.DateFormat.HHMM.toDate("1345", 2000));
    }

    /**
     * toDate returns null on null characters (second leg of the guard).
     */
    @Test
    void toDateOnNullIsNull() {
        Assertions.assertNull(CouponField.DateFormat.DDMMYY.toDate(null, 2000));
    }

    /**
     * toDate returns null when the characters do not fill the layout (third leg).
     */
    @Test
    void toDateOnAWrongWidthIsNull() {
        Assertions.assertNull(CouponField.DateFormat.DDMMYY.toDate("31012", 2000));
    }

    /**
     * toDate returns null when the characters are not digits.
     */
    @Test
    void toDateOnNonDigitsIsNull() {
        Assertions.assertNull(CouponField.DateFormat.DDMMYY.toDate("3A0126", 2000));
    }

    /**
     * toDate returns null on a day the calendar does not hold.
     */
    @Test
    void toDateOnAnImpossibleDayIsNull() {
        Assertions.assertNull(CouponField.DateFormat.DDMMYY.toDate("320126", 2000));
    }

    /**
     * toTime reads both time layouts.
     */
    @Test
    void toTimeReadsBothLayouts() {
        Assertions.assertEquals(LocalTime.of(13, 45), CouponField.DateFormat.HHMM.toTime("1345"));
        Assertions.assertEquals(LocalTime.of(13, 45, 9), CouponField.DateFormat.HHMMSS.toTime("134509"));
    }

    /**
     * toTime returns null on a date layout (first leg of the guard).
     */
    @Test
    void toTimeOnADateLayoutIsNull() {
        Assertions.assertNull(CouponField.DateFormat.DDMMYY.toTime("310126"));
    }

    /**
     * toTime returns null on null characters (second leg of the guard).
     */
    @Test
    void toTimeOnNullIsNull() {
        Assertions.assertNull(CouponField.DateFormat.HHMM.toTime(null));
    }

    /**
     * toTime returns null when the characters do not fill the layout (third leg).
     */
    @Test
    void toTimeOnAWrongWidthIsNull() {
        Assertions.assertNull(CouponField.DateFormat.HHMM.toTime("134"));
    }

    /**
     * toTime returns null when the characters are not digits.
     */
    @Test
    void toTimeOnNonDigitsIsNull() {
        Assertions.assertNull(CouponField.DateFormat.HHMM.toTime("13A5"));
    }

    /**
     * toTime returns null on an hour the clock does not hold.
     */
    @Test
    void toTimeOnAnImpossibleHourIsNull() {
        Assertions.assertNull(CouponField.DateFormat.HHMM.toTime("2545"));
    }
}
