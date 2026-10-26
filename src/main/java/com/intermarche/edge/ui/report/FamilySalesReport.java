package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.VoucherPayment;
import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ÉTAT VENTES PAR FAMILLE (BO-06-04-01): what each article family sold over the
 * period, broken by RAYON (code and label of the rayon, then a subtotal), with
 * every figure the requirement enumerates.
 *
 * <p>How each figure is read from the sold lines, so nobody has to guess:
 * <ul>
 *   <li>« montant des ventes » sums the lines sold, at the price the customer
 *       paid, promotions and discounts deducted; « retours » are the return
 *       lines rung on a ticket, counted and summed in absolute value;
 *       « ventes nettes » is the sales minus the returns;</li>
 *   <li>« remises » are the lines sold below their listed price — the price
 *       before a manual modification, else the unit price — counted and summed
 *       by what they took off; « ventes brutes » is the sales plus those
 *       discounts, the sales at the listed price;</li>
 *   <li>« coupons » are the coupons and vouchers that settled the tickets,
 *       apportioned to the lines of each ticket in proportion of their amount:
 *       « brut » including VAT, « net » excluding the VAT of the line they are
 *       apportioned to;</li>
 *   <li>« e-Commerce » is the sales of the tickets the e-commerce channel
 *       brought (drive, site);</li>
 *   <li>« nombre de clients » is the number of tickets holding the family,
 *       « panier moyen » the net sales per such ticket, and the two shares are
 *       read against the rayon: the family's net sales over the rayon's, its
 *       customers over the rayon's (a ticket holding two families of a rayon is
 *       ONE customer of that rayon);</li>
 *   <li>« annulations » are the lines cancelled over the period.</li>
 * </ul>
 */
