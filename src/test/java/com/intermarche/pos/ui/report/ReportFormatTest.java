package com.intermarche.pos.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ReportFormat}.
 * <p>
 * Branch enumeration: {@code cell} has a null guard and one arm per
 * {@link ReportColumn.Kind} (seven). {@code date}, {@code time} and
 * {@code dateTime} each have their accepted temporal types and their fallback
 * arm. {@code decimal} has its BigDecimal arm, its other-Number arm and its
 * non-numeric arm. {@code group} has the decimal / no-decimal arms, the
 * negative / positive arms and the separator's every-third-digit condition on
 * both of its legs.
 */
class ReportFormatTest {

    /** The narrow no-break space the thousands are grouped with. */
    private static final String NARROW = " ";

    /**
     * A null value renders as nothing, whatever the column.
     */
    @Test
    void nullRendersEmpty() {
        assertEquals("", ReportFormat.cell(ReportColumn.amount("a", "A"), null));
    }

    /**
     * An amount carries two decimals, a comma and the currency sign.
     */
    @Test
    void amountCarriesTwoDecimalsAndTheSign() {
        assertEquals("18" + NARROW + "422,55 €",
                ReportFormat.cell(ReportColumn.amount("a", "A"), new BigDecimal("18422.55")));
    }

    /**
     * An amount below a thousand carries no separator (the false leg of the
     * grouping condition).
     */
    @Test
    void smallAmountCarriesNoSeparator() {
        assertEquals("12,30 €", ReportFormat.cell(ReportColumn.amount("a", "A"), new BigDecimal("12.3")));
    }

    /**
     * A negative amount keeps its sign ahead of the grouped digits.
     */
    @Test
    void negativeAmountKeepsItsSign() {
        assertEquals("-1" + NARROW + "000,00 €",
                ReportFormat.cell(ReportColumn.amount("a", "A"), new BigDecimal("-1000")));
    }

    /**
     * A million is grouped twice, which exercises the separator on a second
     * boundary.
     */
    @Test
    void millionsAreGroupedTwice() {
        assertEquals("1" + NARROW + "234" + NARROW + "567,00 €",
                ReportFormat.cell(ReportColumn.amount("a", "A"), new BigDecimal("1234567")));
    }

    /**
     * A share carries one decimal and a percent sign.
     */
    @Test
    void percentCarriesOneDecimal() {
        assertEquals("47,3 %", ReportFormat.cell(ReportColumn.percent("p", "P"), new BigDecimal("47.34")));
    }

    /**
     * A count is rounded to the unit and grouped.
     */
    @Test
    void countIsRoundedAndGrouped() {
        assertEquals("2" + NARROW + "304", ReportFormat.cell(ReportColumn.count("n", "N"), 2304L));
    }

    /**
     * Free text is rendered as it stands.
     */
    @Test
    void textIsRenderedAsItStands() {
        assertEquals("Carte bancaire", ReportFormat.cell(ReportColumn.text("t", "T"), "Carte bancaire"));
    }

    /**
     * A day column renders a {@code LocalDate}.
     */
    @Test
    void dayColumnRendersALocalDate() {
        ReportColumn column = new ReportColumn("d", "D", ReportColumn.Kind.DATE, false);
        assertEquals("16/09/2026", ReportFormat.cell(column, LocalDate.of(2026, 9, 16)));
    }

    /**
     * A day column also renders the day part of a moment.
     */
    @Test
    void dayColumnRendersTheDayOfAMoment() {
        ReportColumn column = new ReportColumn("d", "D", ReportColumn.Kind.DATE, false);
        assertEquals("16/09/2026", ReportFormat.cell(column, LocalDateTime.of(2026, 9, 16, 22, 5)));
    }

    /**
     * A day column falls back to the value's own text when it is not temporal.
     */
    @Test
    void dayColumnFallsBackToTheValuesText() {
        ReportColumn column = new ReportColumn("d", "D", ReportColumn.Kind.DATE, false);
        assertEquals("hier", ReportFormat.cell(column, "hier"));
    }

