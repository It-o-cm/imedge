package com.intermarche.edge.ui.report;

import java.util.List;

/**
 * ONE report: its identity, its columns, and how to produce its rows for a
 * given set of edition parameters.
 *
 * <p>A report knows nothing about how it will be shown. It states its columns
 * and returns raw values — a {@code BigDecimal} stays a {@code BigDecimal}, a
 * date stays a date — and the screen, the CSV, the spreadsheet and the PDF each
 * render them their own way from {@link ReportColumn#kind()}. That is what
 * makes « la totalité des rapports doivent être exportables » (BO-06-01-01/02/03)
 * one piece of code rather than one per report.
 *
 * <p>Implementations are CDI beans; {@link ReportCatalog} discovers them. Adding
 * a report is adding a bean, never touching the frame.
 */
public interface ReportDefinition {

    /**
     * Returns the stable code that names this report in a URL and in a file
     * name.
     *
     * @return the code, lower-case, dash-separated
     */
    String code();

    /**
     * Returns the title shown on the screen and at the top of every export.
     *
     * @return the French title
     */
    String label();

    /**
     * Returns the requirement this report answers, shown beside its title so
     * the reader can trace a column back to what asked for it.
     *
     * @return the requirement code, for example {@code BO-06-06-09}
     */
    String requirement();

    /**
     * Returns the family this report is filed under in the report list.
     *
     * @return the family, never null
     */
    ReportFamily family();

    /**
     * Returns the columns, in display order.
     *
     * @return the columns, never empty
     */
    List<ReportColumn> columns();

    /**
     * Produces the rows for the given edition parameters.
     *
     * <p>Each row carries one value per column, in the same order. A report
     * returns its rows WHOLE: paging is the screen's business, and an export
     * that paginated would be an export of one page.
     *
     * @param criteria the edition parameters
     * @return the rows, possibly empty, never null
     */
    List<List<Object>> rows(ReportCriteria criteria);

    /**
     * Returns the index of the column whose values the chart plots, or -1 when
     * this report has nothing worth drawing.
     *
     * <p>The chart (BO-06-01-04) is drawn for every AGGREGATE report — hours of
     * the day, settlement methods, cashiers, families — whatever its number of
     * rows; this is the series drawn when the operator chose none, and the
     * operator may add any other numeric column. A LIST of tickets or of events
     * is not a measurement to draw, and says so by returning -1.
     *
     * @return the plotted column index, or -1 for no chart
     */
    default int chartColumn() {
        return -1;
    }

    /**
     * Returns the index of the column holding each bar's label.
     *
     * @return the label column index, the first column by default
     */
    default int chartLabelColumn() {
        return 0;
    }

    /**
     * Returns the label of the report-specific option, or null when this report
     * offers none.
     *
     * @return the option's label, or null
     */
    default String optionLabel() {
        return null;
    }

    /**
     * Returns the values the report-specific option accepts, as
     * {@code value:label} pairs.
     *
     * @return the option's choices, empty when this report offers none
     */
    default List<String> optionChoices() {
        return List.of();
    }

    /**
     * Returns the edition criteria this report honours beyond its period,
     * which decides the fields its form shows.
     *
     * @return the honoured criteria, the registers by default
     */
    default java.util.Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS);
    }

    /**
     * Returns the label of the report-specific MULTIPLE choice, or null when
     * this report offers none.
     *
     * @return the choice's label, or null
     */
    default String pickLabel() {
        return null;
    }

    /**
     * Returns the values the multiple choice offers, as {@code value:label}
     * pairs.
     *
     * @return the choices, empty when this report offers none
     */
    default List<String> pickChoices() {
        return List.of();
    }

    /**
     * Returns the label of the report-specific text range — a range of
     * customer numbers, of names, of transactions — or null when this report
     * offers none.
     *
     * @return the range's label, or null
     */
    default String boundLabel() {
        return null;
    }

    /**
     * Returns the lines the report states ABOVE its table: what a reader needs
     * to know of the rows before reading them — the survey recalled with its
     * question and period, the nomenclature level chosen.
     *
     * @param criteria the edition parameters
     * @return the lines, in French, empty by default
     */
    default List<String> preamble(ReportCriteria criteria) {
        return List.of();
    }

    /**
     * Returns the key of the column the rows are BROKEN on (« rupture »), or
     * null when the report has no break.
     *
     * <p>The report returns its rows ordered by that column; the frame closes
     * each group with a subtotal row of the totalled columns, on the screen and
     * in every export alike.
     *
     * @return the break column's key, or null
     */
    default String breakKey() {
        return null;
    }

    /**
     * Returns the address a row's ACTION opens on the screen — the duplicate
     * of an invoice (BO-06-04-03) — or null when the row has none. The
     * exports never carry it: an action is a gesture of the screen.
     *
     * @param row the row
     * @return the address, or null
     */
    default String rowLink(List<Object> row) {
        return null;
    }

    /**
     * Returns the label of the row action, or null when the report has none.
     *
     * @return the French label, or null
     */
    default String rowLinkLabel() {
        return null;
    }

    /**
     * Returns the signature line printed at the foot of the document, or null:
     * a document « à faire signer par la caissière » (BO-06-06-08) carries the
     * place where she signs.
     *
     * @return the French signature caption, or null
     */
    default String signature() {
        return null;
    }
}
