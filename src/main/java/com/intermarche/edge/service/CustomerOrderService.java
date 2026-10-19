package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.sale.OrderKind;
import com.intermarche.edge.domain.sale.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * The single RECALL-BY-CODE mechanism the store's onglet-G documents share
 * (EXP-90/91/92/85): a quote, an order, a sale voucher and a click-and-collect
 * basket are recalled by their code, settled in full or by a deposit, and handed
 * over — one service, four documents, rather than three parallel gestures.
 *
 * <p>It holds only the recall and the money transitions; the back-office CRUD of
 * the orders lives on its admin screen, and the till reaches {@link #recall} to
 * load a code at point of sale.
 */
@ApplicationScoped
public class CustomerOrderService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CustomerOrderService.class);

    /**
     * Recalls an order by its code, for the till to load at point of sale — but
     * only when it is still recallable (open or partially paid). A code that
     * matches nothing, or an order already paid, collected or cancelled, recalls
     * nothing.
     *
     * @param code the code handed to the customer
     * @return the recallable order, or null
     */
    public CustomerOrder recall(String code) {
        LOGGER.info("Entering method recall with code: " + code);
        CustomerOrder order = CustomerOrder.findByCode(code);
        if (order == null || !order.isRecallable()) {
            LOGGER.info("Exiting method recall: nothing recallable");
            return null;
        }
        LOGGER.info("Exiting method recall");
        return order;
    }

    /**
     * Records a DEPOSIT on an order (EXP-85): allowed only for a kind that permits
     * one (a quote or an order) and only for a strictly positive amount that does
     * not exceed what remains. The order becomes {@link OrderStatus#PAID} when the
     * deposit clears the balance, {@link OrderStatus#PARTIALLY_PAID} otherwise.
     *
     * @param order the recalled order
     * @param amount the deposit amount, in euros
     * @return true when the deposit was recorded, false when refused
     */
    @Transactional
    public boolean applyDeposit(CustomerOrder order, BigDecimal amount) {
        LOGGER.info("Entering method applyDeposit");
        if (order == null || !order.isRecallable() || !order.kind.isDepositAllowed()) {
            LOGGER.info("Exiting method applyDeposit: refused (state or kind)");
            return false;
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(order.remaining()) > 0) {
            LOGGER.info("Exiting method applyDeposit: refused (amount)");
            return false;
        }
        order.depositPaid = order.depositPaid.add(amount);
        order.status = order.remaining().signum() == 0 ? OrderStatus.PAID : OrderStatus.PARTIALLY_PAID;
        LOGGER.info("Exiting method applyDeposit: recorded");
        return true;
    }

    /**
     * Settles the WHOLE remaining balance of an order at the till, marking it
     * {@link OrderStatus#PAID}. Refused when the order is not recallable.
     *
     * @param order the recalled order
     * @return true when the settlement was recorded, false when refused
     */
    @Transactional
    public boolean settleRemaining(CustomerOrder order) {
        LOGGER.info("Entering method settleRemaining");
        if (order == null || !order.isRecallable()) {
            LOGGER.info("Exiting method settleRemaining: refused");
            return false;
        }
        order.depositPaid = order.totalAmount == null ? BigDecimal.ZERO : order.totalAmount;
        order.status = OrderStatus.PAID;
        LOGGER.info("Exiting method settleRemaining: settled");
        return true;
    }

    /**
     * Marks a fully-paid order as collected once the goods are handed over.
     * Refused unless the order is paid.
     *
     * @param order the order
     * @return true when marked collected, false when refused
     */
    @Transactional
    public boolean collect(CustomerOrder order) {
        LOGGER.info("Entering method collect");
        if (order == null || order.status != OrderStatus.PAID) {
            LOGGER.info("Exiting method collect: refused");
            return false;
        }
        order.status = OrderStatus.COLLECTED;
        LOGGER.info("Exiting method collect: collected");
        return true;
    }

    /**
     * Creates or refreshes an order a REGISTER pushed (LC-06-03-01): the
     * cashier registered the articles at the till, keyed the deposit and the
     * register pushes the order so the store holds it, the back office lists
     * it and any register of the store recalls it by its code (LC-06-03-07).
     *
     * <p>IDEMPOTENT BY CODE: a push of a code the store already holds
     * overwrites the row it names — a register whose push was answered but
     * never acknowledged pushes again, and the second push must land on the
     * same order rather than a duplicate. A blank code or an unreadable kind
     * answers null and nothing is written.
     *
     * <p>The deposit's VAT detail rides along ({@code LC-06-03-05}): the back
     * office reads it at period end for the suspense accounts by rate, and
     * the recall hands it back so the final ticket restates it
     * ({@code LC-06-03-08}). The deposit's MONEY does not ride here — it
     * travels as the register's {@code CUSTOMER_DEPOSIT} movement, imputed
     * by the order reference.
     *
     * @param code the order code, the recall key handed to the customer
     * @param kind the kind of document, never null
     * @param totalAmount the order total, TTC
     * @param depositVatDetail the per-rate VAT of the deposit taken, or null
     * @param lines the order's article lines, frozen as registered
     * @return the created or refreshed order, or null when refused
     */
    @Transactional
    public CustomerOrder createFromTill(String code, OrderKind kind, BigDecimal totalAmount,
                                         String depositVatDetail,
                                         java.util.List<CustomerOrder.OrderLine> lines) {
        LOGGER.info("Entering method createFromTill with code: " + code);
        if (code == null || code.isBlank() || kind == null) {
            LOGGER.info("Exiting method createFromTill: refused (code or kind)");
            return null;
        }
        CustomerOrder order = CustomerOrder.findByCode(code);
        boolean created = order == null;
        if (created) {
            order = new CustomerOrder();
            order.code = code.trim();
            order.kind = kind;
            order.customerAccountNumber = null;
        }
        order.status = OrderStatus.OPEN;
        order.label = null;
        order.orderDate = java.time.LocalDateTime.now();
        order.totalAmount = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        order.depositPaid = BigDecimal.ZERO;
        order.depositVatDetail = depositVatDetail;
        order.lines = lines == null ? new java.util.ArrayList<>() : lines;
        if (created) {
            order.persist();
        }
        LOGGER.info("Exiting method createFromTill (" + (created ? "created" : "refreshed") + ")");
        return order;
    }

    /**
     * Settles an order a REGISTER closed the sale of (LC-06-03-07): the balance
     * was paid at the till and the goods went with it, so the order becomes
     * PAID then COLLECTED, and its code can no longer be recalled and paid a
     * second time.
     *
     * <p>IDEMPOTENT BY DESIGN: settling an order already paid or collected —
     * or a node whose settle push was answered but never acknowledged —
     * answers true without writing anything. Refused only on an order the
     * store does not hold or one already cancelled.
     *
     * @param order the order the till settled
     * @return true when the order stands settled, false when refused
     */
    @Transactional
    public boolean settleAtTill(CustomerOrder order) {
        LOGGER.info("Entering method settleAtTill with order: " + order);
        if (order == null) {
            LOGGER.info("Exiting method settleAtTill: refused (no order)");
            return false;
        }
        if (order.status == OrderStatus.PAID || order.status == OrderStatus.COLLECTED) {
            LOGGER.info("Exiting method settleAtTill: already settled");
            return true;
        }
        if (!order.isRecallable()) {
            LOGGER.info("Exiting method settleAtTill: refused (cancelled)");
            return false;
        }
        order.depositPaid = order.totalAmount == null ? BigDecimal.ZERO : order.totalAmount;
        order.status = OrderStatus.PAID;
        order.status = OrderStatus.COLLECTED;
        LOGGER.info("Exiting method settleAtTill: settled");
        return true;
    }
}
