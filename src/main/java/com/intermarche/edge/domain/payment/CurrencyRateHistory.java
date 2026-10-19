package com.intermarche.edge.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ONE CHANGE OF ONE EXCHANGE RATE, kept (BO-03-02-40).
 *
 * <p>The currency referential has carried a rate all along; what it never
 * carried was WHEN that rate became the rate. The questionnaire asks for the
 * history explicitly — "il est nécessaire de pouvoir gérer un taux de change
 * avec un historique (stocker la date de modification)" — and the reason is
 * not curiosity: a frontier store that took francs on Tuesday at one rate
 * cannot justify Tuesday's till with Wednesday's rate.
 *
 * <p>Written by the currency screen and never edited: a history that can be
 * corrected is not a history. The rate in force is the newest row, which is
 * also the one on the currency itself — the column stays, so every reader
 * that already asks the currency for its rate keeps working.
 */
@Entity
@Table(name = "currency_rate_history",
        indexes = @Index(name = "idx_currency_rate_history",
                columnList = "currency_code, changed_at"))
public class CurrencyRateHistory extends PanacheEntity {

    /** The currency whose rate changed. */
    @Column(name = "currency_code", nullable = false, length = 10)
    public String currencyCode;

    /** The rate that came into force, in euros for one unit. */
    @Column(name = "euro_per_unit", nullable = false, precision = 19, scale = 6)
    public BigDecimal euroPerUnit;

    /** The rate it replaced, or null for the first one recorded. */
    @Column(name = "previous_euro_per_unit", precision = 19, scale = 6)
    public BigDecimal previousEuroPerUnit;

    /** When it came into force. */
    @Column(name = "changed_at", nullable = false)
    public LocalDateTime changedAt = LocalDateTime.now();

    /** Who changed it, as the back office knows them. */
    @Column(name = "changed_by", length = 100)
    public String changedBy;

    /**
     * Records a rate change, and only a CHANGE.
     *
     * <p>Saving a currency without touching its rate writes nothing: a history
     * padded with rows that say the same figure hides the three that do not.
     *
     * @param currencyCode the currency
     * @param previous the rate before, or null when there was none
     * @param next the rate after
     * @param changedBy who changed it, or null
     */
    public static void record(String currencyCode, BigDecimal previous, BigDecimal next,
            String changedBy) {
        if (next == null || (previous != null && previous.compareTo(next) == 0)) {
            return;
        }
        CurrencyRateHistory entry = new CurrencyRateHistory();
        entry.currencyCode = currencyCode;
        entry.previousEuroPerUnit = previous;
        entry.euroPerUnit = next;
        entry.changedBy = changedBy;
        entry.persist();
    }

    /**
     * The rate changes of one currency, newest first.
     *
     * @param currencyCode the currency
     * @return its history, newest first
     */
    public static List<CurrencyRateHistory> listFor(String currencyCode) {
        return list("currencyCode = ?1 order by changedAt desc", currencyCode);
    }
}
