package com.intermarche.edge.ui.report;

import com.intermarche.edge.service.PosSettingsService;
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
 * ÉTAT PROFIL DES PERFORMANCES CAISSIÈRES, second part (BO-06-06-02): for each
 * cashier, the EXPRESS orders apart from the NORMAL ones — the express order
 * holding at most the administered number of articles ({@code
 * report.express-max-items}, ten by default: « moins de 10 articles » against
 * « à partir de 11 ») — each compared with the store's average of its kind:
 * the efficiency rank, the mean articles per order, the share of the orders,
 * the articles per minute, the mean coupons per customer, the shares of
 * customers paying by cheque and with food vouchers, and the mean times of
 * scanning, payment, other and customer, in seconds.
 *
 * <p>The efficiency rank orders the cashiers of a kind by their articles per
 * minute, the fastest first: what the requirement compares the cashiers on.
 * The « other » time is what the customer time holds beyond the scanning and
 * the payment — the bagging, the conversation, the wait for the next customer
 * to step up.
 */
@ApplicationScoped
public class CashierOrderProfileReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashierOrderProfileReport.class);

    /** The label of the express kind. */
    static final String EXPRESS = "Commandes express";

    /** The label of the normal kind. */
    static final String NORMAL = "Commandes normales";

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /** The parameters, for the express threshold. */
    @Inject PosSettingsService posSettingsService;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "cashier-orders";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Profil des performances caissières — commandes express et normales";
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
     * @return the columns of the second part of the requirement
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.text("kind", "Type de commande"),
                ReportColumn.figure("rank", "Rang d'efficacité"),
                ReportColumn.decimal("itemsPerOrder", "Nb moyen d'articles par commande"),
                ReportColumn.percent("orderShare", "% commandes"),
                ReportColumn.decimal("itemsPerMinute", "Articles par minute"),
                ReportColumn.decimal("couponsPerCustomer", "Nb moyen de coupons par client"),
                ReportColumn.percent("cheques", "% clients avec chèques"),
                ReportColumn.percent("foodVouchers", "% clients avec bons alimentaires"),
                ReportColumn.decimal("scan", "Temps moyen de scanning (s)"),
                ReportColumn.decimal("pay", "Temps moyen de paiement (s)"),
                ReportColumn.decimal("other", "Temps moyen autre (s)"),
                ReportColumn.decimal("customer", "Temps client moyen (s)"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the articles-per-minute column
     */
    @Override
    public int chartColumn() {
        return 6;
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
     * @return per kind: one row per cashier who served one, ranked, then the
     *         store's average of the kind
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        int threshold = posSettingsService.reportExpressMaxItems();
        List<CashierActivity.Figures> cashiers = CashierActivity.load(entityManager, criteria).cashiers();
        List<List<Object>> rows = new ArrayList<>();
        for (boolean express : new boolean[]{true, false}) {
            String kind = express ? EXPRESS : NORMAL;
            List<List<Object>> kindRows = new ArrayList<>();
            List<CashierActivity.Basket> all = new ArrayList<>();
            long orders = 0;
            for (CashierActivity.Figures f : cashiers) {
                List<CashierActivity.Basket> baskets = of(f.baskets, threshold, express);
                orders += f.baskets.size();
                if (!baskets.isEmpty()) {
                    kindRows.add(row(f.badge, f.name(), kind, baskets, f.baskets.size()));
                    all.addAll(baskets);
                }
            }
            kindRows.sort((a, b) -> ((BigDecimal) b.get(6)).compareTo((BigDecimal) a.get(6)));
            for (int i = 0; i < kindRows.size(); i++) {
                kindRows.get(i).set(3, (long) i + 1);
            }
            rows.addAll(kindRows);
            if (!all.isEmpty()) {
                ReportData.Subtotal mean = new ReportData.Subtotal();
                mean.addAll(row("", "Moyenne magasin", kind, all, orders));
                rows.add(mean);
            }
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Keeps the baskets of one kind.
     *
     * @param baskets the baskets
     * @param threshold the most articles an express order holds
     * @param express whether the express ones are kept
     * @return the baskets of the kind
     */
    static List<CashierActivity.Basket> of(List<CashierActivity.Basket> baskets, int threshold, boolean express) {
        List<CashierActivity.Basket> kept = new ArrayList<>();
        for (CashierActivity.Basket basket : baskets) {
            if ((basket.items <= threshold) == express) {
                kept.add(basket);
            }
        }
        return kept;
    }

    /**
     * Builds the row of a set of baskets of one kind.
     *
     * @param badge the badge shown
     * @param name the name shown
     * @param kind the kind
     * @param baskets the baskets of the kind, at least one
     * @param orders the number of orders the share is read against
     * @return the row, rank empty
     */
    List<Object> row(String badge, String name, String kind, List<CashierActivity.Basket> baskets, long orders) {
        long count = baskets.size();
        long items = 0;
        long coupons = 0;
        long cheques = 0;
        long vouchers = 0;
        long scan = 0;
        long pay = 0;
        long customer = 0;
        for (CashierActivity.Basket basket : baskets) {
            items += basket.items;
            coupons += basket.coupons;
            cheques += basket.cheque ? 1 : 0;
            vouchers += basket.foodVoucher ? 1 : 0;
            scan += basket.scanSeconds;
            pay += basket.paySeconds;
            customer += basket.customerSeconds;
        }
        long other = Math.max(0, customer - scan - pay);
        return new ArrayList<>(Arrays.asList(
                badge,
                name,
                kind,
                null,
                CashierStateReport.ratio(items, count),
                percent(count, orders),
                CashierStateReport.ratio(items * 60, scan),
                CashierStateReport.ratio(coupons, count),
                percent(cheques, count),
                percent(vouchers, count),
                CashierStateReport.ratio(scan, count),
                CashierStateReport.ratio(pay, count),
                CashierStateReport.ratio(other, count),
                CashierStateReport.ratio(customer, count)));
    }

    /**
     * Computes a share in percent.
     *
     * @param part the part
     * @param whole the whole
     * @return the share at one decimal, zero for a zero whole
     */
    static BigDecimal percent(long part, long whole) {
        if (whole == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }
}
