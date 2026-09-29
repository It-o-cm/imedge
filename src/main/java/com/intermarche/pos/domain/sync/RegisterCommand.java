package com.intermarche.pos.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An order the STORE NODE leaves for one register, waiting to be collected.
 *
 * <p>The registers pull; the node does not push. That is the whole shape of the
 * synchronization — a shop's registers sit behind whatever network the store
 * has, and a node that had to reach them would need each of them addressable.
 * So an order from the back office is not a call, it is a ROW: the node writes
 * it, the register collects it on its next cycle and acknowledges it, and the
 * acknowledgement is what proves it was carried out (LC-01-02-07, BO-04-03-13).
 *
 * <p>This entity exists on the NODE only. A register never stores an order — it
 * reads it, applies it and acknowledges it — so giving the forty registers of a
 * shop an always-empty table would be dead weight on each of them.
 *
 * <p>An order is never deleted once carried out: it is stamped. A back office
 * that closed a register at 20:14 has to be able to say so afterwards, and a
 * deleted row says nothing.
 *
 * <p>Extends {@code PanacheEntity} and not {@code BaseEntity}: this is
 * plumbing, like {@code SyncOutbox} and {@code RefState}, and the audit
 * columns of the business model would be noise on a mailbox row.
 */
@Entity
@Table(name = "register_commands",
        indexes = @Index(name = "idx_register_command_target", columnList = "terminal_id, consumed_at"))
public class RegisterCommand extends PanacheEntity {

    /** What a register is being asked to do. */
    public enum CommandType {

        /**
         * Close the operator session: the register logs its operator out as if
         * a supervisor had forced it, and journals the forcing.
         */
        CLOSE_SESSION,

        /**
         * Close the operator session ONLY when the register is at rest.
         *
         * <p>The order the end of period leaves (LC-01-02-08, BO-09-03-07).
         * The node cannot tell a register standing idle from one halfway
         * through a customer's shopping — a cart being built is never pushed
         * anywhere — so the register is the one that decides: it closes when
         * nothing is on the screen, and otherwise leaves the order PENDING and
         * takes it again at its next turn, which is the moment the cashier
         * finishes.
         */
        CLOSE_SESSION_IF_IDLE,

        /**
         * Display a back-office message on the register (EXP-20). The text rides
         * in {@link #reason}. The register shows it on the CASHIER screen only,
         * never the customer display, and it never interrupts an in-progress
         * sale — that rendering rule is the register's; the node only issues the
         * order. Persisted by {@code STRING}, so appending this value is
         * wire-safe; carry it across to impos on the register side.
         */
        DISPLAY_MESSAGE
    }

    /** Stable identity of the order, the token the acknowledgement carries. */
    @Column(name = "command_uid", nullable = false, unique = true, length = 64)
    public String commandUid;

    /** The register the order is addressed to. */
    @Column(name = "terminal_id", nullable = false, length = 32)
    public String terminalId;

    /** What the register is asked to do. */
    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 32)
    public CommandType commandType;

    /** Who issued it, for the journal and for the screen that lists it. */
    @Column(name = "issued_by", length = 60)
    public String issuedBy;

    /** Why it was issued, free text shown on the register's receipt. */
    @Column(name = "reason", length = 200)
    public String reason;

    /** When it was left for the register. */
    @Column(name = "issued_at", nullable = false)
    public LocalDateTime issuedAt = LocalDateTime.now();

    /** When the register acknowledged carrying it out, null while pending. */
    @Column(name = "consumed_at")
    public LocalDateTime consumedAt;

    /**
     * Lists the orders one register has not acknowledged yet, oldest first.
     *
     * @param terminalId the register asking
     * @return its pending orders, possibly empty
     */
    public static List<RegisterCommand> pendingFor(String terminalId) {
        return find("terminalId = ?1 and consumedAt is null order by issuedAt", terminalId).list();
    }

    /**
     * Finds an order by the token an acknowledgement carries.
     *
     * @param commandUid the order's stable identity
     * @return the order, or null when no order carries that token
     */
    public static RegisterCommand findByUid(String commandUid) {
        return find("commandUid", commandUid).firstResult();
    }

    /**
     * Counts the orders still waiting for a given register.
     *
     * @param terminalId the register
     * @return the number of pending orders
     */
    public static long countPending(String terminalId) {
        return count("terminalId = ?1 and consumedAt is null", terminalId);
    }
}
