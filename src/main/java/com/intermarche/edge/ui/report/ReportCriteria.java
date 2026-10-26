package com.intermarche.edge.ui.report;

import jakarta.ws.rs.core.MultivaluedMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The EDITION PARAMETERS of a report: the period, the registers and the
 * cashiers it is restricted to, and a free option every report reads its own
 * way.
 *
 * <p>Parsed from the query string and echoed back to the form, so a report,
 * its export and its pager all describe the same search: the export is the
 * list, in another container (BO-06-01-01/02/03), which only holds if the three
 * read their criteria from one place.
 *
 * <p>Beyond the period and the registers, the criteria carry what several
 * states share: a time-of-day range, a checkout island, the article criteria
 * (EAN range, EAN codes, families, saved list), a multiple choice and a text
 * range a report reads its own way, and the chart the operator asked for
 * (BO-06-01-04).
 *
 * <p>An ABSENT criterion contributes nothing — it is not a wildcard written as
 * a value, it is a condition that is never added to the query. That is what
 * lets the parameters combine freely without the report having to enumerate
 * their combinations.
 */
public class ReportCriteria {

    /** The chosen period, never null. */
    public ReportPeriod period = ReportPeriod.TODAY;

    /** The date the period is computed around, or null for today. */
    public LocalDate reference;

    /** The first day of a free range, or null. */
    public LocalDate rangeFrom;

    /** The last day of a free range (inclusive), or null. */
    public LocalDate rangeTo;

    /** The registers the report is restricted to, empty for all. */
    public Set<String> terminals = new LinkedHashSet<>();

    /**
     * The registers left out of the report because the back office deactivated
     * them (BO-03-09-01), empty when none is; a register the operator chose
     * explicitly is never left out.
     */
    public Set<String> excludedTerminals = new LinkedHashSet<>();

    /** Whether the deactivated registers are kept in the report (BO-03-09-01). */
    public boolean includeInactive;

    /** The cashier badge identifiers the report is restricted to, empty for all. */
    public Set<String> cashiers = new LinkedHashSet<>();

    /**
     * The report-specific option, or null.
     *
     * <p>One slot, not a map: the reports that need a choice of their own need
     * exactly one — the price-forcing report separates markdowns from markups
     * (BO-06-06-10), the pending-ticket report separates recalled from
     * un-recalled tickets (BO-06-06-01), the hourly report chooses its slice
     * width (BO-06-04-06). A second slot would be invented for no report.
     */
    public String option;

    /**
     * The name of the saved article list restricting the report (BO-02-03-31),
     * or null for every article.
     */
    public String selection;

    /**
     * The EANs of the saved list's articles, resolved by the caller, or null
     * when no list restricts the report.
     */
    public Set<String> articleEans;

    /**
     * The first time of day the report keeps, inclusive, or null from midnight
     * (« plage horaire », BO-06-04-02, BO-06-04-07).
     */
    public LocalTime hourFrom;

    /** The first time of day the report no longer keeps, or null to midnight. */
    public LocalTime hourTo;

    /** The checkout island the report is restricted to (BO-06-06-06), or null. */
    public String island;

    /**
     * The registers of the chosen island, resolved by the caller, or null when
     * no island restricts the report.
     */
    public Set<String> islandTerminals;

    /** The first EAN of the article range, inclusive, or null (BO-06-04-02). */
    public String eanFrom;

    /** The last EAN of the article range, inclusive, or null. */
    public String eanTo;

    /**
     * The family codes the article lines must fall under, empty for every
     * family: a code selects itself and every node below it, the nomenclature
     * codes being positional (a rayon code selects its families).
     */
    public Set<String> families = new LinkedHashSet<>();

    /** The EAN codes the article lines must carry, empty for every article. */
    public Set<String> eans = new LinkedHashSet<>();

    /**
     * The report-specific MULTIPLE choice: the surveys, the reason-code types,
     * the promotions the report is restricted to, empty for all of them.
     */
    public Set<String> picks = new LinkedHashSet<>();

    /** The lower bound of the report-specific text range, or null. */
    public String boundFrom;

    /** The upper bound of the report-specific text range, or null. */
    public String boundTo;

    /** The chart type asked for (BO-06-01-04), or null for the default bars. */
    public String chart;

    /** The keys of the columns the chart plots, empty for the report's own. */
    public List<String> series = new java.util.ArrayList<>();

    /**
     * The language the exports write their headings and labels in — the page's
     * or the planning operator's — French by default; the values stay
     * machine-readable whatever the language (BO-06-01-02).
     */
    public String language = com.intermarche.edge.ui.admin.BackOfficeMessages.DEFAULT_LANGUAGE;

