package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.deploy.PdvRevenue;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CHIFFRE D'AFFAIRES PAR POINT DE VENTE ET PAR ADHÉRENT ({@code BO-02-05-05}):
 * the revenue every point of sale reported for the period, regrouped by the
 * adhérent operating it, a subtotal closing each adhérent.
 *
 * <p>Read on the central node, from what the stores state in their report
 * cycle — a store standing alone reports its own. The lines come sorted by
 * adhérent; the option narrows the report to ONE adhérent, or, written
 * {@code *}, folds it into one line per adhérent with its points of sale —
 * the regrouping view the requirement asks for in the point-of-sale reports.
 * The report's own total then sums what is shown, never a subtotal twice.
 *
 * <p>A day is filed under the number its point of sale carried that day
 * ({@code BO-02-05-01}): a former number stays a line of its own, named after
 * the point of sale that carried it, so the revenue produced under it is never
 * read as another's.
 */
@ApplicationScoped
public class PdvRevenueReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PdvRevenueReport.class);

    /** The adhérent label of the points of sale attached to none. */
    static final String NO_ADHERENT = "—";

    /** The option asking for one line per adhérent rather than per point of sale. */
    static final String ALL_ADHERENTS = "*";

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "pdv-revenue";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Chiffre d'affaires par point de vente et par adhérent";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-02-05-05";
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
     * @return the columns of the regrouping
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("adherent", "Adhérent"),
                ReportColumn.text("pdv", "Point de vente"),
                ReportColumn.text("name", "Nom"),
                ReportColumn.text("enseigne", "Enseigne"),
                ReportColumn.text("country", "Pays"),
                ReportColumn.count("tickets", "Tickets"),
                ReportColumn.amount("revenue", "CA TTC"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the column the chart draws
     */
    @Override
    public int chartColumn() {
        return 6;
    }

    /**
     * {@inheritDoc}
     *
     * @return the column naming the chart's bars
     */
    @Override
    public int chartLabelColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @return the label of the adhérent filter
     */
    @Override
    public String optionLabel() {
        return "Adhérent (code), * : un total par adhérent — vide : tous";
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the period, and the adhérent in the option
     * @return one row per point of sale, sorted by adhérent, or one per adhérent
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String wanted = criteria.optionOr(null);
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        Map<String, Long> tickets = new LinkedHashMap<>();
        for (PdvRevenue day : PdvRevenue.listBetween(criteria.from().toLocalDate(),
                criteria.to().minusNanos(1).toLocalDate())) {
            revenue.merge(day.pdvNumber, day.revenue, BigDecimal::add);
            tickets.merge(day.pdvNumber, day.tickets, Long::sum);
        }
        Map<String, List<List<Object>>> byAdherent = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : revenue.entrySet()) {
            Pdv pdv = holderOf(entry.getKey());
            String adherent = pdv == null || pdv.adherentCode == null ? NO_ADHERENT : pdv.adherentCode;
            if (wanted != null && !ALL_ADHERENTS.equals(wanted.trim()) && !wanted.trim().equalsIgnoreCase(adherent)) {
                continue;
            }
            Enseigne enseigne = pdv == null || pdv.enseigneCode == null ? null : Enseigne.findByCode(pdv.enseigneCode);
            byAdherent.computeIfAbsent(adherent, code -> new ArrayList<>()).add(Arrays.asList(
                    adherent,
                    entry.getKey(),
                    nameOf(pdv, entry.getKey()),
                    pdv == null || pdv.enseigneCode == null ? "" : pdv.enseigneCode,
                    enseigne == null || enseigne.countryCode == null ? "" : enseigne.countryCode,
                    tickets.get(entry.getKey()),
                    entry.getValue()));
        }
        boolean summary = ALL_ADHERENTS.equals(wanted == null ? null : wanted.trim());
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<List<Object>>> group : byAdherent.entrySet()) {
            if (!summary) {
                rows.addAll(group.getValue());
                continue;
            }
            long count = 0L;
            BigDecimal total = BigDecimal.ZERO;
            List<String> numbers = new ArrayList<>();
            for (List<Object> row : group.getValue()) {
                numbers.add((String) row.get(1));
                count += (Long) row.get(5);
                total = total.add((BigDecimal) row.get(6));
            }
            rows.add(Arrays.asList(group.getKey(), String.join(", ", numbers), "", "", "", count, total));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * The point of sale a number designates: the one carrying it today, else
     * the one that carried it before being renumbered.
     *
     * @param number the number the revenue was filed under
     * @return the point of sale, or null when the organisation knows none
     */
    private Pdv holderOf(String number) {
        Pdv current = Pdv.findByNumber(number);
        if (current != null) {
            return current;
        }
        Long former = PdvHistory.formerHolderOf(number);
        return former == null ? null : Pdv.findById(former);
    }

    /**
     * The name a line shows: the point of sale's, a former number saying so.
     *
     * @param pdv the point of sale, possibly null
     * @param number the number the revenue was filed under
     * @return the name, empty when unknown
     */
    private String nameOf(Pdv pdv, String number) {
        if (pdv == null) {
            return "";
        }
        return number.equals(pdv.pdvNumber) ? pdv.name : pdv.name + " (ancien n°, actuel " + pdv.pdvNumber + ")";
    }
}
