package com.intermarche.pos.service;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records the cash movements of this register (withdrawal, deposit, expense,
 * customer down-payment, cash-count declaration). Movements live in the
 * register's own database and are pushed to the store node THROUGH THE OUTBOX
 * (entity type {@code MOVEMENT}); nothing here makes a synchronous network
 * call in the sale thread, so a register cut off from the network keeps
 * recording movements.
 * <p>
 * A movement above the endorsement threshold requires a manager endorsement:
 * the endorsing manager's badge must be supplied, else the recording is
 * refused. This service READS NO SCREEN — the session, the cashier and the
 * endorsement decision are all handed to it by the caller. The threshold is
 * read from the administered catalog of {@code PosSettingsService} (key {@code
 * cash.movement-endorsement-threshold}, distributed by the SETTINGS domain of
 * the referential pull — the parameter that serves BO-04-01-44), never from a
 * hardcoded configuration key.
 */
@ApplicationScoped
public class CashMovementService {

    private static final Logger LOGGER = Logger.getLogger(CashMovementService.class);

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    SyncOutboxService syncOutboxService;

    @Inject
    PosSettingsService posSettingsService;

    /**
     * Indicates whether a movement of the given amount requires a manager
     * endorsement (strictly above the administered threshold). A null amount
     * never requires one.
     *
     * @param amount the movement amount, or null
     * @return true when the amount is strictly above the endorsement threshold
     */
    public boolean requiresEndorsement(BigDecimal amount) {
        LOGGER.info("Entering method requiresEndorsement with amount: " + amount);
        LOGGER.info("Exiting method requiresEndorsement");
        return amount != null
                && amount.compareTo(posSettingsService.cashMovementEndorsementThreshold()) > 0;
    }

    /**
     * Records a cash movement and enqueues it for store synchronization,
     * joining the caller's transaction. When the amount requires an endorsement
     * and none is supplied, the movement is refused and nothing is recorded.
     *
     * @param session the cash session the movement belongs to
     * @param cashier the cashier who performed the movement
     * @param type the kind of movement
     * @param amount the movement amount
     * @param reason the free-text reason, or null
     * @param endorsedBy the badge of the endorsing manager, or null when none
     * @return the recorded movement, or null when an endorsement was required
     *         but not supplied
     */
    @Transactional
    public CashMovement record(CashSession session, Employee cashier, CashMovement.MovementType type,
                               BigDecimal amount, String reason, String endorsedBy) {
        LOGGER.info("Entering method record with session: " + session + ", cashier: " + cashier + ", type: " + type + ", amount: " + amount + ", reason: " + reason + ", endorsedBy: " + endorsedBy);
        LOGGER.info("Exiting method record");
        return record(session, cashier, type, amount, reason, endorsedBy, null, null, null);
    }

