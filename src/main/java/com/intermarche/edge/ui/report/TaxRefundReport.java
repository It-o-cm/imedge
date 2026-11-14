package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.TaxRefundForm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * SUIVI DU REMBOURSEMENT DE LA DÉTAXE (BO-06-07-03): every export sales slip the
 * welcome desk printed over the range of dates — its date, the customer's name,
 * the amount to refund, and rate by rate (2,10 %, 5,5 %, 10 %, 20 %) the
 * amount of the articles sold and their VAT — with its state: awaiting the
 * customs stamp, or refunded and when.
 *
 * <p>The follow-up the accountants kept in a spreadsheet: the slips come from
 * the Détaxe screen, which prints them from the customer's ticket and records
 * the refund when the stamped slip comes back. The same module serves the
 * refund of the tax of fishing boats.
 */
@ApplicationScoped
public class TaxRefundReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TaxRefundReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "tax-refunds";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Suivi du remboursement de la détaxe";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-07-03";
    }

    /**
     * {@inheritDoc}
     *
     * @return the tax family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.TAX;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns the requirement enumerates, then the refund's state
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("date", "Date"),
                ReportColumn.text("number", "N° du bordereau"),
                ReportColumn.text("customer", "Nom du client"),
                ReportColumn.amount("refund", "Montant à rembourser"),
                ReportColumn.amount("sales210", "Articles à 2,10 %"),
                ReportColumn.amount("vat210", "TVA à 2,10 %"),
                ReportColumn.amount("sales55", "Articles à 5,5 %"),
                ReportColumn.amount("vat55", "TVA à 5,5 %"),
                ReportColumn.amount("sales10", "Articles à 10 %"),
                ReportColumn.amount("vat10", "TVA à 10 %"),
                ReportColumn.amount("sales20", "Articles à 20 %"),
                ReportColumn.amount("vat20", "TVA à 20 %"),
                ReportColumn.text("state", "État"),
                ReportColumn.dateTime("refundedAt", "Remboursé le"));
    }

    /**
     * {@inheritDoc}
     *
     * @return what the state option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Bordereaux";
    }

    /**
     * {@inheritDoc}
     *
     * @return all, awaiting, refunded
     */
    @Override
    public List<String> optionChoices() {
        return List.of("ALL:Tous les bordereaux", TaxRefundForm.ISSUED + ":En attente du visa",
                TaxRefundForm.REFUNDED + ":Remboursés");
    }

    /**
     * {@inheritDoc}
     *
     * @return no register criterion: the slips are the desk's
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.noneOf(ReportFilter.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per slip, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr("ALL");
        boolean all = "ALL".equals(option);
        var query = entityManager.createQuery(
                        "select f from TaxRefundForm f where f.createdAt >= :from and f.createdAt < :to"
                                + (all ? "" : " and f.status = :status") + " order by f.createdAt",
                        TaxRefundForm.class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!all) {
            query.setParameter("status", option);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (TaxRefundForm form : query.getResultList()) {
            rows.add(Arrays.asList(form.createdAt, form.number, form.customerName, form.refundAmount,
                    form.sales210, form.vat210, form.sales55, form.vat55, form.sales10, form.vat10,
                    form.sales20, form.vat20,
                    TaxRefundForm.REFUNDED.equals(form.status) ? "Remboursé" : "En attente du visa",
                    form.refundedAt));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }
}
