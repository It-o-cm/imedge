package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.VatRate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ÉTAT DES RÉGIMES DE TVA ({@code BO-02-02-03}): every regime the store
 * knows, with its code, its rate, the country it answers to, the legal
 * mention it obliges and how many articles it taxes — the exempt ones named
 * apart.
 *
 * <p>Asked for by the accounting and tax side rather than by the shop: the
 * question is "are the rates and the exempt articles conform", and it is
 * answered by reading the whole table at once rather than by scrolling an
 * administration screen. So it is a REPORT and not a second screen — it
 * comes out in XLSX, CSV and PDF like the others, which is what a
 * conformity check is filed as.
 *
 * <p>Independent of the edition period, and deliberately: a regime is not an
 * event, it is the state of the referential at the moment the check is run.
 * The period and the register filters of the other reports are simply not
 * read here.
 */
@ApplicationScoped
public class VatRegimeReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(VatRegimeReport.class);

    /** The persistence session the counts run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "vat-regimes";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État des régimes de TVA";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-02-02-03";
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
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns a conformity check reads
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("code", "Code TVA"),
                ReportColumn.text("rate", "Taux"),
                ReportColumn.text("label", "Libellé"),
                ReportColumn.text("country", "Pays"),
                ReportColumn.text("mention", "Mention légale"),
                ReportColumn.count("prices", "Articles tarifés"),
                ReportColumn.text("exempt", "Exonéré"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters, unread by this report
     * @return one row per regime, in code order
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        List<List<Object>> rows = new ArrayList<>();
        for (VatRate regime : VatRate.listAllOrdered()) {
            rows.add(Arrays.asList(
                    regime.number == null ? "" : String.valueOf(regime.number),
                    regime.rate == null ? "" : regime.getRateFormatted() + " %",
                    regime.label == null ? "" : regime.label,
                    regime.country == null ? "" : regime.country,
                    regime.legalMention == null ? "" : regime.legalMention,
                    pricedArticles(regime),
                    exempt(regime)));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * How many priced articles this regime taxes.
     *
     * @param regime the regime counted
     * @return the number of prices pointing at it
     */
    private long pricedArticles(VatRate regime) {
        if (regime.id == null) {
            return 0L;
        }
        return entityManager
                .createQuery("select count(p) from Price p where p.vat.id = :id", Long.class)
                .setParameter("id", regime.id)
                .getSingleResult();
    }

    /**
     * Whether a regime exempts what it taxes (BO-02-02-01): its EXEMPTION flag,
     * which a zero rate alone does not raise — a zero-rated donation is not an
     * exemption — followed by the administration's code when it carries one.
     *
     * @param regime the regime examined
     * @return "Oui", "Oui (code)" or "Non"
     */
    private String exempt(VatRate regime) {
        if (!regime.exempt) {
            return "Non";
        }
        return regime.exemptionCode == null ? "Oui" : "Oui (" + regime.exemptionCode + ")";
    }
}
