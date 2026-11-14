package com.intermarche.edge.ui.report;

/**
 * The EDITION CRITERIA a report may offer beside its period — each one a field
 * of the report form that the report actually honours.
 *
 * <p>Declared by the report rather than shown everywhere: a cashier selector on
 * a state that ignores it would let the operator believe they had restricted a
 * figure they had not. The frame draws the fields a report declares and nothing
 * else, so the form of a state is always a true description of what it reads.
 */
public enum ReportFilter {

    /** The registers, chosen one by one (BO-03-09-01). */
    TERMINALS,

    /** The checkout island whose registers the report keeps (BO-06-06-06). */
    ISLAND,

    /** The cashiers, one, several or all (BO-06-06-02, -03, -07, BO-06-08-01). */
    CASHIERS,

    /** The time-of-day range (BO-06-04-02, BO-06-04-07). */
    HOURS,

    /**
     * The article criteria: EAN range, EAN codes, families and the saved
     * article list (BO-06-04-02, BO-06-04-07, BO-06-08-02).
     */
    ARTICLES
}
