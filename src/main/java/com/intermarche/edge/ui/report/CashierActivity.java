package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.ui.PriceModType;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What every CASHIER did over a period, measured once for the three cashier
 * states that read it: the cashier state (BO-06-06-03), the performance profile
 * (BO-06-06-02) and the weekly synthesis (BO-06-06-08).
 *
 * <p>Every figure is READ, none is estimated. The register dates the first
 * article of a ticket, the moment the cashier went to payment and the closing;
 * it journals its unlocks and locks — telling the cashier's own lock from the
 * idle one — the sessions it opens and closes, the drawer it opens out of a
 * sale and the cash operations it times; the node adds them up per cashier:
 * <ul>
 *   <li>customer time: from the first article to the closing; scanning: from
 *       the first article to the payment; payment: from the payment to the
 *       closing; other: what the customer time holds beyond the two;</li>
 *   <li>session time: the cash sessions the cashier opened, cut to the period;
 *       active time: from each unlock to the next lock of the same register;
 *       idle time: the active time the customers did not fill, of which the
 *       timed cash operations;</li>
 *   <li>cancellations: the tickets cancelled, the last lines cancelled (« ligne
 *       »), the other lines cancelled (« articles »), the refunds made and the
 *       prices forced;</li>
 *   <li>by tender: what was taken, withdrawn, brought in and paid out, and the
 *       float of the sessions opened.</li>
 * </ul>
 *
 * <p>A ticket is the cashier's whose badge it carries; a session, the cashier's
 * who opened it; an event, the cashier's it names.
 */
public final class CashierActivity {

    /** The payment keys of a cheque. */
    static final List<String> CHEQUES = List.of("CHEQUE", "CHEQUE_DEFERRED");

    /** The payment keys of a food voucher. */
    static final List<String> FOOD_VOUCHERS = List.of("TR", "TRD", "RESTOFLASH");

    /** The payment key of a coupon. */
    static final String COUPON = "VOUCHER";

    /**
     * The French names of the register's payment keys, for a tender the shop
     * did not administer: a state never shows a bare technical key.
     */
    static final Map<String, String> TENDER_NAMES = Map.ofEntries(
            Map.entry("CASH", "Espèces"),
            Map.entry("CARD", "Carte bancaire"),
            Map.entry("CHEQUE", "Chèque"),
            Map.entry("CHEQUE_DEFERRED", "Chèque différé"),
            Map.entry("TR", "Titres-restaurant"),
            Map.entry("TRD", "Titre-restaurant dématérialisé"),
            Map.entry("RESTOFLASH", "Restoflash"),
            Map.entry("VOUCHER", "Bon d'achat"),
            Map.entry("GIFTCARD", "Carte cadeau"),
            Map.entry("FIDELITY", "Fidélité"),
            Map.entry("CREDIT", "Crédit client"),
            Map.entry("ACOMPTE", "Acompte"),
            Map.entry("DEVISE", "Devise"),
            Map.entry("ARRONDI", "Arrondi"),
            Map.entry("SECOURS", "Paiement de secours"),
            Map.entry("SOGEC", "Coupons SOGEC"),
            Map.entry("CAS", "Carte d'aide sociale"));

    /** One closed ticket, as the performance states read it. */
    public static final class Basket {

        /** The articles sold. */
        public long items;

        /** The amount paid. */
        public BigDecimal amount = BigDecimal.ZERO;

        /** From the first article to the closing, in seconds. */
        public long customerSeconds;

        /** From the first article to the payment, in seconds. */
        public long scanSeconds;

        /** From the payment to the closing, in seconds. */
        public long paySeconds;

        /** The coupons that settled it. */
        public long coupons;

        /** Whether a cheque settled it. */
        public boolean cheque;

        /** Whether a food voucher settled it. */
        public boolean foodVoucher;

        /** When it closed. */
        public LocalDateTime closedAt;
    }

    /** The figures of one cashier. */
    public static final class Figures {

        /** The cashier's badge. */
        public String badge = "";

        /** The cashier's first name. */
        public String firstName = "";

        /** The cashier's last name. */
        public String lastName = "";

        /** The closed tickets. */
        public final List<Basket> baskets = new ArrayList<>();

        /** The amount sold, discounts deducted, returns excluded. */
        public BigDecimal sales = BigDecimal.ZERO;

