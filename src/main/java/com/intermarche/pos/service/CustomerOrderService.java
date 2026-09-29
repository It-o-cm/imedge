package com.intermarche.pos.service;

import com.intermarche.pos.domain.sale.CustomerOrder;
import com.intermarche.pos.domain.sale.OrderStatus;
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
}
