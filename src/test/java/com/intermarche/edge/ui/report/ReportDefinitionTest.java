package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ReportDefinition} interface, exercising the four
 * default methods a report inherits when it overrides none of them.
 * <p>
 * The interface has no branches; the point of these tests is the DEFAULT bodies
 * ({@code chartColumn}, {@code chartLabelColumn}, {@code optionLabel} and
 * {@code optionChoices}), which no report that overrides them ever runs and which
 * therefore stay uncovered until a minimal implementation leaves them in place.
 * A bare implementation is defined here that supplies only the abstract members
 * and inherits every default, so each default's constant answer is asserted.
 */
class ReportDefinitionTest {

    /**
     * A report that overrides only the abstract methods, so every default method
     * of {@link ReportDefinition} runs unchanged.
     */
    private static final class BareReport implements ReportDefinition {

        /**
         * {@inheritDoc}
         *
         * @return a fixed code
         */
        @Override
        public String code() {
            return "bare";
        }

        /**
         * {@inheritDoc}
         *
         * @return a fixed label
         */
        @Override
        public String label() {
            return "Bare";
        }

        /**
         * {@inheritDoc}
         *
         * @return a fixed requirement
         */
        @Override
        public String requirement() {
            return "BO-00-00-00";
        }

        /**
         * {@inheritDoc}
         *
         * @return an arbitrary family
         */
        @Override
        public ReportFamily family() {
            return ReportFamily.values()[0];
        }

        /**
         * {@inheritDoc}
         *
         * @return a single column
         */
        @Override
        public List<ReportColumn> columns() {
            return List.of(ReportColumn.text("k", "K"));
        }

        /**
         * {@inheritDoc}
         *
         * @param criteria the edition parameters, ignored here
         * @return no rows
         */
        @Override
        public List<List<Object>> rows(ReportCriteria criteria) {
            return List.of();
        }
    }

    /**
     * The default {@code chartColumn} says "no chart" by answering -1.
     */
    @Test
    void chartColumnDefaultsToNoChart() {
        assertEquals(-1, new BareReport().chartColumn());
    }

    /**
     * The default {@code chartLabelColumn} points at the first column.
     */
    @Test
    void chartLabelColumnDefaultsToFirstColumn() {
        assertEquals(0, new BareReport().chartLabelColumn());
    }

    /**
     * The default {@code optionLabel} is null, meaning the report offers no
     * report-specific option.
     */
    @Test
    void optionLabelDefaultsToNull() {
        assertNull(new BareReport().optionLabel());
    }

    /**
     * The default {@code optionChoices} is the empty list, matching the absent
     * option.
     */
    @Test
    void optionChoicesDefaultsToEmpty() {
        assertTrue(new BareReport().optionChoices().isEmpty());
    }
}
