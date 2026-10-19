package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Comparator;

/**
 * Reassigns a CLOSED ticket to a fidelity customer (LC-03-06-01 to -06): the
 * gesture that chains what the register already had — the ticket search, the
 * card entry, the engine revaluation and the printer — into ONE operation.
 *
 * <p>THE SEQUENCE THE QUESTIONNAIRE NAMES, in the order it names it: a card is
 * attached (the customer presented it too late, the sale is already closed),
 * the advantages are RECALCULATED against that card — the reconciler's line
 * trace replaces the one the cardless sale carried — the operation is
 * recorded for reporting (journal event travelling to the store node), and
 * the new ticket is printed for the customer, the shop's duplicata printed
 * when it is administered.
 *
 * <p>THE AMOUNT GAP IS ANNOUNCED, NOT SETTLED (LC-03-06-04): a closed ticket
 * is paid money, and refunding or completing it is the business of the
 * return's refund and the payment Epic — a reassignment that silently moved
 * settled money would be worse than none. The recalculated gap is therefore
 * named to the operator and journaled with the event, and nothing is
 * collected back nor demanded.
 *
 * <p>Placement: the gesture belongs to the reprint screens — it is served
 * from the closed-ticket detail and parks its form through the endorsement
 * flow, both of which live here.
 */
