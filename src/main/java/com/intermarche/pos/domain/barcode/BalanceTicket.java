package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A COUNTER TICKET pushed by a scale system and waiting to be picked up by a
 * register (LC-06-01).
 *
 * <p>The traditional counters — butcher, cheese, deli — weigh and price in
 * front of the customer, then hand over one paper carrying one barcode. What
 * the customer walks to the till with is a REFERENCE, not the goods: the
 * detail lives here.
 *
 * <p>THIS ROW ONLY EXISTS ON THE STORE NODE. The scale cannot push to a
 * register — at weighing time nobody knows which lane the customer will
 * choose — so it pushes here, and the register asks for it when the customer
 * finally presents the paper. That is also what makes the shop able to answer
 * the one question a copy on every register never could: has this ticket
 * already been sold?
 *
 * <p>CONSUMED ONCE, AND THE SHOP ARBITRATES. {@link #consumedAt} is set by the
 * register's pick-up, in the same transaction that serves the detail. Two
 * registers racing for the same reference, or one cashier scanning the paper
 * twice, meet the same refusal — because there is one row and one shop, not
 * twelve copies to keep in step.
 */
@Entity
@Table(name = "balance_tickets")
public class BalanceTicket extends PanacheEntity {

    /** The scannable reference printed on the counter paper, unique. */
    @Column(name = "reference", length = 32, unique = true, nullable = false)
    public String reference;

    /** The counter that weighed it (BOUCHERIE, FROMAGE...), for the operator's eyes. */
    @Column(name = "counter_label", length = 40)
    public String counterLabel;

    /** When the scale system emitted it. */
    @Column(name = "emitted_at")
    public LocalDateTime emittedAt;

    /** When a register picked it up, or null while it is still available. */
    @Column(name = "consumed_at")
    public LocalDateTime consumedAt;

    /** The register that picked it up, or null while it is still available. */
    @Column(name = "consumed_by_terminal", length = 20)
    public String consumedByTerminal;

    /** The weighed lines, in the order the counter served them. */
    @OneToMany(mappedBy = "balanceTicket", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    public List<BalanceTicketLine> lines = new ArrayList<>();

    /**
     * Finds a counter ticket by its printed reference.
     *
     * @param reference the reference scanned at the till
     * @return the ticket, or null when the shop holds no such reference
     */
    public static BalanceTicket findByReference(String reference) {
        return find("reference", reference).firstResult();
    }

    /**
     * Tells whether this ticket has already been picked up by a register.
     *
     * @return true once a register has consumed it
     */
    public boolean isConsumed() {
        return consumedAt != null;
    }
}
