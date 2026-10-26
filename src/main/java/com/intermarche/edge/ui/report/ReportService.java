package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Runs a report and hands it to the right renderer.
 *
 * <p>The one place a report is executed, so the screen and the three exports
 * measure the same thing over the same period: the export is the list in another
 * container, and it stays true because both go through here.
 */
@ApplicationScoped
public class ReportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReportService.class);

    /** The date stamp a downloaded file carries in its name. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** The report registry. */
    @Inject ReportCatalog catalog;

    /** The renderers, discovered like the reports themselves. */
    @Inject Instance<ReportWriter> writers;

    /** This node's point of sale, which owns the saved article lists (BO-02-03-31). */
    @Inject com.intermarche.edge.service.NodeEchelonService nodeEchelonService;

    /**
     * Resolves the criteria that name something the node holds — the saved
     * article list (BO-02-03-31) and the checkout island (BO-06-06-06) — into
     * the articles and registers they stand for. The screen, the exports and
     * the planned extractions all prepare their criteria here, so the three
     * read the same restriction.
     *
     * @param criteria the edition parameters, restricted in place
     */
    public void prepare(ReportCriteria criteria) {
        restrictToSelection(criteria);
        if (criteria.island != null) {
            com.intermarche.edge.domain.store.CheckoutIsland island =
                    com.intermarche.edge.domain.store.CheckoutIsland.findByCode(criteria.island);
            // An island this node does not know restricts the report to
            // nothing rather than silently widening it to every register.
            criteria.restrictToIsland(island == null ? List.of() : island.terminals());
        }
    }

    /**
     * Restricts a report to the articles of the saved list it names
     * (BO-02-03-31): the list's criteria are run as the article search runs
     * them, and their EANs bound the ticket lines the report reads. A list this
     * point of sale does not know restricts the report to nothing rather than
     * silently widening it to everything.
     *
     * @param criteria the edition parameters, restricted in place
     */
    void restrictToSelection(ReportCriteria criteria) {
        if (criteria.selection == null) {
            return;
        }
        com.intermarche.edge.domain.catalog.ArticleSelection selection =
                com.intermarche.edge.domain.catalog.ArticleSelection.findForPdv(criteria.selection, pdv());
        java.util.Set<String> eans = new java.util.TreeSet<>();
        if (selection != null) {
            for (com.intermarche.edge.domain.catalog.Product product
                    : com.intermarche.edge.ui.admin.ArticleSearchCriteria.fromQueryString(selection.criteria).find().list()) {
                eans.add(product.ean);
            }
        }
        criteria.restrictToArticles(eans);
    }

    /**
     * The point of sale of this node, null on a service built without it.
     *
     * @return the PDV code, or null
     */
    public String pdv() {
        return nodeEchelonService == null ? null : nodeEchelonService.currentPdv();
    }

    /**
     * Runs a report.
     *
     * <p>Transactional so the report's queries share one persistence session
     * whatever thread serves the request — the same posture the journal takes.
     *
     * @param definition the report to run
     * @param criteria the edition parameters
     * @return the executed report, totals included
     */
    @Transactional
    public ReportData run(ReportDefinition definition, ReportCriteria criteria) {
        LOGGER.info("Entering method run for report: " + definition.code());
        // BO-03-09-01: the registers the back office deactivated are left out,
        // unless the operator kept them or chose them by name.
        criteria.excludeDeactivated(
                com.intermarche.edge.domain.store.CheckoutPoint.deactivatedTerminals());
        List<List<Object>> rows = definition.rows(criteria);
        LOGGER.info("Exiting method run with " + rows.size() + " row(s)");
        return new ReportData(definition, rows);
    }

    /**
     * Finds the renderer of a format.
     *
     * @param format the format token carried by the URL, possibly null
     * @return the renderer, or null when no renderer claims that token
     */
    public ReportWriter writer(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        for (ReportWriter writer : writers) {
            if (writer.format().equalsIgnoreCase(format.trim())) {
                return writer;
            }
        }
        return null;
    }

    /**
     * Builds the name the downloaded file carries.
     *
     * <p>Report code and period, not a bare "export": three reports downloaded
     * in a row must not overwrite one another in the browser's folder, and the
     * one found there a month later has to say what it holds.
     *
     * @param definition the report that was run
     * @param criteria the edition parameters
     * @param writer the renderer, read for its extension
     * @return the file name
     */
    public String fileName(ReportDefinition definition, ReportCriteria criteria, ReportWriter writer) {
        return definition.code() + "-" + STAMP.format(criteria.from()) + "." + writer.extension();
    }

    /**
     * Returns the registry, so a resource does not have to inject both.
     *
     * @return the report catalog
     */
    public ReportCatalog catalog() {
        return catalog;
    }
}
