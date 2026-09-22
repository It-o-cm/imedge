package com.intermarche.pos.service;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.safe.AccountingPeriod;
import com.intermarche.pos.domain.safe.BankDeposit;
import com.intermarche.pos.domain.safe.CashPouch;
import com.intermarche.pos.domain.safe.CoinOrder;
import com.intermarche.pos.domain.safe.SafeInventory;
import com.intermarche.pos.domain.safe.SafeMovement;
import com.intermarche.pos.domain.safe.SafeMovementType;
import com.intermarche.pos.domain.safe.SafeVatLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The SAFE of the point of sale ({@code BO-05-02}): its balance, its ledger, its
 * accounting periods and the gestures that move it.
 *
 * <p>Lives in the general services and not in {@code ui.admin} because it serves
 * more than a screen: the movement screen, the pouch screen, the bank deposit,
 * the inventory, the closure and the automatic integration of receipts all write
 * through it, and the register's own consolidation reads it. Nothing here knows
 * what a page looks like.
 *
 * <p>ONE RULE ABOVE THE OTHERS: the balance of the safe is the sum of its
 * movements. There is no stored balance to drift, no gesture that moves the safe
 * without writing a movement, and no movement written outside the open period.
 * Everything else in this class follows from that.
 */
@ApplicationScoped
public class SafeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SafeService.class);

    /** The refusal returned when a gesture is attempted with no period open. */
    public static final String NO_OPEN_PERIOD = "Aucune période comptable ouverte.";

    /** The refusal returned when a closure is attempted with movements still pending. */
    public static final String PENDING_MOVEMENTS =
            "Des mouvements de la période ne sont pas validés.";

    /** The refusal returned when a correction is attempted on a frozen movement. */
    public static final String FROZEN_MOVEMENT =
            "Ce mouvement est validé ou sa période est close.";

    /**
     * The balance of the safe: the sum of every movement ever written.
     *
     * @return the balance, never null
     */
    public BigDecimal balance() {
        LOGGER.info("Entering method balance");
        BigDecimal balance = sum(SafeMovement.<SafeMovement>find("order by id").list());
        LOGGER.info("Exiting method balance");
        return balance;
    }

    /**
     * The balance the safe held at the end of a period: the period's opening
     * balance plus every movement that falls in it.
     *
     * @param period the period, null answering zero
     * @return the balance, never null
     */
    public BigDecimal balanceOf(AccountingPeriod period) {
        LOGGER.info("Entering method balanceOf");
        if (period == null) {
            LOGGER.info("Exiting method balanceOf");
            return BigDecimal.ZERO;
        }
        BigDecimal opening = period.openingBalance == null
                ? BigDecimal.ZERO : period.openingBalance;
        BigDecimal balance = opening.add(sum(movementsOf(period)));
        LOGGER.info("Exiting method balanceOf");
        return balance;
    }

    /**
     * The movements of one period, oldest first.
     *
     * @param period the period, null answering an empty list
     * @return the movements of that period
     */
    public List<SafeMovement> movementsOf(AccountingPeriod period) {
        LOGGER.info("Entering method movementsOf");
        if (period == null || period.id == null) {
            LOGGER.info("Exiting method movementsOf");
            return List.of();
        }
        List<SafeMovement> movements = SafeMovement
                .<SafeMovement>find("period.id = ?1 order by movementDate, id", period.id).list();
        LOGGER.info("Exiting method movementsOf with " + movements.size() + " movement(s)");
        return movements;
    }

    /**
     * The movements of a stretch of days, oldest first — the journal of the safe
     * ({@code BO-05-02-13}).
     *
     * @param from the first day, null meaning from the beginning
     * @param to the last day, null meaning up to today
     * @return the movements, oldest first
     */
    public List<SafeMovement> journal(LocalDate from, LocalDate to) {
        LOGGER.info("Entering method journal with from: " + from + ", to: " + to);
        LocalDate first = from == null ? LocalDate.of(1970, 1, 1) : from;
        LocalDate last = to == null ? LocalDate.now() : to;
        List<SafeMovement> movements = SafeMovement.<SafeMovement>find(
                "movementDate >= ?1 and movementDate <= ?2 order by movementDate, id",
                first, last).list();
        LOGGER.info("Exiting method journal with " + movements.size() + " movement(s)");
        return movements;
    }

    /**
     * The period currently open, or null when the safe has never been opened.
     *
     * @return the open period, or null
     */
    public AccountingPeriod openPeriod() {
        LOGGER.info("Entering method openPeriod");
        AccountingPeriod period = AccountingPeriod.open();
        LOGGER.info("Exiting method openPeriod");
        return period;
    }

    /**
     * Opens the safe's first period, or the one following a closure
     * ({@code BO-05-02-12}).
     *
     * <p>The opening balance is the balance the safe holds right now, which after
     * a closure is the closing balance of the period before: the chain is written
     * once and never recomputed.
     *
     * @param label the operator-facing name of the period
     * @param startDate the first day the period counts
     * @return the period opened, or null when one is already open
     */
    @Transactional
    public AccountingPeriod open(String label, LocalDate startDate) {
        LOGGER.info("Entering method open with label: " + label + ", startDate: " + startDate);
        if (AccountingPeriod.open() != null) {
            LOGGER.info("Exiting method open: a period is already open");
            return null;
        }
        AccountingPeriod period = new AccountingPeriod();
        period.label = label;
        period.startDate = startDate == null ? LocalDate.now() : startDate;
        period.openingBalance = balance();
        period.status = AccountingPeriod.PeriodStatus.OPEN;
        period.persist();
        LOGGER.infof("Période comptable %s ouverte avec un solde de %s", period.label,
                period.openingBalance);
        LOGGER.info("Exiting method open");
        return period;
    }

    /**
     * Closes the open period ({@code BO-05-02-12}).
     *
     * <p>Refused while a movement of the period is still pending: the requirement
     * asks for the movements to be pointed and validated BEFORE the period is
     * stopped, and a closure that swallowed an unchecked movement would freeze a
     * mistake instead of catching it.
     *
     * @param closedBy who closes, null when not recorded
     * @return the refusal, or null when the period was closed
     */
    @Transactional
    public String close(Employee closedBy) {
        LOGGER.info("Entering method close");
        AccountingPeriod period = AccountingPeriod.open();
        if (period == null) {
            LOGGER.info("Exiting method close: no open period");
            return NO_OPEN_PERIOD;
        }
        if (countPending(period) > 0) {
            LOGGER.info("Exiting method close: pending movements");
            return PENDING_MOVEMENTS;
        }
        period.closingBalance = balanceOf(period);
        period.closedBy = closedBy;
        period.closedAt = LocalDateTime.now();
        period.endDate = LocalDate.now();
        period.status = AccountingPeriod.PeriodStatus.CLOSED;
        period.persist();
        LOGGER.infof("Période comptable %s close avec un solde de %s", period.label,
                period.closingBalance);
        LOGGER.info("Exiting method close");
        return null;
    }

    /**
     * How many movements of a period are still waiting to be validated
     * ({@code BO-05-02-06}).
     *
     * @param period the period, null answering zero
     * @return the number of pending movements
     */
    public long countPending(AccountingPeriod period) {
        LOGGER.info("Entering method countPending");
        if (period == null || period.id == null) {
            LOGGER.info("Exiting method countPending");
            return 0;
        }
        long pending = SafeMovement.count("period.id = ?1 and status = ?2", period.id,
                SafeMovement.MovementStatus.PENDING);
        LOGGER.info("Exiting method countPending with " + pending);
        return pending;
    }

    /**
     * Writes a movement into the ledger, attached to the open period.
     *
     * <p>The ONE door into the ledger: every gesture of the safe comes through
     * here, so a period that is closed — or never opened — stops all of them at
     * once instead of each screen remembering to ask.
     *
     * @param type what the movement does to the safe
     * @param amount the amount, always positive
     * @param label the operator-facing wording
     * @param day the day the movement counts for, null meaning today
     * @param enteredBy who entered it, null when it came from an integration
     * @param employee the employee it concerns, null when it concerns none
     * @param reference the external reference, null when it carries none
     * @param vatLines the VAT breakdown, null or empty when it bears none
     * @return the movement written, or null when no period is open
     */
    @Transactional
    public SafeMovement record(SafeMovementType type, BigDecimal amount, String label,
            LocalDate day, Employee enteredBy, Employee employee, String reference,
            List<SafeVatLine> vatLines) {
        LOGGER.info("Entering method record with type: " + type + ", amount: " + amount);
        AccountingPeriod period = AccountingPeriod.open();
        if (period == null) {
            LOGGER.info("Exiting method record: no open period");
            return null;
        }
        SafeMovement movement = new SafeMovement();
        movement.type = type;
        movement.amount = amount == null ? BigDecimal.ZERO : amount.abs();
        movement.label = label;
        movement.movementDate = day == null ? LocalDate.now() : day;
        movement.enteredAt = LocalDateTime.now();
        movement.period = period;
        movement.enteredBy = enteredBy;
        movement.employee = employee;
        movement.reference = reference;
        movement.status = SafeMovement.MovementStatus.PENDING;
        movement.vatLines = stated(type, vatLines);
        movement.persist();
        LOGGER.infof("Mouvement de coffre %s de %s enregistré : %s", type, movement.amount, label);
        LOGGER.info("Exiting method record");
        return movement;
    }

    /**
     * Validates a movement ({@code BO-05-02-06}).
     *
     * @param movement the movement pointed, null doing nothing
     * @param validatedBy who validates, null when not recorded
     * @return true when the movement moved to validated
     */
    @Transactional
    public boolean validate(SafeMovement movement, Employee validatedBy) {
        LOGGER.info("Entering method validate");
        if (movement == null || movement.isValidated()) {
            LOGGER.info("Exiting method validate: nothing to do");
            return false;
        }
        movement.status = SafeMovement.MovementStatus.VALIDATED;
        movement.validatedBy = validatedBy;
        movement.validatedAt = LocalDateTime.now();
        movement.persist();
        LOGGER.info("Exiting method validate");
        return true;
    }

    /**
     * Corrects a movement before it is validated ({@code BO-05-02-06}).
     *
     * @param movement the movement corrected, null doing nothing
     * @param amount the new amount, null leaving it alone
     * @param label the new wording, null or blank leaving it alone
     * @param day the new day, null leaving it alone
     * @return the refusal, or null when the correction was written
     */
    @Transactional
    public String correct(SafeMovement movement, BigDecimal amount, String label, LocalDate day) {
        LOGGER.info("Entering method correct");
        if (movement == null) {
            LOGGER.info("Exiting method correct: unknown movement");
            return FROZEN_MOVEMENT;
        }
        if (!movement.isCorrectable()) {
            LOGGER.info("Exiting method correct: frozen movement");
            return FROZEN_MOVEMENT;
        }
        if (amount != null) {
            movement.amount = amount.abs();
        }
        if (label != null && !label.isBlank()) {
            movement.label = label.trim();
        }
        if (day != null) {
            movement.movementDate = day;
        }
        movement.persist();
        LOGGER.info("Exiting method correct");
        return null;
    }

    /**
     * Brings a pouch of a day's takings into the safe ({@code BO-05-02-10}).
     *
     * @param pouch the pouch declared, its number and amount already set
     * @param depositedBy who brought it, null when not recorded
     * @return the refusal, or null when the pouch was taken in
     */
    @Transactional
    public String depositPouch(CashPouch pouch, Employee depositedBy) {
        LOGGER.info("Entering method depositPouch");
        if (pouch == null) {
            LOGGER.info("Exiting method depositPouch: no pouch");
            return NO_OPEN_PERIOD;
        }
        SafeMovement movement = record(SafeMovementType.POUCH, pouch.amount,
                "Remise au coffre " + pouch.pouchNumber, pouch.accountingDay, depositedBy, null,
                pouch.pouchNumber, null);
        if (movement == null) {
            LOGGER.info("Exiting method depositPouch: no open period");
            return NO_OPEN_PERIOD;
        }
        pouch.movement = movement;
        pouch.depositedBy = depositedBy;
        pouch.status = CashPouch.PouchStatus.IN_SAFE;
        pouch.persist();
        LOGGER.info("Exiting method depositPouch");
        return null;
    }

    /**
     * Sends pouches to the bank ({@code BO-05-02-07}).
     *
     * <p>The deposit's amount is the SUM OF ITS POUCHES and is never typed: a
     * bag whose declared amount differed from what it holds would be a
     * discrepancy nobody could explain, since the pouches are what physically
     * went into it.
     *
     * @param deposit the deposit declared, its bag number and day already set
     * @param pouches the pouches it takes away, empty refusing the deposit
     * @param declaredBy who declares it, null when not recorded
     * @return the refusal, or null when the deposit was written
     */
    @Transactional
    public String depositToBank(BankDeposit deposit, List<CashPouch> pouches,
            Employee declaredBy) {
        LOGGER.info("Entering method depositToBank");
        if (deposit == null || pouches == null || pouches.isEmpty()) {
            LOGGER.info("Exiting method depositToBank: nothing to deposit");
            return "Aucune pochette sélectionnée.";
        }
        BigDecimal total = BigDecimal.ZERO;
        for (CashPouch pouch : pouches) {
            total = total.add(pouch.amount == null ? BigDecimal.ZERO : pouch.amount);
        }
        deposit.amount = total;
        deposit.declaredBy = declaredBy;
        SafeMovement movement = record(SafeMovementType.BANK_DEPOSIT, total,
                "Remise en banque " + deposit.bagNumber, deposit.depositDay, declaredBy, null,
                deposit.bagNumber, null);
        if (movement == null) {
            LOGGER.info("Exiting method depositToBank: no open period");
            return NO_OPEN_PERIOD;
        }
        deposit.movement = movement;
        deposit.persist();
        for (CashPouch pouch : pouches) {
            pouch.bankDeposit = deposit;
            pouch.status = CashPouch.PouchStatus.BANKED;
            pouch.persist();
        }
        LOGGER.infof("Remise en banque %s de %s (%d pochette(s))", deposit.bagNumber, total,
                pouches.size());
        LOGGER.info("Exiting method depositToBank");
        return null;
    }

    /**
     * Records the delivery of a coin order ({@code BO-05-02-05}).
     *
     * @param order the order the delivery answers, its received quantities already set
     * @param receivedBy who received it, null when not recorded
     * @return the refusal, or null when the delivery was written
     */
    @Transactional
    public String receiveCoinOrder(CoinOrder order, Employee receivedBy) {
        LOGGER.info("Entering method receiveCoinOrder");
        if (order == null || !order.isAwaitingDelivery()) {
            LOGGER.info("Exiting method receiveCoinOrder: nothing to receive");
            return "Cette demande n'attend plus de livraison.";
        }
        BigDecimal received = order.receivedAmount();
        if (received.signum() <= 0) {
            LOGGER.info("Exiting method receiveCoinOrder: empty delivery");
            return "Aucune quantité reçue n'a été saisie.";
        }
        SafeMovement movement = record(SafeMovementType.COIN_DELIVERY, received,
                "Réception de monnaie " + order.orderNumber, LocalDate.now(), receivedBy, null,
                order.orderNumber, null);
        if (movement == null) {
            LOGGER.info("Exiting method receiveCoinOrder: no open period");
            return NO_OPEN_PERIOD;
        }
        order.movement = movement;
        order.receivedAt = LocalDateTime.now();
        order.receivedBy = receivedBy;
        order.status = CoinOrder.OrderStatus.RECEIVED;
        order.persist();
        LOGGER.info("Exiting method receiveCoinOrder");
        return null;
    }

    /**
     * Takes an inventory of the safe ({@code BO-05-02-11}).
     *
     * <p>Writes nothing into the ledger: a count is an observation, and what the
     * shop does about the discrepancy is a decision it takes afterwards.
     *
     * @param inventory the count, its lines already set
     * @param countedBy who counted, null when not recorded
     * @return the count, its theoretical, counted and variance amounts written
     */
    @Transactional
    public SafeInventory takeInventory(SafeInventory inventory, Employee countedBy) {
        LOGGER.info("Entering method takeInventory");
        inventory.countedAt = LocalDateTime.now();
        inventory.countedBy = countedBy;
        inventory.period = AccountingPeriod.open();
        inventory.theoreticalAmount = balance();
        inventory.countedAmount = inventory.countedTotal();
        inventory.variance = inventory.countedAmount.subtract(inventory.theoreticalAmount);
        inventory.persist();
        LOGGER.infof("Inventaire du coffre : compté %s, théorique %s, écart %s",
                inventory.countedAmount, inventory.theoreticalAmount, inventory.variance);
        LOGGER.info("Exiting method takeInventory");
        return inventory;
    }

    /**
     * Writes off the discrepancy an inventory found ({@code BO-05-02-11}).
     *
     * @param inventory the count whose discrepancy is accepted
     * @param acceptedBy who accepts it, null when not recorded
     * @return the refusal, or null when the correction was written
     */
    @Transactional
    public String adjustFromInventory(SafeInventory inventory, Employee acceptedBy) {
        LOGGER.info("Entering method adjustFromInventory");
        if (inventory == null || !inventory.isAdjustable()) {
            LOGGER.info("Exiting method adjustFromInventory: nothing to adjust");
            return "Cet inventaire ne présente pas d'écart à régulariser.";
        }
        SafeMovementType type = inventory.variance.signum() > 0
                ? SafeMovementType.INVENTORY_ADJUSTMENT : SafeMovementType.EXPENSE;
        String label = inventory.variance.signum() > 0
                ? "Régularisation d'inventaire (excédent)"
                : "Régularisation d'inventaire (manquant)";
        SafeMovement movement = record(type, inventory.variance.abs(), label, LocalDate.now(),
                acceptedBy, null, "INVENTAIRE-" + inventory.id, null);
        if (movement == null) {
            LOGGER.info("Exiting method adjustFromInventory: no open period");
            return NO_OPEN_PERIOD;
        }
        inventory.adjustment = movement;
        inventory.persist();
        LOGGER.info("Exiting method adjustFromInventory");
        return null;
    }

    /**
     * Keeps only the VAT lines that state something, and only on a kind that may
     * bear them at all.
     *
     * @param type the kind of movement
     * @param vatLines the lines posted, possibly null
     * @return the lines to store, never null
     */
    private List<SafeVatLine> stated(SafeMovementType type, List<SafeVatLine> vatLines) {
        List<SafeVatLine> kept = new ArrayList<>();
        if (vatLines == null || type == null || !type.isVatBearing()) {
            return kept;
        }
        for (SafeVatLine line : vatLines) {
            if (line != null && line.isStated()) {
                kept.add(line);
            }
        }
        return kept;
    }

    /**
     * Sums the signed amounts of a list of movements.
     *
     * @param movements the movements, never null
     * @return the sum, never null
     */
    private BigDecimal sum(List<SafeMovement> movements) {
        BigDecimal total = BigDecimal.ZERO;
        for (SafeMovement movement : movements) {
            total = total.add(movement.signedAmount());
        }
        return total;
    }
}
