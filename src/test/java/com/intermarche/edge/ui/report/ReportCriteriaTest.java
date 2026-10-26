package com.intermarche.edge.ui.report;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.Set;

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

    /**
     * BO-03-09-01: the deactivated registers are left out unless kept by the
     * operator or chosen by name; the inactive flag is read from the query.
     */
    @Test
    void deactivatedRegistersAreExcludedUnlessKeptOrChosen() {
        jakarta.ws.rs.core.MultivaluedMap<String, String> query = params();
        query.add("terminal", "C02");
        ReportCriteria criteria = ReportCriteria.fromParams(query);
        assertFalse(criteria.includeInactive);
        criteria.excludeDeactivated(List.of("C01", "C02"));
        assertEquals(Set.of("C01"), criteria.excludedTerminals);
        query.add("inactive", "true");
        ReportCriteria kept = ReportCriteria.fromParams(query);
        assertTrue(kept.includeInactive);
        kept.excludeDeactivated(List.of("C01"));
        assertTrue(kept.excludedTerminals.isEmpty());
    }

    /**
     * BO-03-09-01: the register restriction states the chosen registers, the
     * excluded ones, both, or nothing, and binds exactly what it states.
     */
    @Test
    void theTerminalFilterStatesAndBindsWhatApplies() {
        ReportCriteria none = new ReportCriteria();
        assertEquals("", none.terminalFilter("t.terminalId"));
        jakarta.persistence.Query unbound = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        none.bindTerminals(unbound);
        org.mockito.Mockito.verifyNoInteractions(unbound);
        ReportCriteria both = new ReportCriteria();
        both.terminals.add("C02");
        both.excludedTerminals.add("C01");
        assertEquals(" and t.terminalId in :terminals and (t.terminalId is null or t.terminalId not in"
                + " :excludedTerminals)", both.terminalFilter("t.terminalId"));
        jakarta.persistence.Query bound = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        both.bindTerminals(bound);
        org.mockito.Mockito.verify(bound).setParameter("terminals", both.terminals);
        org.mockito.Mockito.verify(bound).setParameter("excludedTerminals", both.excludedTerminals);
    }

    /**
     * The article-list filter is nothing without a list, refuses every line for
     * a list selecting no article, and binds the EANs otherwise (BO-02-03-31).
     */
    @org.junit.jupiter.api.Test
    void theArticleFilterFollowsTheSavedList() {
        ReportCriteria criteria = ReportCriteria.fromParams(new jakarta.ws.rs.core.MultivaluedHashMap<>(
                java.util.Map.of("selection", "Épicerie")));
        org.junit.jupiter.api.Assertions.assertEquals("Épicerie", criteria.selection);
        org.junit.jupiter.api.Assertions.assertEquals("", criteria.articleFilter("l.ean"));
        jakarta.persistence.Query query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        criteria.bindArticles(query);
        criteria.restrictToArticles(java.util.Set.of());
        org.junit.jupiter.api.Assertions.assertEquals(" and 1 = 0", criteria.articleFilter("l.ean"));
        criteria.bindArticles(query);
        criteria.restrictToArticles(java.util.Set.of("300"));
        org.junit.jupiter.api.Assertions.assertEquals(" and l.ean in :articleEans", criteria.articleFilter("l.ean"));
        criteria.bindArticles(query);
        org.mockito.Mockito.verify(query).setParameter("articleEans", java.util.Set.of("300"));
        org.mockito.Mockito.verifyNoMoreInteractions(query);
    }

    /**
     * The new parameters are read: hours, island, article criteria (each
     * value itself a list), multiple choice, text range and chart.
     */
    @Test
    void theLotTwentyOneParametersAreRead() {
        MultivaluedMap<String, String> params = params();
        params.add("hourFrom", "08:00");
        params.add("hourTo", "nonsense");
        params.add("island", " I1 ");
        params.add("eanFrom", "300");
        params.add("eanTo", " ");
        params.add("family", "10; 20,30");
        params.add("family", null);
        params.add("ean", "301 302");
        params.add("pick", "A");
        params.add("boundFrom", "D");
        params.add("boundTo", "M");
        params.add("chart", "PIE");
        params.add("series", "a");
        params.add("series", "a");
        params.add("series", "b");
        ReportCriteria criteria = ReportCriteria.fromParams(params);
        assertEquals(java.time.LocalTime.of(8, 0), criteria.hourFrom);
        assertNull(criteria.hourTo);
        assertEquals("I1", criteria.island);
        assertEquals("300", criteria.eanFrom);
        assertNull(criteria.eanTo);
        assertEquals(Set.of("10", "20", "30"), criteria.families);
        assertEquals(Set.of("301", "302"), criteria.eans);
        assertEquals(Set.of("A"), criteria.picks);
        assertEquals("D", criteria.boundFrom);
        assertEquals("M", criteria.boundTo);
        assertEquals("PIE", criteria.chart);
        assertEquals(List.of("a", "b"), criteria.series);
        params.putSingle("hourFrom", " ");
        assertNull(ReportCriteria.fromParams(params).hourFrom);
    }

    /**
     * The time-of-day range keeps everything when unset, refuses a null moment
     * under a range, and keeps the moments from its first time inclusive to
     * its last one exclusive, each bound optional.
     */
    @Test
    void theHourRangeBoundsTheMoments() {
        ReportCriteria criteria = new ReportCriteria();
        java.time.LocalDateTime nine = java.time.LocalDateTime.of(2026, 10, 1, 9, 0);
        assertTrue(criteria.inHours(null));
        assertFalse(criteria.hasHours());
        criteria.hourFrom = java.time.LocalTime.of(9, 0);
        assertTrue(criteria.hasHours());
        assertFalse(criteria.inHours(null));
        assertTrue(criteria.inHours(nine));
        assertFalse(criteria.inHours(nine.minusMinutes(1)));
        criteria.hourTo = java.time.LocalTime.of(10, 0);
        assertFalse(criteria.inHours(nine.plusHours(1)));
        assertTrue(criteria.inHours(nine.plusMinutes(59)));
        criteria.hourFrom = null;
        assertTrue(criteria.hasHours());
        assertTrue(criteria.inHours(nine.minusHours(5)));
    }

    /**
     * The island restricts the registers: to its own, or to none at all.
     */
    @Test
    void theIslandRestrictsTheRegisters() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.restrictToIsland(List.of());
        assertEquals(" and 1 = 0", criteria.terminalFilter("t.terminalId"));
        criteria.restrictToIsland(List.of("C01"));
        assertEquals(" and t.terminalId in :islandTerminals", criteria.terminalFilter("t.terminalId"));
        jakarta.persistence.Query query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        criteria.bindTerminals(query);
        org.mockito.Mockito.verify(query).setParameter("islandTerminals", Set.of("C01"));
        ReportCriteria empty = new ReportCriteria();
        empty.restrictToIsland(List.of());
        jakarta.persistence.Query other = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        empty.bindTerminals(other);
        org.mockito.Mockito.verifyNoInteractions(other);
    }

    /**
     * The line filter combines the saved list, the EAN range, the EAN codes
     * and the families, and binds each of them; nothing chosen states nothing.
     */
    @Test
    void theLineFilterCombinesEveryArticleCriterion() {
        ReportCriteria criteria = new ReportCriteria();
        assertEquals("", criteria.lineFilter("l"));
        assertFalse(criteria.hasArticleFilter());
        criteria.restrictToArticles(Set.of("1"));
        criteria.eanFrom = "100";
        criteria.eanTo = "200";
        criteria.eans.add("150");
        criteria.families.add("10");
        criteria.families.add("20");
        assertEquals(" and l.ean in :articleEans and l.ean >= :eanFrom and l.ean <= :eanTo and l.ean in :eans"
                + " and (l.familyCode like :family0 or l.familyCode like :family1)", criteria.lineFilter("l"));
        jakarta.persistence.Query query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        criteria.bindLines(query);
        org.mockito.Mockito.verify(query).setParameter("articleEans", Set.of("1"));
        org.mockito.Mockito.verify(query).setParameter("eanFrom", "100");
        org.mockito.Mockito.verify(query).setParameter("eanTo", "200");
        org.mockito.Mockito.verify(query).setParameter("eans", Set.of("150"));
        org.mockito.Mockito.verify(query).setParameter("family0", "10%");
        org.mockito.Mockito.verify(query).setParameter("family1", "20%");
        assertTrue(criteria.hasArticleFilter());
        jakarta.persistence.Query none = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        new ReportCriteria().bindLines(none);
        org.mockito.Mockito.verifyNoInteractions(none);
    }

    /**
     * Each article criterion alone makes an article filter.
     */
    @Test
    void eachArticleCriterionAloneFilters() {
        ReportCriteria list = new ReportCriteria();
        list.restrictToArticles(Set.of());
        assertTrue(list.hasArticleFilter());
        ReportCriteria from = new ReportCriteria();
        from.eanFrom = "1";
        assertTrue(from.hasArticleFilter());
        ReportCriteria to = new ReportCriteria();
        to.eanTo = "1";
        assertTrue(to.hasArticleFilter());
        ReportCriteria eans = new ReportCriteria();
        eans.eans.add("1");
        assertTrue(eans.hasArticleFilter());
        ReportCriteria families = new ReportCriteria();
        families.families.add("1");
        assertTrue(families.hasArticleFilter());
    }

    /**
     * The cashier selection filters a query, binds it, and keeps a cashier in
     * Java; an empty selection keeps everyone.
     */
    @Test
    void theCashierSelectionFiltersAndKeeps() {
        ReportCriteria criteria = new ReportCriteria();
        assertEquals("", criteria.cashierFilter("t.cashier.badgeId"));
        assertTrue(criteria.keepsCashier(null));
        jakarta.persistence.Query none = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        criteria.bindCashiers(none);
        org.mockito.Mockito.verifyNoInteractions(none);
        criteria.cashiers.add("111");
        assertEquals(" and t.cashier.badgeId in :cashierBadges", criteria.cashierFilter("t.cashier.badgeId"));
        jakarta.persistence.Query query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        criteria.bindCashiers(query);
        org.mockito.Mockito.verify(query).setParameter("cashierBadges", Set.of("111"));
        assertTrue(criteria.keepsCashier("111"));
        assertFalse(criteria.keepsCashier("222"));
        assertFalse(criteria.keepsCashier(null));
    }

    /**
     * The register criteria keep a register in Java: the chosen ones, the
     * island's, never a deactivated one; a null register only when nothing
     * restricts.
     */
    @Test
    void theRegisterCriteriaKeepInJava() {
        ReportCriteria criteria = new ReportCriteria();
        assertTrue(criteria.keepsTerminal(null));
        assertTrue(criteria.keepsTerminal("C01"));
        criteria.excludedTerminals.add("C09");
        assertFalse(criteria.keepsTerminal("C09"));
        ReportCriteria chosen = new ReportCriteria();
        chosen.terminals.add("C01");
        assertTrue(chosen.keepsTerminal("C01"));
        assertFalse(chosen.keepsTerminal("C02"));
        assertFalse(chosen.keepsTerminal(null));
        ReportCriteria island = new ReportCriteria();
        island.restrictToIsland(List.of("C01"));
        assertTrue(island.keepsTerminal("C01"));
        assertFalse(island.keepsTerminal("C02"));
        assertFalse(island.keepsTerminal(null));
    }

    /**
     * The multiple choice keeps everything when nothing is picked, else only
     * what is; the text range keeps everything unbounded, refuses a null text
     * under a bound, and compares without regard to case.
     */
    @Test
    void thePicksAndTheBoundsKeep() {
        ReportCriteria criteria = new ReportCriteria();
        assertTrue(criteria.picked(null));
        criteria.picks.add("A");
        assertTrue(criteria.picked("A"));
        assertFalse(criteria.picked("B"));
        assertFalse(criteria.picked(null));
        assertTrue(criteria.withinBounds(null));
        criteria.boundFrom = "durand";
        assertFalse(criteria.withinBounds(null));
        assertTrue(criteria.withinBounds("Martin"));
        assertFalse(criteria.withinBounds("Bernard"));
        criteria.boundTo = "MARTIN";
        assertTrue(criteria.withinBounds("martin"));
        assertFalse(criteria.withinBounds("Petit"));
        criteria.boundFrom = null;
        assertTrue(criteria.withinBounds("Bernard"));
    }

    /**
     * The detail is the option DETAIL, anything else the summary.
     */
    @Test
    void theDetailIsTheDetailOption() {
        ReportCriteria criteria = new ReportCriteria();
        assertFalse(criteria.detailed());
        criteria.option = "DETAIL";
        assertTrue(criteria.detailed());
    }

    /**
     * A copy over another range keeps every criterion but the period.
     */
    @Test
    void aCopyOverARangeKeepsTheCriteria() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.cashiers.add("111");
        criteria.option = "X";
        criteria.island = "I1";
        criteria.language = "pt";
        ReportCriteria copy = criteria.within(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11));
        assertEquals(ReportPeriod.RANGE, copy.period);
        assertEquals(java.time.LocalDateTime.of(2026, 10, 5, 0, 0), copy.from());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 12, 0, 0), copy.to());
        assertEquals(Set.of("111"), copy.cashiers);
        assertEquals("X", copy.option);
        assertEquals("I1", copy.island);
        assertEquals("pt", copy.language);
    }

    /**
     * The edition's language is read from the {@code lang} parameter a planned
     * extraction carries; without it, or blank, the edition is French
     * (BO-06-01-02).
     */
    @Test
    void theLanguageIsReadFromItsParameter() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        assertEquals("fr", ReportCriteria.fromParams(params).language);
        params.add("lang", " ");
        assertEquals("fr", ReportCriteria.fromParams(params).language);
        params.putSingle("lang", "nl");
        assertEquals("nl", ReportCriteria.fromParams(params).language);
    }

    /**
     * A pasted list of codes is split on semicolons, commas and spaces, the
     * blanks and a missing value dropped.
     */
    @Test
    void pastedCodesAreSplit() {
        jakarta.ws.rs.core.MultivaluedHashMap<String, String> params = new jakarta.ws.rs.core.MultivaluedHashMap<>();
        params.put("ean", java.util.Arrays.asList(null, " 301;302, 303 "));
        assertEquals(java.util.Set.of("301", "302", "303"), ReportCriteria.fromParams(params).eans);
    }
}