        /** The return lines. */
        public long returnCount;

        /**
         * The ARTICLES the return lines take back ({@code LS-03-04-04}): units of a
         * line returned by the piece, one per weighed or measured line.
         */
        public long returnArticles;

        /** The return lines' amount, in absolute value. */
        public BigDecimal returns = BigDecimal.ZERO;

        /** The lines sold below their listed price. */
        public long discountCount;

        /** What those discounts took off. */
        public BigDecimal discounts = BigDecimal.ZERO;

        /** The amount the closed tickets took. */
        public BigDecimal takings = BigDecimal.ZERO;

        /** The VAT of the closed tickets. */
        public final VatBreakdown vat = new VatBreakdown();

        /** What each tender took, by payment key. */
        public final Map<String, BigDecimal> tenders = new TreeMap<>();

        /** How many settlements each tender took, by payment key. */
        public final Map<String, Long> tenderCounts = new TreeMap<>();

        /** What was withdrawn, by tender. */
        public final Map<String, BigDecimal> withdrawals = new TreeMap<>();

        /** What was brought in. */
        public BigDecimal contributions = BigDecimal.ZERO;

        /** What was paid out. */
        public BigDecimal outflows = BigDecimal.ZERO;

        /** The float of the sessions opened. */
        public BigDecimal floats = BigDecimal.ZERO;

        /** The drawer opened outside a sale. */
        public long drawerOpenings;

        /** The locks the cashier made. */
        public long manualLocks;

        /** The locks the idle delay made. */
        public long automaticLocks;

        /** The sessions the cashier closed. */
        public long closings;

        /** The cash discrepancy of the sessions the cashier closed. */
        public BigDecimal variance = BigDecimal.ZERO;

        /** The time of the sessions opened, in seconds. */
        public long sessionSeconds;

        /** The time spent unlocked, in seconds. */
        public long activeSeconds;

        /** The time spent on timed cash operations, in seconds. */
        public long cashOperationSeconds;

        /** The tickets cancelled. */
        public long ticketCancels;

        /** Their amount. */
        public BigDecimal ticketCancelAmount = BigDecimal.ZERO;

        /** The last lines cancelled. */
        public long lineCancels;

        /** Their amount. */
        public BigDecimal lineCancelAmount = BigDecimal.ZERO;

        /** The other lines cancelled. */
        public long articleCancels;

        /** Their amount. */
        public BigDecimal articleCancelAmount = BigDecimal.ZERO;

        /** The refunds made. */
        public long refunds;

        /** Their amount. */
        public BigDecimal refundAmount = BigDecimal.ZERO;

        /** The prices forced. */
        public long forcings;

        /** What the forcings moved the price by. */
        public BigDecimal forcingAmount = BigDecimal.ZERO;

        /** The non-merchandise sales: gift cards and other money products. */
        public BigDecimal nonMerchandise = BigDecimal.ZERO;

        /** The sales taxed at a positive rate. */
        public BigDecimal taxable = BigDecimal.ZERO;

        /** The VAT of the sales, returns excluded. */
        public BigDecimal grossVat = BigDecimal.ZERO;

        /** The VAT of the return lines. */
        public BigDecimal returnVat = BigDecimal.ZERO;

        /** The lines exempt of VAT. */
        public long exemptCount;

        /** Their amount. */
        public BigDecimal exempt = BigDecimal.ZERO;

        /** The sales the e-commerce channel brought. */
        public BigDecimal ecommerce = BigDecimal.ZERO;

        /** The articles scanned. */
        public long scannedCount;

        /** Their amount. */
        public BigDecimal scanned = BigDecimal.ZERO;

        /** The articles keyed. */
        public long keyedCount;

        /** Their amount. */
        public BigDecimal keyed = BigDecimal.ZERO;

        /** The discounts granted on the whole ticket. */
        public long globalDiscountCount;

        /** Their amount. */
        public BigDecimal globalDiscounts = BigDecimal.ZERO;

        /** The withdrawals made. */
        public long withdrawalCount;

        /** The expenses paid out. */
        public long outflowCount;

        /** The down-payments cashed. */
        public long depositCount;

        /** Their amount. */
        public BigDecimal deposits = BigDecimal.ZERO;

        /** The VAT of the refunds. */
        public BigDecimal refundVat = BigDecimal.ZERO;

