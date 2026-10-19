package com.intermarche.pos.service;

import com.intermarche.pos.domain.payment.TenderDefinition;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cash session lifecycle of this register: opening with an initial float,
 * read-only X snapshot, Z closing with counted amount, variance and
 * withdrawal. Sessions live in the register's own database, so they survive
 * register restarts by construction.
 * <p>
 * The theoretical cash is the opening float, plus the cash payments of the
 * closed tickets of the session, minus its cash refunds, plus the net of the
 * session's cash MOVEMENTS: deposits and customer down-payments add cash to
 * the drawer, withdrawals and expenses take it out, and a cash-count
 * declaration moves nothing (it is a witness, not a transfer). A session with
 * no movement is therefore strictly unchanged.
 * <p>
 * Reports are computed from the DATABASE, never from in-memory state: an X
 * or Z after a register restart is exact by construction. The Z closing has
 * two side effects beyond the session itself: the register's PARKED tickets
 * are cancelled (each pushed to the store node), and the session is pushed
 * a second time — it was already pushed at opening, the closing push
 * completes the same row by number upsert, so consolidated tickets can
 * reference their session from the first minute.
 */
@ApplicationScoped
public class CashSessionService {

    private static final Logger LOGGER = Logger.getLogger(CashSessionService.class);

    /** The register's own retention sweep (BO-10-08-13/-14/-15). */
    @jakarta.inject.Inject
    StoredValueRetentionService storedValueRetentionService;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    SyncOutboxService syncOutboxService;

    @Inject
    PosSettingsService posSettingsService;

    /**
     * Read-only snapshot of a session (X report content), also used as the
     * body of the Z report once the session is closed.
     */
    public static class SessionReport {
        /** The reported session. */
        public CashSession session;
        /** The number of closed tickets of the session. */
        public int ticketCount;
        /** The tax-included revenue of the closed tickets. */
        public BigDecimal totalIncludingTax = BigDecimal.ZERO;
        /** The payment totals per method key, in first-seen order. */
        public Map<String, BigDecimal> totalsByMethod = new LinkedHashMap<>();
        /** The theoretical cash in the drawer (float + cash payments - cash refunds + net movements). */
        public BigDecimal theoreticalCash = BigDecimal.ZERO;
        /** The net cash impact of the session's movements (deposits/acomptes minus withdrawals/expenses). */
        public BigDecimal netCashMovements = BigDecimal.ZERO;
        /** The total refunded during the session, all methods. */
        public BigDecimal totalRefunds = BigDecimal.ZERO;
        /** True when this report closes the session (Z), false for an X snapshot. */
        public boolean closing;

        /**
         * The tenders the withdrawal report states line by line (BO-03-02-25).
         *
         * <p>Computed by the report and not by the renderer, so the printed X and
         * the displayed X can never disagree about what is detailed.
         */
        public java.util.Set<String> detailedMethods = new java.util.LinkedHashSet<>();

        /**
         * The total of the tenders the report does NOT detail, stated as one
         * line (BO-03-02-25).
         */
        public BigDecimal otherMethodsTotal = BigDecimal.ZERO;

        /** The total of the tenders deposited at the bank (BO-03-02-21). */
        public BigDecimal bankDepositTotal = BigDecimal.ZERO;

        /** The total of the tenders reported to the fidelity programme (BO-03-02-30). */
        public BigDecimal fidelityReportedTotal = BigDecimal.ZERO;

        /**
         * The cash the drawer must KEEP at closing: the opening float plus the net
         * cash movements of the session.
         *
         * <p>It is the figure the closing withdrawal is deduced from — counted minus
         * this — and the cashier used to do that subtraction on a piece of paper. It
         * is not the opening float alone, because a coin supply received during the
         * day RAISES what the drawer must keep (the store handed over change, not
         * revenue), and a withdrawal already made lowers it. Count it right and the
         * difference is exactly the cash revenue.
         *
         * <p>It follows the same float rule as the theoretical (BO-03-02-22): where
         * the cash is not a tender the back office lets make up the float, the float
         * is not part of it either.
         */
        public BigDecimal floatReference = BigDecimal.ZERO;

        /**
         * The cash the cashier DECLARED during the session (the last declaration
         * movement), or null when none was recorded.
         *
         * <p>The declaration used to be written and never read: the register stored
         * a figure nothing compared to anything, so the function answered no question.
         * It is now put beside the theoretical, which is the only reason to ask a
         * cashier what the drawer holds.
         */
        public BigDecimal declaredCash;

        /**
         * The declared cash MINUS the theoretical, zero while no declaration was
         * recorded. Positive, the drawer holds more than the register expects.
         */
        public BigDecimal declaredCashVariance = BigDecimal.ZERO;

        /**
         * True when the cash is declared by the register rather than counted by
         * the cashier (BO-03-02-17).
         */
        public boolean cashDeclaredAutomatically;

