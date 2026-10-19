package com.intermarche.edge.ui.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * A report standing in for a real one in the frame's tests: one column of each
 * kind, so a renderer is exercised on every formatting arm it owns without
 * dragging a query and an {@code EntityManager} into a unit test.
 */
class ReportFixture implements ReportDefinition {

    /** The index of the column the chart plots, or -1. */
    private final int chartColumn;

    /** The rows this fixture returns. */
    private final List<List<Object>> rows;

    /**
     * Builds a fixture.
     *
     * @param chartColumn the plotted column index, or -1 for no chart
     * @param rows the rows to return
     */
    ReportFixture(int chartColumn, List<List<Object>> rows) {
        this.chartColumn = chartColumn;
        this.rows = rows;
    }

    /**
     * Builds a fixture over the standard four rows, plotting its amount column.
     *
     * @return the fixture
     */
    static ReportFixture standard() {
        return new ReportFixture(3, Arrays.asList(
                Arrays.asList("CASH", "Espèces \"caisse\"", 1284L, new BigDecimal("18422.55"),
                        new BigDecimal("47.3"), LocalDateTime.of(2026, 9, 16, 19, 42)),
                Arrays.asList("CARD", "Carte & <banque>", 902L, new BigDecimal("17110.10"),
                        new BigDecimal("43.9"), LocalDateTime.of(2026, 9, 16, 19, 55))));
    }

    /**
     * {@inheritDoc}
     *
     * @return the fixture's code
     */
    @Override
    public String code() {
        return "fixture";
    }

    /**
     * {@inheritDoc}
     *
     * @return the fixture's title
     */
    @Override
    public String label() {
        return "État des règlements";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement the fixture claims
     */
    @Override
    public String requirement() {
        return "BO-06-06-09";
    }

    /**
     * {@inheritDoc}
     *
     * @return the settlements family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.SETTLEMENTS;
    }

    /**
     * {@inheritDoc}
     *
     * @return one column per kind that matters to a renderer
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("k", "Type"),
                ReportColumn.text("l", "Libellé du mode"),
                ReportColumn.count("n", "Nombre"),
                ReportColumn.amount("a", "Valeur nette"),
                ReportColumn.percent("p", "% du total"),
                ReportColumn.dateTime("d", "Dernier règlement"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the plotted column index
     */
    @Override
    public int chartColumn() {
        return chartColumn;
    }

    /**
     * {@inheritDoc}
     *
     * @return the label column the chart names its bars from
     */
    @Override
    public int chartLabelColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters, ignored by the fixture
     * @return the rows the fixture was built with
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        return rows;
    }
}
