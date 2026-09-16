package com.intermarche.pos.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import com.intermarche.pos.domain.sale.DocumentType;

/**
 * The number sequence of one document type on one register.
 * <p>
 * One row per (register, document type), read under a pessimistic write lock and
 * incremented inside the transaction that creates the document, exactly as
 * {@link TicketCounter} is for tickets: numbering and creation commit or roll back
 * together, which is what "unique, séquentiel et sans rupture par type de document"
 * ({@code BO-03-03-02}) demands.
 * <p>
 * A ROW OF ITS OWN, NOT A COLUMN ON {@link TicketCounter}, and the reason matters.
 * The ticket counter carries the three sale sequences AND the fiscal chaining
 * anchors on a single row precisely so that one lock serialises them all; an invoice
 * is issued on a ticket that is already closed and chained, so it has no business
 * holding that lock. Two documents of different types can therefore be numbered
 * without waiting for each other, and no invoice transaction ever blocks a sale.
 * <p>
 * Extends {@link PanacheEntity} directly: a counter is plumbing, and the audit
 * fields of the business entities would be noise on it — the same choice as
 * {@link TicketCounter}.
 */
@Entity
@Table(name = "document_counters",
        indexes = @Index(name = "idx_document_counter_terminal_type",
                columnList = "terminal_id, document_type", unique = true)
)
public class DocumentCounter extends PanacheEntity {

    /** The identifier of the register this sequence belongs to (pos.terminal.id). */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The document type this sequence numbers. */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    public DocumentType documentType;

    /** The last number issued for this register and this type (0 = none yet). */
    @Column(name = "last_number", nullable = false)
    public long lastNumber;
}
