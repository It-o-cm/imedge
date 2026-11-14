package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.VatBreakdown;
import com.intermarche.edge.domain.session.CashMovement;
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
import java.util.TreeSet;

/**
 * ÉTAT CAISSIÈRE (BO-06-06-03), complete: per cashier, every block the
 * requirement enumerates — drawer openings without sale and customers; sales,
 * returns, discounts and net takings; by tender the sales, withdrawals, float,
 * contributions and outflows; the recap by VAT rate; the cancellations of
 * tickets, lines and articles, the refunds and the price forcings; and the
 * statistics of time and cadence the register now measures.
 *
 * <p>« Possibilité d'imprimer un résumé ou un détail par caissière »: the
 * SUMMARY is the global recap of the cashiers selected — what the shop counts
 * its bank deposit from, the withdrawals of every drawer together — and the
 * DETAIL states the same blocks for each cashier, then the recap.
 *
 * <p>The durations' shares are read against the time of the sessions the
 * cashier opened — or the time spent unlocked, when longer — : the active
 * session, the customer time, the scanning, the payment, the idle time and,
 * inside it, the timed cash operations.
 *
 * <p>The figures are measured by {@link CashierActivity}, which says how each
 * one is read.
 */
@ApplicationScoped
public class CashierStateReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashierStateReport.class);

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "cashier-state";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État caissière";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-03";
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
     * @return the column the chart draws (BO-06-01-04)
     */
    @Override
    public int chartColumn() {
        return 5;
    }

    /**
     * {@inheritDoc}
     *
     * @return the column naming each bar
     */
    @Override
    public int chartLabelColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashier, the block, the item and its figures
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.text("section", "Rubrique"),
                ReportColumn.text("item", "Libellé"),
                ReportColumn.figure("count", "Nombre"),
                ReportColumn.average("amount", "Montant"),
                ReportColumn.average("excluding", "Montant HT"),
                ReportColumn.average("vat", "Montant TVA"),
                ReportColumn.decimal("value", "Valeur"),
                ReportColumn.text("duration", "Durée"),
                ReportColumn.percent("share", "Part"));
    }

    /**
     * {@inheritDoc}
     *
     * @return what the edition option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Édition";
    }

    /**
     * {@inheritDoc}
     *
     * @return the summary and the detail per cashier
     */
    @Override
    public List<String> optionChoices() {
        return List.of("SUMMARY:Résumé (récapitulatif global)", "DETAIL:Détail par caissière");
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
     * @return the blocks of each cashier when detailed, then the global recap
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        CashierActivity activity = CashierActivity.load(entityManager, criteria);
        List<List<Object>> rows = new ArrayList<>();
        if (criteria.detailed()) {
            for (CashierActivity.Figures figures : activity.cashiers()) {
                block(rows, figures);
            }
        }
        if (!activity.cashiers().isEmpty()) {
            block(rows, activity.total());
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Writes every block of one cashier, or of the recap.
     *
     * @param rows the rows to fill
     * @param f the figures
     */
    void block(List<List<Object>> rows, CashierActivity.Figures f) {
        long customers = f.baskets.size();
        String activity = "Activité";
        rows.add(row(f, activity, "Tickets non-vente (ouvertures de tiroir)", f.drawerOpenings, null));
        rows.add(row(f, activity, "Nombre de clients", customers, null));
        String sales = "Ventes";
        rows.add(row(f, sales, "Ventes", null, f.sales));
        rows.add(row(f, sales, "Retours", f.returnCount, f.returns));
        rows.add(row(f, sales, "Remises", f.discountCount, f.discounts));
        rows.add(row(f, sales, "Recette nette", null, f.takings));
        Set<String> tenders = new TreeSet<>(f.tenders.keySet());
        tenders.addAll(f.withdrawals.keySet());
        tenders.add(CashMovement.CASH);
        for (String tender : tenders) {
            // The tender names its own block: a label the page translates whole.
            String section = CashierActivity.tenderLabel(tender);
            rows.add(row(f, section, "Ventes", null, f.tenders.getOrDefault(tender, BigDecimal.ZERO)));
            rows.add(row(f, section, "Prélèvement", null, f.withdrawals.getOrDefault(tender, BigDecimal.ZERO)));
            if (CashMovement.CASH.equals(tender)) {
                rows.add(row(f, section, "Fond de caisse", null, f.floats));
                rows.add(row(f, section, "Apport", null, f.contributions));
                rows.add(row(f, section, "Sortie", null, f.outflows));
            }
        }
        for (VatBreakdown.Bucket bucket : f.vat.getBuckets()) {
            com.intermarche.edge.domain.catalog.VatRate regime =
                    com.intermarche.edge.domain.catalog.VatRate.resolve(bucket.rate, bucket.number);
            String code = regime == null || regime.number == null ? "" : String.valueOf(regime.number);
            String label = regime == null || regime.label == null
                    ? CashierActivity.value(bucket.rate).multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP).toPlainString() + " %"
                    : regime.label;
            List<Object> vat = row(f, "Récapitulatif TVA", (code + " " + label).trim(), null,
                    bucket.totalIncludingTax);
            vat.set(6, bucket.totalExcludingTax);
            vat.set(7, bucket.vatAmount);
            rows.add(vat);
        }
        String cancels = "Annulations";
        rows.add(row(f, cancels, "Annulations ticket", f.ticketCancels, f.ticketCancelAmount));
        rows.add(row(f, cancels, "Annulations ligne", f.lineCancels, f.lineCancelAmount));
        rows.add(row(f, cancels, "Annulations articles", f.articleCancels, f.articleCancelAmount));
        rows.add(row(f, cancels, "Remboursements", f.refunds, f.refundAmount));
        rows.add(row(f, cancels, "Forçages prix", f.forcings, f.forcingAmount));
        statistics(rows, f, customers);
    }

    /**
     * Writes the statistics block of one cashier.
     *
     * @param rows the rows to fill
     * @param f the figures
     * @param customers the number of customers
     */
    private void statistics(List<List<Object>> rows, CashierActivity.Figures f, long customers) {
        String stats = "Statistiques";
        long items = f.items();
        // The shares are read against the session, or against the active time
        // when the cashier was unlocked longer than the session lasted — an
        // unlock before the session opens — so that no share exceeds the whole.
        long session = Math.max(f.sessionSeconds, f.activeSeconds);
        List<Object> mean = row(f, stats, "Nombre moyen d'articles par client", null, null);
        mean.set(8, ratio(items, customers));
        rows.add(mean);
        rows.add(row(f, stats, "Nombre d'articles", items, null));
        rows.add(duration(f, stats, "Durée d'ouverture de la session", f.sessionSeconds, session));
        rows.add(duration(f, stats, "Session active", f.activeSeconds, session));
        rows.add(duration(f, stats, "Temps client", f.customerSeconds(), session));
        rows.add(duration(f, stats, "Scanning", f.scanSeconds(), session));
        rows.add(duration(f, stats, "Paiement", f.paySeconds(), session));
        rows.add(duration(f, stats, "Inactivité", f.idleSeconds(), session));
        rows.add(duration(f, stats, "dont opérations de caisse", f.cashOperationSeconds, session));
        List<Object> perHour = row(f, stats, "Clients par heure", null, null);
        perHour.set(8, ratio(customers * 3600, f.activeSeconds));
        rows.add(perHour);
        List<Object> salesPerHour = row(f, stats, "Ventes par heure", null, null);
        salesPerHour.set(5, f.activeSeconds == 0 ? BigDecimal.ZERO
                : f.takings.multiply(BigDecimal.valueOf(3600))
                        .divide(BigDecimal.valueOf(f.activeSeconds), 2, RoundingMode.HALF_UP));
        rows.add(salesPerHour);
        List<Object> cadence = row(f, stats, "Articles par minute", null, null);
        cadence.set(8, ratio(items * 60, f.scanSeconds()));
        rows.add(cadence);
    }

    /**
     * Builds one row of a block.
     *
     * @param f the figures, for the cashier's badge and name
     * @param section the block
     * @param item the item
     * @param count the count, or null
     * @param amount the amount, or null
     * @return the row, every other figure empty
     */
    private List<Object> row(CashierActivity.Figures f, String section, String item, Long count, BigDecimal amount) {
        return new ArrayList<>(Arrays.asList(f.badge, f.name(), section, item, count, amount,
                null, null, null, null, null));
    }

    /**
     * Builds the row of a duration and its share of the session.
     *
     * @param f the figures
     * @param section the block
     * @param item the item
     * @param seconds the duration
     * @param session the session's duration, the whole of the share
     * @return the row
     */
    private List<Object> duration(CashierActivity.Figures f, String section, String item, long seconds,
                                  long session) {
        List<Object> row = row(f, section, item, null, null);
        row.set(9, CashierActivity.clock(seconds));
        row.set(10, session == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(seconds * 100).divide(BigDecimal.valueOf(session), 1, RoundingMode.HALF_UP));
        return row;
    }

    /**
     * Divides two counts.
     *
     * @param dividend the dividend
     * @param divisor the divisor
     * @return the quotient at two decimals, zero for a zero divisor
     */
    static BigDecimal ratio(long dividend, long divisor) {
        if (divisor == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(dividend).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
    }
}