    /**
     * Records a drawer movement that names the tender it concerns, joining the
     * caller's transaction.
     *
     * <p>A MOVEMENT WITHOUT ITS TENDER IS A CASH MOVEMENT. The drawer holds cheques
     * and meal vouchers too ({@code LC-12-03-02}), and a withdrawal of those must not
     * move the cash theoretical; naming the tender on the movement is what keeps the
     * two apart. A transfer ({@code LC-12-10-01}) names both ends, and the amount
     * leaves {@code paymentMethod} to land on {@code transferTo}.
     *
     * @param session the cash session the movement belongs to
     * @param cashier the cashier who performed the movement
     * @param type the kind of movement
     * @param amount the movement amount
     * @param reason the free-text reason, or null
     * @param endorsedBy the badge of the endorsing manager, or null when none
     * @param paymentMethod the tender the movement takes out, or null for cash
     * @param transferTo the tender a transfer lands on, or null when not a transfer
     * @param denominationDetail the per-denomination detail of a cash withdrawal, or null
     * @return the recorded movement, or null when an endorsement was required
     *         but not supplied
     */
    @Transactional
    public CashMovement record(CashSession session, Employee cashier, CashMovement.MovementType type,
                               BigDecimal amount, String reason, String endorsedBy,
                               String paymentMethod, String transferTo, String denominationDetail) {
        LOGGER.info("Entering method record with session: " + session + ", cashier: " + cashier + ", type: " + type + ", amount: " + amount + ", reason: " + reason + ", endorsedBy: " + endorsedBy + ", paymentMethod: " + paymentMethod + ", transferTo: " + transferTo + ", denominationDetail: " + denominationDetail);
        if (requiresEndorsement(amount) && endorsedBy == null) {
            LOGGER.warnf("Mouvement refuse : aval manager requis au-dela de %s",
                    posSettingsService.cashMovementEndorsementThreshold().toPlainString());
            LOGGER.info("Exiting method record");
            return null;
        }
        CashMovement movement = new CashMovement();
        movement.movementUid = UUID.randomUUID().toString();
        movement.terminalId = ticketNumberService.getTerminalId();
        movement.session = session;
        movement.cashier = cashier;
        movement.type = type;
        movement.amount = amount;
        movement.reason = reason;
        movement.movementDate = LocalDateTime.now();
        movement.endorsedBy = endorsedBy;
        movement.paymentMethod = paymentMethod;
        movement.transferTo = transferTo;
        movement.denominationDetail = denominationDetail;
        movement.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.MOVEMENT, movement.id);
        LOGGER.info("Exiting method record");
        return movement;
    }

    /**
     * Records a down-payment (acompte) ATTACHED to an order (EXP-85): a
     * {@code CUSTOMER_DEPOSIT} movement carrying the order reference, so a later
     * recall of the order can deduct what was already collected. Refused on a
     * non-positive amount or a blank order reference.
     *
     * @param session the open session the movement belongs to
     * @param cashier the operator recording the acompte, or null
     * @param amount the down-payment amount
     * @param orderReference the code of the order the acompte is attached to
     * @return the recorded movement, or null when the amount or the reference is invalid
     */
    @Transactional
    public CashMovement recordAcompte(CashSession session, Employee cashier,
                                      BigDecimal amount, String orderReference) {
        LOGGER.info("Entering method recordAcompte with amount: " + amount + ", orderReference: " + orderReference);
        if (amount == null || amount.signum() <= 0
                || orderReference == null || orderReference.isBlank()) {
            LOGGER.info("Exiting method recordAcompte with invalid input");
            return null;
        }
        CashMovement movement = new CashMovement();
        movement.movementUid = UUID.randomUUID().toString();
        movement.terminalId = ticketNumberService.getTerminalId();
        movement.session = session;
        movement.cashier = cashier;
        movement.type = CashMovement.MovementType.CUSTOMER_DEPOSIT;
        movement.amount = amount;
        movement.reason = "Acompte commande " + orderReference.trim();
        movement.orderReference = orderReference.trim();
        movement.movementDate = LocalDateTime.now();
        movement.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.MOVEMENT, movement.id);
        LOGGER.info("Exiting method recordAcompte");
        return movement;
    }

    /**
     * Records a cash withdrawal destined to the SAFE (EXP-87): a {@code WITHDRAWAL}
     * flagged {@code safeDeposit}, so the store node (imedge, which owns the safe
     * entity) accounts it against the safe rather than as a plain drawer
     * prélèvement. The caisse only feeds the safe; it never holds its balance.
     * Above the administered threshold a manager endorsement is required, like any
     * movement. Refused on a non-positive amount.
     *
     * @param session the open session the movement belongs to
     * @param cashier the operator recording the withdrawal, or null
     * @param amount the amount taken out toward the safe
     * @param denominationDetail the per-denomination detail, or null
     * @param endorsedBy the endorsing manager badge, or null when none was needed
     * @return the recorded movement, or null on a non-positive amount or a missing
     *         required endorsement
     */
    @Transactional
    public CashMovement recordSafeDeposit(CashSession session, Employee cashier,
                                          BigDecimal amount, String denominationDetail,
                                          String endorsedBy) {
        LOGGER.info("Entering method recordSafeDeposit with amount: " + amount);
        if (amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method recordSafeDeposit with invalid amount");
            return null;
        }
        if (requiresEndorsement(amount) && endorsedBy == null) {
            LOGGER.info("Exiting method recordSafeDeposit (endorsement required)");
            return null;
        }
        CashMovement movement = new CashMovement();
        movement.movementUid = UUID.randomUUID().toString();
        movement.terminalId = ticketNumberService.getTerminalId();
        movement.session = session;
        movement.cashier = cashier;
        movement.type = CashMovement.MovementType.WITHDRAWAL;
        movement.amount = amount;
        movement.reason = "Prélèvement coffre";
        movement.paymentMethod = CashMovement.CASH;
        movement.denominationDetail = denominationDetail;
        movement.safeDeposit = true;
        movement.endorsedBy = endorsedBy;
        movement.movementDate = LocalDateTime.now();
        movement.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.MOVEMENT, movement.id);
        LOGGER.info("Exiting method recordSafeDeposit");
        return movement;
    }
}
