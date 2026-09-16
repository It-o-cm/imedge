package com.intermarche.pos.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Per-line trace of the engine valuation (phase 7 lot 2): what the register
 * would have charged locally, what the engine valued (advantage allocations
 * included), and the human-readable offer and advantage labels printed
 * under the article.
 * <p>
 * Rows exist only for tickets valued by the remote engine, are rewritten
 * wholesale at each payment entry (delete-then-insert by ticket) and
 * removed when the payments are cancelled — the LINE remains the fiscal
 * truth (its total IS the valued amount once applied); these rows are the
 * explanation, not the money. Extends {@code PanacheEntity} directly like
 * the other technical tables (no audit fields on a trace).
 */
@Entity
@Table(name = "ticket_line_valuations", indexes = {
        @Index(name = "idx_line_valuation_ticket", columnList = "ticket_id")
})
public class TicketLineValuation extends PanacheEntity {

    /** The valued ticket. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    public Ticket ticket;

    /** The lineUid of the valued line. */
    @Column(name = "line_uid", nullable = false, length = 36)
    public String lineUid;

    /** The register-local line total (tax included) before valuation. */
    @Column(name = "local_total", nullable = false, precision = 19, scale = 2)
    public BigDecimal localTotal;

    /** The engine-valued line total (tax included), advantage cuts allocated. */
    @Column(name = "valued_total", nullable = false, precision = 19, scale = 2)
    public BigDecimal valuedTotal;

    /** The offer label(s) covering the line (offerId when emitted, type string otherwise). */
    @Column(name = "offer_label", length = 200)
    public String offerLabel;

    /** The advantage label(s) allocated on the line, or null. */
    @Column(name = "advantage_label", length = 200)
    public String advantageLabel;

    /** The advantage amount allocated on this line (tax included), or null. */
    @Column(name = "advantage_amount", precision = 19, scale = 2)
    public BigDecimal advantageAmount;
}