    /**
     * Returns the first instant of the resolved period.
     *
     * @return the inclusive lower bound
     */
    public LocalDateTime from() {
        return period.from(reference, rangeFrom);
    }

    /**
     * Returns the first instant after the resolved period.
     *
     * @return the exclusive upper bound
     */
    public LocalDateTime to() {
        return period.to(reference, rangeTo);
    }

    /**
     * Returns the chosen option, or the given fallback when none was chosen.
     *
     * @param fallback the value a report uses when the operator chose nothing
     * @return the option in force
     */
    public String optionOr(String fallback) {
        return (option == null || option.isBlank()) ? fallback : option;
    }

    /**
     * Parses the edition parameters out of a query string.
     *
     * @param params the query parameters, possibly empty
     * @return the criteria, with today's period when nothing was submitted
     */
    public static ReportCriteria fromParams(MultivaluedMap<String, String> params) {
        ReportCriteria criteria = new ReportCriteria();
        if (params == null) {
            return criteria;
        }
        criteria.period = parsePeriod(first(params, "period"));
        criteria.reference = parseDate(first(params, "reference"));
        criteria.rangeFrom = parseDate(first(params, "from"));
        criteria.rangeTo = parseDate(first(params, "to"));
        criteria.option = blankToNull(first(params, "option"));
        addAll(criteria.terminals, params.get("terminal"));
        addAll(criteria.cashiers, params.get("cashier"));
        criteria.includeInactive = "true".equals(first(params, "inactive"));
        criteria.selection = blankToNull(first(params, "selection"));
        criteria.hourFrom = parseTime(first(params, "hourFrom"));
        criteria.hourTo = parseTime(first(params, "hourTo"));
        criteria.island = blankToNull(first(params, "island"));
        criteria.eanFrom = blankToNull(first(params, "eanFrom"));
        criteria.eanTo = blankToNull(first(params, "eanTo"));
        addTokens(criteria.families, params.get("family"));
        addTokens(criteria.eans, params.get("ean"));
        addAll(criteria.picks, params.get("pick"));
        criteria.boundFrom = blankToNull(first(params, "boundFrom"));
        criteria.boundTo = blankToNull(first(params, "boundTo"));
        criteria.chart = blankToNull(first(params, "chart"));
        java.util.Set<String> series = new LinkedHashSet<>();
        addAll(series, params.get("series"));
        criteria.series.addAll(series);
        String language = blankToNull(first(params, "lang"));
        if (language != null) {
            criteria.language = language;
        }
        return criteria;
    }

    /**
     * Copies these criteria over another range of days, every other criterion
     * kept: a weekly synthesis edits one week after the other over the period
     * chosen.
     *
     * @param first the first day, inclusive
     * @param last the last day, inclusive
     * @return the copy
     */
    public ReportCriteria within(LocalDate first, LocalDate last) {
        ReportCriteria copy = new ReportCriteria();
        copy.period = ReportPeriod.RANGE;
        copy.rangeFrom = first;
        copy.rangeTo = last;
        copy.terminals = terminals;
        copy.excludedTerminals = excludedTerminals;
        copy.includeInactive = includeInactive;
        copy.cashiers = cashiers;
        copy.option = option;
        copy.hourFrom = hourFrom;
        copy.hourTo = hourTo;
        copy.island = island;
        copy.islandTerminals = islandTerminals;
        copy.picks = picks;
        copy.language = language;
        return copy;
    }

    /**
     * Whether a moment falls inside the chosen time-of-day range: from the
     * first time inclusive to the last one exclusive, each bound optional.
     *
     * @param when the moment, possibly null
     * @return true when no range is chosen or the moment is inside it; false
     *         for a null moment under a range
     */
    public boolean inHours(LocalDateTime when) {
        if (hourFrom == null && hourTo == null) {
            return true;
        }
        if (when == null) {
            return false;
        }
        LocalTime time = when.toLocalTime();
        return (hourFrom == null || !time.isBefore(hourFrom)) && (hourTo == null || time.isBefore(hourTo));
    }

    /**
     * Whether a time-of-day range restricts the report.
     *
     * @return true when either bound is chosen
     */
    public boolean hasHours() {
        return hourFrom != null || hourTo != null;
    }

    /**
     * Restricts the report to the registers of the chosen island
     * (BO-06-06-06), resolved by the caller.
     *
     * @param terminals the island's registers, possibly empty
     */
    public void restrictToIsland(java.util.Collection<String> terminals) {
        islandTerminals = new LinkedHashSet<>(terminals);
    }

