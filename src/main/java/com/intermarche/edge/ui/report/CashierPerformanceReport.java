package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * ÉTAT PROFIL DES PERFORMANCES CAISSIÈRES, first part (BO-06-06-02): for each
 * cashier, compared with the store's average, the session time, the closings,
 * the manual and automatic locks, the customers, the sales per hour, the mean
 * basket, the mean article price, the articles per hour and per customer, the
 * articles per minute and the scanning time.
 *
 * <p>The store's average closes the list, as a row of its own that the frame
 * neither totals nor draws: the mean of each figure over the cashiers measured.
 * The rates are read against the time the cashier was ACTIVE — unlocked at a
 * register — and the articles per minute against her scanning time, both
 * measured by the register ({@link CashierActivity}).
 *
 * <p>The second part — express and normal orders, efficiency rank — is
 * {@link CashierOrderProfileReport}.
 */
@ApplicationScoped
public class CashierPerformanceReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashierPerformanceReport.class);

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "cashier-performance";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Profil des performances caissières";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-02";
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashiers family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CASHIERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns of the first part of the requirement
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.text("session", "Durée totale de la session"),
                ReportColumn.figure("closings", "Nb de fermetures"),
                ReportColumn.figure("manualLocks", "Nb de verrouillages manuels"),
                ReportColumn.figure("autoLocks", "Nb de verrouillages automatiques"),
                ReportColumn.figure("customers", "Nb de clients"),
                ReportColumn.average("salesPerHour", "Ventes par heure"),
                ReportColumn.average("basket", "Panier moyen"),
                ReportColumn.average("articlePrice", "Prix article moyen"),
                ReportColumn.decimal("itemsPerHour", "Nb articles moyen par heure"),
                ReportColumn.decimal("itemsPerCustomer", "Nb articles moyen par client"),
                ReportColumn.decimal("itemsPerMinute", "Articles par minute"),
                ReportColumn.text("scanning", "Temps de scanning"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the articles-per-minute column, the productivity drawn
     */
    @Override
    public int chartColumn() {
        return 12;
    }

    /**
     * {@inheritDoc}
     *
     * @return the name column
     */
    @Override
    public int chartLabelColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island and the cashiers
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per cashier, then the store's average
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        List<List<Object>> rows = new ArrayList<>();
        List<CashierActivity.Figures> cashiers = CashierActivity.load(entityManager, criteria).cashiers();
        for (CashierActivity.Figures f : cashiers) {
            rows.add(row(f.badge, f.name(), f));
        }
        if (!cashiers.isEmpty()) {
            rows.add(average(cashiers));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Builds the row of one cashier.
     *
     * @param badge the badge shown
     * @param name the name shown
     * @param f the figures
     * @return the row
     */
    List<Object> row(String badge, String name, CashierActivity.Figures f) {
        long customers = f.baskets.size();
        long items = f.items();
        return new ArrayList<>(Arrays.asList(
                badge,
                name,
                CashierActivity.clock(f.sessionSeconds),
                f.closings,
                f.manualLocks,
                f.automaticLocks,
                customers,
                money(f.takings.multiply(BigDecimal.valueOf(3600)), f.activeSeconds),
                money(f.takings, customers),
                money(f.sales, items),
                CashierStateReport.ratio(items * 3600, f.activeSeconds),
                CashierStateReport.ratio(items, customers),
                CashierStateReport.ratio(items * 60, f.scanSeconds()),
                CashierActivity.clock(f.scanSeconds())));
    }

    /**
     * Builds the store's average: each figure averaged over the cashiers.
     *
     * @param cashiers the cashiers measured, at least one
     * @return the average row, flagged so the frame neither totals nor draws it
     */
    private List<Object> average(List<CashierActivity.Figures> cashiers) {
        int count = cashiers.size();
        CashierActivity.Figures sum = new CashierActivity.Figures();
        for (CashierActivity.Figures f : cashiers) {
            sum.add(f);
        }
        List<Object> mean = row("", "Moyenne magasin", sum);
        mean.set(2, CashierActivity.clock(sum.sessionSeconds / count));
        mean.set(3, Math.round((double) sum.closings / count));
        mean.set(4, Math.round((double) sum.manualLocks / count));
        mean.set(5, Math.round((double) sum.automaticLocks / count));
        mean.set(6, Math.round((double) sum.baskets.size() / count));
        mean.set(13, CashierActivity.clock(sum.scanSeconds() / count));
        ReportData.Subtotal row = new ReportData.Subtotal();
        row.addAll(mean);
        return row;
    }

    /**
     * Divides an amount by a count.
     *
     * @param amount the amount
     * @param by the count
     * @return the quotient at two decimals, zero for a zero count
     */
    static BigDecimal money(BigDecimal amount, long by) {
        if (by == 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(BigDecimal.valueOf(by), 2, RoundingMode.HALF_UP);
    }
}
