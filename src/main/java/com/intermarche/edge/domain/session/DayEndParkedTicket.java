package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A ticket found ON HOLD when the day ended, as the end of day recorded it
 * (BO-09-01-05, BO-09-01-08, BO-09-02-01).
 *
 * <p>The end of day does not wait for the carts left parked: it closes WITH
 * them, and writes down every one of them — register, number, cashier,
 * articles and amount — so the fate of each is stated at the close itself
 * and not only at the Z closing the register will perform some time later.
 * A cart whose reset the back office ordered carries {@link Fate#RAZ_ORDERED}
 * until its register, acting on the order, cancels it.
 */
@Entity
@Table(name = "day_end_parked_tickets")
public class DayEndParkedTicket extends PanacheEntity {

    /** What the end of day did with the cart. */
    public enum Fate {

        /** Recorded at the close and left on hold. */
        RECORDED,

        /** Its reset was ordered to its register. */
        RAZ_ORDERED
    }

    /** The day whose end found the cart. */
    @Column(name = "close_day", nullable = false)
    public LocalDate closeDay;

    /** When it was recorded. */
    @Column(name = "recorded_at", nullable = false)
    public LocalDateTime recordedAt;

    /** Who or what recorded it: the automatic end of period or the operator. */
    @Column(name = "recorded_by", nullable = false, length = 80)
    public String recordedBy;

    /** The register it waits on. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The cart's number. */
    @Column(name = "ticket_number", nullable = false, length = 30)
    public String ticketNumber;

    /** Who parked it, or an empty string. */
    @Column(name = "cashier", length = 120)
    public String cashier;

    /** How many articles it holds. */
    @Column(name = "item_count", nullable = false)
    public int itemCount;

    /** Its amount so far, TTC. */
    @Column(name = "amount", precision = 19, scale = 2)
    public BigDecimal amount;

    /** Its lines, one per row: quantity, label, amount. */
    @Lob
    @Column(name = "content")
    public String content;

    /** What the end of day did with it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "fate", nullable = false, length = 16)
    public Fate fate = Fate.RECORDED;

    /**
     * Finds the record of a cart for a day.
     *
     * @param day the day
     * @param ticketNumber the cart's number
     * @return the record, or null when the day has not recorded it yet
     */
    public static DayEndParkedTicket findFor(LocalDate day, String ticketNumber) {
        return find("closeDay = ?1 and ticketNumber = ?2", day, ticketNumber).firstResult();
    }

    /**
     * The latest records, most recent first, for the end-of-day screen.
     *
     * @param limit how many
     * @return the records
     */
    public static List<DayEndParkedTicket> latest(int limit) {
        return find("order by recordedAt desc, id desc").page(0, limit).list();
    }
}