    /**
     * The JPQL fragment restricting the ticket lines to every article
     * criterion at once: the saved list (BO-02-03-31), the EAN range, the EAN
     * codes and the families (BO-06-04-02, BO-06-04-07). Each absent criterion
     * contributes nothing.
     *
     * @param line the alias of the ticket line, e.g. {@code l}
     * @return the fragment, empty when no article criterion is chosen
     */
    public String lineFilter(String line) {
        StringBuilder filter = new StringBuilder(articleFilter(line + ".ean"));
        if (eanFrom != null) {
            filter.append(" and ").append(line).append(".ean >= :eanFrom");
        }
        if (eanTo != null) {
            filter.append(" and ").append(line).append(".ean <= :eanTo");
        }
        if (!eans.isEmpty()) {
            filter.append(" and ").append(line).append(".ean in :eans");
        }
        if (!families.isEmpty()) {
            filter.append(" and (");
            for (int i = 0; i < families.size(); i++) {
                filter.append(i == 0 ? "" : " or ").append(line).append(".familyCode like :family").append(i);
            }
            filter.append(')');
        }
        return filter.toString();
    }

    /**
     * Binds the parameters of {@link #lineFilter(String)}.
     *
     * @param query the query built with that filter
     */
    public void bindLines(jakarta.persistence.Query query) {
        bindArticles(query);
        if (eanFrom != null) {
            query.setParameter("eanFrom", eanFrom);
        }
        if (eanTo != null) {
            query.setParameter("eanTo", eanTo);
        }
        if (!eans.isEmpty()) {
            query.setParameter("eans", eans);
        }
        int i = 0;
        for (String family : families) {
            query.setParameter("family" + i, family + "%");
            i++;
        }
    }

    /**
     * Whether an article criterion of {@link #lineFilter(String)} is chosen.
     *
     * @return true when a list, a range, a code or a family restricts the lines
     */
    public boolean hasArticleFilter() {
        return articleEans != null || eanFrom != null || eanTo != null || !eans.isEmpty() || !families.isEmpty();
    }

    /**
     * The JPQL fragment restricting a query to the chosen cashiers.
     *
     * @param badge the qualified badge column, e.g. {@code t.cashier.badgeId}
     * @return the fragment, empty when every cashier is kept
     */
    public String cashierFilter(String badge) {
        return cashiers.isEmpty() ? "" : " and " + badge + " in :cashierBadges";
    }

    /**
     * Binds the parameter of {@link #cashierFilter(String)}.
     *
     * @param query the query built with that filter
     */
    public void bindCashiers(jakarta.persistence.Query query) {
        if (!cashiers.isEmpty()) {
            query.setParameter("cashierBadges", cashiers);
        }
    }

    /**
     * Whether a register is kept by the register criteria, read in Java for a
     * row the query could not restrict: the registers chosen, the island's,
     * and the deactivated ones left out.
     *
     * @param terminal the register, possibly null
     * @return true when the register is kept
     */
    public boolean keepsTerminal(String terminal) {
        if (!terminals.isEmpty() && (terminal == null || !terminals.contains(terminal))) {
            return false;
        }
        if (islandTerminals != null && (terminal == null || !islandTerminals.contains(terminal))) {
            return false;
        }
        return terminal == null || !excludedTerminals.contains(terminal);
    }

    /**
     * Whether a cashier is kept by the cashier selection.
     *
     * @param badge the cashier's badge, possibly null
     * @return true when every cashier is kept or this one is chosen
     */
    public boolean keepsCashier(String badge) {
        return cashiers.isEmpty() || (badge != null && cashiers.contains(badge));
    }

    /**
     * Whether a value is kept by the report-specific multiple choice.
     *
     * @param value the value, possibly null
     * @return true when nothing is picked or this value is
     */
    public boolean picked(String value) {
        return picks.isEmpty() || (value != null && picks.contains(value));
    }

