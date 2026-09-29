package com.intermarche.pos.ui.report;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReportColumn}: each factory, the accessors and every
 * {@link ReportColumn.Kind}, including the numeric() predicate over all kinds.
 */
class ReportColumnTest {

    /**
     * text() builds a TEXT column that is never totalled and not numeric.
     */
    @Test
    void textFactoryBuildsNonTotalledTextColumn() {
        ReportColumn column = ReportColumn.text("label", "Label");
        Assertions.assertEquals("label", column.key());
        Assertions.assertEquals("Label", column.label());
        Assertions.assertEquals(ReportColumn.Kind.TEXT, column.kind());
        Assertions.assertFalse(column.totalled());
        Assertions.assertFalse(column.numeric());
    }

    /**
     * count() builds a totalled INTEGER column that is numeric.
     */
    @Test
    void countFactoryBuildsTotalledIntegerColumn() {
        ReportColumn column = ReportColumn.count("tickets", "Tickets");
        Assertions.assertEquals("tickets", column.key());
        Assertions.assertEquals("Tickets", column.label());
        Assertions.assertEquals(ReportColumn.Kind.INTEGER, column.kind());
        Assertions.assertTrue(column.totalled());
        Assertions.assertTrue(column.numeric());
    }

    /**
     * amount() builds a totalled AMOUNT column that is numeric.
     */
    @Test
    void amountFactoryBuildsTotalledAmountColumn() {
        ReportColumn column = ReportColumn.amount("ca", "Turnover");
        Assertions.assertEquals("ca", column.key());
        Assertions.assertEquals("Turnover", column.label());
        Assertions.assertEquals(ReportColumn.Kind.AMOUNT, column.kind());
        Assertions.assertTrue(column.totalled());
        Assertions.assertTrue(column.numeric());
    }

    /**
     * percent() builds a PERCENT column that is numeric but not totalled.
     */
    @Test
    void percentFactoryBuildsNonTotalledPercentColumn() {
        ReportColumn column = ReportColumn.percent("share", "Share");
        Assertions.assertEquals("share", column.key());
        Assertions.assertEquals("Share", column.label());
        Assertions.assertEquals(ReportColumn.Kind.PERCENT, column.kind());
        Assertions.assertFalse(column.totalled());
        Assertions.assertTrue(column.numeric());
    }

    /**
     * dateTime() builds a DATETIME column that is neither totalled nor numeric.
     */
    @Test
    void dateTimeFactoryBuildsNonTotalledDateTimeColumn() {
        ReportColumn column = ReportColumn.dateTime("when", "When");
        Assertions.assertEquals("when", column.key());
        Assertions.assertEquals("When", column.label());
        Assertions.assertEquals(ReportColumn.Kind.DATETIME, column.kind());
        Assertions.assertFalse(column.totalled());
        Assertions.assertFalse(column.numeric());
    }

    /**
     * numeric() is true only for INTEGER, AMOUNT and PERCENT, across every kind.
     */
    @Test
    void numericIsTrueOnlyForCountAmountAndPercentKinds() {
        Assertions.assertFalse(new ReportColumn("k", "l", ReportColumn.Kind.TEXT, false).numeric());
        Assertions.assertTrue(new ReportColumn("k", "l", ReportColumn.Kind.INTEGER, true).numeric());
        Assertions.assertTrue(new ReportColumn("k", "l", ReportColumn.Kind.AMOUNT, true).numeric());
        Assertions.assertTrue(new ReportColumn("k", "l", ReportColumn.Kind.PERCENT, false).numeric());
        Assertions.assertFalse(new ReportColumn("k", "l", ReportColumn.Kind.DATE, false).numeric());
        Assertions.assertFalse(new ReportColumn("k", "l", ReportColumn.Kind.TIME, false).numeric());
        Assertions.assertFalse(new ReportColumn("k", "l", ReportColumn.Kind.DATETIME, false).numeric());
    }

    /**
     * The Kind enum exposes exactly the seven declared constants, valueOf round-trips.
     */
    @Test
    void kindEnumDeclaresSevenConstants() {
        ReportColumn.Kind[] values = ReportColumn.Kind.values();
        Assertions.assertEquals(7, values.length);
        Assertions.assertEquals(ReportColumn.Kind.TEXT, ReportColumn.Kind.valueOf("TEXT"));
        Assertions.assertEquals(ReportColumn.Kind.INTEGER, ReportColumn.Kind.valueOf("INTEGER"));
        Assertions.assertEquals(ReportColumn.Kind.AMOUNT, ReportColumn.Kind.valueOf("AMOUNT"));
        Assertions.assertEquals(ReportColumn.Kind.PERCENT, ReportColumn.Kind.valueOf("PERCENT"));
        Assertions.assertEquals(ReportColumn.Kind.DATE, ReportColumn.Kind.valueOf("DATE"));
        Assertions.assertEquals(ReportColumn.Kind.TIME, ReportColumn.Kind.valueOf("TIME"));
        Assertions.assertEquals(ReportColumn.Kind.DATETIME, ReportColumn.Kind.valueOf("DATETIME"));
    }
}
