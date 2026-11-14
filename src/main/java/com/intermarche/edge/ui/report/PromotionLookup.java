package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Promotion;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads the promotions the back office DESCRIBES (BO-06-08-01, BO-06-08-02):
 * their label, their type and their upstream financing, by number, once per
 * report run.
 *
 * <p>A promotion nobody described is still reported: under its number, the
 * label the sold line carried, and a type read off that label — a label
 * stating a percentage is a discount in percent, any other a discount by
 * value — and not financed upstream.
 */
public class PromotionLookup {

    /** The promotions already read, by number; null when none carries it. */
    private final Map<String, Promotion> byCode = new HashMap<>();

    /**
     * Reads the description of a promotion, once.
     *
     * @param code the promotion's number
     * @return the description, or null when none is administered
     */
    Promotion find(String code) {
        return byCode.computeIfAbsent(code == null ? "" : code,
                key -> key.isEmpty() ? null : Promotion.findByCode(key));
    }

    /**
     * Returns the label a state prints.
     *
     * @param code the promotion's number
     * @param fallback the label the line carried, possibly null
     * @return the administered label, else the line's, else an empty text
     */
    public String label(String code, String fallback) {
        Promotion promotion = find(code);
        if (promotion != null) {
            return promotion.label;
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * Returns the promotion's type.
     *
     * @param code the promotion's number
     * @param label the label the line carried, possibly null
     * @return {@link Promotion#BY_PERCENT} or {@link Promotion#BY_VALUE}
     */
    public String kind(String code, String label) {
        Promotion promotion = find(code);
        if (promotion != null) {
            return promotion.kind;
        }
        return label != null && label.contains("%") ? Promotion.BY_PERCENT : Promotion.BY_VALUE;
    }

    /**
     * Whether the supplier finances the promotion.
     *
     * @param code the promotion's number
     * @return true only when administered so
     */
    public boolean upstream(String code) {
        Promotion promotion = find(code);
        return promotion != null && promotion.upstreamFunded;
    }
}