    /**
     * Whether a text falls inside the report-specific range, bounds included,
     * compared without regard to case: a range of names from « DUPONT » to
     * « MARTIN » keeps « Durand ».
     *
     * @param value the text, possibly null
     * @return true when no bound is chosen or the text is inside them; false
     *         for a null text under a bound
     */
    public boolean withinBounds(String value) {
        if (boundFrom == null && boundTo == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return (boundFrom == null || value.compareToIgnoreCase(boundFrom) >= 0)
                && (boundTo == null || value.compareToIgnoreCase(boundTo) <= 0);
    }

    /**
     * Whether the operator chose the detailed edition rather than the summary
     * (« résumé ou détail », BO-06-06-03, BO-06-07-01, BO-06-07-02).
     *
     * @return true when the option reads {@code DETAIL}
     */
    public boolean detailed() {
        return "DETAIL".equals(option);
    }

    /**
     * Restricts the report to the articles of a saved list (BO-02-03-31): the
     * EANs the list's criteria select, resolved by the caller.
     *
     * @param eans the EANs of the list's articles
     */
    public void restrictToArticles(Set<String> eans) {
        articleEans = eans;
    }

    /**
     * The JPQL fragment restricting the ticket lines to the saved list, or
     * nothing when no list restricts the report. A list selecting no article
     * selects no line.
     *
     * @param column the qualified EAN column of the line, e.g. {@code l.ean}
     * @return the fragment, empty when unrestricted
     */
    public String articleFilter(String column) {
        if (articleEans == null) {
            return "";
        }
        return articleEans.isEmpty() ? " and 1 = 0" : " and " + column + " in :articleEans";
    }

    /**
     * Binds the EANs of the saved list on a query built with
     * {@link #articleFilter(String)}.
     *
     * @param query the query
     */
    public void bindArticles(jakarta.persistence.Query query) {
        if (articleEans != null && !articleEans.isEmpty()) {
            query.setParameter("articleEans", articleEans);
        }
    }

    /**
     * Leaves the deactivated registers out of the report (BO-03-09-01), unless
     * the operator asked to keep them; a register chosen explicitly stays.
     *
     * @param deactivated the identifiers of the deactivated registers
     */
    public void excludeDeactivated(List<String> deactivated) {
        if (includeInactive) {
            return;
        }
        for (String terminal : deactivated) {
            if (!terminals.contains(terminal)) {
                excludedTerminals.add(terminal);
            }
        }
    }

    /**
     * Builds the register restriction of a query: the registers chosen, then
     * the registers left out, nothing at all when neither applies.
     *
     * @param column the register column of the query, for example {@code t.terminalId}
     * @return the JPQL condition, starting with {@code and}, or an empty string
     */
    public String terminalFilter(String column) {
        StringBuilder filter = new StringBuilder();
        if (!terminals.isEmpty()) {
            filter.append(" and ").append(column).append(" in :terminals");
        }
        if (islandTerminals != null) {
            filter.append(islandTerminals.isEmpty() ? " and 1 = 0"
                    : " and " + column + " in :islandTerminals");
        }
        if (!excludedTerminals.isEmpty()) {
            filter.append(" and (").append(column).append(" is null or ").append(column)
                    .append(" not in :excludedTerminals)");
        }
        return filter.toString();
    }

    /**
     * Binds the parameters of {@link #terminalFilter(String)}.
     *
     * @param query the query built with that filter
     */
    public void bindTerminals(jakarta.persistence.Query query) {
        if (!terminals.isEmpty()) {
            query.setParameter("terminals", terminals);
        }
        if (islandTerminals != null && !islandTerminals.isEmpty()) {
            query.setParameter("islandTerminals", islandTerminals);
        }
        if (!excludedTerminals.isEmpty()) {
            query.setParameter("excludedTerminals", excludedTerminals);
        }
    }

    /**
     * Reads the first value of a parameter.
     *
     * @param params the query parameters
     * @param name the parameter name
     * @return the first value, or null when absent
     */
    private static String first(MultivaluedMap<String, String> params, String name) {
        List<String> values = params.get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * Adds every non-blank value of a multi-valued parameter to a set.
     *
     * @param target the set to fill
     * @param values the submitted values, possibly null
     */
    private static void addAll(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value.trim());
            }
        }
    }

    /**
     * Adds every code of a multi-valued parameter, each value being itself a
     * list separated by semicolons, commas or whitespace: a list of EANs is
     * pasted, not typed one field at a time.
     *
     * @param target the set to fill
     * @param values the submitted values, possibly null
     */
    private static void addTokens(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null) {
                for (String token : value.split("[;,\\s]+")) {
                    if (!token.isBlank()) {
                        target.add(token.trim());
                    }
                }
            }
        }
    }

    /**
     * Parses a time of day, tolerating absence and rubbish like the dates.
     *
     * @param value the submitted text, {@code HH:mm}, possibly null
     * @return the time, or null
     */
    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    /**
     * Parses a period name, falling back to the current day.
     *
     * @param value the submitted name, possibly null or unknown
     * @return the period, never null
     */
    private static ReportPeriod parsePeriod(String value) {
        if (value == null || value.isBlank()) {
            return ReportPeriod.TODAY;
        }
        try {
            return ReportPeriod.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return ReportPeriod.TODAY;
        }
    }

    /**
     * Parses an ISO date, tolerating absence and rubbish.
     *
     * <p>A malformed date is read as an ABSENT date rather than as an error: a
     * report reached from a stale link is not an incident, and answering it
     * with the current day shows the operator something they can correct.
     *
     * @param value the submitted text, possibly null
     * @return the date, or null
     */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    /**
     * Maps a blank text to null so an empty form field is an absent criterion.
     *
     * @param value the submitted text, possibly null
     * @return the trimmed text, or null when blank
     */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
