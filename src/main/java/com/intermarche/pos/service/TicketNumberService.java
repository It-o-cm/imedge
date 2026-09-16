package com.intermarche.pos.service;

import com.intermarche.pos.domain.session.DocumentCounter;
import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.session.TicketCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Issues strictly increasing ticket numbers per register.
 * <p>
 * The number format is {@code <terminalId>-<8-digit sequence>}, e.g.
 * {@code C04-00000123} (fits the 30-char ticket_number column). The sequence
 * is backed by a {@link TicketCounter} row in the register's database,
 * locked pessimistically for the duration of the enclosing transaction —
 * ticket creation and number reservation therefore commit or roll back
 * together, which the phase 1 fiscal chaining will rely on.
 * <p>
 * The terminal identifier comes from the {@code pos.terminal.id} configuration
 * property; each register executable must define its own value in
 * application.properties.
 * <p>
 * OPERATIONAL TRAP: the default value "POS01" exists so a lone dev register
 * boots unconfigured, but two registers left on the default would push
 * documents under the SAME terminal identity to the store node (colliding
 * numbers, broken per-register chains). Setting a unique
 * {@code pos.terminal.id} is part of commissioning a register, alongside
 * the store URL and token. {@code lockCounter} is THE serialization point
 * of the register: the three sequences and the chaining anchors live on one
 * row, so whoever holds the lock owns numbering AND chaining until commit.
 */
@ApplicationScoped
public class TicketNumberService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketNumberService.class);

    /** The identifier of this register, unique within the store. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Reserves and returns the next ticket number for this terminal.
     * <p>
     * Joins the caller's transaction when one is active (typical case: called
     * from the draft-ticket creation), so the counter increment is atomic with
     * the ticket insert.
     *
     * @return the next ticket number, e.g. "C04-00000123"
     */
    @Transactional
    public String nextTicketNumber() {
        LOGGER.info("Entering method nextTicketNumber");
        TicketCounter counter = lockCounter(terminalId);
        counter.lastNumber++;
        LOGGER.info("Exiting method nextTicketNumber");
        return String.format("%s-%08d", terminalId, counter.lastNumber);
    }

    /**
     * Reserves and returns the next cash-session number for this terminal,
     * under the same counter row lock as the ticket sequence.
     *
     * @return the next session number, e.g. "C04-S00012"
     */
    @Transactional
    public String nextSessionNumber() {
        LOGGER.info("Entering method nextSessionNumber");
        TicketCounter counter = lockCounter(terminalId);
        counter.lastSessionNumber++;
        LOGGER.info("Exiting method nextSessionNumber");
        return String.format("%s-S%05d", terminalId, counter.lastSessionNumber);
    }

    /**
     * Reserves and returns the next refund number for this terminal, under
     * the same counter row lock as the other sequences.
     *
     * @return the next refund number, e.g. "C04-R000012"
     */
    @Transactional
    public String nextRefundNumber() {
        LOGGER.info("Entering method nextRefundNumber");
        TicketCounter counter = lockCounter(terminalId);
        counter.lastRefundNumber++;
        LOGGER.info("Exiting method nextRefundNumber");
        return String.format("%s-R%06d", terminalId, counter.lastRefundNumber);
    }

    /**
     * Reserves and returns the next account-customer number for this terminal, e.g.
     * {@code C04-CLI000042}, under the same counter row lock as the sale sequences.
     *
     * @return the next customer number
     */
    @Transactional
    public String nextCustomerNumber() {
        LOGGER.info("Entering method nextCustomerNumber");
        TicketCounter counter = lockCounter(terminalId);
        counter.lastCustomerNumber++;
        LOGGER.info("Exiting method nextCustomerNumber");
        return String.format("%s-CLI%06d", terminalId, counter.lastCustomerNumber);
    }

    /**
     * Reserves and returns the next number of a commercial document for this
     * terminal, e.g. {@code C04-F000123} for an invoice.
     * <p>
     * On its OWN counter row, one per document type, and deliberately not on the
     * ticket counter. That row carries the three sale sequences and the fiscal
     * chaining anchors together so that a single lock serialises them; a document
     * is issued on a ticket that is already closed and chained, and has no business
     * holding that lock. Two document types are therefore numbered independently,
     * and issuing an invoice never blocks a sale.
     *
     * @param type the kind of document to number
     * @return the next document number for that type
     */
    @Transactional
    public String nextDocumentNumber(DocumentType type) {
        LOGGER.info("Entering method nextDocumentNumber with type: " + type);
        DocumentCounter counter = lockDocumentCounter(terminalId, type);
        counter.lastNumber++;
        LOGGER.info("Exiting method nextDocumentNumber");
        return String.format("%s-%s%06d", terminalId, type.getPrefix(), counter.lastNumber);
    }

    /**
     * Loads the document counter of the given terminal and type with a pessimistic
     * write lock, creating it lazily on first use (the unique constraint on the pair
     * protects against a concurrent first creation).
     *
     * @param terminal the terminal identifier whose counter is needed
     * @param type     the document type whose sequence is needed
     * @return the locked counter row
     */
    @Transactional
    public DocumentCounter lockDocumentCounter(String terminal, DocumentType type) {
        LOGGER.info("Entering method lockDocumentCounter with terminal: " + terminal + ", type: " + type);
        DocumentCounter counter = DocumentCounter
                .<DocumentCounter>find("terminalId = ?1 and documentType = ?2", terminal, type)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();

        if (counter == null) {
            counter = new DocumentCounter();
            counter.terminalId = terminal;
            counter.documentType = type;
            counter.lastNumber = 0;
            counter.persist();
        }
        LOGGER.info("Exiting method lockDocumentCounter");
        return counter;
    }

    /**
     * Loads the counter row of the given terminal with a pessimistic write
     * lock, creating it lazily on first use (the unique constraint on
     * terminal_id protects against a concurrent first creation). The lock is
     * held until the end of the enclosing transaction, making every operation
     * derived from the counter (number sequence, fiscal chain anchor,
     * perpetual grand total) atomic per register.
     *
     * @param terminal the terminal identifier whose counter is needed
     * @return the locked counter row
     */
    @Transactional
    public TicketCounter lockCounter(String terminal) {
        LOGGER.info("Entering method lockCounter with terminal: " + terminal);
        TicketCounter counter = TicketCounter
                .<TicketCounter>find("terminalId", terminal)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();

        if (counter == null) {
            counter = new TicketCounter();
            counter.terminalId = terminal;
            counter.lastNumber = 0;
            counter.persist();
        }
        LOGGER.info("Exiting method lockCounter");
        return counter;
    }

    /**
     * Returns the identifier of this register.
     *
     * @return the configured terminal id
     */
    public String getTerminalId() {
        LOGGER.info("Entering method getTerminalId");
        LOGGER.info("Exiting method getTerminalId");
        return terminalId;
    }
}