        /**
         * Returns the cashier's name.
         *
         * @return first then last name
         */
        public String name() {
            return (firstName + " " + lastName).trim();
        }

        /**
         * Returns the articles sold.
         *
         * @return the sum over the closed tickets
         */
        public long items() {
            long items = 0;
            for (Basket basket : baskets) {
                items += basket.items;
            }
            return items;
        }

        /**
         * Returns the customer time.
         *
         * @return the sum over the closed tickets, in seconds
         */
        public long customerSeconds() {
            long seconds = 0;
            for (Basket basket : baskets) {
                seconds += basket.customerSeconds;
            }
            return seconds;
        }

        /**
         * Returns the scanning time.
         *
         * @return the sum over the closed tickets, in seconds
         */
        public long scanSeconds() {
            long seconds = 0;
            for (Basket basket : baskets) {
                seconds += basket.scanSeconds;
            }
            return seconds;
        }

        /**
         * Returns the payment time.
         *
         * @return the sum over the closed tickets, in seconds
         */
        public long paySeconds() {
            long seconds = 0;
            for (Basket basket : baskets) {
                seconds += basket.paySeconds;
            }
            return seconds;
        }

        /**
         * Adds another cashier's figures to these, for the global recap.
         *
         * @param other the other cashier's figures
         */
        void add(Figures other) {
            baskets.addAll(other.baskets);
            sales = sales.add(other.sales);
            returnCount += other.returnCount;
            returnArticles += other.returnArticles;
            returns = returns.add(other.returns);
            discountCount += other.discountCount;
            discounts = discounts.add(other.discounts);
            takings = takings.add(other.takings);
            for (VatBreakdown.Bucket bucket : other.vat.getBuckets()) {
                vat.add(bucket.rate, bucket.number, bucket.totalIncludingTax);
            }
            other.tenders.forEach((key, amount) -> tenders.merge(key, amount, BigDecimal::add));
            other.tenderCounts.forEach((key, count) -> tenderCounts.merge(key, count, Long::sum));
            other.withdrawals.forEach((key, amount) -> withdrawals.merge(key, amount, BigDecimal::add));
            contributions = contributions.add(other.contributions);
            outflows = outflows.add(other.outflows);
            floats = floats.add(other.floats);
            drawerOpenings += other.drawerOpenings;
            manualLocks += other.manualLocks;
            automaticLocks += other.automaticLocks;
            closings += other.closings;
            variance = variance.add(other.variance);
            sessionSeconds += other.sessionSeconds;
            activeSeconds += other.activeSeconds;
            cashOperationSeconds += other.cashOperationSeconds;
            ticketCancels += other.ticketCancels;
            ticketCancelAmount = ticketCancelAmount.add(other.ticketCancelAmount);
            lineCancels += other.lineCancels;
            lineCancelAmount = lineCancelAmount.add(other.lineCancelAmount);
            articleCancels += other.articleCancels;
            articleCancelAmount = articleCancelAmount.add(other.articleCancelAmount);
            refunds += other.refunds;
            refundAmount = refundAmount.add(other.refundAmount);
            forcings += other.forcings;
            forcingAmount = forcingAmount.add(other.forcingAmount);
            nonMerchandise = nonMerchandise.add(other.nonMerchandise);
            taxable = taxable.add(other.taxable);
            grossVat = grossVat.add(other.grossVat);
            returnVat = returnVat.add(other.returnVat);
            exemptCount += other.exemptCount;
            exempt = exempt.add(other.exempt);
            ecommerce = ecommerce.add(other.ecommerce);
            scannedCount += other.scannedCount;
            scanned = scanned.add(other.scanned);
            keyedCount += other.keyedCount;
            keyed = keyed.add(other.keyed);
            globalDiscountCount += other.globalDiscountCount;
            globalDiscounts = globalDiscounts.add(other.globalDiscounts);
            withdrawalCount += other.withdrawalCount;
            outflowCount += other.outflowCount;
            depositCount += other.depositCount;
            deposits = deposits.add(other.deposits);
            refundVat = refundVat.add(other.refundVat);
        }

        /**
         * Returns the idle time: the active time no customer filled.
         *
         * @return the seconds, never negative
         */
        public long idleSeconds() {
            return Math.max(0, activeSeconds - customerSeconds());
        }
    }