@ApplicationScoped
public class TicketReassignmentService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketReassignmentService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The shared POS state, whose reprint sub-state carries the outcome. */
    @Inject
    PosState state;

    /** The back-office parameters (PDV duplicata, LC-03-06-05). */
    @Inject
    PosSettingsService posSettingsService;

    /** The register's identity — the journal's till column. */
    @Inject
    TicketNumberService ticketNumberService;

    /** The journal the reporting movement is recorded in (LC-03-06-06). */
    @Inject
    TechnicalEventService technicalEventService;

    /** The store push, so the reassigned ticket reaches the node. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** The valuation engine call (LC-03-06-04). */
    @Inject
    ValuationService valuationService;

    /** The reconciler that turns the engine offer into line-borne money. */
    @Inject
    ValuationReconciler valuationReconciler;

    /** The printer of the customer's new ticket (LC-03-06-05). */
    @Inject
    TicketPrinterService ticketPrinterService;

    /**
     * Performs the reassignment end to end: guards, recalculation,
     * attachment, journal and print. Every refusal is NAMED on the reprint
     * state rather than thrown — the operator stays on the screen they acted
     * from, exactly as the duplicata and bon-pour-échange gestures do.
     *
     * @param ticketId the database id of the closed ticket
     * @param card the fidelity card number, as typed in the mask
     */
    @Transactional
    public void perform(Long ticketId, String card) {
        LOGGER.info("Entering method perform with ticketId: " + ticketId + ", card: " + card);
        String typed = card == null ? "" : card.trim();
        if (state.trainingMode) {
            // A training session must never touch a real closed ticket: the
            // reassignment rewrites its advantages trace and bumps its print
            // counter, both of which are fiscal records.
            state.reprint.reassignError = i18n.get("op.reaffectationIndisponibleFormation");
            state.touch();
            LOGGER.info("Exiting method perform");
            return;
        }
        if (typed.isBlank()) {
            state.reprint.reassignError = i18n.get("op.numeroCarteFideliteVide");
            state.touch();
            LOGGER.info("Exiting method perform");
            return;
        }
        Ticket ticket = ticketId == null ? null : Ticket.findById(ticketId);
        if (ticket == null) {
            state.reprint.reassignError = i18n.get("op.reaffectationTicketInconnu");
            state.touch();
            LOGGER.info("Exiting method perform");
            return;
        }
        if (ticket.status != Ticket.TicketStatus.CLOSED) {
            state.reprint.reassignError = i18n.get("op.reaffectationTicketNonClos");
            state.touch();
            LOGGER.info("Exiting method perform");
            return;
        }
        if (ticket.fidelityCard != null && !ticket.fidelityCard.isBlank()) {
            // LC-03-06-02: only a ticket NO client is attached to is offered
            // — a card already present has had its advantages.
            state.reprint.reassignError = i18n.get("op.reaffectationTicketDejaAffecte");
            state.touch();
            LOGGER.info("Exiting method perform");
            return;
        }
        BigDecimal adjustment = recalculateAdvantages(ticket, typed);
        // LC-03-06-03: the attachment — the card is the one the closed ticket
        // now answers to, and the push carries it to the node like every
        // mutation of a stored ticket.
        ticket.fidelityCard = typed;
        ticket.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
        journal(ticket, typed, adjustment);
        print(ticket);
        state.reprint.reassignMaskOpen = false;
        state.reprint.reassignCard = "";
        state.reprint.reassignError = outcomeMessage(adjustment);
        state.reprint.setViewedTicket(ticket);
        state.touch();
        LOGGER.info("Exiting method perform");
    }

    /**
     * Recalculates the advantages the card entitles the customer to
     * (LC-03-06-04): the closed ticket's lines are rebuilt into a DETACHED
     * cart — the live sale on the register's state is never touched — priced
     * by the engine with the card as its customerCode, and the reconciler
     * writes the new line-borne valuation onto the stored ticket, replacing
     * the trace the cardless sale carried.
     *
     * @param ticket the closed ticket to revalue
     * @param card the fidelity card the advantages are recalculated for
     * @return the total adjustment the recalculation made, zero when the engine
     *         was not consulted (LOCAL) — null when it could not be (DEGRADED)
     */
    private BigDecimal recalculateAdvantages(Ticket ticket, String card) {
        TicketState cart = rebuildCart(ticket);
        ValuationService.ValuationOutcome outcome =
                valuationService.valuate(cart, card, ticket.creationDate);
        if ("ENGINE".equals(outcome.status)) {
            LOGGER.info("Exiting method recalculateAdvantages");
            return valuationReconciler.apply(cart, ticket.id, outcome.responseJson);
        }
        if ("DEGRADED".equals(outcome.status)) {
            LOGGER.warn("Réaffectation: moteur de valorisation indisponible, avantages non recalculés");
            LOGGER.info("Exiting method recalculateAdvantages");
            return null;
        }
        LOGGER.info("Exiting method recalculateAdvantages");
        return BigDecimal.ZERO;
    }

    /**
     * Rebuilds a DETACHED in-memory cart from a stored ticket's lines, stable
     * uids carried over — the same recipe the startup recovery uses, on a
     * cart that is nobody's sale.
     *
     * @param ticket the stored ticket to read the lines of
     * @return a fresh cart holding the ticket's non-cancelled lines
     */
    private TicketState rebuildCart(Ticket ticket) {
        TicketState cart = new TicketState();
        for (TicketLine line : ticket.lines.stream()
                .filter(l -> !l.cancelled)
                .sorted(Comparator.comparingInt(l -> l.lineNumber))
                .toList()) {
            TicketState.TicketItem item = new TicketState.TicketItem(
                    line.ean, line.plu, line.productLabel, line.unitPrice, line.quantity, line.vatRate);
            if (line.lineUid != null) {
                item.uid = line.lineUid;
            }
            cart.items.add(item);
        }
        cart.recomputeTotal();
        LOGGER.info("Exiting method rebuildCart");
        return cart;
    }

    /**
     * Records the operation for reporting (LC-03-06-06): one journal event
     * carrying the whole characteristic set the line names — the PDV through
     * the event's context, the till in its column, the operator in its
     * column, and the card, the ticket amount and the acquired advantages in
     * the detail — and travelling to the store node on the event push.
     *
     * @param ticket the reassigned ticket
     * @param card the card it was reassigned to
     * @param adjustment the recalculation's total adjustment, or null when the
     *        engine was unreachable
     */
    private void journal(Ticket ticket, String card, BigDecimal adjustment) {
        String detail = "ticket=" + ticket.ticketNumber
                + " carte=" + card
                + " montant=" + (ticket.totalIncludingTax != null
                        ? ticket.totalIncludingTax.toPlainString() : "?")
                + " avantages=" + (adjustment != null ? adjustment.toPlainString() : "moteur indisponible")
                + " caisse=" + ticketNumberService.getTerminalId();
        technicalEventService.log(TechnicalEvent.EventType.TICKET_REASSIGNED, detail,
                state.auth.operatorBadgeId);
        LOGGER.info("Exiting method journal");
    }

    /**
     * Prints the customer's new ticket — the re-rendered closed ticket, now
     * carrying the card and its recalculated advantages — and the shop's
     * duplicata when the back office administers one (LC-03-06-05).
     *
     * @param ticket the reassigned ticket
     */
    private void print(Ticket ticket) {
        ticketPrinterService.printTicket(ticket.id);
        if (posSettingsService.reassignPdvDuplicate()) {
            ticketPrinterService.printTicket(ticket.id);
        }
        LOGGER.info("Exiting method print");
    }

    /**
     * Composes the operator-facing outcome of a performed reassignment: the
     * gesture's own word, and the announced amount gap of the recalculation
     * (LC-03-06-04) — never a silent difference on money that was settled.
     *
     * @param adjustment the recalculation's total adjustment, or null when the
     *        engine was unreachable
     * @return the message shown on the reprint state
     */
    private String outcomeMessage(BigDecimal adjustment) {
        if (adjustment == null) {
            LOGGER.info("Exiting method outcomeMessage");
            return i18n.get("op.reaffectationMoteurIndisponible");
        }
        if (adjustment.signum() != 0) {
            LOGGER.info("Exiting method outcomeMessage");
            return i18n.format("op.reaffectationEcart", adjustment.toPlainString());
        }
        LOGGER.info("Exiting method outcomeMessage");
        return i18n.get("op.reaffectationEffectuee");
    }
}
