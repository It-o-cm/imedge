package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.*;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Parks and resumes carts on this register (phase 3, single-register scope).
 * <p>
 * Parking relies on the continuously synchronized draft: the current draft is
 * flipped to {@link Ticket.TicketStatus#PARKED} and the in-memory state is
 * cleared; resuming flips it back to OPEN and restores it through the shared
 * restore machinery of {@link TicketRecoveryService}. Parked tickets that are
 * never resumed are cancelled by the Z closing of the session.
 * <p>
 * Parking is refused once a payment exists (in progress or already
 * registered): registered payments are persisted on the draft, and a parked
 * ticket holding money would be a liability in limbo — the cashier settles
 * or clears the payments first. In training mode the draft sync returns
 * null, so parking answers "SYNCHRONISATION IMPOSSIBLE": consistent with
 * training persisting nothing (a training cart cannot outlive its session).
 * <p>
 * Placement: ui.ticket — consumed only by ParkedTicketResource and the parked-ticket scan handler (consumer-exclusivity rule).
 */
@ApplicationScoped
public class TicketParkingService {

    private static final Logger LOGGER = Logger.getLogger(TicketParkingService.class);

    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;


    @Inject
    PosState state;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    @Inject
    TicketRecoveryService ticketRecoveryService;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    TechnicalEventService technicalEventService;

    /** Printer — the parked receipt carries the resume number (LC-04-01-02). */
    @Inject
    TicketPrinterService ticketPrinterService;

    /** The back-office parameters (parked-receipt printing policy). */
    @Inject
    PosSettingsService posSettingsService;

    /** The store-node seam for inter-till resume (EXP-46); offline = no node. */
    @Inject
    InterTillResumeClient interTillResumeClient;

    /**
     * Parks the current cart: synchronizes the draft one last time, flips it
     * to PARKED and clears the in-memory state.
     *
     * @return null on success, or an error message shown to the cashier
     */
    @Transactional
    public String parkCurrent() {
        LOGGER.info("Entering method parkCurrent");
        if (state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.aucunTicketMettreAttente");
        }
        if (state.payment.paymentInProgress || state.payment.paidAmount.signum() > 0) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.paiementCoursMiseAttente");
        }
        // EXP-45: a single ticket may wait at a time. Several used to be allowed;
        // the register now refuses a second park until the first is resumed.
        if (!listParked().isEmpty()) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.ticketDejaAttenteReprenez");
        }
        Long ticketId = ticketPersistenceService.syncDraft(state);
        if (ticketId == null) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.synchronisationImpossible");
        }
        Ticket draft = Ticket.findById(ticketId);
        if (draft == null || draft.status != Ticket.TicketStatus.OPEN) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.ticketIntrouvable2");
        }
        draft.status = Ticket.TicketStatus.PARKED;
        // BO-06-06-01: when it was parked, kept whatever happens next.
        draft.parkedAt = java.time.LocalDateTime.now();
        draft.persist();
        technicalEventService.log(TechnicalEvent.EventType.TICKET_PARKED, draft.ticketNumber);
        // LC-04-01-02: the parked receipt carries the ticket number — the
        // code the resume scan (or a manual entry) recognizes. Printing is
        // administered on the back office.
        if (posSettingsService.parkingPrintReceipt()) {
            ticketPrinterService.printParkedTicket(draft);
        }
        LOGGER.infof("Ticket mis en attente ID: %d (%s)", draft.id, draft.ticketNumber);

        state.clearTicket();
        state.touch();
        LOGGER.info("Exiting method parkCurrent");
        return null;
    }

    /**
     * Lists the parked tickets of this register, oldest first.
     *
     * @return the parked tickets
     */
    public List<Ticket> listParked() {
        LOGGER.info("Entering method listParked");
        LOGGER.info("Exiting method listParked");
        return Ticket.list("terminalId = ?1 and status = ?2 order by id",
                ticketNumberService.getTerminalId(), Ticket.TicketStatus.PARKED);
    }

    /**
     * Resumes a parked ticket of this register: flips it back to OPEN and
     * restores it into the in-memory state. Refused while a cart is already
     * in progress.
     *
     * @param ticketId the database id of the parked ticket
     * @return null on success, or an error message shown to the cashier
     */
    @Transactional
    public String resume(Long ticketId) {
        LOGGER.info("Entering method resume with ticketId: " + ticketId);
        if (!state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method resume");
            return i18n.get("op.ticketCoursMettezAttente");
        }
        Ticket draft = Ticket.findById(ticketId);
        if (draft == null
                || draft.status != Ticket.TicketStatus.PARKED
                || !ticketNumberService.getTerminalId().equals(draft.terminalId)) {
            LOGGER.info("Exiting method resume");
            return i18n.get("op.ticketAttenteIntrouvable");
        }
        draft.status = Ticket.TicketStatus.OPEN;
        // BO-06-06-01: the recall is dated and named, so the state of pending
        // tickets can tell a cart that came back from one still waiting.
        draft.resumedAt = java.time.LocalDateTime.now();
        draft.resumedTerminalId = ticketNumberService.getTerminalId();
        draft.persist();
        ticketRecoveryService.restoreDraft(draft);
        technicalEventService.log(TechnicalEvent.EventType.TICKET_RESUMED, draft.ticketNumber);
        // BO-03-03-07: the resume ticket, where the store administers it. A
        // local resume keeps its number, so the document's two numbers are the
        // same one — it states both anyway, because it is the SAME document
        // that serves the inter-till resume.
        ticketPrinterService.printResumeTicket(draft, draft.ticketNumber);
        LOGGER.info("Exiting method resume");
        return null;
    }
    /**
     * Resumes a parked ticket from its SCANNED (or typed) ticket number
     * (LC-04-02-01) — the number printed on the parked receipt. Resolution
     * is register-local: the number must belong to this terminal and still
     * be PARKED; everything else answers the same message as an unknown id,
     * and the resume itself reuses the guarded {@link #resume(Long)}.
     *
     * @param ticketNumber the scanned or typed ticket number
     * @return null on success, or an error message shown to the cashier
     */
    public String resumeByNumber(String ticketNumber) {
        LOGGER.info("Entering method resumeByNumber with ticketNumber: " + ticketNumber);
        Ticket draft = Ticket.<Ticket>find(
                "ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                ticketNumber, ticketNumberService.getTerminalId(),
                Ticket.TicketStatus.PARKED).firstResult();
        if (draft != null) {
            LOGGER.info("Exiting method resumeByNumber (local)");
            return resume(draft.id);
        }
        // EXP-46: not parked on THIS till. Offline, that is the end — the
        // degraded rule resumes only its own tickets. Connected, the store node
        // may hand over a ticket parked on ANOTHER till; the client reaches no
        // node offline (returns null) so the standalone lane is unchanged.
        Ticket foreign = interTillResumeClient.fetchParkedTicket(ticketNumber);
        if (foreign == null || foreign.status != Ticket.TicketStatus.PARKED) {
            LOGGER.info("Exiting method resumeByNumber");
            return i18n.get("op.ticketAttenteIntrouvable");
        }
        LOGGER.info("Exiting method resumeByNumber (inter-caisses)");
        return resumeForeign(foreign);
    }

    /**
     * Resumes a ticket the store node handed over from another till (EXP-46,
     * connected mode): unlike {@link #resume(Long)} it does not check the
     * originating terminal — the node authorized the hand-over — and it re-homes
     * the ticket to this register. Refused while a cart is already in progress,
     * like every resume.
     *
     * @param foreign the parked ticket the node returned
     * @return null on success, or an error message shown to the cashier
     */
    @Transactional
    String resumeForeign(Ticket foreign) {
        LOGGER.info("Entering method resumeForeign");
        if (!state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method resumeForeign");
            return i18n.get("op.ticketCoursMettezAttente");
        }
        // BO-03-03-07: the number the parked paper carried, read BEFORE the
        // resume re-homes the ticket onto this register.
        String parkedNumber = foreign.ticketNumber;
        foreign.status = Ticket.TicketStatus.OPEN;
        // BO-06-06-01: the recalling lane is written BEFORE the re-homing
        // overwrites the parking one — after it, the two are the same.
        foreign.resumedAt = java.time.LocalDateTime.now();
        foreign.resumedTerminalId = ticketNumberService.getTerminalId();
        foreign.terminalId = ticketNumberService.getTerminalId();
        foreign.persist();
        ticketRecoveryService.restoreDraft(foreign);
        technicalEventService.log(TechnicalEvent.EventType.TICKET_RESUMED, foreign.ticketNumber);
        ticketPrinterService.printResumeTicket(foreign, parkedNumber);
        LOGGER.infof("Ticket repris inter-caisses ID: %d (%s)", foreign.id, foreign.ticketNumber);
        LOGGER.info("Exiting method resumeForeign");
        return null;
    }
}