    /**
     * A time column renders a {@code LocalTime}.
     */
    @Test
    void timeColumnRendersALocalTime() {
        ReportColumn column = new ReportColumn("h", "H", ReportColumn.Kind.TIME, false);
        assertEquals("08:05", ReportFormat.cell(column, LocalTime.of(8, 5)));
    }

    /**
     * A time column renders the time part of a moment.
     */
    @Test
    void timeColumnRendersTheTimeOfAMoment() {
        ReportColumn column = new ReportColumn("h", "H", ReportColumn.Kind.TIME, false);
        assertEquals("22:05", ReportFormat.cell(column, LocalDateTime.of(2026, 9, 16, 22, 5)));
    }

    /**
     * A time column falls back to the value's own text.
     */
    @Test
    void timeColumnFallsBackToTheValuesText() {
        ReportColumn column = new ReportColumn("h", "H", ReportColumn.Kind.TIME, false);
        assertEquals("midi", ReportFormat.cell(column, "midi"));
    }

    /**
     * A moment column renders day and time together.
     */
    @Test
    void momentColumnRendersDayAndTime() {
        assertEquals("16/09/2026 22:05",
                ReportFormat.cell(ReportColumn.dateTime("m", "M"), LocalDateTime.of(2026, 9, 16, 22, 5)));
    }

    /**
     * A moment column falls back to the value's own text.
     */
    @Test
    void momentColumnFallsBackToTheValuesText() {
        assertEquals("jamais", ReportFormat.cell(ReportColumn.dateTime("m", "M"), "jamais"));
    }

    /**
     * A decimal is read back as itself.
     */
    @Test
    void decimalReadsABigDecimal() {
        assertEquals(new BigDecimal("2.50"), ReportFormat.decimal(new BigDecimal("2.50")));
    }

    /**
     * Another kind of number is read through its double value.
     */
    @Test
    void decimalReadsAnyOtherNumber() {
        assertEquals(0, BigDecimal.valueOf(4L).compareTo(ReportFormat.decimal(4L)));
    }

    /**
     * A non-numeric value reads as zero rather than failing.
     */
    @Test
    void decimalReadsANonNumberAsZero() {
        assertEquals(BigDecimal.ZERO, ReportFormat.decimal("douze"));
    }

    /**
     * An {@code Integer} is read through its long value (its own arm of the
     * integral compound guard).
     */
    @Test
    void decimalReadsAnInteger() {
        assertEquals(0, BigDecimal.valueOf(7L).compareTo(ReportFormat.decimal(Integer.valueOf(7))));
    }

    /**
     * A {@code Short} is read through its long value (its own arm of the
     * integral compound guard).
     */
    @Test
    void decimalReadsAShort() {
        assertEquals(0, BigDecimal.valueOf(9L).compareTo(ReportFormat.decimal(Short.valueOf((short) 9))));
    }

    /**
     * A {@code Byte} is read through its long value (its own arm of the
     * integral compound guard).
     */
    @Test
    void decimalReadsAByte() {
        assertEquals(0, BigDecimal.valueOf(5L).compareTo(ReportFormat.decimal(Byte.valueOf((byte) 5))));
    }

    /**
     * A {@code Double} falls past the integral guard and is read through its
     * double value (the compound guard's all-false leg).
     */
    @Test
    void decimalReadsADoubleThroughItsDoubleValue() {
        assertEquals(0, BigDecimal.valueOf(2.5d).compareTo(ReportFormat.decimal(Double.valueOf(2.5d))));
    }

    /**
     * A count column is numeric, a text column is not.
     */
    @Test
    void numericTellsFiguresFromText() {
        assertEquals(true, ReportColumn.count("n", "N").numeric());
        assertEquals(true, ReportColumn.amount("a", "A").numeric());
        assertEquals(true, ReportColumn.percent("p", "P").numeric());
        assertEquals(false, ReportColumn.text("t", "T").numeric());
        assertEquals(false, ReportColumn.dateTime("m", "M").numeric());
    }
}