        /**
         * True when the closing withdrawal of the cash is computed rather than
         * typed (BO-03-02-18).
         */
        public boolean cashWithdrawnAutomatically;

        /**
         * The revenue aggregated per cashier of the session (EXP-79): added
         * ALONGSIDE the terminal totals above, which stay the poste document. A
         * session worked by one cashier holds one row.
         */
        public List<CashierRow> cashierRows = new java.util.ArrayList<>();

        /**
         * The paper tenders the cashier declares by count at closing (EXP-83),
         * each with the theoretical quantity the register expects (the number of
         * settlements taken in that tender during the session).
         */
        public List<PaperTenderRow> paperTenders = new java.util.ArrayList<>();

        /**
         * One per-cashier revenue line (EXP-79).
         *
         * @param cashier the cashier's display name
         * @param ticketCount the number of closed tickets that cashier signed
         * @param totalFormatted the tax-included revenue, French format
         */
        public record CashierRow(String cashier, int ticketCount, String totalFormatted) {
        }

        /**
         * One paper tender to declare at closing (EXP-83).
         *
         * @param code the tender code
         * @param label the tender label
         * @param theoreticalQty the number of settlements the register counted in
         *        that tender during the session
         */
        public record PaperTenderRow(String code, String label, int theoreticalQty) {
        }

        /**
         * Returns the per-cashier revenue rows (EXP-79).
         *
         * @return the cashier rows, possibly empty
         */
        public List<CashierRow> getCashierRows() {
            return cashierRows;
        }

        /**
         * Returns the paper tenders to declare at closing (EXP-83).
         *
         * @return the paper-tender rows, possibly empty
         */
        public List<PaperTenderRow> getPaperTenders() {
            return paperTenders;
        }

        /**
         * Returns the settlement lines the report details, in the order the
         * methods were first seen (BO-03-02-25).
         *
         * @return the detailed settlement rows, possibly empty
         */
        public List<MethodRow> getDetailedMethodRows() {
            List<MethodRow> rows = new java.util.ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : totalsByMethod.entrySet()) {
                if (detailedMethods.contains(entry.getKey())) {
                    rows.add(new MethodRow(entry.getKey(), money(entry.getValue())));
                }
            }
            return rows;
        }

        /**
         * Tells whether the report carries tenders it does not detail, which is
         * what decides whether the lump line is worth printing (BO-03-02-25).
         *
         * @return true when at least one tender is left undetailed
         */
        public boolean isCarryingUndetailedMethods() {
            return detailedMethods.size() < totalsByMethod.size();
        }

        /**
         * Returns the total of the undetailed tenders, French format.
         *
         * @return the formatted lump total
         */
        public String getOtherMethodsTotalFormatted() {
            return money(otherMethodsTotal);
        }

        /**
         * Returns the total deposited at the bank, French format.
         *
         * @return the formatted bank-deposit total
         */
        public String getBankDepositTotalFormatted() {
            return money(bankDepositTotal);
        }

        /**
         * Returns the total reported to the fidelity programme, French format.
         *
         * @return the formatted fidelity total
         */
        public String getFidelityReportedTotalFormatted() {
            return money(fidelityReportedTotal);
        }

        /**
         * One settlement line of the report: a payment method and its total.
         *
         * <p>The map above is what the report COMPUTES; this is what a screen and a
         * roll of paper both need — a label and an already-formatted amount. Deriving
         * it here rather than in each renderer is what keeps the printed X and the
         * displayed X from ever disagreeing.
         *
         * @param method the payment method key
         * @param amountFormatted the total, French format
         */
        public record MethodRow(String method, String amountFormatted) {
        }

