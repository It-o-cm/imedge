package com.intermarche.edge.service.export;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Unit tests for {@link FlatRecord} (BO-11-01-12), targeting 100% branch coverage.
 *
 * <p>Everything here is about ONE property: the offset of the next field never
 * moves. Each test therefore asserts a LENGTH as well as a content — a field that
 * holds the right characters at the wrong width is the defect this class exists to
 * prevent, and it is invisible to an assertion on content alone.
 *
 * <p>Branch enumeration, leg by leg. {@code text}: null, shorter than its field,
 * exactly its width, longer than its field, and a value carrying a line break or a
 * separator. {@code amount}: null, positive, negative, zero, a value too wide for
 * its field and a negative one too wide for what the sign leaves it.
 * {@code number}: zero, positive, negative and too wide. {@code date} and
 * {@code instant}: present and absent.
 */
class FlatRecordTest {

    /** A text shorter than its field is padded on the right. */
    @Test
    void aShortTextIsPaddedToItsWidth() {
        String line = new FlatRecord().text("AB", 5).line();
        Assertions.assertEquals("AB   ", line);
        Assertions.assertEquals(5, line.length());
    }

    /** A text exactly as wide as its field is written as is. */
    @Test
    void anExactTextIsWrittenAsIs() {
        Assertions.assertEquals("ABCDE", new FlatRecord().text("ABCDE", 5).line());
    }

    /** A text longer than its field is TRUNCATED, never allowed to shift the rest. */
    @Test
    void aLongTextIsTruncatedRatherThanShiftingTheRecord() {
        String line = new FlatRecord().text("ABCDEFGH", 5).text("X", 1).line();
        Assertions.assertEquals("ABCDEX", line);
        Assertions.assertEquals(6, line.length());
    }

    /** An absent text is an empty field of the right width, not a missing field. */
    @Test
    void anAbsentTextIsAnEmptyFieldOfTheRightWidth() {
        Assertions.assertEquals("    ", new FlatRecord().text(null, 4).line());
    }

    /**
     * A line break or a separator inside a value is replaced by a space: one
     * newline in a customer's name would otherwise split one record into two.
     */
    @Test
    void aLineBreakOrASeparatorInsideAValueIsNeutralised() {
        String line = new FlatRecord().text("DU\r\nPONT;SA", 12).line();
        Assertions.assertEquals("DU  PONT SA ", line);
        Assertions.assertEquals(12, line.length());
    }

    /** An amount is written in cents, right-aligned and zero-filled. */
    @Test
    void anAmountIsWrittenInCents() {
        Assertions.assertEquals("0000012050",
                new FlatRecord().amount(new BigDecimal("120.50"), 10).line());
    }

    /** An amount is rounded to the cent, half up, before being written. */
    @Test
    void anAmountIsRoundedToTheCent() {
        Assertions.assertEquals("0000000005",
                new FlatRecord().amount(new BigDecimal("0.045"), 10).line());
    }

    /** An absent amount is zero, not a blank: a blank is not a number. */
    @Test
    void anAbsentAmountIsZero() {
        Assertions.assertEquals("00000", new FlatRecord().amount(null, 5).line());
    }

    /** A negative amount keeps its sign in the first character of the field. */
    @Test
    void aNegativeAmountKeepsItsSignFirst() {
        String line = new FlatRecord().amount(new BigDecimal("-12.00"), 8).line();
        Assertions.assertEquals("-0001200", line);
        Assertions.assertEquals(8, line.length());
    }

    /**
     * An amount too wide for its field is written as NINES: a truncated amount is a
     * plausible wrong number, a field of nines is visibly impossible.
     */
    @Test
    void anAmountTooWideIsWrittenAsNines() {
        String line = new FlatRecord().amount(new BigDecimal("99999.99"), 5).line();
        Assertions.assertEquals("99999", line);
        Assertions.assertEquals(5, line.length());
    }

    /**
     * The sign takes a character, so a negative amount overflows one digit sooner
     * than a positive one: four digits still fit on five characters, five do not.
     */
    @Test
    void aNegativeAmountOverflowsOneDigitSooner() {
        Assertions.assertEquals("-9999",
                new FlatRecord().amount(new BigDecimal("-99.99"), 5).line());
        Assertions.assertEquals("99999",
                new FlatRecord().amount(new BigDecimal("-999.99"), 5).line());
    }

    /** An integer is right-aligned and zero-filled, with no implied decimals. */
    @Test
    void anIntegerIsZeroFilled() {
        Assertions.assertEquals("00042", new FlatRecord().number(42L, 5).line());
    }

    /** Zero is a number like any other. */
    @Test
    void zeroIsWrittenAsZeroes() {
        Assertions.assertEquals("000", new FlatRecord().number(0L, 3).line());
    }

    /** A negative integer keeps its sign in the first character. */
    @Test
    void aNegativeIntegerKeepsItsSignFirst() {
        Assertions.assertEquals("-0042", new FlatRecord().number(-42L, 5).line());
    }

    /** An integer too wide for its field is written as nines. */
    @Test
    void anIntegerTooWideIsWrittenAsNines() {
        Assertions.assertEquals("99", new FlatRecord().number(1234L, 2).line());
    }

    /** A date is spelled yyyyMMdd on eight characters. */
    @Test
    void aDateIsSpelledWithoutSeparators() {
        Assertions.assertEquals("20260430",
                new FlatRecord().date(LocalDate.of(2026, 4, 30)).line());
    }

    /** An absent date is eight spaces, so the next field stays at its offset. */
    @Test
    void anAbsentDateIsEightSpaces() {
        Assertions.assertEquals("        ", new FlatRecord().date(null).line());
    }

    /** An instant is spelled yyyyMMddHHmm on twelve characters. */
    @Test
    void anInstantCarriesItsHourAndMinute() {
        Assertions.assertEquals("202603171830",
                new FlatRecord().instant(LocalDateTime.of(2026, 3, 17, 18, 30)).line());
    }

    /** An absent instant is twelve spaces. */
    @Test
    void anAbsentInstantIsTwelveSpaces() {
        Assertions.assertEquals("            ", new FlatRecord().instant(null).line());
    }

    /** Fields chain in the order they are appended, each at its own offset. */
    @Test
    void fieldsChainAtTheirOwnOffsets() {
        String line = new FlatRecord()
                .text("H", 1)
                .text("FA-001", 10)
                .number(7L, 3)
                .amount(new BigDecimal("12.00"), 8)
                .date(LocalDate.of(2026, 1, 2))
                .line();
        Assertions.assertEquals(30, line.length());
        Assertions.assertEquals("H", line.substring(0, 1));
        Assertions.assertEquals("FA-001    ", line.substring(1, 11));
        Assertions.assertEquals("007", line.substring(11, 14));
        Assertions.assertEquals("00001200", line.substring(14, 22));
        Assertions.assertEquals("20260102", line.substring(22, 30));
    }
}
