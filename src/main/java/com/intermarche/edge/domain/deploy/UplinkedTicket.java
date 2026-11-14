package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * A ticket of a point of sale as its store node sent it up to the central
 * node, for the electronic journal regrouped by adhérent (BO-02-05-05).
 *
 * <p>The central node sells nothing and consolidates no register: what it
 * knows of the tickets of the organisation is what each store pushes up after
 * its registers pushed them to it. The row keeps the searchable columns of the
 * journal — point of sale, register, number, cashier, date, amount, type — and
 * the materialized detail the store's own journal shows, so the central view
 * opens a ticket without asking the store again.
 *
 * <p>Filed under the number its point of sale carried when the ticket was
 * produced: a renumbered point of sale keeps its past tickets under its former
 * number (BO-02-05-01), and a later restatement of such a ticket updates the
 * row where it is filed.
 */
@Entity
@Table(name = "uplinked_ticket",
        indexes = {
                @Index(name = "idx_uplinked_ticket_key", columnList = "pdv_number, ticket_number", unique = true),
                @Index(name = "idx_uplinked_ticket_date", columnList = "ticket_date")
        })
public class UplinkedTicket extends PanacheEntity {

    /** A closed sale of a positive or nil amount. */
    public static final String SALE = "SALE";

    /** A closed transaction of a negative amount (a return transaction). */
    public static final String NEGATIVE = "NEGATIVE";

    /** A ticket abandoned before its validation. */
    public static final String CANCELLED = "CANCELLED";

    /** The point of sale, by the number it carried when the ticket was produced. */
    @Column(name = "pdv_number", nullable = false, length = 5)
    public String pdvNumber;

    /** The ticket number, unique on its store node. */
    @Column(name = "ticket_number", nullable = false, length = 30)
    public String ticketNumber;

    /** The register (TPV) identifier. */
    @Column(name = "terminal_id", length = 20)
    public String terminalId;

    /** The badge of the cashier, or null. */
    @Column(name = "cashier_badge", length = 40)
    public String cashierBadge;

    /** The lifecycle status on the store (CLOSED or CANCELLED). */
    @Column(name = "status", nullable = false, length = 16)
    public String status;

    /** The journal type: {@link #SALE}, {@link #NEGATIVE} or {@link #CANCELLED}. */
    @Column(name = "kind", nullable = false, length = 16)
    public String kind;

    /** When the ticket was created, the date the store's journal shows. */
    @Column(name = "ticket_date", nullable = false)
    public LocalDateTime ticketDate;

    /** The tax-included total. */
    @Column(name = "total_ttc", nullable = false, precision = 19, scale = 4)
    public BigDecimal totalIncludingTax = BigDecimal.ZERO;

    /** The number of article lines. */
    @Column(name = "item_count", nullable = false)
    public int itemCount;

    /** The last modification of the ticket on its store, the push watermark. */
    @Column(name = "source_updated_at", nullable = false)
    public LocalDateTime sourceUpdatedAt;

    /** When the central node last received the ticket. */
    @Column(name = "received_at", nullable = false)
    public LocalDateTime receivedAt;

    /** The materialized detail (header, lines, payments, loyalty), as JSON. */
    @Lob
    @Column(name = "detail")
    public String detail;

    /** The ticket as its register printed it, or null. */
    @Lob
    @Column(name = "printed")
    public String printed;

    /**
     * The journal type of a ticket.
     *
     * @param status the store status, possibly null
     * @param total the tax-included total, possibly null
     * @return {@link #CANCELLED}, {@link #NEGATIVE} or {@link #SALE}
     */
    public static String kindOf(String status, BigDecimal total) {
        if (CANCELLED.equals(status)) {
            return CANCELLED;
        }
        return total != null && total.signum() < 0 ? NEGATIVE : SALE;
    }

    /**
     * Finds the row of a ticket among the numbers its point of sale carried.
     *
     * @param pdvNumbers the current and former numbers of the point of sale
     * @param ticketNumber the ticket number
     * @return the row, or null when the ticket was never received
     */
    public static UplinkedTicket findFiled(Collection<String> pdvNumbers, String ticketNumber) {
        return find("pdvNumber in ?1 and ticketNumber = ?2", pdvNumbers, ticketNumber).firstResult();
    }

    /**
     * Finds the latest received modification of a point of sale, the watermark
     * its store pushes from.
     *
     * @param pdvNumbers the current and former numbers of the point of sale
     * @return the most recently modified row, or null when none was received
     */
    public static UplinkedTicket latestOf(Collection<String> pdvNumbers) {
        return find("pdvNumber in ?1 order by sourceUpdatedAt desc, id desc", pdvNumbers).firstResult();
    }
}
