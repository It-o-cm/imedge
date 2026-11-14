package com.intermarche.edge.ui.journal;

import com.intermarche.edge.domain.deploy.UplinkedTicket;
import jakarta.ws.rs.core.MultivaluedMap;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The parsed form of the electronic journal regrouped by adhérent
 * (BO-02-05-05): the adhérent whose points of sale are read, the optional
 * echelon filters — country, enseigne, point of sale — and the usual journal
 * criteria: period, register, ticket number, amount range and type.
 *
 * <p>Every field is optional: a blank one filters nothing, and a value that
 * does not parse (a malformed day, a non-numeric amount, an unknown type) is
 * treated as absent — the journal is a read screen, a bad filter narrows
 * nothing.
 */
public class AdherentJournalCriteria {

    /** The journal types a search may keep. */
    public static final List<String> KINDS = List.of(UplinkedTicket.SALE, UplinkedTicket.NEGATIVE,
            UplinkedTicket.CANCELLED);

    /** The adhérent whose points of sale are read, or null for every one. */
    public String adherent;

    /** The country kept, or null. */
    public String country;

    /** The enseigne kept, or null. */
    public String enseigne;

    /** The point of sale kept, or null. */
    public String pdv;

    /** The first day, inclusive, or null. */
    public LocalDate dateFrom;

    /** The last day, inclusive, or null. */
    public LocalDate dateTo;

    /** The register identifier, or null. */
    public String terminal;

    /** A part of the ticket number, or null. */
    public String ticket;

    /** The lowest tax-included amount, or null. */
    public BigDecimal amountMin;

    /** The highest tax-included amount, or null. */
    public BigDecimal amountMax;

    /** The journal type kept, one of {@link #KINDS}, or null. */
    public String kind;

    /** The 1-based page asked. */
    public int page = 1;

    /**
     * Reads the criteria out of the query parameters.
     *
     * @param params the query parameters
     * @return the criteria
     */
    public static AdherentJournalCriteria from(MultivaluedMap<String, String> params) {
        AdherentJournalCriteria criteria = new AdherentJournalCriteria();
        criteria.adherent = value(params, "adherent");
        criteria.country = value(params, "country");
        criteria.enseigne = value(params, "enseigne");
        criteria.pdv = value(params, "pdv");
        criteria.dateFrom = day(value(params, "dateFrom"));
        criteria.dateTo = day(value(params, "dateTo"));
        criteria.terminal = value(params, "terminal");
        criteria.ticket = value(params, "ticket");
        criteria.amountMin = amount(value(params, "amountMin"));
        criteria.amountMax = amount(value(params, "amountMax"));
        String kind = value(params, "kind");
        criteria.kind = kind != null && KINDS.contains(kind) ? kind : null;
        String page = value(params, "page");
        criteria.page = page != null && page.matches("\\d{1,6}") ? Integer.parseInt(page) : 1;
        return criteria;
    }

    /**
     * Reads one parameter, blank as absent.
     *
     * @param params the query parameters
     * @param name the parameter
     * @return the trimmed value, or null
     */
    static String value(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Reads an ISO day, an unreadable one counting as none.
     *
     * @param value the day, possibly null
     * @return the day, or null
     */
    static LocalDate day(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /**
     * Reads an amount, a French comma accepted, an unreadable one counting as
     * none.
     *
     * @param value the amount, possibly null
     * @return the amount, or null
     */
    static BigDecimal amount(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    /**
     * Whether an echelon filter narrows the points of sale read.
     *
     * @return true when the adhérent, the country, the enseigne or the point of sale is set
     */
    public boolean narrowsEchelons() {
        return adherent != null || country != null || enseigne != null || pdv != null;
    }

    /**
     * The query string reproducing these criteria without their page, ending
     * with an ampersand when not empty, for the pager links.
     *
     * @return the parameters, URL-encoded
     */
    public String query() {
        StringBuilder query = new StringBuilder();
        append(query, "adherent", adherent);
        append(query, "country", country);
        append(query, "enseigne", enseigne);
        append(query, "pdv", pdv);
        append(query, "dateFrom", dateFrom);
        append(query, "dateTo", dateTo);
        append(query, "terminal", terminal);
        append(query, "ticket", ticket);
        append(query, "amountMin", amountMin);
        append(query, "amountMax", amountMax);
        append(query, "kind", kind);
        return query.toString();
    }

    /**
     * Appends one optional parameter.
     *
     * @param query the query being built
     * @param name the parameter
     * @param value its value, skipped when null
     */
    private static void append(StringBuilder query, String name, Object value) {
        if (value != null) {
            query.append(name).append('=').append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8))
                    .append('&');
        }
    }
}
