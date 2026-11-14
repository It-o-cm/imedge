package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.store.Pdv;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * What a supervision or deployment console is asked to show
 * (BO-08-01-17, BO-08-03-06, BO-08-04-15/16/20, BO-08-02-02).
 *
 * <p>The ORGANISATION VIEW is the grouping — the whole organisation, by
 * country, by enseigne, by point of sale — and the three echelon filters
 * narrow it to one branch of the tree. The status filter keeps the OK or the
 * KO lines; the anomaly filter keeps the registers with one kind of trouble.
 * The ADHÉRENT is a grouping and a filter of its own (BO-02-05-05): the points
 * of sale one adhérent operates, whatever their country or enseigne.
 * Every value is optional: a blank one filters nothing.
 */
public class ConsoleFilter {

    /** The whole organisation as one group. */
    public static final String GLOBAL = "GLOBAL";

    /** One group per country. */
    public static final String COUNTRY = "COUNTRY";

    /** One group per enseigne. */
    public static final String ENSEIGNE = "ENSEIGNE";

    /** One group per point of sale. */
    public static final String PDV = "PDV";

    /** One group per adhérent (BO-02-05-05). */
    public static final String ADHERENT = "ADHERENT";

    /** The grouping. */
    public String group = PDV;

    /** The country kept, or null. */
    public String country;

    /** The enseigne kept, or null. */
    public String enseigne;

    /** The point of sale kept, or null. */
    public String pdv;

    /** The adhérent whose points of sale are kept, or null (BO-02-05-05). */
    public String adherent;

    /** OK, KO, or null for both. */
    public String status;

    /** REGISTER, STORE, CENTRAL, or null for every kind. */
    public String kind;

    /** OFFLINE, APP, PERIPHERAL, SYNC, or null for any. */
    public String anomaly;

    /** The referential domain kept, or null. */
    public String domain;

    /** The category kept, or null. */
    public String category;

    /** The channel kept, or null. */
    public String channel;

    /** The product kept, or null. */
    public String product;

    /** The deployment lot kept, or null. */
    public String lot;

    /** How many days back the statistics read. */
    public int days = 7;

    /**
     * Reads a filter out of the query parameters.
     *
     * @param params the query parameters
     * @return the filter
     */
    public static ConsoleFilter from(MultivaluedMap<String, String> params) {
        ConsoleFilter filter = new ConsoleFilter();
        String group = value(params, "group");
        filter.group = group == null ? PDV : group;
        filter.country = value(params, "country");
        filter.enseigne = value(params, "enseigne");
        filter.pdv = value(params, "pdv");
        filter.adherent = value(params, "adherent");
        filter.status = value(params, "status");
        filter.kind = value(params, "kind");
        filter.anomaly = value(params, "anomaly");
        filter.domain = value(params, "domain");
        filter.category = value(params, "category");
        filter.channel = value(params, "channel");
        filter.product = value(params, "product");
        filter.lot = value(params, "lot");
        String days = value(params, "days");
        filter.days = days != null && days.matches("\\d{1,3}") ? Integer.parseInt(days) : 7;
        return filter;
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
     * Whether a value passes an optional filter.
     *
     * @param wanted the filter's value, null for any
     * @param actual the line's value
     * @return true when nothing is filtered or the values match
     */
    public static boolean keeps(String wanted, String actual) {
        return wanted == null || wanted.equalsIgnoreCase(actual);
    }

    /**
     * Whether a line passes the status filter.
     *
     * @param ok whether the line is healthy
     * @return true when kept
     */
    public boolean keepsStatus(boolean ok) {
        return status == null || "OK".equals(status) == ok;
    }

    /**
     * Whether a line of the organisation passes the echelon filters.
     *
     * @param lineCountry the line's country
     * @param lineEnseigne the line's enseigne
     * @param linePdv the line's point of sale
     * @return true when kept
     */
    public boolean keepsEchelon(String lineCountry, String lineEnseigne, String linePdv) {
        return keeps(country, lineCountry) && keeps(enseigne, lineEnseigne) && keeps(pdv, linePdv)
                && (adherent == null || keeps(adherent, Pdv.adherentOf(linePdv)));
    }

    /**
     * The group a line falls in.
     *
     * @param lineCountry the line's country
     * @param lineEnseigne the line's enseigne
     * @param linePdv the line's point of sale
     * @return the group's label, a dash when the echelon is unknown
     */
    public String groupOf(String lineCountry, String lineEnseigne, String linePdv) {
        String label = switch (group) {
            case GLOBAL -> "Organisation";
            case COUNTRY -> lineCountry;
            case ENSEIGNE -> lineEnseigne;
            case ADHERENT -> Pdv.adherentOf(linePdv);
            default -> linePdv;
        };
        return label == null ? "—" : label;
    }

    /**
     * The query string reproducing this filter, for the export links.
     *
     * @return the parameters, URL-encoded
     */
    public String query() {
        StringBuilder query = new StringBuilder("group=").append(encode(group));
        append(query, "country", country);
        append(query, "enseigne", enseigne);
        append(query, "pdv", pdv);
        append(query, "adherent", adherent);
        append(query, "status", status);
        append(query, "kind", kind);
        append(query, "anomaly", anomaly);
        append(query, "domain", domain);
        append(query, "category", category);
        append(query, "channel", channel);
        append(query, "product", product);
        append(query, "lot", lot);
        return query.append("&days=").append(days).toString();
    }

    /**
     * Appends one optional parameter.
     *
     * @param query the query being built
     * @param name the parameter
     * @param value its value, skipped when null
     */
    private static void append(StringBuilder query, String name, String value) {
        if (value != null) {
            query.append('&').append(name).append('=').append(encode(value));
        }
    }

    /**
     * URL-encodes a value.
     *
     * @param value the value
     * @return the encoded value
     */
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
