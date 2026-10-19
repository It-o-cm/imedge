package com.intermarche.pos.service;

import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.payment.VoucherPayment;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.DiscriminatorValue;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;

/**
 * Restores the in-memory cart from the database at register startup.
 * <p>
 * Phase 0 lot 2: since the draft is persisted from the first article, a crash
 * or restart must not lose the sale. At startup this service looks for the
 * most recent OPEN draft of this terminal in its database,
 * rebuilds the cart (lines with their stable uids, totals, fidelity card) and
 * the already-registered payments, then reattaches the draft id. Older OPEN
 * drafts of the same terminal (crash leftovers) are cancelled.
 * <p>
 * The register comes back locked: the cashier logs in again and simply
 * continues the restored sale.
 * <p>
 * Cancelling the older OPEN drafts is not cleanup, it ENFORCES the
 * single-draft invariant the whole persistence relies on (one OPEN draft
 * per terminal). The payment restoration also covers the narrow crash
 * window between payment completion and ticket closing: when the restored
 * payments already settle the total, the completion flag is set back and
 * the cashier lands on the completion modal, not on a half-paid screen.
 * The restore machinery ({@code restoreDraft}) is deliberately shared with
 * the parked-ticket resume — one code path to trust for rebuilding a cart.
 */
@ApplicationScoped
public class TicketRecoveryService {

    private static final Logger LOGGER = Logger.getLogger(TicketRecoveryService.class);

