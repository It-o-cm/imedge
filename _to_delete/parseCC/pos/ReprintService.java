package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Drives the ticket-reprint screen: loads the closed-ticket history and
 * prints numbered duplicatas.
 * <p>
 * Phase 6: reprinting is blocked in training mode — a duplicata is a real
 * fiscal document and its print counter is part of the record, so a
 * training action must neither produce one nor bump one.
 */
@ApplicationScoped
public class ReprintService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReprintService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    @Inject
    PosState state;

    @Inject
    TicketPrinterService ticketPrinterService;

    /** Asks the store node for a ticket this register does not hold (LC-08-05-05). */
    @Inject
    StoreTicketClient storeTicketClient;

    /** The shared ticket search engine (EXP-47), common to return and duplicata. */
    @Inject
    com.intermarche.pos.ui.ticket.TicketSearchService ticketSearchService;

    /** The manager-endorsement flow, for the reassignment gesture (LC-03-06-01). */
    @Inject
    com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    /** The reassignment itself — the chained gesture of LC-03-06-01 to -06. */
    @Inject
    TicketReassignmentService reassignmentService;

    /**
     * Loads the closed tickets, most recent first, into the reprint state.
     */
    public void loadHistory() {
        LOGGER.info("Entering method loadHistory");
        state.reprint.setTickets(
                Ticket.find("status = ?1 ORDER BY creationDate DESC", Ticket.TicketStatus.CLOSED).list()
        );
        state.touch();
        LOGGER.info("Exiting method loadHistory");
    }

    /**
     * Enters the REASSIGNMENT browsing mode (LC-03-06-02): the list and every
     * further search keep only the closed tickets no client is attached to,
     * because only those are offered to the gesture.
     */
    public void startReassignMode() {
        LOGGER.info("Entering method startReassignMode");
        state.reprint.reassignMode = true;
        state.reprint.reassignMaskOpen = false;
        state.reprint.reassignCard = "";
        state.reprint.reassignError = "";
        state.reprint.setTickets(Ticket.find(
                "status = ?1 and fidelityCard is null ORDER BY creationDate DESC",
                Ticket.TicketStatus.CLOSED).list());
        state.touch();
        LOGGER.info("Exiting method startReassignMode");
    }

    /**
     * Leaves the REASSIGNMENT browsing mode and restores the ordinary
     * duplicata history (LC-03-06-02).
     */
    public void cancelReassignMode() {
        LOGGER.info("Entering method cancelReassignMode");
        state.reprint.reassignMode = false;
        state.reprint.reassignMaskOpen = false;
        state.reprint.reassignCard = "";
        state.reprint.reassignError = "";
        loadHistory();
        LOGGER.info("Exiting method cancelReassignMode");
    }

    /**
     * Opens the card mask of the reassignment on the viewed ticket
     * (LC-03-06-03): the card number is typed — the manual entry is the
     * fallback the whole fidelity screen already models — and the previous
     * attempt's outcome is cleared.
     *
     * @param ticketId the database id of the ticket to reassign
     */
    public void beginReassign(Long ticketId) {
        LOGGER.info("Entering method beginReassign with ticketId: " + ticketId);
        state.reprint.reassignMaskOpen = true;
        state.reprint.reassignCard = "";
        state.reprint.reassignError = "";
        state.touch();
        LOGGER.info("Exiting method beginReassign");
    }

    /**
     * Leaves the card mask without reassigning anything (LC-03-06-03).
     */
    public void cancelReassignMask() {
        LOGGER.info("Entering method cancelReassignMask");
        state.reprint.reassignMaskOpen = false;
        state.reprint.reassignCard = "";
        state.reprint.reassignError = "";
        state.touch();
        LOGGER.info("Exiting method cancelReassignMask");
    }

    /**
     * Performs the reassignment of the viewed ticket to the typed card
     * (LC-03-06-01 to -06), through the administered endorsement: a store
     * that demands a supervisor's aval for the gesture has the form parked —
     * ticket and card — until a manager's credential answers, and a store
     * that does not runs the chained gesture at once.
     *
     * @param ticketId the database id of the closed ticket
     * @param card the fidelity card number, as typed in the mask
     */
    public void applyReassign(Long ticketId, String card) {
        LOGGER.info("Entering method applyReassign with ticketId: " + ticketId + ", card: " + card);
        state.reprint.reassignCard = card == null ? "" : card;
        if (endorsementService.gestureAuthorized(state, "ticket-reassign")) {
            reassignmentService.perform(ticketId, card);
            LOGGER.info("Exiting method applyReassign");
            return;
        }
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("ticketId", ticketId == null ? "" : String.valueOf(ticketId));
        form.put("card", card == null ? "" : card);
        endorsementService.requestAuthorization(state, "TICKET_REASSIGN", form,
                "/reprint/view/" + (ticketId == null ? "" : ticketId));
        LOGGER.info("Exiting method applyReassign");
    }

    /**
     * Searches the closed tickets for the duplicata by number, date or amount
     * (EXP-47) — the SAME engine the return search uses. A blank pattern falls
     * back to the full history.
     *
     * @param pattern the value keyed on the search field
     */
    public void search(String pattern) {
        LOGGER.info("Entering method search with pattern: " + pattern);
        if (pattern == null || pattern.isBlank()) {
            if (state.reprint.reassignMode) {
                startReassignMode();
                LOGGER.info("Exiting method search");
                return;
            }
            loadHistory();
            LOGGER.info("Exiting method search");
            return;
        }
        if (state.reprint.reassignMode) {
            // LC-03-06-02: the reassignment pool keeps only the tickets no
            // client is attached to, whatever criterion searched them.
            state.reprint.setTickets(
                    ticketSearchService.searchByPattern(pattern, true));
            state.touch();
            LOGGER.info("Exiting method search");
            return;
        }
        state.reprint.setTickets(ticketSearchService.searchByPattern(pattern));
        state.touch();
        LOGGER.info("Exiting method search");
    }

    /**
     * Prints a numbered duplicata of a closed ticket; refused in training
     * mode (real documents are untouchable there).
     *
     * @param ticketId the database id of the ticket to reprint
     */
    public void print(Long ticketId) {
        LOGGER.info("Entering method print with ticketId: " + ticketId);
        if (state.trainingMode) {
            state.ticket.setError(i18n.get("op.reimpressionIndisponibleFormation"));
            state.touch();
            LOGGER.info("Exiting method print");
            return;
        }
        if (ticketId != null) {
            ticketPrinterService.printTicket(ticketId);
        }
        LOGGER.info("Exiting method print");
    }

    /**
     * Opens the bon-pour-échange preparation on the viewed ticket (LC-08-05-10).
     */
    public void startExchange() {
        LOGGER.info("Entering method startExchange");
        state.reprint.clearExchange();
        state.reprint.exchangeMode = true;
        state.touch();
        LOGGER.info("Exiting method startExchange");
    }

    /**
     * Leaves the bon-pour-échange preparation without printing anything.
     */
    public void cancelExchange() {
        LOGGER.info("Entering method cancelExchange");
        state.reprint.clearExchange();
        state.touch();
        LOGGER.info("Exiting method cancelExchange");
    }

    /**
     * Names a line for the bon pour échange, or unnames it (LC-08-05-12).
     *
     * @param lineId the database id of the line touched
     */
    public void toggleExchangeLine(Long lineId) {
        LOGGER.info("Entering method toggleExchangeLine with lineId: " + lineId);
        state.reprint.toggleExchangeLine(lineId);
        state.touch();
        LOGGER.info("Exiting method toggleExchangeLine");
    }

    /**
     * Prints the bon pour échange of a ticket (LC-08-05-13) and leaves the
     * preparation.
     *
     * <p>Refused in training for the same reason a duplicata is: the bon carries a
     * real ticket's references, and a number that leaves the register must name a sale
     * that happened. It bumps no print counter — it states no amount, so it is not a
     * copy of the ticket.
     *
     * @param ticketId the database id of the original ticket
     */
    public void printExchange(Long ticketId) {
        LOGGER.info("Entering method printExchange with ticketId: " + ticketId);
        if (state.trainingMode) {
            state.ticket.setError(i18n.get("op.impressionIndisponibleFormation"));
            state.touch();
            LOGGER.info("Exiting method printExchange");
            return;
        }
        if (ticketId != null) {
            ticketPrinterService.printExchangeVoucher(ticketId,
                    new java.util.LinkedHashSet<>(state.reprint.exchangeSelection));
        }
        state.reprint.clearExchange();
        state.touch();
        LOGGER.info("Exiting method printExchange");
    }

    /**
     * Opens the mask naming a ticket held by another register (LC-08-05-05).
     */
    public void startForeign() {
        LOGGER.info("Entering method startForeign");
        state.reprint.foreignTicketNumber = "";
        state.reprint.foreignError = "";
        state.touch();
        LOGGER.info("Exiting method startForeign");
    }

    /**
     * Prints the duplicata of a ticket made on ANOTHER register (LC-08-05-05).
     *
     * <p>The shop's sales all reach the store node, so that is where such a ticket is
     * asked for — already rendered, so the paper reads exactly like the one the
     * customer got at the other till. Nothing of it is stored here.
     *
     * @param ticketNumber the number of the ticket, as printed on the customer's paper
     */
    public void printForeign(String ticketNumber) {
        LOGGER.info("Entering method printForeign with ticketNumber: " + ticketNumber);
        state.reprint.foreignTicketNumber = ticketNumber == null ? "" : ticketNumber.trim();
        state.reprint.foreignError = "";
        if (state.trainingMode) {
            state.reprint.foreignError = i18n.get("op.reimpressionIndisponibleFormation");
            state.touch();
            LOGGER.info("Exiting method printForeign");
            return;
        }
        if (state.reprint.foreignTicketNumber.isEmpty()) {
            state.reprint.foreignError = i18n.get("op.numeroTicketVide");
            state.touch();
            LOGGER.info("Exiting method printForeign");
            return;
        }
        if (!storeTicketClient.isAvailable()) {
            state.reprint.foreignError = i18n.get("op.aucunNUdMagasin");
            state.touch();
            LOGGER.info("Exiting method printForeign");
            return;
        }
        java.util.Optional<String> rendered =
                storeTicketClient.fetchDuplicate(state.reprint.foreignTicketNumber);
        if (rendered.isEmpty()) {
            state.reprint.foreignError = i18n.format("op.ticketIntrouvableMagasin",
                    state.reprint.foreignTicketNumber);
            state.touch();
            LOGGER.info("Exiting method printForeign");
            return;
        }
        ticketPrinterService.printRenderedTicket(rendered.get());
        state.touch();
        LOGGER.info("Exiting method printForeign");
    }
}
