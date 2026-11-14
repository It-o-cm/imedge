package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A ticket a register PRINTED, kept in its 42-column format and carried up to
 * the store node, which puts it at the disposal of third-party services — the
 * customer's online account, the enseigne's mobile application (LC-08-02-02).
 *
 * <p>Every ticket the register generates is one: the customer's papers, the
 * store's technical tickets (session opening and closing, X and Z reports, cash
 * movements, unknown articles…) and the slips a policy kept from the printer.
 * The {@link Audience} tells them apart, and the {@link #source} names the
 * register function that produced the paper.
 *
 * <p>Shared by the register, which writes the row and pushes it, and the store
 * node, which keeps the pushed copy under the register's own id.
 */
@Entity
@Table(name = "printed_tickets", indexes = @Index(name = "idx_printed_tickets_source",
        columnList = "terminal_id, source_id"))
public class PrintedTicket extends PanacheEntity {

    /** Who a printed ticket is meant for. */
    public enum Audience {

        /** The customer: receipts, slips, vouchers, credit notes. */
        CUSTOMER,

        /** The store: the technical tickets of the point of sale (LC-08-03-08). */
        STORE,

        /** A ticket printed in training mode, which is no real document (LC-13-05-06). */
        TRAINING
    }

    /** The register that printed it. */
    @Column(name = "terminal_id", length = 20)
    public String terminalId;

    /** The register's own id of the row, on the store node's copy; null on the register. */
    @Column(name = "source_id")
    public Long sourceId;

    /** The register function that produced the paper. */
    @Column(name = "source", length = 80)
    public String source;

    /** Who the ticket is meant for. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", length = 10)
    public Audience audience;

    /** The ticket as printed, 42-column text. */
    @Lob
    @Column(name = "content")
    public String content;

    /** When it was printed. */
    @Column(name = "issued_at")
    public LocalDateTime issuedAt;

    /**
     * Finds the store node's copy of a register's row.
     *
     * @param terminalId the register
     * @param sourceId the register's id of the row
     * @return the copy, or null when none was pushed yet
     */
    public static PrintedTicket findBySource(String terminalId, Long sourceId) {
        return find("terminalId = ?1 and sourceId = ?2", terminalId, sourceId).firstResult();
    }

    /**
     * Lists the printed tickets after a given id, oldest first, for the
     * third-party services that read them in turn.
     *
     * @param afterId the last id the reader already holds, 0 for the first read
     * @param audience the audience read
     * @param limit the maximum number of rows
     * @return the rows, never null
     */
    public static List<PrintedTicket> listAfter(long afterId, Audience audience, int limit) {
        return find("id > ?1 and audience = ?2 order by id", afterId, audience).page(0, limit).list();
    }
}
