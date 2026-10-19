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
