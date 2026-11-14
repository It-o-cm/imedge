package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The revenue of ONE point of sale on ONE day, as its store node last reported
 * it to the central node (BO-02-05-05).
 *
 * <p>The central node sells nothing and holds no ticket: what it knows of the
 * revenue of the organisation is what each store says of its own days in its
 * report cycle. A day is restated at every report until it is over, so the row
 * is an upsert by (point of sale, day), the latest statement winning.
 *
 * <p>Filed under the number the point of sale carried that day: a renumbered
 * point of sale keeps its past revenue under its former number (BO-02-05-01).
 */
@Entity
@Table(name = "pdv_revenue",
        indexes = @Index(name = "idx_pdv_revenue_key", columnList = "pdv_number, revenue_day", unique = true))
public class PdvRevenue extends PanacheEntity {

    /** The point of sale, by the number it carried that day. */
    @Column(name = "pdv_number", nullable = false, length = 5)
    public String pdvNumber;

    /** The day. */
    @Column(name = "revenue_day", nullable = false)
    public LocalDate day;

    /** The revenue of the closed sales of the day, tax included. */
    @Column(name = "revenue", nullable = false, precision = 19, scale = 2)
    public BigDecimal revenue = BigDecimal.ZERO;

    /** How many sales were closed that day. */
    @Column(name = "tickets", nullable = false)
    public long tickets;

    /** When the central node last received the figure. */
    @Column(name = "received_at", nullable = false)
    public LocalDateTime receivedAt;

    /**
     * Finds the row of one point of sale and one day.
     *
     * @param pdvNumber the point-of-sale number
     * @param day the day
     * @return the row, or null when that day was never reported
     */
    public static PdvRevenue findDay(String pdvNumber, LocalDate day) {
        return find("pdvNumber = ?1 and day = ?2", pdvNumber, day).firstResult();
    }

    /**
     * Lists the reported days of a period, every point of sale.
     *
     * @param from the first day, inclusive
     * @param to the last day, inclusive
     * @return the rows, by point of sale then day
     */
    public static List<PdvRevenue> listBetween(LocalDate from, LocalDate to) {
        return list("day >= ?1 and day <= ?2 order by pdvNumber, day", from, to);
    }
}
