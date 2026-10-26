package com.intermarche.edge.domain.sale;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.store.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A sale ticket, persisted as a draft from the first scanned article
 * (phase 0 lot 2) and closed at payment completion.
 * <p>
 * The register that owns the ticket is recorded in {@link #terminalId}; this
 * is what restart recovery uses to find the open draft of this terminal in
 * the register's database, and what the phase 1 per-register fiscal
 * chaining ({@link #signature}, {@link #previousSignature},
 * {@link #grandTotal}) keys on.
 * <p>
 * Lifecycle: OPEN from the first article; OPEN → PARKED (cart parked, only
 * with no payment registered) → OPEN again on resume; OPEN → CLOSED at
 * payment completion (number chained, totals frozen); OPEN → CANCELLED on an
 * abandoned cart; PARKED → CANCELLED at the Z closing of the session. CLOSED
 * and CANCELLED are terminal. In training mode (phase 6) no row is ever
 * created: the draft sync is the single guard everything else derives from.
 * <p>
 * Store synchronization (phase 5): pushed at CLOSED and CANCELLED, upserted
 * on the store node by {@link #ticketNumber} — the only portable identity
 * (database ids are local to each register). {@link #digitalKey} is 16 hex
 * characters generated at draft creation: unguessable, short enough to print,
 * and independent of the closing so the online-receipt link exists before the
 * signature does; the public page only serves CLOSED tickets.
 */
@Entity
@Table(name = "tickets",
        indexes = {
                @Index(name = "idx_ticket_number", columnList = "ticket_number", unique = true),
                @Index(name = "idx_ticket_date", columnList = "creation_date"),
                @Index(name = "idx_ticket_cashier", columnList = "cashier_id"),
                @Index(name = "idx_ticket_terminal_status", columnList = "terminal_id, status")
        }
)
public class Ticket extends BaseEntity {

    // --------------------------------------------------
    // Ticket status
    // --------------------------------------------------

    /**
     * Lifecycle status of a ticket.
     */
    public enum TicketStatus {
        /** Draft in progress (cart being built or payment in progress). */
        OPEN,
        /** Parked cart, waiting to be resumed on this register. */
        PARKED,
        /** Finalized and validated. */
        CLOSED,
        /** Abandoned before validation (cancelled cart); documents sequence gaps. */
        CANCELLED
    }

    /**
     * Returns the last ticket this register CLOSED, or null when it has closed
     * none.
     *
     * <p>Ordered by id, which on this register is the closing order: the number
     * sequence and the primary key are both handed out by the same serialization
     * point, so the highest id of a terminal IS its last sale.
     *
     * @param terminalId the identifier of this register
     * @return the last closed ticket, or null
     */
    public static Ticket findLastClosedByTerminal(String terminalId) {
        return find("terminalId = ?1 and status = ?2 order by id desc",
                terminalId, TicketStatus.CLOSED).firstResult();
    }

    /** The lifecycle status; defaults to OPEN. */
    @Column(name = "status", nullable = false)
    @NotNull
    public TicketStatus status = TicketStatus.OPEN;

    // --------------------------------------------------
    // Identity & date
    // --------------------------------------------------

    /** The sequential ticket number, e.g. "C04-00000123", unique per register database. */
    @Column(name = "ticket_number", unique = true, nullable = false, length = 30)
    @NotBlank
    public String ticketNumber;

    /** The identifier of the register that created the ticket (pos.terminal.id). */
    /**
     * The ticket AS PRINTED, in the register's 42-column format (LC-08-02-02).
     * <p>
     * Frozen at the fiscal moment and carried up to the store node, which puts it at
     * the disposal of third-party services (the customer's online account, the
     * enseigne's mobile application). The RAW sale already travels as structured
     * data; this is the same sale as the customer's paper reads it, and only the
     * register that made it can produce that — it is the only place that knows what
     * its own printer put on the roll, loyalty section included.
     * <p>
     * Null on a draft, and on any ticket closed before this field existed.
     */
    @jakarta.persistence.Lob
    @Column(name = "formatted_content")
    public String formattedContent;

    /**
     * The customer language the ticket was sold and printed in — chosen by the
     * customer or retrieved from the loyalty base (LC-13-06-09, -14) — so its
     * restitution, here or through the store node's API, speaks it too; null when
     * the sale ran in the register's language.
     */
    @Column(name = "customer_language", length = 8)
    public String customerLanguage;

    @Column(name = "terminal_id", nullable = false, length = 20)
    @NotNull
    public String terminalId;

    /** The creation timestamp of the draft. */
    @Column(name = "creation_date", nullable = false)
    @NotNull
    public LocalDateTime creationDate;

    /** The closing timestamp, set when the ticket is validated, or null. */
    @Column(name = "closing_date")
    public LocalDateTime closingDate;

    // --------------------------------------------------
    // Fiscal chaining (phase 1)
    // --------------------------------------------------

    /** SHA-256 signature of this ticket, chained per register; set at closing. */
    @Column(name = "signature", length = 64)
    public String signature;

    /** Signature of the previous closed ticket of the same register, or "GENESIS". */
    @Column(name = "previous_signature", length = 64)
    public String previousSignature;

    /** Snapshot of the register's perpetual grand total after this ticket. */
    @Column(name = "grand_total", precision = 19, scale = 4)
    public BigDecimal grandTotal;

    /** Number of times this ticket was printed (beyond 1 = duplicata). */
    @Column(name = "print_count", nullable = false)
    public int printCount = 0;

    // --------------------------------------------------
    // Valuation (dormant until phase 7)
    // --------------------------------------------------

    /** Valuation status of a ticket toward the external valuation engine. */
    public enum ValuationStatus {
        /** Catalog prices only; the valuation engine was not consulted. */
        NOT_VALUATED,
        /** Successfully valuated by the engine. */
        VALUATED,
        /** Engine unreachable or inconsistent; catalog prices applied (degraded mode). */
        DEGRADED
    }

    /** The valuation status; defaults to NOT_VALUATED until phase 7 wires the engine. */
    @Column(name = "valuation_status", nullable = false, length = 20)
    @NotNull
    public ValuationStatus valuationStatus = ValuationStatus.NOT_VALUATED;

    // --------------------------------------------------
    // Relations (unidirectional)
    // --------------------------------------------------

    /** The store the ticket belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    @NotNull
    public Store store;

    /** The cashier who opened the ticket. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id", nullable = false)
    @NotNull
    public Employee cashier;

    /** The cash session the ticket was sold under (null on pre-phase-2 rows). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    public CashSession session;

    /** The fidelity card presented for this ticket, or null. */
    @Column(name = "fidelity_card", length = 50)
    public String fidelityCard;

    /**
     * The loyalty earn this sale DISPLAYED, frozen at closing; null when the
     * sale earned nothing or the loyalty service said nothing (BO-03-03-31).
     *
     * <p>Frozen rather than read back: the projection lives in the register's
     * live state and dies with the sale, so a ticket reprinted an hour later —
     * or on another register through the store node — would otherwise carry
     * somebody else's cagnotte, or none at all. The recomputation made at
     * ingestion stays authoritative for the ACCOUNT; this column is what the
     * customer was shown on the paper.
     */
    @Column(name = "fidelity_earn_total", precision = 19, scale = 4)
    public BigDecimal fidelityEarnTotal;

    /**
     * The AVAILABLE balance read when the card was attached, frozen at closing;
     * null when no card was presented or the loyalty service did not answer.
     */
    @Column(name = "fidelity_available_balance", precision = 19, scale = 4)
    public BigDecimal fidelityAvailableBalance;

    /**
     * The PROJECTED balance after this sale's earn (imfid response B,
     * {@code balances.projectedAfterEarn}), frozen at closing so a duplicata
     * restates the same forecast; null when no card resolved. A forecast only —
     * nothing is credited before ingestion (BO-03-03-31).
     */
    @Column(name = "fidelity_projected_after_earn", precision = 19, scale = 4)
    public BigDecimal fidelityProjectedAfterEarn;

    /**
     * The balance AFTER the fidelity burn, read from the ledger at confirmation
     * (imfid response B), frozen at closing and printed as SOLDE APRES; null
     * when no burn was confirmed. Never recomputed by the register.
     */
    @Column(name = "fidelity_balance_after", precision = 19, scale = 4)
    public BigDecimal fidelityBalanceAfter;

    /**
     * True when {@link #fidelityEarnTotal} is an ANONYMOUS projection for a
     * card-less ticket (imfid response C) — « ce que vous auriez gagné avec la
     * carte » — rather than an earned amount (BO-03-03-28).
     */
    @Column(name = "fidelity_anonymous_projection", nullable = false)
    public boolean fidelityAnonymousProjection;

    /**
     * True when the loyalty service was unreachable while this sale was made
     * (BO-03-03-29, BO-03-03-30).
     *
     * <p>Frozen for the same reason as the rest: a document states what
     * happened at the till, and whether the programme answered that day is part
     * of it. A shop that does not run the programme at all is NOT unavailable —
     * there was nothing to reach.
     */
    @Column(name = "fidelity_unavailable", nullable = false)
    public boolean fidelityUnavailable;

    /**
     * The balance of each loyalty pot as the programme computed it with this
     * sale, frozen at closing so the receipt and any duplicata print the
     * "SOLDES DES CAGNOTTES" block (BO-10-02-33); null when the shop does not
     * read the pots or the programme returned none.
     *
     * <p>One pot per line, its label then a tab then its balance as a plain
     * decimal (empty when the programme sent none). Text rather than a child
     * table: the block is a printed statement of the day, never queried.
     */
    @jakarta.persistence.Lob
    @Column(name = "fidelity_pot_balances")
    public String fidelityPotBalances;

    /**
     * The loyalty programme's MESSAGES for this sale, frozen at closing so the
     * receipt and any duplicata print them (BO-03-03-26, BO-03-03-27): the
     * short two-line advantage message, then up to five commercial messages of
     * up to ten lines each; null when the programme sent none.
     *
     * <p>Written by {@code ImfidClient.LoyaltyMessages.encode}: one message per
     * block, blocks separated by an empty line, the first block being the
     * short message. Text rather than a child table, like the pots: a printed
     * statement of the day, never queried.
     */
    @jakarta.persistence.Lob
    @Column(name = "fidelity_messages")
    public String fidelityMessages;

    /**
     * The advantage lines as displayed, frozen at closing; empty when the sale
     * displayed none (BO-03-03-25).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ticket_fidelity_lines",
            joinColumns = @JoinColumn(name = "ticket_id"))
    public List<TicketFidelityLine> fidelityLines = new ArrayList<>();

    /**
     * The engine offer messages shown and printed for this sale, frozen at
     * closing; empty when the engine emitted none (GB-01-07-05/06). They carry
     * no money — a reprint restates them, nothing recomputes from them.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ticket_messages",
            joinColumns = @JoinColumn(name = "ticket_id"))
    public List<TicketMessage> messages = new ArrayList<>();

    /**
     * Ticket-level discount request kind ("AMOUNT"/"PERCENT"), or null
     * (phase: global ticket discount). The request survives a crash; the
     * allocation is recomputed at restore.
     */
    @jakarta.persistence.Column(name = "global_discount_type", length = 10)
    public String globalDiscountType;

    /** Ticket-level discount requested value (euros or percent). */
    @jakarta.persistence.Column(name = "global_discount_value", precision = 10, scale = 2)
    public java.math.BigDecimal globalDiscountValue;

    /** Ticket-level discount APPLIED amount at last sync (print/reporting). */
    @jakarta.persistence.Column(name = "global_discount_applied", precision = 10, scale = 2)
    public java.math.BigDecimal globalDiscountApplied;

    /** Access key of the online digital receipt, generated at draft creation. */
    @Column(name = "digital_key", length = 16)
    public String digitalKey;

    /** The email the digital receipt was sent to, or null. */
    @Column(name = "customer_email", length = 120)
    public String customerEmail;

    /** Fiscal identifier selected for this sale, independently of a customer account (LC-08-06-02/-03). */
    @Column(name = "customer_tax_id", length = 30)
    public String customerTaxId;

    /**
     * The sales channel the transaction comes from (BO-07-01-02): null on a
     * sale of the store's own lanes, the third-party system's channel on a
     * transaction the point of sale integrated.
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "sales_channel", length = 16)
    public SalesChannel salesChannel;

    /**
     * True when the sale was a SELF-SCANNING basket loaded at the till
     * (BO-04-01-41/50): the « Autonome » column of the electronic journal.
     */
    @Column(name = "self_scanning", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean selfScanning;

    /**
     * True when the self-scanning basket was put under a control the hostess
     * decided at the till (BO-04-01-42).
     */
    @Column(name = "self_scan_controlled", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean selfScanControlled;

    /**
     * True when the self-scanning basket was put under a control the system
     * decided — drawn by the store's rate or required by the basket
     * (BO-04-01-43).
     */
    @Column(name = "self_scan_verified", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean selfScanVerified;

    /**
     * True when the sale was made while the register had lost the store node
     * (BO-04-01-14, event « déconnecté »).
     */
    @Column(name = "offline_sale", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    public boolean offline;

    /** The ticket lines (FK held by the child table, orphan removal on). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ticket_id", nullable = false)
    public List<TicketLine> lines = new ArrayList<>();

    /**
     * When the cart was PARKED ({@code BO-06-06-01}), or null on a ticket
     * that was never parked.
     *
     * <p>Kept even after the resume, and that is the whole point: a parked
     * cart that comes back becomes an ordinary sale, and without this column
     * the shop loses every trace that it waited — which is exactly what the
     * state of pending tickets is asked to show.
     */
    @Column(name = "parked_at")
    public java.time.LocalDateTime parkedAt;

    /**
     * When the parked cart was RECALLED ({@code BO-06-06-01}), or null while
     * it is still waiting — which is what tells a resumed cart from a
     * pending one.
     */
    @Column(name = "resumed_at")
    public java.time.LocalDateTime resumedAt;

    /**
     * The register that recalled the parked cart ({@code BO-06-06-01}), or
     * null while it is still waiting.
     *
     * <p>Its own column and not read from {@link #terminalId}: an inter-till
     * resume RE-HOMES the ticket onto the recalling lane, so after the resume
     * the ticket's terminal is the recalling one and the parking lane is
     * lost. Both are worth knowing, so both are written.
     */
    @Column(name = "resumed_terminal_id", length = 30)
    public String resumedTerminalId;

    /**
     * When the cashier went to PAYMENT on this ticket (BO-06-06-02,
     * BO-06-06-03), null while it is still being rung up.
     *
     * <p>The first article opens the ticket ({@link #creationDate}) and the
     * last payment closes it ({@link #closingDate}): this third instant cuts
     * the customer's time in two — the scanning before it, the payment after
     * it — which is what the cashier performance states measure.
     */
    @Column(name = "payment_started_at")
    public java.time.LocalDateTime paymentStartedAt;

    /** The registered payments (FK held by the child table, orphan removal on). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ticket_id", nullable = false)
    public List<TicketPayment> payments = new ArrayList<>();

    // --------------------------------------------------
    // Financial totals
    // --------------------------------------------------

    /** The number of lines on the ticket. */
    @Column(name = "item_count", nullable = false)
    public int itemCount;

    /** The total excluding tax. */
    @Column(name = "total_ht", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalExcludingTax;

    /** The total including tax. */
    @Column(name = "total_ttc", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalIncludingTax;

    /** The total VAT amount. */
    @Column(name = "total_vat", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalVat;

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Adds a line to the ticket.
     *
     * @param line the line to add
     */
    public void addLine(TicketLine line) {
        this.lines.add(line);
    }

    /**
     * Adds a payment to the ticket.
     *
     * @param payment the payment to add
     */
    public void addPayment(TicketPayment payment) {
        this.payments.add(payment);
    }

    /**
     * Returns the tax-included total formatted for display (2 decimals, French comma).
     * Formatting is done server-side on purpose.
     *
     * @return the formatted total
     */
    public String getTotalFormatted() {
        if (this.totalIncludingTax == null) return "0,00";
        BigDecimal rounded = this.totalIncludingTax.setScale(2, RoundingMode.HALF_UP);
        return String.format("%.2f", rounded).replace(".", ",");
    }

    /**
     * Returns the audit checksum of the ticket.
     *
     * @return a hash of the identifying, financial and chaining fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(ticketNumber, creationDate, status, totalIncludingTax, signature);
    }
}
