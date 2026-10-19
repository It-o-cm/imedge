package com.intermarche.pos.service;

import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.session.DocumentCounter;
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
    /**
     * The administered range of customer numbers reserved to this point of sale
     * (BO-03-06-53). Nullable: a numbering service built outside CDI falls back
     * to the per-register numbering.
     */
    @jakarta.inject.Inject
    PosSettingsService posSettingsService;

    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Reserves and returns the next number of a FISCAL DOCUMENT
     * (BO-03-04-19, -21, -22, -23 and -28).
     *
     * <p>Four keys and not one: the type, this register, the point of sale and
     * the year. The counter starts again at one each January, which is what
     * {@code chaque FS /FT / RC pour chaque caisse Point De Vente doit avoir
     * son code unique par Année} asks for, and is exactly why it is not the
     * ticket counter — that one anchors the fiscal chain and is perpetual.
     *
     * <p>The PREFIX is administered on the document's gabarit; a document type
     * nobody gave one to is numbered under its own name, which keeps two
     * series apart even before a paramétreur has been there.
     *
     * <p>The shape is {@code PREFIX TERMINAL/YEAR/NNNNNNNN}: the prefix names
     * the series, the register and the year are the keys an administration
     * reads the number by, and the rank is padded so the numbers of one year
     * sort as text.
     *
     * @param type the fiscal document being issued
     * @param pdvNumber the point of sale, empty when the shop is not configured
     * @param year the year the document is issued in
     * @return the reserved number
     */
    @Transactional
    public String nextDocumentNumber(
            com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type,
            String pdvNumber, int year) {
        LOGGER.info("Entering method nextDocumentNumber with type: " + type);
        String administered =
                com.intermarche.pos.domain.setting.DocumentTemplate.prefixFor(type);
        String prefix = administered == null ? type.name() : administered;
        String shop = pdvNumber == null ? "" : pdvNumber;
        long rank = com.intermarche.pos.domain.session.DocumentSequence.next(
                type.name(), terminalId, shop, year);
        LOGGER.info("Exiting method nextDocumentNumber");
        return String.format("%s %s/%d/%08d", prefix, terminalId, year, rank);
    }

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
     * Restarts the TICKET counter of this terminal at one (BO-09-03-04).
     *
     * <p>Only the ticket number: the session number, the refund number and the
     * customer number keep counting, and the FISCAL numbering is not even on
     * this row — a fiscal sequence that counted backwards would be a fiscal
     * sequence no administration accepts.
     *
     * <p>The last number is set to zero and not to one: the next ticket is the
     * first, and every caller of {@link #nextTicketNumber} increments before
     * formatting.
     */
    @Transactional
    public void resetTicketNumbers() {
        LOGGER.info("Entering method resetTicketNumbers");
        TicketCounter counter = lockCounter(terminalId);
        counter.lastNumber = 0;
        LOGGER.info("Exiting method resetTicketNumbers");
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
        String administered = administeredCustomerNumber(counter.lastCustomerNumber);
        if (administered != null) {
            LOGGER.info("Exiting method nextCustomerNumber");
            return administered;
        }
        LOGGER.info("Exiting method nextCustomerNumber");
        return String.format("%s-CLI%06d", terminalId, counter.lastCustomerNumber);
    }

    /**
     * Reserves and returns the next ORDER CODE of this terminal, e.g.
     * {@code C04-CMD000001} (LC-06-03-01).
     *
     * <p>An order code is a RECALL KEY and not a fiscal number: it travels on
     * the deposit slip's barcode, is keyed at any register of the store and
     * names the order on the store node. The {@code -CMD} marker keeps it
     * apart from every ticket ({@code -<digits>}), parked-ticket and refund
     * number the register already knows, so a scan of the slip is recognized
     * by its shape alone.
     *
     * <p>The sequence is a {@code DocumentSequence} under the {@code ORDER}
     * key — a rank of its own, by terminal and year, without touching the
     * fiscal counters of the ticket chain.
     *
     * @return the reserved order code
     */
    @Transactional
    public String nextOrderCode() {
        LOGGER.info("Entering method nextOrderCode");
        long rank = com.intermarche.pos.domain.session.DocumentSequence.next(
                "ORDER", terminalId, "", java.time.Year.now().getValue());
        LOGGER.info("Exiting method nextOrderCode");
        return String.format("%s-CMD%06d", terminalId, rank);
    }

    /**
     * Numbers a customer created at the till inside the range the head office
     * reserved for the point of sale (BO-03-06-53).
     *
     * <p>The range is stated as {@code PREFIX:FIRST-LAST}. The number is the
     * first bound plus what this register has already created, so a customer
     * opened at a till never collides with one the commercial management
     * integrated — which is the whole reason a central range exists.
     *
     * <p>Answers NULL, and the per-register numbering takes over, when nothing
     * is administered, when the range cannot be read, and when it is EXHAUSTED:
     * a register that has run out of reserved numbers must still be able to
     * print an invoice for the customer standing in front of it, and a number
     * outside the reserved range is a referential problem the back office can
     * see, not a sale to refuse.
     *
     * @param created how many customers this register has created, 1-based
     * @return the administered number, or null to fall back
     */
    private String administeredCustomerNumber(long created) {
        String range = posSettingsService == null
                ? null : posSettingsService.customerLocalCodeRange();
        if (range == null || range.isBlank()) {
            return null;
        }
        int colon = range.indexOf(':');
        int dash = range.indexOf('-', colon + 1);
        if (colon <= 0 || dash <= colon + 1 || dash == range.length() - 1) {
            return null;
        }
        String prefix = range.substring(0, colon).trim();
        try {
            long first = Long.parseLong(range.substring(colon + 1, dash).trim());
            long last = Long.parseLong(range.substring(dash + 1).trim());
            long number = first + created - 1;
            if (prefix.isEmpty() || first > last || number > last) {
                return null;
            }
            return prefix + number;
        } catch (NumberFormatException e) {
            return null;
        }
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