    /** The figures, by badge, in badge order. */
    final Map<String, Figures> byBadge = new TreeMap<>();

    /** The edition parameters. */
    private final ReportCriteria criteria;

    /**
     * Builds an empty measure.
     *
     * @param criteria the edition parameters
     */
    CashierActivity(ReportCriteria criteria) {
        this.criteria = criteria;
    }

    /**
     * Returns the figures of every cashier together: the global recap.
     *
     * @return the summed figures, named « Toutes les caissières »
     */
    public Figures total() {
        Figures total = new Figures();
        total.lastName = "Toutes les caissières";
        for (Figures figures : byBadge.values()) {
            total.add(figures);
        }
        return total;
    }

    /**
     * Returns the figures of every cashier the criteria keep.
     *
     * @return the figures, in badge order
     */
    public List<Figures> cashiers() {
        return new ArrayList<>(byBadge.values());
    }

    /**
     * Measures the period.
     *
     * @param entityManager the persistence session
     * @param criteria the edition parameters
     * @return the measure
     */
    public static CashierActivity load(EntityManager entityManager, ReportCriteria criteria) {
        CashierActivity activity = new CashierActivity(criteria);
        activity.addTickets(list(entityManager, criteria, Ticket.class,
                "select t from Ticket t where t.status = :closed and t.closingDate >= :from"
                        + " and t.closingDate < :to" + criteria.terminalFilter("t.terminalId")
                        + criteria.cashierFilter("t.cashier.badgeId") + " order by t.closingDate",
                "closed", Ticket.TicketStatus.CLOSED));
        activity.addCancelledTickets(list(entityManager, criteria, Ticket.class,
                "select t from Ticket t where t.status = :cancelled and t.creationDate >= :from"
                        + " and t.creationDate < :to" + criteria.terminalFilter("t.terminalId")
                        + criteria.cashierFilter("t.cashier.badgeId"),
                "cancelled", Ticket.TicketStatus.CANCELLED));
        activity.addCancelledLines(list(entityManager, criteria, TicketLine.class,
                "select l from Ticket t join t.lines l where l.cancelled = true and l.cancellationDate >= :from"
                        + " and l.cancellationDate < :to" + criteria.terminalFilter("t.terminalId"),
                null, null));
        activity.addRefunds(list(entityManager, criteria, Refund.class,
                "select r from Refund r where r.creationDate >= :from and r.creationDate < :to"
                        + criteria.terminalFilter("r.terminalId"),
                null, null));
        activity.addSessions(list(entityManager, criteria, CashSession.class,
                "select s from CashSession s where s.openingDate < :to"
                        + " and (s.closingDate is null or s.closingDate >= :from)"
                        + criteria.terminalFilter("s.terminalId"),
                null, null));
        activity.addMovements(list(entityManager, criteria, CashMovement.class,
                "select m from CashMovement m where m.movementDate >= :from and m.movementDate < :to"
                        + criteria.terminalFilter("m.terminalId"),
                null, null));
        activity.addEvents(list(entityManager, criteria, TechnicalEvent.class,
                "select e from TechnicalEvent e where e.eventType in :types and e.eventDate >= :from"
                        + " and e.eventDate < :to" + criteria.terminalFilter("e.terminalId")
                        + " order by e.eventDate",
                "types", List.of(TechnicalEvent.EventType.REGISTER_UNLOCKED, TechnicalEvent.EventType.REGISTER_LOCKED,
                        TechnicalEvent.EventType.SESSION_CLOSED, TechnicalEvent.EventType.DRAWER_FORCED_OPEN,
                        TechnicalEvent.EventType.CASH_OPERATION_TIMED)));
        return activity;
    }

    /**
     * Runs one query of the period.
     *
     * @param <T> the entity type
     * @param entityManager the persistence session
     * @param criteria the edition parameters
     * @param type the entity class
     * @param jpql the query, bound on {@code from} and {@code to}
     * @param name the name of one more parameter, or null
     * @param value its value
     * @return the entities
     */
    private static <T> List<T> list(EntityManager entityManager, ReportCriteria criteria, Class<T> type,
                                    String jpql, String name, Object value) {
        var query = entityManager.createQuery(jpql, type)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (name != null) {
            query.setParameter(name, value);
        }
        criteria.bindTerminals(query);
        if (jpql.contains(":cashierBadges")) {
            criteria.bindCashiers(query);
        }
        return query.getResultList();
    }

