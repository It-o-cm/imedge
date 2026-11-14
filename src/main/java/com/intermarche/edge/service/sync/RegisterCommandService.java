package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sync.RegisterCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The store node's ORDER BOOK for its registers: what has been asked of each
 * one, and what it has acknowledged.
 *
 * <p>Three gestures and no more — leave an order, hand the pending ones to the
 * register that asks, stamp the one it acknowledges. Everything else about the
 * mechanism lives in the two ends: the back office decides to issue, the
 * register decides how to obey.
 *
 * <p>Issuing is IDEMPOTENT per register and per kind: asking twice for the same
 * register to close leaves one order, not two. A back office button pressed
 * twice by an impatient hand must not make a register close, reopen and close
 * again.
 */
@ApplicationScoped
public class RegisterCommandService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RegisterCommandService.class);

    /**
     * Leaves an order for a register, unless the same one is already waiting.
     *
     * @param terminalId the register the order is addressed to
     * @param type what the register is asked to do
     * @param issuedBy who issued it
     * @param reason why, free text shown to the operator
     * @return the order waiting for that register, new or already there
     */
    @Transactional
    public RegisterCommand issue(String terminalId, RegisterCommand.CommandType type,
                                 String issuedBy, String reason) {
        LOGGER.info("Entering method issue with terminalId: " + terminalId + ", type: " + type);
        RegisterCommand existing = RegisterCommand
                .<RegisterCommand>find("terminalId = ?1 and commandType = ?2 and consumedAt is null",
                        terminalId, type)
                .firstResult();
        if (existing != null) {
            LOGGER.info("Exiting method issue with the order already pending");
            return existing;
        }
        RegisterCommand command = create(terminalId, type, issuedBy, reason, null);
        LOGGER.info("Exiting method issue");
        return command;
    }

    /**
     * Leaves an order ANSWERING one request of a register ({@code LS-04-03-13}):
     * an authorisation or a refusal, valid for that request only. Idempotent per
     * register, kind and request, so two requests never share one answer.
     *
     * @param terminalId the register the order is addressed to
     * @param type what the register is asked to do
     * @param issuedBy who issued it
     * @param reason why, free text shown to the operator
     * @param requestRef the request answered, or null when the register named none
     * @return the order waiting for that register, new or already there
     */
    @Transactional
    public RegisterCommand answer(String terminalId, RegisterCommand.CommandType type,
                                  String issuedBy, String reason, String requestRef) {
        LOGGER.info("Entering method answer with terminalId: " + terminalId + ", type: " + type);
        if (requestRef == null) {
            LOGGER.info("Exiting method answer for a request without reference");
            return issue(terminalId, type, issuedBy, reason);
        }
        RegisterCommand existing = RegisterCommand
                .<RegisterCommand>find("terminalId = ?1 and commandType = ?2 and requestRef = ?3 and consumedAt is null",
                        terminalId, type, requestRef)
                .firstResult();
        if (existing != null) {
            LOGGER.info("Exiting method answer with the order already pending");
            return existing;
        }
        RegisterCommand command = create(terminalId, type, issuedBy, reason, requestRef);
        LOGGER.info("Exiting method answer");
        return command;
    }

    /**
     * Drops the answers still waiting for a register when it withdraws the
     * request they answer ({@code LS-04-03-13}): an authorisation the register
     * has not collected yet must not let a later gesture through. A register
     * that names no request withdraws every pending answer it was left.
     *
     * @param terminalId the register withdrawing its request
     * @param requestRef the request withdrawn, or null
     * @return how many pending answers were dropped
     */
    @Transactional
    public long withdrawAnswers(String terminalId, String requestRef) {
        LOGGER.info("Entering method withdrawAnswers with terminalId: " + terminalId);
        String answers = "terminalId = ?1 and consumedAt is null and commandType in ?2";
        List<RegisterCommand.CommandType> kinds = List.of(RegisterCommand.CommandType.SUPERVISOR_GRANTED,
                RegisterCommand.CommandType.SUPERVISOR_REFUSED);
        long dropped = requestRef == null
                ? RegisterCommand.delete(answers, terminalId, kinds)
                : RegisterCommand.delete(answers + " and requestRef = ?3", terminalId, kinds, requestRef);
        LOGGER.info("Exiting method withdrawAnswers with " + dropped);
        return dropped;
    }

    /**
     * Creates and persists one order, every mandatory column set first.
     *
     * @param terminalId the register the order is addressed to
     * @param type what the register is asked to do
     * @param issuedBy who issued it
     * @param reason why
     * @param requestRef the request answered, or null
     * @return the persisted order
     */
    private RegisterCommand create(String terminalId, RegisterCommand.CommandType type,
                                   String issuedBy, String reason, String requestRef) {
        RegisterCommand command = new RegisterCommand();
        command.commandUid = UUID.randomUUID().toString();
        command.terminalId = terminalId;
        command.commandType = type;
        command.issuedBy = issuedBy;
        command.reason = reason;
        command.requestRef = requestRef;
        command.issuedAt = LocalDateTime.now();
        command.persist();
        return command;
    }

    /**
     * Returns the orders a register has not acknowledged yet.
     *
     * @param terminalId the register asking
     * @return its pending orders, oldest first
     */
    @Transactional
    public List<RegisterCommand> pending(String terminalId) {
        LOGGER.info("Entering method pending with terminalId: " + terminalId);
        List<RegisterCommand> commands = RegisterCommand.pendingFor(terminalId);
        LOGGER.info("Exiting method pending with " + commands.size() + " order(s)");
        return commands;
    }

    /**
     * Stamps an order as carried out.
     *
     * <p>An unknown token is answered false rather than with an exception: a
     * register acknowledging an order the node has forgotten is a stale
     * message, not an incident. An order acknowledged TWICE keeps its first
     * stamp — the moment it was carried out, not the moment it was mentioned
     * again.
     *
     * @param commandUid the token the register presented
     * @return true when an order was stamped by this call
     */
    @Transactional
    public boolean acknowledge(String commandUid) {
        LOGGER.info("Entering method acknowledge with commandUid: " + commandUid);
        RegisterCommand command = RegisterCommand.findByUid(commandUid);
        if (command == null || command.consumedAt != null) {
            LOGGER.info("Exiting method acknowledge with false");
            return false;
        }
        command.consumedAt = LocalDateTime.now();
        command.persist();
        LOGGER.info("Exiting method acknowledge with true");
        return true;
    }

    /**
     * Whether an order of a kind is still waiting for a register — what the
     * end of period asks before leaving its close order, so the other orders
     * waiting for the register never hold the close back.
     *
     * @param terminalId the register
     * @param type the kind of order
     * @return true when one is pending
     */
    public boolean isPending(String terminalId, RegisterCommand.CommandType type) {
        LOGGER.info("Entering method isPending with terminalId: " + terminalId + ", type: " + type);
        boolean pending = RegisterCommand.count("terminalId = ?1 and commandType = ?2 and consumedAt is null",
                terminalId, type) > 0;
        LOGGER.info("Exiting method isPending with " + pending);
        return pending;
    }

    /**
     * Counts the orders still waiting for a register, for the screen that
     * shows whether a forced close has been picked up.
     *
     * @param terminalId the register
     * @return the number of pending orders
     */
    public long pendingCount(String terminalId) {
        LOGGER.info("Entering method pendingCount with terminalId: " + terminalId);
        long count = RegisterCommand.countPending(terminalId);
        LOGGER.info("Exiting method pendingCount with " + count);
        return count;
    }
}