@ApplicationScoped
public class FamilySalesReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FamilySalesReport.class);

    /** The precision a discount must exceed to count as one. */
    private static final BigDecimal CENT = new BigDecimal("0.005");

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "family-sales";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État ventes par famille";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-04-01";
    }

    /**
     * {@inheritDoc}
     *
     * @return the sales family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.SALES;
    }

    /**
     * {@inheritDoc}
     *
     * @return every column the requirement enumerates, rayon first
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("rayon", "Code rayon"),
                ReportColumn.text("rayonLabel", "Libellé du rayon"),
                ReportColumn.text("code", "Code famille"),
                ReportColumn.text("label", "Libellé de la famille"),
                ReportColumn.amount("net", "Ventes nettes"),
                ReportColumn.percent("share", "% ventes nettes du rayon"),
                ReportColumn.count("salesCount", "Nombre de ventes"),
                ReportColumn.average("basket", "Panier moyen"),
                ReportColumn.amount("discountAmount", "Montant des remises"),
                ReportColumn.count("discountCount", "Nombre de remises"),
                ReportColumn.amount("sales", "Montant des ventes"),
                ReportColumn.amount("returnAmount", "Montant des retours"),
                ReportColumn.count("returnCount", "Nombre de retours"),
                ReportColumn.amount("cancelledAmount", "Montant des annulations"),
                ReportColumn.count("cancelled", "Nombre d'annulations"),
                ReportColumn.amount("gross", "Ventes brutes"),
                ReportColumn.amount("couponGross", "Montant des coupons brut"),
                ReportColumn.amount("couponNet", "Montant des coupons net"),
                ReportColumn.amount("ecommerce", "Montant e-Commerce"),
                ReportColumn.figure("customers", "Nombre de clients"),
                ReportColumn.percent("customerShare", "% clients du rayon"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the net-sales column, drawn as a bar per family
     */
    @Override
    public int chartColumn() {
        return 4;
    }

    /**
     * {@inheritDoc}
     *
     * @return the family label column, so each bar is named by its family
     */
    @Override
    public int chartLabelColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return the rayon code: a subtotal closes each rayon
     */
    @Override
    public String breakKey() {
        return "rayon";
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island, the hours and the article criteria
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS,
                ReportFilter.ARTICLES);
    }

    /** The running figures of one family. */
    static final class Family {

        /** The rayon code. */
        String rayon;

        /** The family code. */
        String code;

        /** The family label. */
        String label = "";

        /** The sales, discounts deducted. */
        BigDecimal sales = BigDecimal.ZERO;

        /** The number of lines sold. */
        long salesCount;

        /** The discounts granted. */
        BigDecimal discount = BigDecimal.ZERO;

        /** The number of lines discounted. */
        long discountCount;

        /** The returns, in absolute value. */
        BigDecimal returns = BigDecimal.ZERO;

        /** The number of return lines. */
        long returnCount;

        /** The coupons apportioned, VAT included. */
        BigDecimal couponGross = BigDecimal.ZERO;

        /** The coupons apportioned, VAT excluded. */
        BigDecimal couponNet = BigDecimal.ZERO;

        /** The e-commerce sales. */
        BigDecimal ecommerce = BigDecimal.ZERO;

        /** The tickets holding the family. */
        final Set<Object> tickets = new HashSet<>();

        /** The number of cancelled lines. */
        long cancelled;

        /** The amount of the cancelled lines. */
        BigDecimal cancelledAmount = BigDecimal.ZERO;

        /**
         * The net sales: the sales minus the returns.
         *
         * @return the net sales
         */
        BigDecimal net() {
            return sales.subtract(returns);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per family, ordered by rayon then family code
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        NomenclatureLookup nomenclature = NomenclatureLookup.current();
        int rayonRank = nomenclature.rayonRank();
        Map<Object, BigDecimal[]> coupons = coupons(criteria);
        Map<String, Family> families = new TreeMap<>();
        for (Object[] line : sold(criteria)) {
            if (!criteria.inHours((LocalDateTime) line[1])) {
                continue;
            }
            Family family = family(families, nomenclature, rayonRank, (String) line[3], (String) line[4]);
            BigDecimal total = decimal(line[5]);
            family.tickets.add(line[0]);
            if (Boolean.TRUE.equals(line[9])) {
                family.returnCount++;
                family.returns = family.returns.add(total.abs());
                continue;
            }
            family.salesCount++;
            family.sales = family.sales.add(total);
            if (line[2] == SalesChannel.ECOMMERCE) {
                family.ecommerce = family.ecommerce.add(total);
            }
            BigDecimal listed = (line[8] != null && decimal(line[8]).signum() > 0 ? decimal(line[8]) : decimal(line[7]))
                    .multiply(decimal(line[6]));
            BigDecimal cut = listed.subtract(total);
            if (cut.compareTo(CENT) > 0) {
                family.discountCount++;
                family.discount = family.discount.add(cut.setScale(2, RoundingMode.HALF_UP));
            }
            BigDecimal[] coupon = coupons.get(line[0]);
            if (coupon != null && coupon[1].signum() > 0) {
                BigDecimal share = coupon[0].multiply(total).divide(coupon[1], 2, RoundingMode.HALF_UP);
                family.couponGross = family.couponGross.add(share);
                family.couponNet = family.couponNet.add(share.divide(BigDecimal.ONE.add(decimal(line[10])),
                        2, RoundingMode.HALF_UP));
            }
        }
        for (Object[] undone : cancelled(criteria)) {
            if (criteria.inHours((LocalDateTime) undone[2])) {
                Family family = family(families, nomenclature, rayonRank, (String) undone[0], (String) undone[1]);
                family.cancelled++;
                family.cancelledAmount = family.cancelledAmount.add(decimal(undone[3]));
            }
        }
        List<List<Object>> rows = render(families, nomenclature);
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Renders the families, each share read against its rayon.
     *
     * @param families the families, ordered by rayon then family
     * @param nomenclature the nomenclature lookup, for the rayon labels
     * @return the rows
     */
    private List<List<Object>> render(Map<String, Family> families, NomenclatureLookup nomenclature) {
        Map<String, BigDecimal> rayonNet = new HashMap<>();
        Map<String, Set<Object>> rayonTickets = new HashMap<>();
        for (Family family : families.values()) {
            rayonNet.merge(family.rayon, family.net(), BigDecimal::add);
            rayonTickets.computeIfAbsent(family.rayon, key -> new HashSet<>()).addAll(family.tickets);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Family family : families.values()) {
            long customers = family.tickets.size();
            rows.add(Arrays.asList(
                    family.rayon,
                    nomenclature.label(family.rayon),
                    family.code,
                    family.label,
                    family.net(),
                    share(family.net(), rayonNet.get(family.rayon)),
                    family.salesCount,
                    customers == 0 ? BigDecimal.ZERO
                            : family.net().divide(BigDecimal.valueOf(customers), 2, RoundingMode.HALF_UP),
                    family.discount,
                    family.discountCount,
                    family.sales,
                    family.returns,
                    family.returnCount,
                    family.cancelledAmount,
                    family.cancelled,
                    family.sales.add(family.discount),
                    family.couponGross,
                    family.couponNet,
                    family.ecommerce,
                    customers,
                    share(BigDecimal.valueOf(customers), BigDecimal.valueOf(rayonTickets.get(family.rayon).size()))));
        }
        return rows;
    }

    /**
     * Finds or opens a family's figures, filed under its rayon.
     *
     * @param families the families, keyed by rayon then family code
     * @param nomenclature the nomenclature lookup
     * @param rayonRank the rank of the rayon level
     * @param code the family code, possibly null
     * @param label the family label, possibly null
     * @return the family's figures
     */
    private Family family(Map<String, Family> families, NomenclatureLookup nomenclature, int rayonRank,
                          String code, String label) {
        String familyCode = code == null ? "—" : code;
        String rayon = code == null ? "—" : nomenclature.ancestor(code, rayonRank);
        Family family = families.computeIfAbsent(rayon + "\u0000" + familyCode, key -> new Family());
        family.rayon = rayon;
        family.code = familyCode;
        if (family.label.isEmpty() && label != null) {
            family.label = label;
        }
        return family;
    }

    /**
     * Reads the lines sold over the period.
     *
     * @param criteria the edition parameters
     * @return per line: ticket id, closing date, channel, family code, family
     *         label, total, quantity, unit price, original unit price, return
     *         flag, VAT rate
     */
    private List<Object[]> sold(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select t.id, t.closingDate, t.salesChannel, l.familyCode, l.familyLabel, l.totalPrice,"
                                + " l.quantity, l.unitPrice, l.originalUnitPrice, l.returnLine, l.vatRate"
                                + " from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to and l.cancelled = false"
                                + criteria.terminalFilter("t.terminalId") + criteria.lineFilter("l"), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindLines(query);
        return query.getResultList();
    }

    /**
     * Reads the coupons that settled the tickets of the period, beside the
     * amount of each ticket's lines they are apportioned over.
     *
     * @param criteria the edition parameters
     * @return per ticket id: the coupons' amount and the ticket's sold total
     */
    private Map<Object, BigDecimal[]> coupons(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select t.id, sum(p.amount), t.totalIncludingTax from Ticket t join t.payments p"
                                + " where type(p) = :voucher and t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to" + criteria.terminalFilter("t.terminalId")
                                + " group by t.id, t.totalIncludingTax", Object[].class)
                .setParameter("voucher", VoucherPayment.class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        Map<Object, BigDecimal[]> coupons = new HashMap<>();
        for (Object[] row : query.getResultList()) {
            coupons.put(row[0], new BigDecimal[]{decimal(row[1]), decimal(row[2])});
        }
        return coupons;
    }

    /**
     * Reads the lines cancelled over the period, per family.
     *
     * @param criteria the edition parameters
     * @return per cancelled line: family code, family label, moment, amount
     */
    private List<Object[]> cancelled(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select l.familyCode, l.familyLabel, l.cancellationDate, l.totalPrice"
                                + " from Ticket t join t.lines l"
                                + " where l.cancelled = true and l.cancellationDate >= :from"
                                + " and l.cancellationDate < :to" + criteria.terminalFilter("t.terminalId")
                                + criteria.lineFilter("l"), Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindLines(query);
        return query.getResultList();
    }

    /**
     * Reads a decimal cell, null as zero.
     *
     * @param value the cell
     * @return the decimal
     */
    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    /**
     * Computes a share of a whole.
     *
     * @param part the part
     * @param whole the whole
     * @return the share in percent, zero when the whole is zero
     */
    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }
}
