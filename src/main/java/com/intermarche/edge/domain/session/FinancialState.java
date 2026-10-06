package com.intermarche.edge.domain.session;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A financial state of the store node — an X reading or a Z document (R1, R2,
 * R3).
 *
 * <p>An X is a volatile READING of the open interval and is never persisted; a Z
 * is a FROZEN document: it gels its totals at emission, carries a sequence number
 * in its chain, chains to the previous one by signature, and must come out
 * identical ten months later even if an article was re-parented or a ticket
 * arrived late. A Z stores its figures, never a query — a report that recomputed
 * would give another number, and that is exactly what a Z must never do.
 *
 * <p>There is no counter entity on purpose: the counter IS the chain of Z
 * documents, identified by the triple ({@link #scope}, {@link #periodKind},
 * {@link #cashier}). The next Z of a chain opens where the previous one closed,
 * takes the previous sequence plus one, and chains to the previous signature.
 */
@Entity
@Table(name = "financial_states",
        indexes = {
                @Index(name = "idx_fin_state_chain", columnList = "scope, period_kind, cashier_id, chain_sequence"),
                @Index(name = "idx_fin_state_type", columnList = "state_type, scope, period_kind")
        }
)
public class FinancialState extends BaseEntity {

    /** Whether the state is a reading (X) or a reset document (Z). */
    public enum StateType {
        /** A volatile reading of the open interval, never persisted as a document. */
        X,
        /** A frozen reset document, sequenced and chained. */
        Z
    }

    /** The holder of the counter the state belongs to (R1). */
    public enum StateScope {
        /** The point of sale as a whole. */
        STORE,
        /** One cashier. */
        CASHIER
    }

    /** The period bound a state closes on (R2, R3, R4). */
    public enum PeriodKind {
        /** A day. */
        DAY,
        /** A week (Monday to Sunday, R5). */
        WEEK,
        /** A calendar month (R5). */
        MONTH
    }

    /** Whether the state is a reading or a reset document. */
    @Column(name = "state_type", nullable = false, length = 1)
    @Enumerated(EnumType.STRING)
    @NotNull
    public StateType stateType;

    /** The holder of the counter (R1). */
    @Column(name = "scope", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    @NotNull
    public StateScope scope;

    /** The period bound. */
    @Column(name = "period_kind", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    @NotNull
    public PeriodKind periodKind;

    /** The cashier the counter belongs to, or null in {@code STORE} scope. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id")
    public Employee cashier;

    /** The number of the Z within its chain, 0 for an X. */
    @Column(name = "chain_sequence", nullable = false)
    public long sequence;

    /** The inclusive lower bound of the interval. */
    @Column(name = "from_instant", nullable = false)
    @NotNull
    public LocalDateTime fromInstant;

    /** The exclusive upper bound of the interval. */
    @Column(name = "to_instant", nullable = false)
    @NotNull
    public LocalDateTime toInstant;

    /** When the state was emitted. */
    @Column(name = "emitted_at", nullable = false)
    @NotNull
    public LocalDateTime emittedAt;

    /** The operator who emitted the state, or null for an automatic emission. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "emitted_by_id")
    public Employee emittedBy;

    /** True when the state was emitted by the end of a period, not by an operator. */
    @Column(name = "automatic", nullable = false)
    public boolean automatic;

    /** The gross turnover, before any negative operation (R7). */
    @Column(name = "gross_turnover", precision = 19, scale = 4)
    public BigDecimal grossTurnover;

    /** The gross-to-net gap carried by the discounts. */
    @Column(name = "discount_total", precision = 19, scale = 4)
    public BigDecimal discountTotal;

    /** The refunds backed by a ticket (R8). */
    @Column(name = "refund_with_ticket_total", precision = 19, scale = 4)
    public BigDecimal refundWithTicketTotal;

    /** The refunds not backed by a ticket (R8). */
    @Column(name = "refund_without_ticket_total", precision = 19, scale = 4)
    public BigDecimal refundWithoutTicketTotal;

    /** The net turnover, after every negative operation (R7). */
    @Column(name = "net_turnover", precision = 19, scale = 4)
    public BigDecimal netTurnover;

    /** The VAT total. */
    @Column(name = "vat_total", precision = 19, scale = 4)
    public BigDecimal vatTotal;

    /** The number of customers, i.e. positive tickets (R14). */
    @Column(name = "customer_count", nullable = false)
    public int customerCount;

    /** The number of items sold (R15). */
    @Column(name = "item_count", nullable = false)
    public int itemCount;

    /** The average basket, net turnover over customer count (R14). */
    @Column(name = "average_basket", precision = 19, scale = 4)
    public BigDecimal averageBasket;

    /** The food share of the net turnover, as a percentage (R16). */
    @Column(name = "food_share", precision = 9, scale = 4)
    public BigDecimal foodShare;

    /** The number of last-line cancellations (R10). */
    @Column(name = "cancelled_line_count", nullable = false)
    public int cancelledLineCount;

    /** The amount of the last-line cancellations (R10). */
    @Column(name = "cancelled_line_total", precision = 19, scale = 4)
    public BigDecimal cancelledLineTotal;

    /** The number of endorsed cancellations, the fraud indicator (R10). */
    @Column(name = "endorsed_cancel_count", nullable = false)
    public int endorsedCancelCount;

    /** The amount of the endorsed cancellations (R10). */
    @Column(name = "endorsed_cancel_total", precision = 19, scale = 4)
    public BigDecimal endorsedCancelTotal;

    /** The number of abandoned tickets (R9). */
    @Column(name = "abandoned_ticket_count", nullable = false)
    public int abandonedTicketCount;

    /** The amount of the abandoned tickets (R9). */
    @Column(name = "abandoned_ticket_total", precision = 19, scale = 4)
    public BigDecimal abandonedTicketTotal;

    /** The number of gift cards emitted (R12). */
    @Column(name = "gift_card_count", nullable = false)
    public int giftCardCount;

    /** The amount of the gift cards emitted (R12). */
    @Column(name = "gift_card_total", precision = 19, scale = 4)
    public BigDecimal giftCardTotal;

    /** The sum of the net turnovers of the lower-rank Z documents (R2). */
    @Column(name = "control_total", precision = 19, scale = 4)
    public BigDecimal controlTotal;

    /** The gap between {@link #netTurnover} and {@link #controlTotal} (R2). */
    @Column(name = "control_variance", precision = 19, scale = 4)
    public BigDecimal controlVariance;

    /** The number of tickets that arrived after the previous Z was emitted. */
    @Column(name = "late_ticket_count", nullable = false)
    public int lateTicketCount;

    /** The float mode at the moment of emission. */
    @Column(name = "float_mode", length = 30)
    public String floatMode;

    /** The chained signature of this state. */
    @Column(name = "signature", length = 64)
    public String signature;

    /** The signature of the previous Z of the same chain, or the empty string. */
    @Column(name = "previous_signature", length = 64)
    public String previousSignature;

    /** How many times the document has been printed; a reprint is not an emission. */
    @Column(name = "print_count", nullable = false)
    public int printCount;

    /** The lines of the four contents (R7 to R19). */
    @OneToMany(mappedBy = "state", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<FinancialStateLine> lines = new ArrayList<>();

    /**
     * Attaches a line to this state and back-links it, keeping the two ends of
     * the association consistent.
     *
     * @param line the line to attach
     */
    public void addLine(FinancialStateLine line) {
        line.state = this;
        this.lines.add(line);
    }

    /**
     * Computes the chained SHA-256 signature of this state over its canonical
     * fields, the same technique as {@code EngineFeedService} /
     * {@code RefExportService}.
     *
     * <p>The canonical string concatenates, separated by {@code |}: the state
     * type, the scope, the period kind, the cashier login or the empty string,
     * the sequence, the two instants, the gross and net turnovers, the customer
     * and item counts, and the previous signature. The totals must be set before
     * signing, so the frozen figures are the ones the chain commits to.
     *
     * @return the signature, hex encoded
     */
    public String computeSignature() {
        String canonical = String.join("|",
                stateType == null ? "" : stateType.name(),
                scope == null ? "" : scope.name(),
                periodKind == null ? "" : periodKind.name(),
                cashier == null || cashier.loginName == null ? "" : cashier.loginName,
                Long.toString(sequence),
                fromInstant == null ? "" : fromInstant.toString(),
                toInstant == null ? "" : toInstant.toString(),
                grossTurnover == null ? "" : grossTurnover.toPlainString(),
                netTurnover == null ? "" : netTurnover.toPlainString(),
                Integer.toString(customerCount),
                Integer.toString(itemCount),
                previousSignature == null ? "" : previousSignature);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns the audit checksum of the state.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(stateType, scope, periodKind, sequence, netTurnover, signature);
    }
}
