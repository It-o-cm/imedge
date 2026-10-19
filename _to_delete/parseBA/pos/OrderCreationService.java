package com.intermarche.pos.ui.order;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.VatBreakdown;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.recall.BackOfficeDocumentClient;
import com.intermarche.pos.ui.recall.RecalledDocument;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The creation of an order AT THE TILL (LC-06-03): the cashier registers the
 * articles of the order exactly as a sale's — the ordinary {@code addItem}
 * funnel, valuation and draft included (LC-06-03-02) — then, instead of
 * settling the whole basket, keys the deposit the customer leaves and the
 * register turns the basket into an order.
 *
 * <p>WHAT AN ORDER IS, ON THIS LANE: the articles leave with nobody, the
 * deposit is a {@code CUSTOMER_DEPOSIT} movement carrying the order's code
 * (LC-06-03-04, the movement half this register already had), the deposit's
 * VAT is ventilated per rate on the order's own ventilation (LC-06-03-05),
 * the customer walks away with the deposit slip — articles, deposit, VAT
 * recap and the BARCODE of the order code (LC-06-03-06) — and the order
 * itself lives on the STORE NODE, pushed at creation so the back office
 * lists it and any register of the store reopens it by its code
 * (LC-06-03-01/-07).
 *
 * <p>THE DRAFT THE ORDER CAME FROM IS CANCELLED, not abandoned: the articles
 * became an order, they are nobody's pending sale anymore, and leaving the
 * draft open would resurrect a ghost cart at the next recovery.
 */
