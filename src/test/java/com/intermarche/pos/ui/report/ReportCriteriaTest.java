package com.intermarche.pos.ui.report;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReportCriteria}.
 * <p>
 * Branch enumeration: {@code fromParams} has its null-map guard;
 * {@code first} has the absent / empty / present arms; {@code addAll} has the
 * null-list arm and, per value, the null, blank and usable arms;
 * {@code parsePeriod} has the blank arm, the unknown-name arm and the accepted
 * arm; {@code parseDate} has the blank arm, the malformed arm and the accepted
 * arm; {@code blankToNull} has both arms; {@code optionOr} has its null, blank
 * and present arms.
 */
class ReportCriteriaTest {

    /**
     * Builds an empty parameter map.
     *
     * @return a fresh multivalued map
     */
    private MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }

    /**
     * A null parameter map yields the default criteria rather than failing.
     */
    @Test
    void nullParametersYieldTheDefaults() {
        ReportCriteria criteria = ReportCriteria.fromParams(null);
        assertEquals(ReportPeriod.TODAY, criteria.period);
        assertNull(criteria.reference);
        assertTrue(criteria.terminals.isEmpty());
    }

    /**
     * An empty map yields the current day.
     */
    @Test
    void emptyParametersYieldTheCurrentDay() {
        assertEquals(ReportPeriod.TODAY, ReportCriteria.fromParams(params()).period);
    }

    /**
     * A known period name is accepted, whatever its case.
     */
    @Test
    void knownPeriodIsAccepted() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("period", "week");
        assertEquals(ReportPeriod.WEEK, ReportCriteria.fromParams(params).period);
    }

    /**
     * An unknown period name falls back to the current day.
     */
    @Test
    void unknownPeriodFallsBackToToday() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("period", "decade");
        assertEquals(ReportPeriod.TODAY, ReportCriteria.fromParams(params).period);
    }

    /**
     * A blank period name falls back to the current day.
     */
    @Test
    void blankPeriodFallsBackToToday() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("period", "  ");
        assertEquals(ReportPeriod.TODAY, ReportCriteria.fromParams(params).period);
    }

    /**
     * An ISO date is parsed on each of the three date fields.
     */
    @Test
    void isoDatesAreParsed() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("reference", "2026-09-16");
        params.putSingle("from", "2026-09-01");
        params.putSingle("to", "2026-09-30");
        ReportCriteria criteria = ReportCriteria.fromParams(params);
        assertEquals(LocalDate.of(2026, 9, 16), criteria.reference);
        assertEquals(LocalDate.of(2026, 9, 1), criteria.rangeFrom);
        assertEquals(LocalDate.of(2026, 9, 30), criteria.rangeTo);
    }

    /**
     * A malformed date is read as an absent date.
     */
    @Test
    void malformedDateIsReadAsAbsent() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("reference", "16/09/2026");
        assertNull(ReportCriteria.fromParams(params).reference);
    }

    /**
     * A blank date is read as an absent date.
     */
    @Test
    void blankDateIsReadAsAbsent() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("reference", "");
        assertNull(ReportCriteria.fromParams(params).reference);
    }

    /**
     * Repeated register parameters all land in the set, trimmed.
     */
    @Test
    void repeatedTerminalsAllLandInTheSet() {
        MultivaluedMap<String, String> params = params();
        params.put("terminal", Arrays.asList("POS01", " POS02 "));
        ReportCriteria criteria = ReportCriteria.fromParams(params);
        assertEquals(2, criteria.terminals.size());
        assertTrue(criteria.terminals.contains("POS02"));
    }

    /**
     * A blank or null value among the repeated ones is dropped rather than
     * stored as an empty criterion.
     */
    @Test
    void blankAndNullValuesAreDropped() {
        MultivaluedMap<String, String> params = params();
        params.put("cashier", Arrays.asList("007", "  ", null));
        ReportCriteria criteria = ReportCriteria.fromParams(params);
        assertEquals(1, criteria.cashiers.size());
        assertTrue(criteria.cashiers.contains("007"));
    }

    /**
     * An absent multi-valued parameter leaves its set empty (the null-list arm).
     */
    @Test
    void absentMultiValuedParameterLeavesTheSetEmpty() {
        assertTrue(ReportCriteria.fromParams(params()).cashiers.isEmpty());
    }

    /**
     * A parameter present but carrying an empty value list is read as absent (the
     * empty-list arm of the first-value read), falling back to the current day.
     */
    @Test
    void presentButEmptyValueListIsReadAsAbsent() {
        MultivaluedMap<String, String> params = params();
        params.put("period", new java.util.ArrayList<>());
        params.put("reference", new java.util.ArrayList<>());
        ReportCriteria criteria = ReportCriteria.fromParams(params);
        assertEquals(ReportPeriod.TODAY, criteria.period);
        assertNull(criteria.reference);
    }

    /**
     * A blank option is stored as no option at all.
     */
    @Test
    void blankOptionIsStoredAsNone() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("option", "   ");
        assertNull(ReportCriteria.fromParams(params).option);
    }

    /**
     * A chosen option is kept, trimmed.
     */
    @Test
    void chosenOptionIsKept() {
        MultivaluedMap<String, String> params = params();
        params.putSingle("option", " down ");
        assertEquals("down", ReportCriteria.fromParams(params).option);
    }

    /**
     * The fallback applies to a null option and to a blank one, and steps aside
     * for a chosen one.
     */
    @Test
    void fallbackAppliesToNullAndBlankOptions() {
        ReportCriteria criteria = new ReportCriteria();
        assertEquals("all", criteria.optionOr("all"));
        criteria.option = "  ";
        assertEquals("all", criteria.optionOr("all"));
        criteria.option = "up";
        assertEquals("up", criteria.optionOr("all"));
    }

    /**
     * The resolved bounds come from the period and the dates that were parsed.
     */
    @Test
    void boundsComeFromThePeriod() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = LocalDate.of(2026, 9, 16);
        assertEquals(LocalDate.of(2026, 9, 16).atStartOfDay(), criteria.from());
        assertEquals(LocalDate.of(2026, 9, 17).atStartOfDay(), criteria.to());
    }
}
