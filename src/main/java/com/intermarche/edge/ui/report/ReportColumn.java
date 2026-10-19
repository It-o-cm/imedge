package com.intermarche.edge.ui.report;

/**
 * One column of a report: what it is called, what kind of value it carries and
 * whether the report totals it.
 *
 * <p>The KIND is the whole point of this type. A report is rendered four ways —
 * on screen, in CSV, in a spreadsheet and in a PDF (BO-06-01-01/02/03) — and
 * each rendering formats an amount, a count and a percentage differently. Naming
 * the kind once, on the column, is what lets the four renderings agree without
 * any of them knowing what the report is about; it is also what tells the
 * spreadsheet to write a NUMBER where a number belongs, so the recipient can sum
 * a column instead of receiving text that looks like one.
 *
 * @param key the technical name, unique inside its report
 * @param label the header shown to the operator
 * @param kind what the values are, which decides their formatting
 * @param totalled whether the report's total row sums this column
 */
public record ReportColumn(String key, String label, ReportColumn.Kind kind, boolean totalled) {

    /**
     * What a column's values are.
     */
    public enum Kind {

        /** Free text: a label, a code, a name. Never totalled. */
        TEXT,

        /** A whole count: tickets, articles, clients. */
        INTEGER,

        /** A monetary amount, two decimals, in the store's currency. */
        AMOUNT,

        /** A share of a whole, one decimal, rendered with a percent sign. */
        PERCENT,

        /** A calendar day. */
        DATE,

        /** A time of day, to the minute. */
        TIME,

        /** A day and a time of day. */
        DATETIME
    }

    /**
     * Builds a text column, never totalled.
     *
     * @param key the technical name
     * @param label the header
     * @return the column
     */
    public static ReportColumn text(String key, String label) {
        return new ReportColumn(key, label, Kind.TEXT, false);
    }

    /**
     * Builds a totalled count column.
     *
     * @param key the technical name
     * @param label the header
     * @return the column
     */
    public static ReportColumn count(String key, String label) {
        return new ReportColumn(key, label, Kind.INTEGER, true);
    }

    /**
     * Builds a totalled amount column.
     *
     * @param key the technical name
     * @param label the header
     * @return the column
     */
    public static ReportColumn amount(String key, String label) {
        return new ReportColumn(key, label, Kind.AMOUNT, true);
    }

    /**
     * Builds a percentage column.
     *
     * <p>NOT totalled, even though the shares of a whole add up to a hundred:
     * a total row summing them would print 100 % under a column of shares and
     * 99,9 % under the next, which reads as an error rather than as rounding.
     *
     * @param key the technical name
     * @param label the header
     * @return the column
     */
    public static ReportColumn percent(String key, String label) {
        return new ReportColumn(key, label, Kind.PERCENT, false);
    }

    /**
     * Builds a column of days and times.
     *
     * @param key the technical name
     * @param label the header
     * @return the column
     */
    public static ReportColumn dateTime(String key, String label) {
        return new ReportColumn(key, label, Kind.DATETIME, false);
    }

    /**
     * Whether the values of this column are numeric, and so right-aligned on
     * screen and written as numbers in the spreadsheet.
     *
     * @return true for counts, amounts and percentages
     */
    public boolean numeric() {
        return kind == Kind.INTEGER || kind == Kind.AMOUNT || kind == Kind.PERCENT;
    }
}