    /**
     * Finds or opens a cashier's figures, unless the selection leaves the
     * cashier out.
     *
     * @param badge the badge, possibly null
     * @param employee the employee, possibly null, for the name
     * @return the figures, or null when the cashier is not kept
     */
    Figures of(String badge, Employee employee) {
        String key = badge == null ? "" : badge;
        if (!criteria.keepsCashier(key)) {
            return null;
        }
        Figures figures = byBadge.computeIfAbsent(key, k -> {
            Figures fresh = new Figures();
            fresh.badge = k;
            return fresh;
        });
        if (employee != null && figures.lastName.isEmpty()) {
            figures.firstName = employee.firstName == null ? "" : employee.firstName;
            figures.lastName = employee.lastName == null ? "" : employee.lastName;
        }
        return figures;
    }

    /**
     * Adds the closed tickets.
     *
     * @param tickets the tickets
     */
    void addTickets(List<Ticket> tickets) {
        for (Ticket ticket : tickets) {
            if (!criteria.inHours(ticket.closingDate)) {
                continue;
            }
            Figures figures = of(ticket.cashier == null ? null : ticket.cashier.badgeId, ticket.cashier);
            if (figures == null) {
                continue;
            }
            Basket basket = new Basket();
            basket.closedAt = ticket.closingDate;
            basket.amount = value(ticket.totalIncludingTax);
            basket.items = ticket.itemCount;
            basket.customerSeconds = seconds(ticket.creationDate, ticket.closingDate);
            basket.scanSeconds = seconds(ticket.creationDate,
                    ticket.paymentStartedAt == null ? ticket.closingDate : ticket.paymentStartedAt);
            basket.paySeconds = seconds(ticket.paymentStartedAt, ticket.closingDate);
            for (TicketLine line : ticket.lines) {
                addLine(figures, line);
            }
            if (ticket.salesChannel == com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE) {
                figures.ecommerce = figures.ecommerce.add(basket.amount);
            }
            if (ticket.globalDiscountApplied != null && ticket.globalDiscountApplied.signum() != 0) {
                figures.globalDiscountCount++;
                figures.globalDiscounts = figures.globalDiscounts.add(ticket.globalDiscountApplied.abs());
            }
            for (TicketPayment payment : ticket.payments) {
                String key = payment.getMethodKey();
                figures.tenders.merge(key, value(payment.amount), BigDecimal::add);
                figures.tenderCounts.merge(key, 1L, Long::sum);
                basket.coupons += COUPON.equals(key) ? 1 : 0;
                basket.cheque |= CHEQUES.contains(key);
                basket.foodVoucher |= FOOD_VOUCHERS.contains(key);
            }
            figures.takings = figures.takings.add(basket.amount);
            figures.baskets.add(basket);
        }
    }

