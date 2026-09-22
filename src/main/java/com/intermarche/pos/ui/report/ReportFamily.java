package com.intermarche.pos.ui.report;

/**
 * The families the report list groups its reports under — the same cut the
 * questionnaire uses for its BO-06 sub-chapters, so a reader holding the
 * requirement list finds the report where they expect it.
 */
public enum ReportFamily {

    /** Sales seen from the shop floor: by family, by hour, by register. */
    SALES("Ventes"),

    /** What the cashiers did: cancellations, refunds, reason codes, forcings. */
    CASHIERS("États caissières"),

    /** Settlements and their totals. */
    SETTLEMENTS("Règlements"),

    /** Tax breakdowns. */
    TAX("TVA"),

    /** Customers and the documents printed for them. */
    CUSTOMERS("Clients");

    /** The heading shown above the family's reports. */
    private final String label;

    /**
     * Builds a family.
     *
     * @param label the heading shown in the report list
     */
    ReportFamily(String label) {
        this.label = label;
    }

    /**
     * Returns the heading shown in the report list.
     *
     * @return the French heading
     */
    public String getLabel() {
        return label;
    }
}