    @Inject
    PosState state;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Startup hook running the recovery after the dev/test data seeder
     * (default observer priority is 2500; 2600 runs after it).
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes @Priority(2600) StartupEvent event) {
        try {
            recover();
        } catch (Exception e) {
            LOGGER.error("Échec de la reprise du ticket en cours", e);
        }
    }

    /**
     * Performs the recovery: finds the most recent OPEN draft of this
     * terminal, cancels older OPEN leftovers, and rebuilds the in-memory
     * cart and payments from it.
     */
    @Transactional
    public void recover() {
        LOGGER.info("Entering method recover");
        String terminalId = ticketNumberService.getTerminalId();

        List<Ticket> openDrafts = Ticket.list("status = ?1 and terminalId = ?2 order by id desc",
                Ticket.TicketStatus.OPEN, terminalId);
        if (openDrafts.isEmpty()) {
            LOGGER.info("Exiting method recover");
            return;
        }

        Ticket draft = openDrafts.get(0);

        // Cancel older leftovers of this terminal (crash loops, legacy)
        for (int i = 1; i < openDrafts.size(); i++) {
            Ticket stale = openDrafts.get(i);
            stale.status = Ticket.TicketStatus.CANCELLED;
            stale.persist();
            LOGGER.warnf("Draft périmé annulé ID: %d (%s)", stale.id, stale.ticketNumber);
        }

        restoreDraft(draft);
        technicalEventService.log(TechnicalEvent.EventType.DRAFT_RECOVERED, draft.ticketNumber);
        LOGGER.infof("Ticket en cours restauré ID: %d (%s), %d ligne(s), %d paiement(s)",
                draft.id, draft.ticketNumber, draft.lines.size(), draft.payments.size());
        LOGGER.info("Exiting method recover");
    }

    /**
     * Restores a persisted draft into the in-memory state: cart lines with
     * their stable uids, total, fidelity card, already-registered payments and
     * draft id. Shared by the startup recovery and the parked-ticket resume.
     *
     * @param draft the persisted draft to restore
     */
    public void restoreDraft(Ticket draft) {
        LOGGER.info("Entering method restoreDraft with draft: " + draft);
        restoreCart(draft);
        restorePayments(draft);
        state.payment.ticketDbId = draft.id;
        state.touch();
        LOGGER.info("Exiting method restoreDraft");
    }

    /**
     * Rebuilds the in-memory cart lines and total from the persisted draft.
     * Lines keep their stable uid; a legacy line without uid gets a fresh one.
     *
     * @param draft the persisted draft ticket
     */
    private void restoreCart(Ticket draft) {
        state.ticket.items.clear();

        // A cancelled article (lot C4, BO-04-01-16) is a conserved witness on
        // the draft, not part of the live cart: it must never be restored into
        // memory, otherwise a register restart would resurrect it in the cart.
        List<TicketLine> orderedLines = draft.lines.stream()
                .filter(l -> !l.cancelled)
                .sorted(Comparator.comparingInt(l -> l.lineNumber))
                .toList();

        for (TicketLine line : orderedLines) {
            TicketState.TicketItem item = new TicketState.TicketItem(
                    line.ean, line.plu, line.productLabel, line.unitPrice, line.quantity, line.vatRate);
            if (line.lineUid != null) {
                item.uid = line.lineUid;
            }
            // A price modification survives the restart with its label and
            // original price (endorsed once, never re-asked)
            if (line.modifierLabel != null) {
                item.modifierLabel = line.modifierLabel;
                item.modifierType = line.modifierType;
                item.modifierValue = line.modifierValue;
                if (line.originalUnitPrice != null) {
                    item.originalUnitPrice = line.originalUnitPrice;
                }
            }
            // The money-instrument flag is READ BACK from the draft, never
            // re-derived from the catalog: the draft is the durable truth of
            // what was rung up, and it keeps the recovery free of any product
            // lookup (phase: gift-card registry).
            item.moneyProduct = line.moneyProduct;
            item.priceEmbedded = line.priceEmbedded;
            // EXP-26: the deduced weight + price/kg survive a restart so the
            // recovered price-embedded line still shows and prints them.
            item.embeddedWeight = line.embeddedWeight;
            item.embeddedUnitPrice = line.embeddedUnitPrice;
            // Same rule for the reduction ban (BO-02-03-09): read back, never
            // re-derived. Without this the ban vanished at the first restart
            // and the article became discountable again.
            item.discountForbidden = line.discountForbidden;
            // LC-02-08: read back too. A restart between the marking and the
            // payment must not send to the customer's arms an article the desk
            // was going to hand over.
            item.toCollect = line.toCollect;
            // EXP-43: the subtotal markers survive a restart so a recovered sale
            // still shows each shopper's part on screen and on the ticket.
            item.subtotalBoundary = line.subtotalBoundary;
            item.returnLine = line.returnLine;
            // LC-09-01-11 to -18: read back too, so the eligible bases shown during
            // the sale survive a restart instead of falling back to zero.
            item.restrictedTenders = line.restrictedTenders;
            item.unitName = line.unitName;
            item.decimalQuantity = line.decimalQuantity;
            state.ticket.items.add(item);
        }
        state.ticket.recomputeTotal();

        if (draft.globalDiscountType != null) {
            // The REQUEST is restored; the allocation reruns at the next
            // total recomputation (phase: global ticket discount).
            state.ticket.globalDiscountType = draft.globalDiscountType;
            state.ticket.globalDiscountValue = draft.globalDiscountValue;
        }
        if (draft.fidelityCard != null) {
            state.fidelity.assignCard(draft.fidelityCard);
        }
    }

    /**
     * Rebuilds the in-memory payment entries from the persisted payments, in
     * their registration order, and restores the completion flag when the
     * remaining due is already settled (crash between completion and closing).
     *
     * @param draft the persisted draft ticket
     */
    private void restorePayments(Ticket draft) {
        List<TicketPayment> orderedPayments = draft.payments.stream()
                .sorted(Comparator.comparingInt(p -> p.paymentIndex))
                .toList();

        for (TicketPayment payment : orderedPayments) {
            if (payment instanceof VoucherPayment voucher) {
                state.payment.addVoucherPayment(voucher.voucherLabel, voucher.voucherNumber, voucher.amount);
            } else if (payment instanceof CashPayment cash) {
                state.payment.addCashPayment(cash.amount, cash.tenderedAmount);
            } else if (payment instanceof com.intermarche.pos.domain.payment.BackupPayment secours) {
                // The scheme, the transaction and HOW the outcome was obtained are
                // restored with the line: a recovered settlement that lost the manual
                // flag would claim a signature nobody ever verified.
                state.payment.addBackupPayment(secours.amount, secours.methodLabel,
                        secours.transactionNumber, secours.manual);
            } else if (payment instanceof com.intermarche.pos.domain.payment.ForeignCurrencyPayment devise) {
                // The currency, the amount handed over and the rate are restored with
                // the line: LC-07-14-05 prints all three, and a recovered settlement
                // that lost them could not be printed at all.
                state.payment.addCurrencyPayment(devise.amount, devise.currencyCode,
                        devise.foreignAmount, devise.exchangeRate);
            } else if (payment instanceof com.intermarche.pos.domain.payment.CreditPayment credit) {
                // The debtor is restored with the line: a recovered credit
                // settlement that lost its account would print a debt owed by
                // nobody on the receipt the customer is about to be handed.
                // The tender key is the one the RECOVERED line carries, never the
                // one administered today: a recovered sale must come back as it
                // was registered, even if the shop has since changed the key
                // customer credit is written under (BO-10-04-09).
                state.payment.addCreditPayment(credit.amount, credit.accountNumber,
                        credit.accountName, credit.overLimit, methodKeyOf(payment));
            } else if (payment instanceof com.intermarche.pos.domain.payment.DepositPayment acompte) {
                // The deposit's per-rate VAT detail and the order code are
                // restored with the line (LC-06-03-07/-08): a recovered
                // recovery that lost them would print a final receipt with no
                // VAT statement, and the order the sale settles would never be
                // told it was settled at the closing.
                state.payment.addDepositPayment(acompte.amount, acompte.vatDetail,
                        acompte.orderCode);
                if (acompte.orderCode != null && !acompte.orderCode.isBlank()) {
                    state.recalledOrderCode = acompte.orderCode;
                }
            } else {
                state.payment.addPayment(methodKeyOf(payment), payment.amount);
            }
        }

        if (!state.payment.payments.isEmpty() && state.getRemaining().signum() <= 0) {
            state.payment.transactionComplete = true;
        }
    }

    /**
     * Resolves the payment method key of a persisted payment from its JPA
     * discriminator value.
     *
     * @param payment the persisted payment
     * @return the method key (e.g. "CARD"), or "UNKNOWN" when unresolvable
     */
    private String methodKeyOf(TicketPayment payment) {
        Class<?> entityClass = Hibernate.getClass(payment);
        DiscriminatorValue discriminator = entityClass.getAnnotation(DiscriminatorValue.class);
        return discriminator != null ? discriminator.value() : "UNKNOWN";
    }
}