    /**
     * Adds one line of a closed ticket.
     *
     * @param figures the cashier's figures
     * @param line the line
     */
    private void addLine(Figures figures, TicketLine line) {
        if (line.cancelled) {
            return;
        }
        BigDecimal total = value(line.totalPrice);
        figures.vat.add(line.vatRate, line.vatNumber, total);
        BigDecimal rate = value(line.vatRate);
        BigDecimal tax = total.subtract(total.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP));
        if (line.returnLine) {
            figures.returnCount++;
            figures.returnArticles += line.articleUnits();
            figures.returns = figures.returns.add(total.abs());
            figures.returnVat = figures.returnVat.add(tax.abs());
            return;
        }
        figures.sales = figures.sales.add(total);
        figures.grossVat = figures.grossVat.add(tax);
        if (line.moneyProduct) {
            figures.nonMerchandise = figures.nonMerchandise.add(total);
        }
        if (line.vatExempt) {
            figures.exemptCount++;
            figures.exempt = figures.exempt.add(total);
        } else if (rate.signum() > 0) {
            figures.taxable = figures.taxable.add(total);
        }
        if (!line.scanned) {
            figures.keyedCount++;
            figures.keyed = figures.keyed.add(total);
        } else {
            figures.scannedCount++;
            figures.scanned = figures.scanned.add(total);
        }
        BigDecimal listed = (line.originalUnitPrice != null && line.originalUnitPrice.signum() > 0
                ? line.originalUnitPrice : value(line.unitPrice)).multiply(value(line.quantity));
        BigDecimal cut = listed.subtract(total).setScale(2, RoundingMode.HALF_UP);
        if (line.modifierType == PriceModType.FORCE_PRICE) {
            figures.forcings++;
            figures.forcingAmount = figures.forcingAmount.add(cut);
        } else if (cut.signum() > 0) {
            figures.discountCount++;
            figures.discounts = figures.discounts.add(cut);
        }
    }

    /**
     * Adds the cancelled tickets.
     *
     * @param tickets the tickets
     */
    void addCancelledTickets(List<Ticket> tickets) {
        for (Ticket ticket : tickets) {
            Figures figures = of(ticket.cashier == null ? null : ticket.cashier.badgeId, ticket.cashier);
            if (figures != null) {
                figures.ticketCancels++;
                figures.ticketCancelAmount = figures.ticketCancelAmount.add(value(ticket.totalIncludingTax));
            }
        }
    }

    /**
     * Adds the cancelled lines, the last line apart from the others.
     *
     * @param lines the lines
     */
    void addCancelledLines(List<TicketLine> lines) {
        for (TicketLine line : lines) {
            Figures figures = of(line.cancelledBy, null);
            if (figures == null) {
                continue;
            }
            if (line.cancelledLast) {
                figures.lineCancels++;
                figures.lineCancelAmount = figures.lineCancelAmount.add(value(line.totalPrice));
            } else {
                figures.articleCancels++;
                figures.articleCancelAmount = figures.articleCancelAmount.add(value(line.totalPrice));
            }
        }
    }

    /**
     * Adds the refunds.
     *
     * @param refunds the refunds
     */
    void addRefunds(List<Refund> refunds) {
        for (Refund refund : refunds) {
            Figures figures = of(refund.cashierBadgeId, null);
            if (figures != null) {
                figures.refunds++;
                figures.refundAmount = figures.refundAmount.add(value(refund.totalAmount).abs());
                figures.refundVat = figures.refundVat.add(value(refund.totalVat).abs());
            }
        }
    }

    /**
     * Adds the cash sessions: their time to who opened them, their float, and
     * their closing and discrepancy to who closed them.
     *
     * @param sessions the sessions overlapping the period
     */
    void addSessions(List<CashSession> sessions) {
        LocalDateTime now = LocalDateTime.now();
        for (CashSession session : sessions) {
            Figures opener = of(session.openingCashier == null ? null : session.openingCashier.badgeId,
                    session.openingCashier);
            if (opener != null) {
                LocalDateTime start = later(session.openingDate, criteria.from());
                LocalDateTime end = earlier(session.closingDate == null ? now : session.closingDate, criteria.to());
                opener.sessionSeconds += seconds(start, end);
                if (!session.openingDate.isBefore(criteria.from())) {
                    opener.floats = opener.floats.add(value(session.openingFloat));
                }
            }
            if (session.closingDate != null && !session.closingDate.isBefore(criteria.from())
                    && session.closingDate.isBefore(criteria.to())) {
                Figures closer = of(session.closingCashier == null ? null : session.closingCashier.badgeId,
                        session.closingCashier);
                if (closer != null) {
                    closer.closings++;
                    closer.variance = closer.variance.add(value(session.variance));
                }
            }
        }
    }

    /**
     * Adds the cash movements.
     *
     * @param movements the movements
     */
    void addMovements(List<CashMovement> movements) {
        for (CashMovement movement : movements) {
            Figures figures = of(movement.cashier == null ? null : movement.cashier.badgeId, movement.cashier);
            if (figures == null) {
                continue;
            }
            BigDecimal amount = value(movement.amount).abs();
            switch (movement.type) {
                case WITHDRAWAL -> {
                    figures.withdrawalCount++;
                    figures.withdrawals.merge(movement.paymentMethod == null ? CashMovement.CASH
                            : movement.paymentMethod, amount, BigDecimal::add);
                }
                case DEPOSIT -> figures.contributions = figures.contributions.add(amount);
                case EXPENSE -> {
                    figures.outflowCount++;
                    figures.outflows = figures.outflows.add(amount);
                }
                case CUSTOMER_DEPOSIT -> {
                    figures.depositCount++;
                    figures.deposits = figures.deposits.add(amount);
                }
                default -> {
                }
            }
        }
    }

    /**
     * Adds the journaled events: the active intervals between an unlock and the
     * next lock of the same register, the locks, the drawer openings and the
     * timed cash operations.
     *
     * @param events the events, in chronological order
     */
    void addEvents(List<TechnicalEvent> events) {
        Map<String, TechnicalEvent> unlocked = new HashMap<>();
        for (TechnicalEvent event : events) {
            Figures figures = ofOperator(event.operatorBadgeId);
            switch (event.eventType) {
                case REGISTER_UNLOCKED -> {
                    close(unlocked.remove(event.terminalId), event.eventDate);
                    unlocked.put(event.terminalId, event);
                }
                case REGISTER_LOCKED, SESSION_CLOSED -> {
                    close(unlocked.remove(event.terminalId), event.eventDate);
                    if (figures != null && event.eventType == TechnicalEvent.EventType.REGISTER_LOCKED) {
                        figures.manualLocks += TechnicalEvent.LOCK_MANUAL.equals(event.detail) ? 1 : 0;
                        figures.automaticLocks += TechnicalEvent.LOCK_AUTOMATIC.equals(event.detail) ? 1 : 0;
                    }
                }
                case DRAWER_FORCED_OPEN -> {
                    if (figures != null) {
                        figures.drawerOpenings++;
                    }
                }
                default -> {
                    if (figures != null) {
                        figures.cashOperationSeconds += timed(event.detail);
                    }
                }
            }
        }
        LocalDateTime end = earlier(LocalDateTime.now(), criteria.to());
        for (TechnicalEvent open : unlocked.values()) {
            close(open, end);
        }
    }

    /**
     * Closes an active interval opened by an unlock.
     *
     * @param unlock the unlock, or null when the register was not unlocked
     * @param end the end of the interval
     */
    private void close(TechnicalEvent unlock, LocalDateTime end) {
        if (unlock == null) {
            return;
        }
        Figures figures = ofOperator(unlock.operatorBadgeId);
        if (figures != null) {
            figures.activeSeconds += seconds(unlock.eventDate, end);
        }
    }

    /**
     * Finds the figures of the operator an event names. An event naming nobody
     * — a call, a survey answer, a machine event — belongs to no cashier: it
     * must not open a nameless cashier whose zeros would drag the store's
     * averages down.
     *
     * @param badge the event's operator badge, possibly null or blank
     * @return the figures, or null when the event names nobody or the cashier is not kept
     */
    private Figures ofOperator(String badge) {
        return badge == null || badge.isBlank() ? null : of(badge, null);
    }

    /**
     * Reads the seconds of a timed cash operation, {@code kind;seconds}.
     *
     * @param detail the event's detail, possibly null or malformed
     * @return the seconds, zero when unreadable
     */
    static long timed(String detail) {
        if (detail == null || detail.indexOf(';') < 0) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(detail.substring(detail.indexOf(';') + 1).trim()));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    /**
     * Counts the seconds between two moments.
     *
     * @param from the first moment, possibly null
     * @param to the second moment, possibly null
     * @return the seconds, zero when a moment is missing or the order inverted
     */
    static long seconds(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0;
        }
        return Math.max(0, Duration.between(from, to).getSeconds());
    }

    /**
     * The later of two moments.
     *
     * @param a a moment
     * @param b another moment
     * @return the later one
     */
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    /**
     * The earlier of two moments.
     *
     * @param a a moment
     * @param b another moment
     * @return the earlier one
     */
    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * Reads an amount, null as zero.
     *
     * @param amount the amount
     * @return the amount, or zero
     */
    static BigDecimal value(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /**
     * Names a tender by its administered label, so a state never shows a bare
     * payment key (BO-06-06-03, BO-06-06-08).
     *
     * @param key the payment key
     * @return the administered label, else the key's French name, else the key
     */
    static String tenderLabel(String key) {
        com.intermarche.edge.domain.payment.TenderDefinition tender =
                com.intermarche.edge.domain.payment.TenderDefinition.find("code", key).firstResult();
        return tender == null || tender.label == null || tender.label.isBlank()
                ? TENDER_NAMES.getOrDefault(key, key) : tender.label;
    }

    /**
     * Renders a duration in seconds as hours, minutes and seconds.
     *
     * @param seconds the duration
     * @return the text, for example {@code 01:05:09}
     */
    public static String clock(long seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
}
