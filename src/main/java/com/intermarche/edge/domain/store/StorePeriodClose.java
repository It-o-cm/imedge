package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The CONSOLIDATED CLOSE of a point of sale's period (BO-03-12-02): the object
 * above the cash sessions that says what the store itself did when its day
 * ended — which administered actions it chained, in which order and with which
 * outcome, how many registers it found open, and what the day held.
 *
 * <p>One row per day of the store node. A day the opening calendar declares
 * closed still gets its row, marked {@link Status#CLOSED_DAY}: the automation
 * then chained nothing, and the row says so rather than leaving a hole a
 * reader would take for a failure.
 *
 * <p>The store node holds it alone: the registers close their sessions, the
 * node consolidates them.
 */
@Entity
@Table(name = "store_period_close")
public class StorePeriodClose extends PanacheEntity {

    /** What the consolidated close came to. */
    public enum Status {

        /** Every administered action ran. */
        CLOSED,

        /** The opening calendar declares the day closed: nothing was chained. */
        CLOSED_DAY
    }

    /** The day closed, unique. */
    @Column(name = "close_date", unique = true, nullable = false)
    public LocalDate closeDate;

    /** What the close came to. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    public Status status = Status.CLOSED;

    /** When the close ran. */
    @Column(name = "closed_at", nullable = false)
    public LocalDateTime closedAt;

    /** The registers still open when the close ran, deactivated ones left out. */
    @Column(name = "open_registers", nullable = false)
    public int openRegisters;

    /** The tickets closed that day. */
    @Column(name = "ticket_count", nullable = false)
    public long ticketCount;

    /** The takings of the day, tax included. */
    @Column(name = "total_including_tax", precision = 19, scale = 2)
    public BigDecimal totalIncludingTax = BigDecimal.ZERO;

    /** The actions chained and their outcome, one {@code ACTION: outcome} per line. */
    @Column(name = "actions", length = 2000)
    public String actions;

    /**
     * Returns the actions chained, one line each, for the screen.
     *
     * @return the lines, empty when nothing was chained
     */
    public List<String> actionLines() {
        return actions == null || actions.isBlank() ? List.of() : List.of(actions.split("\n"));
    }

    /**
     * Finds the close of one day.
     *
     * @param day the day, possibly null
     * @return the close, or null when the day has none
     */
    public static StorePeriodClose findByDate(LocalDate day) {
        if (day == null) {
            return null;
        }
        return find("closeDate", day).firstResult();
    }

    /**
     * Lists the latest closes, the most recent first.
     *
     * @param count how many to return
     * @return the closes, possibly empty
     */
    public static List<StorePeriodClose> latest(int count) {
        return StorePeriodClose.<StorePeriodClose>find("order by closeDate desc").page(0, count).list();
    }
}