@ApplicationScoped
public class OrderCreationService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(OrderCreationService.class);

    /** The outcome of a creation attempt, one per refusal the screen names. */
    public enum Outcome {

        /** The order was created; the code is on the service answer. */
        CREATED,

        /** No cash session is open on this register. */
        NO_SESSION,

        /** The current ticket carries no article. */
        EMPTY_TICKET,

        /** Training mode touches no real document, an order included. */
        TRAINING,

        /** The deposit amount is unreadable or not strictly positive. */
        BAD_AMOUNT,

        /** The deposit exceeds the order total. */
        OVER_TOTAL
    }

    /**
     * The answer of a creation attempt: the outcome and, on success, the
     * order code the customer leaves with.
     */
    public record Result(Outcome outcome, String orderCode) {

        /**
         * Tells whether the attempt created an order.
         *
         * @return true when the outcome is CREATED
         */
        public boolean created() {
            return outcome == Outcome.CREATED;
        }
    }

    /** The localised operator messages, every sentence a bundle key (LC-13-06). */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** Reserves the order codes of this terminal (LC-06-03-01). */
    @Inject
    TicketNumberService ticketNumberService;

    /** Records the deposit as a CUSTOMER_DEPOSIT movement (LC-06-03-04). */
    @Inject
    CashMovementService cashMovementService;

    /** Reads the open session the deposit movement belongs to. */
    @Inject
    CashSessionService cashSessionService;

    /** Pushes the created order to the store node (LC-06-03-01). */
    @Inject
    BackOfficeDocumentClient documentClient;

    /** Prints the deposit slip with its barcode (LC-06-03-06). */
    @Inject
    TicketPrinterService ticketPrinterService;

    /** Cancels the draft the order came from. */
    @Inject
    TicketPersistenceService ticketPersistenceService;

    /**
     * Creates an order from the articles currently registered and collects
     * the deposit the cashier keyed (LC-06-03-01..-06).
     *
     * <p>On success the in-memory ticket is CLEARED and the sale screen
     * carries the creation notice, so the operator starts the next gesture
     * from an empty lane. On any refusal nothing is recorded, nothing is
     * pushed, nothing is printed and the basket stays exactly as it was.
     *
     * @param state the current POS state
     * @param depositAmount the deposit to collect, TTC, as keyed
     * @return the attempt's outcome and order code
     */
    public Result create(PosState state, BigDecimal depositAmount) {
        LOGGER.info("Entering method create with depositAmount: " + depositAmount);
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method create (no open session)");
            return new Result(Outcome.NO_SESSION, null);
        }
        if (state.trainingMode) {
            LOGGER.info("Exiting method create (training mode)");
            return new Result(Outcome.TRAINING, null);
        }
        if (state.ticket == null || state.ticket.items == null
                || state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method create (empty ticket)");
            return new Result(Outcome.EMPTY_TICKET, null);
        }
        BigDecimal total = state.ticket.totalAmount == null
                ? BigDecimal.ZERO : state.ticket.totalAmount;
        if (depositAmount == null || depositAmount.signum() <= 0) {
            LOGGER.info("Exiting method create (bad amount)");
            return new Result(Outcome.BAD_AMOUNT, null);
        }
        if (depositAmount.compareTo(total) > 0) {
            LOGGER.info("Exiting method create (deposit over total)");
            return new Result(Outcome.OVER_TOTAL, null);
        }
        String orderCode = ticketNumberService.nextOrderCode();
        RecalledDocument order = toOrder(state, orderCode);
        String vatDetail = com.intermarche.pos.domain.payment.DepositVat.compute(
                vatBuckets(state), depositAmount, total);
        // LC-06-03-04: the deposit is the movement the register already
        // knew — its reference to the order is what the node imputes it by.
        Employee cashier = state.auth.operatorId != null
                ? Employee.findById(state.auth.operatorId) : null;
        var movement = cashMovementService.recordAcompte(session, cashier,
                depositAmount, orderCode);
        if (movement == null) {
            LOGGER.warn("Acompte refuse pour la commande " + orderCode);
            LOGGER.info("Exiting method create (movement refused)");
            return new Result(Outcome.BAD_AMOUNT, null);
        }
        order.depositVatDetail = vatDetail;
        // The order lives on the store node; the push is tolerant because the
        // deposit was collected and the slip prints whatever the network says.
        documentClient.pushOrder(order);
        // LC-06-03-06: the slip the customer comes back with — articles,
        // deposit, VAT recap and the barcode of the order code.
        ticketPrinterService.printOrderDepositTicket(orderCode, order.lines,
                depositAmount, vatDetail);
        // The basket became an order: its draft is nobody's pending sale.
        if (state.payment.ticketDbId != null) {
            ticketPersistenceService.cancelDraft(state.payment.ticketDbId);
        }
        state.clearTicket();
        state.ticket.setNotice(i18n.format("order.commandeCreee", orderCode));
        state.touch();
        LOGGER.info("Exiting method create (order " + orderCode + " created)");
        return new Result(Outcome.CREATED, orderCode);
    }

    /**
     * Freezes the current ticket into the order's wire shape — the same
     * {@code RecalledDocument} the node's recall hands back (LC-06-03-07),
     * so the order travels up and reopens down in one form.
     *
     * @param state the current POS state
     * @param orderCode the reserved order code
     * @return the order, kind COMMANDE, carrying the ticket's lines and total
     */
    private RecalledDocument toOrder(PosState state, String orderCode) {
        List<RecalledDocument.Line> lines = new ArrayList<>();
        for (TicketState.TicketItem item : state.ticket.items) {
            if (item == null) {
                continue;
            }
            lines.add(new RecalledDocument.Line(item.ean, item.label,
                    item.quantity, item.unitPrice, item.vatRate));
        }
        RecalledDocument order = new RecalledDocument(RecalledDocument.Kind.COMMANDE,
                orderCode, lines, BigDecimal.ZERO);
        order.totalAmount = state.ticket.totalAmount == null
                ? BigDecimal.ZERO : state.ticket.totalAmount;
        return order;
    }

    /**
     * Ventilates the current ticket's lines per VAT rate, the same rule the
     * printed ticket and the persisted totals follow (LC-06-03-05).
     *
     * @param state the current POS state
     * @return the ticket's VAT buckets, in rate order
     */
    private List<VatBreakdown.Bucket> vatBuckets(PosState state) {
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketState.TicketItem item : state.ticket.items) {
            if (item == null) {
                continue;
            }
            breakdown.add(item.vatRate, item.getTotalPrice()
                    .setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return breakdown.getBuckets();
    }
}
