package com.intermarche.pos.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Per-terminal ticket sequence counter, stored in the register's database.
 * <p>
 * One row per register (terminal). The row is read with a pessimistic write
 * lock and incremented inside the ticket-creation transaction, guaranteeing a
 * strictly increasing, gap-aware sequence per terminal — the foundation of the
 * phase 1 fiscal chaining. Different terminals lock different rows, so there is
 * no contention (one register per database).
 * <p>
 * Extends {@link PanacheEntity} directly (no audit fields needed on a pure
 * technical counter).
 * <p>
 * The three sequences (tickets "C04-00000123", sessions "C04-S00012",
 * refunds "C04-R000012") and the chaining anchors all live on the same row,
 * so one pessimistic lock serializes every numbering and chaining mutation
 * of the register — the invariant the phase 1 fiscal chain rests on.
 * {@link #grandTotal} is perpetual: never reset, snapshotted on each closed
 * ticket ({@code Ticket.grandTotal}) so any truncation of the chain is visible.
 */
@Entity
@Table(name = "ticket_counters",
        indexes = @Index(name = "idx_ticket_counter_terminal", columnList = "terminal_id", unique = true)
)
public class TicketCounter extends PanacheEntity {

    /**
     * The identifier of the register this counter belongs to (pos.terminal.id).
     */
    @Column(name = "terminal_id", nullable = false, unique = true, length = 20)
    public String terminalId;

    /**
     * The last sequence number issued for this terminal (0 = none yet).
     */
    @Column(name = "last_number", nullable = false)
    public long lastNumber;

    /**
     * The last cash-session sequence number issued for this terminal (0 = none yet).
     */
    @Column(name = "last_session_number", nullable = false)
    public long lastSessionNumber;

    /**
     * The last refund sequence number issued for this terminal (0 = none yet).
     */
    @Column(name = "last_refund_number", nullable = false)
    public long lastRefundNumber;

    /**
     * The last account-customer sequence number issued for this terminal (0 = none yet).
     * <p>
     * A customer created at the register needs an account number, and until the
     * commercial-management system sends its own population there is nothing that
     * number could collide with. It sits on this row rather than on a counter of its
     * own because it is a register-scoped sequence exactly like the refund one, and
     * it is drawn outside any sale.
     */
    @Column(name = "last_customer_number", nullable = false)
    public long lastCustomerNumber;

    /**
     * The signature of the last closed ticket of this terminal, anchor of the
     * per-register fiscal chain; null until the first closing.
     */
    @Column(name = "last_signature", length = 64)
    public String lastSignature;

    /**
     * The perpetual grand total of this terminal (sum of every closed ticket's
     * tax-included total, never reset).
     */
    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    public BigDecimal grandTotal = BigDecimal.ZERO;
}
