package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * ÉTAT VENTES MAGASIN (BO-06-04-05): the SHOP's sales report over a day, a
 * week, a month, a year or a range — what the accountants check the GADM
 * against: first part, every indicator the requirement enumerates, with its
 * count where it has one and its amount; second part, the detail by tender —
 * number of settlements, value and cash discrepancy.
 *
 * <p>Consolidated over every register and every cashier the criteria keep,
 * from the same measure as the cashier states ({@link CashierActivity}), so the
 * shop's figures are the sum of the cashiers'. How the indicators are read:
 * net sales are the takings of the closed tickets less the refunds; the
 * non-merchandise sales are the money products (gift cards); the taxable sales
 * are the lines taxed at a positive rate, the exempt ones are counted apart;
 * the gross VAT is the tax of the lines sold, the VAT on returns that of the
 * return lines and refunds; the school-mode sales are the training tickets;
 * the refunded promotions are the advantages the valuation engine granted on
 * the lines; the drive sales are the tickets of the e-commerce channel; the
 * miscellaneous payments are the down-payments cashed; the scanned and keyed
 * articles are counted as the register rang them.
 *
 * <p>The cash discrepancy is the one the counts of the sessions closed over the
 * period found: it is a CASH figure, the other tenders being reconciled by
 * their own acquirers, and reads zero on them.
 */
@ApplicationScoped
public class StoreSalesReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoreSalesReport.class);

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "store-sales";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État ventes magasin";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-04-05";
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
     * @return the column the chart draws (BO-06-01-04)
     */
    @Override
    public int chartColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return the column naming each bar
     */
    @Override
    public int chartLabelColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @return the part, the indicator, its count, its amount and the discrepancy
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("part", "Partie"),
                ReportColumn.text("indicator", "Indicateur"),
                ReportColumn.figure("count", "Nombre"),
                ReportColumn.average("amount", "Montant"),
                ReportColumn.decimal("value", "Valeur"),
                ReportColumn.average("variance", "Écart de caisse"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers and the island
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return the indicators, then the tenders
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        CashierActivity.Figures f = CashierActivity.load(entityManager, criteria).total();
        long customers = f.baskets.size();
        long items = f.items();
        String sales = "Ventes";
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row(sales, "Ventes nettes", null, f.takings.subtract(f.refundAmount)));
        rows.add(row(sales, "Ventes non marchandes", null, f.nonMerchandise));
        rows.add(row(sales, "Paiements divers", f.depositCount, f.deposits));
        rows.add(row(sales, "Ventes imposables", null, f.taxable));
        rows.add(row(sales, "Ventes brutes", null, f.sales.add(f.discounts)));
        rows.add(row(sales, "TVA brute", null, f.grossVat));
        rows.add(row(sales, "TVA sur retours", f.refunds + f.returnCount, f.refundVat.add(f.returnVat)));
        rows.add(row(sales, "TVA exonérée", f.exemptCount, f.exempt));
        Object[] training = training(criteria);
        rows.add(row(sales, "Ventes mode école", (Long) training[0], (BigDecimal) training[1]));
        rows.add(row(sales, "Annulations", f.ticketCancels + f.lineCancels + f.articleCancels,
                f.ticketCancelAmount.add(f.lineCancelAmount).add(f.articleCancelAmount)));
        rows.add(row(sales, "Retours", f.returnCount + f.refunds, f.returns.add(f.refundAmount)));
        rows.add(row(sales, "Remises rabais", f.discountCount, f.discounts));
        rows.add(row(sales, "dont remises sur ticket", f.globalDiscountCount, f.globalDiscounts));
        Object[] promotions = promotions(criteria);
        rows.add(row(sales, "Promotions remboursées", (Long) promotions[0], (BigDecimal) promotions[1]));
        rows.add(row(sales, "Dépenses", f.outflowCount, f.outflows));
        rows.add(row(sales, "Ventes caisse Drive", null, f.ecommerce));
        BigDecimal withdrawn = BigDecimal.ZERO;
        for (BigDecimal amount : f.withdrawals.values()) {
            withdrawn = withdrawn.add(amount);
        }
        rows.add(row(sales, "Montant des prélèvements", f.withdrawalCount, withdrawn));
        rows.add(row(sales, "Ventes et retours articles", items + f.returnArticles, f.sales.subtract(f.returns)));
        rows.add(row(sales, "Comptage client", customers, null));
        List<Object> basket = row(sales, "Panier moyen", null, CashierPerformanceReport.money(f.takings, customers));
        basket.set(4, CashierStateReport.ratio(items, customers));
        rows.add(basket);
        rows.add(row(sales, "Valeur moyenne article", null, CashierPerformanceReport.money(f.sales, items)));
        List<Object> cadence = row(sales, "Articles par minute", null, null);
        cadence.set(4, CashierStateReport.ratio(items * 60, f.scanSeconds()));
        rows.add(cadence);
        rows.add(row(sales, "Articles scannés", f.scannedCount, f.scanned));
        rows.add(row(sales, "Articles saisis", f.keyedCount, f.keyed));
        Set<String> tenders = new TreeSet<>(f.tenders.keySet());
        tenders.add(com.intermarche.edge.domain.session.CashMovement.CASH);
        for (String tender : tenders) {
            List<Object> row = row("Détail par moyen de paiement", CashierActivity.tenderLabel(tender),
                    f.tenderCounts.getOrDefault(tender, 0L),
                    f.tenders.getOrDefault(tender, BigDecimal.ZERO));
            row.set(5, com.intermarche.edge.domain.session.CashMovement.CASH.equals(tender) ? f.variance
                    : BigDecimal.ZERO);
            rows.add(row);
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Builds one indicator row.
     *
     * @param part the part of the report
     * @param indicator the indicator
     * @param count its count, or null
     * @param amount its amount, or null
     * @return the row
     */
    private List<Object> row(String part, String indicator, Long count, BigDecimal amount) {
        return new ArrayList<>(Arrays.asList(part, indicator, count, amount, null, null));
    }

    /**
     * Reads the school-mode sales: the training tickets of the period.
     *
     * @param criteria the edition parameters
     * @return their number and their amount
     */
    private Object[] training(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select count(t), sum(t.totalIncludingTax) from TrainingTicket t"
                                + " where t.parkedAt is null and t.issuedAt >= :from and t.issuedAt < :to"
                                + criteria.terminalFilter("t.terminalId"), Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        return figures(query.getResultList());
    }

    /**
     * Reads the promotions the valuation engine granted on the lines of the
     * tickets of the period.
     *
     * @param criteria the edition parameters
     * @return their number and their amount
     */
    private Object[] promotions(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select count(v), sum(v.advantageAmount) from TicketLineValuation v"
                                + " where v.advantageAmount is not null and v.ticket.status = :closed"
                                + " and v.ticket.closingDate >= :from and v.ticket.closingDate < :to"
                                + criteria.terminalFilter("v.ticket.terminalId"), Object[].class)
                .setParameter("closed", com.intermarche.edge.domain.sale.Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        return figures(query.getResultList());
    }

    /**
     * Reads a count and a sum out of an aggregate query's single row.
     *
     * @param result the query's rows
     * @return the count and the sum, zero when absent
     */
    private static Object[] figures(List<Object[]> result) {
        Object[] row = result.isEmpty() ? new Object[]{null, null} : result.get(0);
        return new Object[]{row[0] == null ? 0L : ((Number) row[0]).longValue(),
                row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]};
    }
}
