package com.intermarche.edge.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.List;

/**
 * A PROMOTION as the store's states name it (BO-06-08-01, BO-06-08-02): its
 * number — the offer identifier the valuation engine stamps on the lines it
 * values, or the rule code of a loyalty advantage — its label, its TYPE (by
 * value or in percent) and whether it is FINANCED UPSTREAM by the supplier.
 *
 * <p>The engine values the basket and says which offer valued which line; what
 * it does not say is how the shop accounts the offer. That is administered
 * here, on the back office, and read by the promotional states: a promotion
 * the shop has not described is still counted, under its number, its type read
 * off its label.
 */
@Entity
@Table(name = "promotions")
public class Promotion extends PanacheEntity {

    /** A discount of an amount. */
    public static final String BY_VALUE = "VALUE";

    /** A discount of a rate. */
    public static final String BY_PERCENT = "PERCENT";

    /** The promotion's number: the offer identifier or the rule code. */
    @Column(name = "code", nullable = false, unique = true, length = 60)
    public String code;

    /** The label the states print. */
    @Column(name = "label", nullable = false, length = 120)
    public String label;

    /** The type: {@link #BY_VALUE} or {@link #BY_PERCENT}. */
    @Column(name = "kind", nullable = false, length = 10)
    public String kind = BY_VALUE;

    /** Whether the supplier finances it (« financement AMONT »). */
    @Column(name = "upstream_funded", nullable = false)
    public boolean upstreamFunded;

    /**
     * Finds a promotion by its number.
     *
     * @param code the number, possibly null
     * @return the promotion, or null
     */
    public static Promotion findByCode(String code) {
        return code == null ? null : find("code", code.trim()).firstResult();
    }

    /**
     * Lists every promotion, by number.
     *
     * @return the promotions
     */
    public static List<Promotion> listOrdered() {
        return list("order by code");
    }
}
