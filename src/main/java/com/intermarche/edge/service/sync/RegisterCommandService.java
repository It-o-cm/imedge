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
        RegisterCommand command = new RegisterCommand();
        command.commandUid = UUID.randomUUID().toString();
        command.terminalId = terminalId;
        command.commandType = type;
        command.issuedBy = issuedBy;
        command.reason = reason;
        command.issuedAt = LocalDateTime.now();
        command.persist();
        LOGGER.info("Exiting method issue");
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
