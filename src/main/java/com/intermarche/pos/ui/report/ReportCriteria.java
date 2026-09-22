package com.intermarche.pos.ui.report;

import jakarta.ws.rs.core.MultivaluedMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        return criteria;
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