        /**
         * Returns the settlement lines, in the order the methods were first seen.
         *
         * @return the settlement rows, possibly empty
         */
        public List<MethodRow> getMethodRows() {
            List<MethodRow> rows = new java.util.ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : totalsByMethod.entrySet()) {
                rows.add(new MethodRow(entry.getKey(), money(entry.getValue())));
            }
            return rows;
        }

        /**
         * Returns the tax-included revenue, French format.
         *
         * @return the formatted revenue
         */
        public String getTotalIncludingTaxFormatted() {
            return money(totalIncludingTax);
        }

        /**
         * Returns the theoretical cash in the drawer, French format.
         *
         * @return the formatted theoretical cash
         */
        public String getTheoreticalCashFormatted() {
            return money(theoreticalCash);
        }

        /**
         * Returns the cash the drawer must keep at closing, French format.
         *
         * @return the formatted float reference
         */
        public String getFloatReferenceFormatted() {
            return money(floatReference);
        }

        /**
         * Returns the cash the drawer must keep at closing as a plain number, for the
         * closing screen's own arithmetic (a dot decimal separator, no currency).
         *
         * @return the float reference, plain
         */
        public String getFloatReferencePlain() {
            BigDecimal value = floatReference == null ? BigDecimal.ZERO : floatReference;
            return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        /**
         * Returns the cash the cashier declared, French format, or a dash when no
         * declaration was recorded this session.
         *
         * @return the formatted declared cash, or a dash
         */
        public String getDeclaredCashFormatted() {
            return declaredCash == null ? "—" : money(declaredCash);
        }

        /**
         * Returns the gap between the declared cash and the theoretical, French
         * format, or a dash when no declaration was recorded this session.
         *
         * @return the formatted declaration variance, or a dash
         */
        public String getDeclaredCashVarianceFormatted() {
            return declaredCash == null ? "—" : money(declaredCashVariance);
        }

        /**
         * Whether a cash declaration was recorded during the session, which is what
         * decides if the two lines above are stated at all.
         *
         * @return true when the cashier declared the drawer
         */
        public boolean isDeclared() {
            return declaredCash != null;
        }

        /**
         * Returns the total refunded during the session, French format.
         *
         * @return the formatted refund total
         */
        public String getTotalRefundsFormatted() {
            return money(totalRefunds);
        }

        /**
         * Returns the net cash impact of the session's movements, French format.
         *
         * @return the formatted net movement
         */
        public String getNetCashMovementsFormatted() {
            return money(netCashMovements);
        }

        /**
         * Formats an amount the way every screen and every ticket of the register
         * does, tolerating a missing value.
         *
         * @param amount the amount, possibly null
         * @return the amount with two decimals and a French comma
         */
        private static String money(BigDecimal amount) {
            BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
            return String.format("%.2f",
                    value.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
        }
    }

    /**
     * Returns the open session of this register, or null when none is open.
     *
     * @return the open session, or null
     */
    public CashSession getOpenSession() {
        LOGGER.info("Entering method getOpenSession");
        LOGGER.info("Exiting method getOpenSession");
        return CashSession.findOpenByTerminal(ticketNumberService.getTerminalId());
    }

    /**
     * The theoretical opening float for the next session (EXP-02): the cash left
     * in the drawer at the previous close — what was counted minus what was
     * withdrawn — carried over rather than a fixed parameter. Null when this
     * register was never closed, so the caller falls back to the administered
     * default float.
     *
     * @return the leftover of the last close (never negative), or null when there
     *         is no previous close to carry over
     */
    public BigDecimal theoreticalOpeningFloat() {
        LOGGER.info("Entering method theoreticalOpeningFloat");
        CashSession last = CashSession.findLastClosedByTerminal(ticketNumberService.getTerminalId());
        if (last == null) {
            LOGGER.info("Exiting method theoreticalOpeningFloat");
            return null;
        }
        BigDecimal counted = last.countedAmount != null ? last.countedAmount : BigDecimal.ZERO;
        BigDecimal withdrawn = last.withdrawnAmount != null ? last.withdrawnAmount : BigDecimal.ZERO;
        BigDecimal leftover = counted.subtract(withdrawn);
        if (leftover.signum() < 0) {
            leftover = BigDecimal.ZERO;
        }
        LOGGER.info("Exiting method theoreticalOpeningFloat");
        return leftover.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * The theoretical cash held in the drawer of the open session when it exceeds
     * the administered cap (EXP-17), else null. Null also when no cap is set or no
     * session is open — the register warns but never blocks, so this only feeds a
     * non-blocking indicator.
     *
     * @return the drawer cash when it is over the cap, or null
     */
    @Transactional
    public BigDecimal drawerCapExcess() {
        LOGGER.info("Entering method drawerCapExcess");
        BigDecimal cap = posSettingsService.drawerCap();
        if (cap == null || cap.signum() <= 0) {
            LOGGER.info("Exiting method drawerCapExcess");
            return null;
        }
        CashSession session = getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method drawerCapExcess");
            return null;
        }
        BigDecimal cash = buildReport(session).theoreticalCash;
        LOGGER.info("Exiting method drawerCapExcess");
        return cash != null && cash.compareTo(cap) > 0 ? cash : null;
    }

    /**
     * Opens a new session for this register with its initial float.
     *
     * @param cashierId the id of the cashier opening the session
     * @param openingFloat the initial cash float placed in the drawer
     * @return the opened session, or null when one is already open or the
     *         cashier cannot be resolved
     */
    @Transactional
    public CashSession openSession(Long cashierId, BigDecimal openingFloat) {
        LOGGER.info("Entering method openSession with cashierId: " + cashierId + ", openingFloat: " + openingFloat);
        if (getOpenSession() != null) {
            LOGGER.warn("Ouverture refusée : une session est déjà ouverte sur cette caisse");
            LOGGER.info("Exiting method openSession");
            return null;
        }
        Employee cashier = (cashierId != null) ? Employee.findById(cashierId) : null;
        if (cashier == null) {
            LOGGER.error("Ouverture refusée : caissier introuvable");
            LOGGER.info("Exiting method openSession");
            return null;
        }
        CashSession session = new CashSession();
        session.sessionNumber = ticketNumberService.nextSessionNumber();
        session.terminalId = ticketNumberService.getTerminalId();
        session.status = CashSession.SessionStatus.OPEN;
        session.openingDate = LocalDateTime.now();
        session.openingCashier = cashier;
        session.openingFloat = openingFloat != null ? openingFloat : BigDecimal.ZERO;
        session.persist();
        technicalEventService.log(TechnicalEvent.EventType.SESSION_OPENED,
                session.sessionNumber + " fond " + session.openingFloat.toPlainString());
        syncOutboxService.enqueue(SyncOutbox.EntityType.SESSION, session.id);
        LOGGER.info("Exiting method openSession");
        return session;
    }

    /**
     * Builds the read-only snapshot of a session: closed-ticket count and
     * revenue, per-method payment totals and theoretical cash.
     *
     * @param session the session to report on
     * @return the report content
     */
    @Transactional
    public SessionReport buildReport(CashSession session) {
        LOGGER.info("Entering method buildReport with session: " + session);
        SessionReport report = new SessionReport();
        report.session = session;

        List<Ticket> tickets = Ticket.list("session = ?1 and status = ?2",
                session, Ticket.TicketStatus.CLOSED);
        report.ticketCount = tickets.size();

        BigDecimal cashTotal = BigDecimal.ZERO;
        // EXP-79: per-cashier revenue, added alongside the terminal totals.
        Map<String, int[]> cashierCount = new LinkedHashMap<>();
        Map<String, BigDecimal> cashierTotal = new LinkedHashMap<>();
        // EXP-83: how many settlements each tender took — the theoretical count a
        // paper tender is declared against.
        Map<String, Integer> paymentCountByMethod = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            report.totalIncludingTax = report.totalIncludingTax.add(ticket.totalIncludingTax);
            String cashierName = (ticket.cashier != null) ? ticket.cashier.getFullName() : "—";
            cashierCount.computeIfAbsent(cashierName, k -> new int[1])[0]++;
            cashierTotal.merge(cashierName, ticket.totalIncludingTax, BigDecimal::add);
            for (TicketPayment payment : ticket.payments) {
                String key = payment.getMethodKey();
                report.totalsByMethod.merge(key, payment.amount, BigDecimal::add);
                paymentCountByMethod.merge(key, 1, Integer::sum);
                if ("CASH".equals(key)) {
                    cashTotal = cashTotal.add(payment.amount);
                }
            }
        }
        for (Map.Entry<String, BigDecimal> entry : cashierTotal.entrySet()) {
            report.cashierRows.add(new SessionReport.CashierRow(entry.getKey(),
                    cashierCount.get(entry.getKey())[0],
                    SessionReport.money(entry.getValue())));
        }
        // EXP-83: the paper tenders the cashier declares by count (cashierDeclaration),
        // each with the theoretical settlement count seen this session.
        for (TenderDefinition tender : TenderDefinition.<TenderDefinition>listAllOrdered()) {
            if (tender.cashierDeclaration) {
                report.paperTenders.add(new SessionReport.PaperTenderRow(tender.code,
                        tender.label, paymentCountByMethod.getOrDefault(tender.code, 0)));
            }
        }
        // Refunds of the session: all methods reported, cash ones lower the drawer
        BigDecimal cashRefunds = BigDecimal.ZERO;
        List<Refund> refunds = Refund.list("session", session);
        for (Refund refund : refunds) {
            report.totalRefunds = report.totalRefunds.add(refund.totalAmount);
            if (refund.refundMethod == Refund.RefundMethod.CASH) {
                cashRefunds = cashRefunds.add(refund.totalAmount);
            }
        }
        // Cash movements of the session: net impact on the drawer (a session
        // without any movement leaves the theoretical strictly unchanged)
        BigDecimal netMovements = BigDecimal.ZERO;
        List<CashMovement> movements = CashMovement.list("session = ?1", session);
        CashMovement lastDeclaration = null;
        for (CashMovement movement : movements) {
            netMovements = netMovements.add(cashImpact(movement));
            // The LAST cash declaration is the one that counts: a cashier who declares
            // twice has corrected themselves, and the earlier figure is not an opinion
            // the report has to carry. A declaration naming another tender is not the
            // cash (a null tender reads as cash, like everywhere else).
            if (movement.type == CashMovement.MovementType.DECLARATION
                    && isCash(movement.paymentMethod)
                    && movement.amount != null
                    && (lastDeclaration == null
                        || laterThan(movement.movementDate, lastDeclaration.movementDate))) {
                lastDeclaration = movement;
            }
        }
        // LC-12-03 and LC-12-10: the theoretical of EACH tender follows its movements
        // too, not only the cash. A withdrawal of cheques lowers the cheques, and a
        // transfer moves an amount from one tender to another — without this the
        // per-method breakdown would keep stating what the tickets said and ignore
        // everything the drawer did afterwards.
        for (CashMovement movement : movements) {
            applyMethodImpact(report.totalsByMethod, movement);
        }
        report.netCashMovements = netMovements;
        // BO-03-02-22: the opening float counts in the cash theoretical only when
        // the cash is a tender the back office lets make up the float. A store
        // that administers nothing keeps today's answer — the float is cash.
        BigDecimal floatPart = tenderAllows(CashMovement.CASH, TenderFlag.FLOAT)
                ? session.openingFloat : BigDecimal.ZERO;
        report.theoreticalCash = floatPart.add(cashTotal).subtract(cashRefunds)
                .add(netMovements).setScale(2, RoundingMode.HALF_UP);
        // What the drawer must KEEP: the float as administered, plus what the day's
        // movements added to it or took from it. The closing withdrawal is the rest.
        report.floatReference = floatPart.add(netMovements).setScale(2, RoundingMode.HALF_UP);
        // The declaration is worth stating only against the theoretical, and the
        // theoretical is only known here.
        if (lastDeclaration != null) {
            report.declaredCash = lastDeclaration.amount.setScale(2, RoundingMode.HALF_UP);
            report.declaredCashVariance = report.declaredCash.subtract(report.theoreticalCash);
        }
        applyReportingRules(report);
        LOGGER.info("Exiting method buildReport");
        return report;
    }

    /**
     * Applies the administered REPORTING rules of each tender to a built report
     * (BO-03-02-17/18/21/25/30).
     *
     * <p>Applied once, here, over the totals the report already holds: every
     * renderer then states the same thing, and the referential is read in one
     * place rather than at each screen and each roll of paper.
     *
     * @param report the report to complete
     */
    private void applyReportingRules(SessionReport report) {
        for (Map.Entry<String, BigDecimal> entry : report.totalsByMethod.entrySet()) {
            String key = entry.getKey();
            BigDecimal total = entry.getValue();
            if (tenderAllows(key, TenderFlag.REPORT_DETAIL)) {
                report.detailedMethods.add(key);
            } else {
                report.otherMethodsTotal = report.otherMethodsTotal.add(total);
            }
            if (tenderCarries(key, TenderFlag.BANK_DEPOSIT)) {
                report.bankDepositTotal = report.bankDepositTotal.add(total);
            }
            if (tenderCarries(key, TenderFlag.FIDELITY_REPORT)) {
                report.fidelityReportedTotal = report.fidelityReportedTotal.add(total);
            }
        }
        report.cashDeclaredAutomatically = tenderCarries(CashMovement.CASH, TenderFlag.DECLARATION);
        report.cashWithdrawnAutomatically = tenderCarries(CashMovement.CASH, TenderFlag.WITHDRAWAL);
    }

    /**
     * Reads an administered flag of a tender, a tender no row administers
     * answering that the flag is NOT set (BO-03-02-17/18/21/30).
     *
     * @param code the settlement key
     * @param flag the flag to read
     * @return true when an administered row carries that flag
     */
    private boolean tenderCarries(String code, TenderFlag flag) {
        TenderDefinition tender = TenderDefinition.findByCode(code);
        return tender != null && flag.of(tender);
    }

    /**
     * Reads an administered flag of a tender, a tender no row administers
     * answering that the flag IS set (BO-03-02-22/25).
     *
     * <p>The other default, and deliberately so: these two flags describe what
     * the register already does — the float is cash, the report details every
     * tender — so an empty referential must leave that behaviour alone.
     *
     * @param code the settlement key
     * @param flag the flag to read
     * @return true unless an administered row clears that flag
     */
    private boolean tenderAllows(String code, TenderFlag flag) {
        TenderDefinition tender = TenderDefinition.findByCode(code);
        return tender == null || flag.of(tender);
    }

    /**
     * The administered flags this service reads off a tender.
     *
     * <p>An enumeration rather than seven lookups spelled out: the reading is
     * the same every time and only the field differs, and the two defaults above
     * then have one place each instead of one per flag.
     */
    private enum TenderFlag {

        /** Whether the tender may make up the opening float (BO-03-02-22). */
        FLOAT {
            @Override
            boolean of(TenderDefinition tender) {
                return tender.floatAllowed;
            }
        },

        /** Whether the withdrawal report details the tender (BO-03-02-25). */
        REPORT_DETAIL {
            @Override
            boolean of(TenderDefinition tender) {
                return tender.withdrawalReportDetail;
            }
        },

        /** Whether the takings of the tender are deposited at the bank (BO-03-02-21). */
        BANK_DEPOSIT {
            @Override
            boolean of(TenderDefinition tender) {
                return tender.bankDeposit;
            }
        },

        /** Whether the use of the tender is reported to fidelity (BO-03-02-30). */
        FIDELITY_REPORT {
            @Override
            boolean of(TenderDefinition tender) {
                return tender.fidelityReported;
            }
        },

        /** Whether the tender is declared by the register (BO-03-02-17). */
        DECLARATION {
            @Override
            boolean of(TenderDefinition tender) {
                return tender.cashierDeclaration;
            }
        },

        /** Whether the tender is withdrawn automatically (BO-03-02-18). */
        WITHDRAWAL {
            @Override
            boolean of(TenderDefinition tender) {
                return tender.automaticWithdrawal;
            }
        };

        /**
         * Reads this flag off an administered tender.
         *
         * @param tender the administered row
         * @return the flag's value
         */
        abstract boolean of(TenderDefinition tender);
    }

    /**
     * Returns the signed impact of a cash movement on the drawer: a deposit or
     * a customer down-payment adds cash, a withdrawal or an expense removes it,
     * a cash-count declaration moves nothing. A movement with a null amount has
     * no impact.
     *
     * @param movement the movement to weigh
     * @return the signed amount added to the drawer (negative when it removes cash)
     */
    private BigDecimal cashImpact(CashMovement movement) {
        if (movement.amount == null) {
            return BigDecimal.ZERO;
        }
        return switch (movement.type) {
            case DEPOSIT, CUSTOMER_DEPOSIT -> movement.amount;
            // LC-12-03-02: a withdrawal now names its tender. Only a withdrawal of
            // CASH lowers the cash in the drawer — taking the cheques out leaves the
            // notes where they are. A movement recorded before the tender existed
            // carries none, and those were all cash.
            case WITHDRAWAL -> isCash(movement.paymentMethod)
                    ? movement.amount.negate() : BigDecimal.ZERO;
            case EXPENSE -> movement.amount.negate();
            // LC-12-10-04: a transfer moves an amount from one tender to another. It
            // touches the cash only when cash is one of the two ends, and not at all
            // when it is both.
            case TRANSFER -> transferCashImpact(movement);
            case DECLARATION -> BigDecimal.ZERO;
        };
    }

    /**
     * Applies a movement to the per-tender theoretical of the drawer
     * ({@code LC-12-03-06}, {@code LC-12-10-04}).
     *
     * <p>Only the two movements that NAME a tender act here. A deposit, an expense or
     * a customer down-payment are cash gestures already carried by the cash
     * theoretical, and a declaration counts without moving anything.
     *
     * @param totals   the per-tender totals being built
     * @param movement the movement to apply
     */
    private void applyMethodImpact(Map<String, BigDecimal> totals, CashMovement movement) {
        if (movement.amount == null) {
            return;
        }
        String from = isCash(movement.paymentMethod) ? CashMovement.CASH : movement.paymentMethod;
        if (movement.type == CashMovement.MovementType.WITHDRAWAL) {
            totals.merge(from, movement.amount.negate(), BigDecimal::add);
            return;
        }
        if (movement.type == CashMovement.MovementType.TRANSFER && movement.transferTo != null) {
            totals.merge(from, movement.amount.negate(), BigDecimal::add);
            totals.merge(movement.transferTo, movement.amount, BigDecimal::add);
        }
    }

    /**
     * Returns the signed impact of a transfer on the CASH in the drawer.
     *
     * @param movement the transfer
     * @return the signed amount added to the cash, zero when neither end is cash
     */
    private BigDecimal transferCashImpact(CashMovement movement) {
        boolean fromCash = isCash(movement.paymentMethod);
        boolean toCash = isCash(movement.transferTo);
        if (fromCash == toCash) {
            return BigDecimal.ZERO;
        }
        return fromCash ? movement.amount.negate() : movement.amount;
    }

    /**
     * Tells whether a tender key names the cash, a missing key included.
     *
     * @param method the tender key, possibly null
     * @return true when the tender is the drawer's cash
     */
    private boolean isCash(String method) {
        return method == null || method.isBlank() || CashMovement.CASH.equals(method);
    }

    /**
     * Tells whether one movement date is later than another, a MISSING date reading
     * as the earliest: a row with no date cannot win over a dated one, and two
     * undated rows keep the first the query returned.
     *
     * @param candidate the date of the movement being weighed, possibly null
     * @param current the date of the movement held so far, possibly null
     * @return true when the candidate is the later of the two
     */
    private boolean laterThan(java.time.LocalDateTime candidate, java.time.LocalDateTime current) {
        if (candidate == null) {
            return false;
        }
        return current == null || candidate.isAfter(current);
    }

    /**
     * How many tickets this register would LOSE by closing now (BO-10-02-08).
     *
     * <p>The closing turns every ticket still parked into a cancelled one; this
     * counts them before the fact, so the screen can warn or stop. Counted on
     * the same terminal and the same status the closing itself walks, because a
     * count that disagreed with the deletion would be worse than no count.
     *
     * @return the number of parked tickets on this register, zero when the
     *         session is already closed or none is parked
     */
    public long parkedTicketCount() {
        LOGGER.info("Entering method parkedTicketCount");
        CashSession session = getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method parkedTicketCount");
            return 0;
        }
        long count = Ticket.count("terminalId = ?1 and status = ?2",
                session.terminalId, Ticket.TicketStatus.PARKED);
        LOGGER.info("Exiting method parkedTicketCount");
        return count;
    }

    /**
     * Closes the open session of this register (Z report): stores the counted
     * amount, the denominations detail, the theoretical amount, the variance
     * and the withdrawal, then marks the session closed and journals it.
     *
     * @param cashierId the id of the cashier closing the session
     * @param countedAmount the cash amount counted in the drawer
     * @param withdrawnAmount the cash withdrawn from the drawer
     * @param countDetail the denominations detail as entered (JSON), or null
     * @return the closing report, or null when no session is open
     */
    @Transactional
    public SessionReport closeSession(Long cashierId, BigDecimal countedAmount,
                                      BigDecimal withdrawnAmount, String countDetail) {
        LOGGER.info("Entering method closeSession (delegating, autoDeclaration=false)");
        LOGGER.info("Exiting method closeSession (delegating)");
        return closeSession(cashierId, countedAmount, withdrawnAmount, countDetail, false);
    }

    /**
     * Closes the open session of this register (Z report), telling whether the
     * end-of-period declaration is AUTOMATIC (BO-03-10-10): when it is, the
     * cashier counted nothing, the declared amount is the theoretical, and the
     * journal records that the declaration was automatic so a zero variance
     * cannot be mistaken for a correct hand count.
     *
     * @param cashierId the id of the cashier closing the session
     * @param countedAmount the cash amount counted in the drawer (ignored when
     *        the declaration is automatic)
     * @param withdrawnAmount the cash withdrawn from the drawer
     * @param countDetail the denominations detail as entered (JSON), or null
     * @param autoDeclaration true when the island declares the count
     *        automatically and the counting screen was skipped
     * @return the closing report, or null when no session is open
     */
    @Transactional
    public SessionReport closeSession(Long cashierId, BigDecimal countedAmount,
                                      BigDecimal withdrawnAmount, String countDetail,
                                      boolean autoDeclaration) {
        return closeSession(cashierId, countedAmount, withdrawnAmount, countDetail,
                autoDeclaration, null, null, false);
    }

    /**
     * Whether a date is a FULL-COUNT day — the evening the CASHIER herself is
     * counted and reset (EXP-95). The answer is the administered float mode's, not
     * the calendar's alone:
     *
     * <ul>
     *   <li>{@code FIXED} — a fixed float is counted EVERY evening, so every day is
     *       a full-count day;</li>
     *   <li>{@code FLOATING_WEEK_MONTH} (and its former spelling {@code FLOATING})
     *       — the end of the week (Saturday or Sunday) or the last calendar day of
     *       the month;</li>
     *   <li>{@code FLOATING_MONTH} — the last calendar day of the month only, the
     *       rare case.</li>
     * </ul>
     *
     * <p>The period exists so the revenue of a period matches what was banked for
     * it. On any other evening a floating register is not fully counted and no
     * variance is arbitrated (§7: no controlled tolerance on no-count evenings).
     *
     * <p>The SHOP's own daily reset is a different event — it is the store's day, not
     * the cashier's, and it does not live on the register.
     *
     * @param date the day to test (passed in, never read from a clock here)
     * @return true when the register must be fully counted that evening
     */
    public boolean isFullCountDay(java.time.LocalDate date) {
        LOGGER.info("Entering method isFullCountDay with date: " + date);
        String mode = posSettingsService.floatMode();
        boolean lastOfMonth = date.getDayOfMonth() == date.lengthOfMonth();
        if ("FLOATING_MONTH".equals(mode)) {
            LOGGER.info("Exiting method isFullCountDay");
            return lastOfMonth;
        }
        if (!posSettingsService.floatingFloat()) {
            LOGGER.info("Exiting method isFullCountDay");
            return true;
        }
        java.time.DayOfWeek dow = date.getDayOfWeek();
        boolean endOfWeek = dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY;
        LOGGER.info("Exiting method isFullCountDay");
        return endOfWeek || lastOfMonth;
    }

    /**
     * Closes the open session (Z report), carrying the Step-3 opposable-close
     * data: the supervisor who validated an over-threshold discrepancy
     * ({@code varianceEndorsedBy}, EXP-82), the paper-tender declaration
     * ({@code paperDeclaration}, EXP-83), and whether this is a FLOATING no-count
     * evening ({@code floatingNoCount}, EXP-95) — in which case NO variance is
     * arbitrated and the withdrawal is the surplus above the initial float.
     *
     * @param cashierId the id of the cashier closing the session
     * @param countedAmount the cash counted (ignored on auto declaration / floating no-count)
     * @param withdrawnAmount the cash withdrawn (computed on floating no-count)
     * @param countDetail the denominations detail (JSON), or null
     * @param autoDeclaration true when the island declares the count automatically
     * @param varianceEndorsedBy the supervisor badge validating the discrepancy, or null
     * @param paperDeclaration the paper-tender declaration JSON, or null
     * @param floatingNoCount true on a floating no-count evening
     * @return the closing report, or null when no session is open
     */
    @Transactional
    public SessionReport closeSession(Long cashierId, BigDecimal countedAmount,
                                      BigDecimal withdrawnAmount, String countDetail,
                                      boolean autoDeclaration, String varianceEndorsedBy,
                                      String paperDeclaration, boolean floatingNoCount) {
        LOGGER.info("Entering method closeSession with cashierId: " + cashierId + ", countedAmount: " + countedAmount + ", withdrawnAmount: " + withdrawnAmount + ", countDetail: " + countDetail + ", autoDeclaration: " + autoDeclaration + ", floatingNoCount: " + floatingNoCount);
        CashSession session = getOpenSession();
        if (session == null) {
            LOGGER.warn("Clôture refusée : aucune session ouverte sur cette caisse");
            LOGGER.info("Exiting method closeSession");
            return null;
        }

        SessionReport report = buildReport(session);
        report.closing = true;

        // Parked tickets never resumed die with the session
        List<Ticket> parked = Ticket.list("terminalId = ?1 and status = ?2",
                session.terminalId, Ticket.TicketStatus.PARKED);
        for (Ticket ticket : parked) {
            ticket.status = Ticket.TicketStatus.CANCELLED;
            ticket.persist();
            syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
        }
        if (!parked.isEmpty()) {
            technicalEventService.log(TechnicalEvent.EventType.TICKET_CANCELLED,
                    parked.size() + " ticket(s) en attente annulé(s) à la clôture");
        }

        session.closingDate = LocalDateTime.now();
        session.closingCashier = (cashierId != null) ? Employee.findById(cashierId) : null;
        // BO-03-02-17 (per-tender) and BO-03-10-10 (island-level auto
        // declaration): the cashier counts nothing and the drawer is taken at
        // its theoretical, which is exactly what "déclaration automatique" asks
        // for. Either path declares by the theoretical.
        session.theoreticalAmount = report.theoreticalCash;
        if (floatingNoCount) {
            // EXP-95: FLOATING no-count evening — the bills above the initial float
            // are withdrawn and NO variance is arbitrated. countedAmount is left at
            // the theoretical so the drawer books balance, but the floatingNoCount
            // flag says the zero variance means "not counted", not "counted exact".
            session.countedAmount = report.theoreticalCash;
            session.variance = BigDecimal.ZERO;
            session.floatingNoCount = true;
            session.withdrawnAmount = report.theoreticalCash
                    .subtract(session.openingFloat).max(BigDecimal.ZERO);
        } else {
            session.countedAmount = (autoDeclaration || report.cashDeclaredAutomatically)
                    ? report.theoreticalCash
                    : (countedAmount != null ? countedAmount : BigDecimal.ZERO);
            session.variance = session.countedAmount.subtract(session.theoreticalAmount);
            // BO-03-02-18: a tender in automatic withdrawal has its closing
            // withdrawal COMPUTED — everything above the float leaves the drawer,
            // and a drawer below its float has nothing to withdraw.
            session.withdrawnAmount = report.cashWithdrawnAutomatically
                    ? report.theoreticalCash.subtract(session.openingFloat).max(BigDecimal.ZERO)
                    : (withdrawnAmount != null ? withdrawnAmount : BigDecimal.ZERO);
        }
        session.countDetail = countDetail;
        session.varianceEndorsedBy = varianceEndorsedBy;
        session.paperDeclaration = paperDeclaration;
        session.status = CashSession.SessionStatus.CLOSED;
        session.persist();

        // BO-10-08-13/-14/-15: the register sweeps its own stale instruments
        // here, because it has no scheduler and the close is the one moment it
        // is certainly not selling. A sweep that fails must not fail a close:
        // the session is already written above.
        try {
            storedValueRetentionService.sweep();
        } catch (Exception purgeFailed) {
            LOGGER.warnf("Purge des codes-barres impossible (%s)", purgeFailed.getMessage());
        }

        // BO-03-10-10: an island auto-declaration is named in the journal, else
        // its zero variance would be indistinguishable from a correct count.
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                session.sessionNumber + " écart " + session.variance.toPlainString()
                        + (autoDeclaration ? " - déclaration automatique" : "")
                        + (floatingNoCount ? " - fond flottant sans comptage" : "")
                        + (varianceEndorsedBy != null ? " - écart validé par " + varianceEndorsedBy : ""),
                varianceEndorsedBy);
        syncOutboxService.enqueue(SyncOutbox.EntityType.SESSION, session.id);
        LOGGER.info("Exiting method closeSession");
        return report;
    }
}
