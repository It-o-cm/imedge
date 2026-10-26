package com.intermarche.edge.service;

import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.safe.CoinMachine;
import com.intermarche.edge.domain.safe.CoinMachineFlow;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.CashMovement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What the COIN MACHINES of the line hold ({@code BO-05-02-16}), kept by the
 * software rather than asked of the machines.
 *
 * <p>The requirement is explicit about the point: the available amount must be
 * known WITHOUT querying the unit. So the figure is carried here, moved by the
 * facts the node already receives — the cash a register collects, the change it
 * gives back, the loadings a manager declares — and the machine is only ever
 * asked when somebody wants to compare.
 *
 * <p>ONE CONSOLIDATED TICKET MOVES IT ONCE. The node's ticket ingestion is an
 * UPSERT: a register re-pushing a ticket must not credit its machine twice, so
 * the collection is driven from the ticket's own cash total and the same ticket
 * arriving again lands on the same figure it already produced.
 *
 * <p>A register with no machine declared moves nothing, which is the normal
 * state of a line that has none: the shops that own coin units declare them, the
 * others never see this at all.
 *
 * <p>EVERY FLOW OF THE REGISTER IS RACCORDED, not only its sales: the cash a
 * refund paid out, the floats, withdrawals, expenses, down-payments and
 * transfers the cashier made in cash at the machine's register, the loadings
 * declared at the back office — and each one is journalled as a
 * {@link CoinMachineFlow}, so the running total can be explained flow by flow
 * without asking the machine. The automatic withdrawal a cash changer books for
 * its own takings ({@link CashMovement#CASH_CHANGER_REASON}) moves nothing: the
 * notes never left the machine.
 */
@ApplicationScoped
public class CoinMachineService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CoinMachineService.class);

    /**
     * Moves the machines of a register by what a consolidated ticket settled in
     * cash.
     *
     * <p>NET and not gross: the customer hands over notes and coins, the machine
     * gives change back, and what stays inside is the amount the ticket applied.
     * Recording the tendered amount and forgetting the change is how a machine
     * ends up believed to hold twice what it does.
     *
     * @param ticket the consolidated ticket, null moving nothing
     * @param previousCash what the same ticket had already contributed, null on
     *                     a ticket the node had never seen
     */
    @Transactional
    public void collect(Ticket ticket, BigDecimal previousCash) {
        LOGGER.info("Entering method collect");
        if (ticket == null || ticket.terminalId == null) {
            LOGGER.info("Exiting method collect: no register named");
            return;
        }
        BigDecimal moved = cashOf(ticket)
                .subtract(previousCash == null ? BigDecimal.ZERO : previousCash);
        if (moved.signum() == 0) {
            LOGGER.info("Exiting method collect: nothing moved");
            return;
        }
        add(ticket.terminalId, moved, CoinMachineFlow.Kind.TICKET, ticket.ticketNumber);
        LOGGER.info("Exiting method collect");
    }

    /**
     * Moves the machines of a register by what a consolidated refund paid out in
     * cash ({@code BO-05-02-16}).
     *
     * @param refund the consolidated refund, null moving nothing
     * @param previousCash what the same refund had already paid out, null on a
     *                     refund the node had never seen
     */
    @Transactional
    public void collectRefund(Refund refund, BigDecimal previousCash) {
        LOGGER.info("Entering method collectRefund");
        if (refund == null || refund.terminalId == null) {
            LOGGER.info("Exiting method collectRefund: no register named");
            return;
        }
        BigDecimal moved = refundCashOf(refund)
                .subtract(previousCash == null ? BigDecimal.ZERO : previousCash);
        if (moved.signum() == 0) {
            LOGGER.info("Exiting method collectRefund: nothing moved");
            return;
        }
        add(refund.terminalId, moved.negate(), CoinMachineFlow.Kind.REFUND, refund.refundNumber);
        LOGGER.info("Exiting method collectRefund");
    }

    /**
     * The cash a refund paid out.
     *
     * @param refund the refund, null answering zero
     * @return its amount when paid in cash, zero otherwise
     */
    public BigDecimal refundCashOf(Refund refund) {
        if (refund == null || refund.refundMethod != Refund.RefundMethod.CASH || refund.totalAmount == null) {
            return BigDecimal.ZERO;
        }
        return refund.totalAmount.abs();
    }

    /**
     * Moves the machines of a register by a drawer movement made at it
     * ({@code BO-05-02-16}).
     *
     * @param movement the consolidated movement, null moving nothing
     * @param previousImpact what the same movement had already moved, null on a
     *                       movement the node had never seen
     */
    @Transactional
    public void collectMovement(CashMovement movement, BigDecimal previousImpact) {
        LOGGER.info("Entering method collectMovement");
        if (movement == null || movement.terminalId == null) {
            LOGGER.info("Exiting method collectMovement: no register named");
            return;
        }
        BigDecimal moved = impactOf(movement)
                .subtract(previousImpact == null ? BigDecimal.ZERO : previousImpact);
        if (moved.signum() == 0) {
            LOGGER.info("Exiting method collectMovement: nothing moved");
            return;
        }
        add(movement.terminalId, moved, CoinMachineFlow.Kind.MOVEMENT, movement.movementUid);
        LOGGER.info("Exiting method collectMovement");
    }

    /**
     * What a drawer movement does to the cash of its register's machines.
     *
     * <p>A float or a down-payment taken in cash adds, a withdrawal or an expense
     * paid in cash takes out, a transfer touches the cash only when cash is one
     * of its two ends, and a declaration moves nothing. The automatic withdrawal
     * of a cash changer's own takings moves nothing either.
     *
     * @param movement the movement, null answering zero
     * @return the signed amount, zero when the cash is not concerned
     */
    public BigDecimal impactOf(CashMovement movement) {
        if (movement == null || movement.amount == null || movement.type == null
                || CashMovement.CASH_CHANGER_REASON.equals(movement.reason)) {
            return BigDecimal.ZERO;
        }
        boolean fromCash = isCash(movement.paymentMethod);
        return switch (movement.type) {
            case DEPOSIT, CUSTOMER_DEPOSIT -> fromCash ? movement.amount : BigDecimal.ZERO;
            case WITHDRAWAL, EXPENSE -> fromCash ? movement.amount.negate() : BigDecimal.ZERO;
            case TRANSFER -> fromCash == isCash(movement.transferTo) ? BigDecimal.ZERO
                    : fromCash ? movement.amount.negate() : movement.amount;
            case DECLARATION -> BigDecimal.ZERO;
        };
    }

    /**
     * Journals a count or a realignment of a machine ({@code BO-05-02-16}).
     *
     * @param machine the machine counted or realigned
     * @param kind {@code COUNT} or {@code REALIGN}
     * @param amount what the gesture moved, zero for a count
     */
    @Transactional
    public void journal(CoinMachine machine, CoinMachineFlow.Kind kind, BigDecimal amount) {
        LOGGER.info("Entering method journal with kind: " + kind);
        record(machine, kind, null, amount);
        LOGGER.info("Exiting method journal");
    }

    /**
     * Tells whether a tender key names the cash, a missing key included.
     *
     * @param method the tender key, possibly null
     * @return true for the cash
     */
    static boolean isCash(String method) {
        return method == null || method.isBlank() || CashMovement.CASH.equals(method);
    }

    /**
     * The cash a ticket settled, change already deducted.
     *
     * @param ticket the ticket, null answering zero
     * @return the net cash, never null
     */
    public BigDecimal cashOf(Ticket ticket) {
        LOGGER.info("Entering method cashOf");
        BigDecimal total = BigDecimal.ZERO;
        if (ticket == null || ticket.payments == null) {
            LOGGER.info("Exiting method cashOf");
            return total;
        }
        for (TicketPayment payment : ticket.payments) {
            if (payment instanceof CashPayment && payment.amount != null) {
                total = total.add(payment.amount);
            }
        }
        LOGGER.info("Exiting method cashOf");
        return total;
    }

    /**
     * Declares cash loaded into or taken out of a machine ({@code BO-05-02-16}).
     *
     * @param code the machine's code
     * @param amount the amount loaded, negative when emptied
     * @return the machine moved, or null when the code names none
     */
    @Transactional
    public CoinMachine load(String code, BigDecimal amount) {
        LOGGER.info("Entering method load with code: " + code + ", amount: " + amount);
        CoinMachine machine = CoinMachine.findByCode(code);
        if (machine == null || amount == null || amount.signum() == 0) {
            LOGGER.info("Exiting method load: nothing to move");
            return null;
        }
        move(machine, amount, CoinMachineFlow.Kind.LOAD, null);
        LOGGER.info("Exiting method load");
        return machine;
    }

    /**
     * Adds an amount to every machine of a register.
     *
     * <p>Split evenly when a register carries several, because nothing in what
     * the register pushes says which unit swallowed which coin — and a rule that
     * pretended otherwise would be a guess dressed as a figure.
     *
     * @param terminalId the register identifier
     * @param amount the amount to spread
     * @param kind what moved the machines
     * @param reference the business reference of the flow, or null
     */
    void add(String terminalId, BigDecimal amount, CoinMachineFlow.Kind kind, String reference) {
        List<CoinMachine> machines = CoinMachine.ofTerminal(terminalId);
        if (machines.isEmpty()) {
            return;
        }
        BigDecimal share = amount.divide(BigDecimal.valueOf(machines.size()), 4,
                java.math.RoundingMode.HALF_UP);
        for (CoinMachine machine : machines) {
            move(machine, share, kind, reference);
        }
    }

    /**
     * Moves one machine's running total.
     *
     * @param machine the machine moved
     * @param amount the amount to add, negative to take out
     * @param kind what moved it
     * @param reference the business reference of the flow, or null
     */
    private void move(CoinMachine machine, BigDecimal amount, CoinMachineFlow.Kind kind, String reference) {
        BigDecimal held = machine.declaredAmount == null
                ? BigDecimal.ZERO : machine.declaredAmount;
        machine.declaredAmount = held.add(amount);
        machine.updatedOn = LocalDateTime.now();
        machine.persist();
        LOGGER.infof("Monnayeur %s : %s, nouveau montant tenu %s", machine.code, amount,
                machine.declaredAmount);
        record(machine, kind, reference, amount);
    }

    /**
     * Writes one flow of a machine into its journal.
     *
     * @param machine the machine
     * @param kind what moved it
     * @param reference the business reference, or null
     * @param amount the signed amount moved
     */
    private void record(CoinMachine machine, CoinMachineFlow.Kind kind, String reference, BigDecimal amount) {
        CoinMachineFlow flow = new CoinMachineFlow();
        flow.machineCode = machine.code;
        flow.kind = kind;
        flow.reference = reference;
        flow.amount = amount == null ? BigDecimal.ZERO : amount;
        flow.balanceAfter = machine.declaredAmount == null ? BigDecimal.ZERO : machine.declaredAmount;
        flow.recordedAt = LocalDateTime.now();
        flow.persist();
    }
}
