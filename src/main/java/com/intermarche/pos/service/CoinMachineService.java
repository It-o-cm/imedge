package com.intermarche.pos.service;

import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.safe.CoinMachine;
import com.intermarche.pos.domain.sale.Ticket;
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
        add(ticket.terminalId, moved);
        LOGGER.info("Exiting method collect");
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
        move(machine, amount);
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
     */
    void add(String terminalId, BigDecimal amount) {
        List<CoinMachine> machines = CoinMachine.ofTerminal(terminalId);
        if (machines.isEmpty()) {
            return;
        }
        BigDecimal share = amount.divide(BigDecimal.valueOf(machines.size()), 4,
                java.math.RoundingMode.HALF_UP);
        for (CoinMachine machine : machines) {
            move(machine, share);
        }
    }

    /**
     * Moves one machine's running total.
     *
     * @param machine the machine moved
     * @param amount the amount to add, negative to take out
     */
    private void move(CoinMachine machine, BigDecimal amount) {
        BigDecimal held = machine.declaredAmount == null
                ? BigDecimal.ZERO : machine.declaredAmount;
        machine.declaredAmount = held.add(amount);
        machine.updatedOn = LocalDateTime.now();
        machine.persist();
        LOGGER.infof("Monnayeur %s : %s, nouveau montant tenu %s", machine.code, amount,
                machine.declaredAmount);
    }
}
