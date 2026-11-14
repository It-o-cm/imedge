package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SYNTHÈSE HEBDOMADAIRE CAISSIÈRE (BO-06-06-08): for each cashier, WEEK BY WEEK
 * over the period chosen, what the two source states say of her — the
 * productivity of the performance profile (BO-06-06-02) and the payments of the
 * cashier state (BO-06-06-03), in their synthetic form — and the cash errors
 * the counts of her sessions found.
 *
 * <p>Edited for one cashier over a few weeks, it is the document « à faire
 * signer par la caissière »: the signature line closes it on paper, and the
 * chart of its articles per minute week after week is the evolution of her
 * productivity the individual interview reads.
 *
 * <p>A week is Monday to Sunday, cut to the period chosen at both ends.
 */
@ApplicationScoped
public class CashierWeeklyReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashierWeeklyReport.class);

    /** The day format of a week's bounds. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "cashier-weekly";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Synthèse hebdomadaire caissière";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-08";
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
     * @return the cashier, the week, the productivity, the payments and the errors
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.text("week", "Semaine"),
                ReportColumn.count("customers", "Nb de clients"),
                ReportColumn.amount("takings", "Recette"),
                ReportColumn.count("items", "Nb d'articles"),
                ReportColumn.decimal("itemsPerMinute", "Articles par minute"),
                ReportColumn.decimal("customersPerHour", "Clients par heure"),
                ReportColumn.average("basket", "Panier moyen"),
                ReportColumn.text("active", "Temps actif"),
                ReportColumn.text("payments", "Synthèse des paiements"),
                ReportColumn.amount("errors", "Erreurs de caisse"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the articles-per-minute column, the productivity's evolution
     */
    @Override
    public int chartColumn() {
        return 6;
    }

    /**
     * {@inheritDoc}
     *
     * @return the week column
     */
    @Override
    public int chartLabelColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashier's name: a subtotal closes each cashier's weeks
     */
    @Override
    public String breakKey() {
        return "name";
    }

    /**
     * {@inheritDoc}
     *
     * @return the place where the cashier signs
     */
    @Override
    public String signature() {
        return "Signature de la caissière";
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
     * @return per cashier, one row per week she worked, in order
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        LocalDate first = criteria.from().toLocalDate();
        LocalDate last = criteria.to().toLocalDate().minusDays(1);
        Map<String, List<List<Object>>> byCashier = new java.util.TreeMap<>();
        com.intermarche.edge.ui.admin.BackOfficeMessages messages =
                com.intermarche.edge.ui.admin.BackOfficeMessages.in(criteria.language);
        LocalDate monday = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        while (!monday.isAfter(last)) {
            LocalDate start = monday.isBefore(first) ? first : monday;
            LocalDate sunday = monday.plusDays(6);
            LocalDate end = sunday.isAfter(last) ? last : sunday;
            String week = DAY.format(start) + " – " + DAY.format(end);
            for (CashierActivity.Figures f
                    : CashierActivity.load(entityManager, criteria.within(start, end)).cashiers()) {
                byCashier.computeIfAbsent(f.name() + "\u0000" + f.badge, key -> new ArrayList<>()).add(row(f, week, messages));
            }
            monday = monday.plusWeeks(1);
        }
        List<List<Object>> rows = new ArrayList<>();
        byCashier.values().forEach(rows::addAll);
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Builds one week of one cashier.
     *
     * @param f the figures of the week
     * @param week the week's label
     * @param messages the language of the edition, the tenders' names being written in it
     * @return the row
     */
    List<Object> row(CashierActivity.Figures f, String week, com.intermarche.edge.ui.admin.BackOfficeMessages messages) {
        long customers = f.baskets.size();
        long items = f.items();
        StringBuilder payments = new StringBuilder();
        for (Map.Entry<String, BigDecimal> tender : f.tenders.entrySet()) {
            payments.append(payments.length() == 0 ? "" : " ; ").append(messages.text(CashierActivity.tenderLabel(tender.getKey()))).append(' ')
                    .append(ReportFormat.cell(ReportColumn.amount("t", "t"), tender.getValue()));
        }
        return Arrays.asList(
                f.badge,
                f.name(),
                week,
                customers,
                f.takings,
                items,
                CashierStateReport.ratio(items * 60, f.scanSeconds()),
                CashierStateReport.ratio(customers * 3600, f.activeSeconds),
                CashierPerformanceReport.money(f.takings, customers),
                CashierActivity.clock(f.activeSeconds),
                payments.toString(),
                f.variance);
    }
}
