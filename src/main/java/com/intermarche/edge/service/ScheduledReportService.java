package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.ScheduledReportRun;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.ui.report.ReportCriteria;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportDefinition;
import com.intermarche.edge.ui.report.ReportPeriod;
import com.intermarche.edge.ui.report.ReportService;
import com.intermarche.edge.ui.report.ReportWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Edits the PROGRAMMED reports when the period ends ({@code BO-09-01-10},
 * {@code BO-09-01-11}).
 *
 * <p>The shop names the reports it wants in {@code period.reports}, a
 * semicolon list of report codes read left to right, and the format in
 * {@code period.reports-format}. At the end of period each one is run over
 * the day that is closing and the document is KEPT, so the state of the
 * period survives the retention sweep that runs in the same pass.
 *
 * <p>An unknown code is skipped rather than refused, exactly as the dashboard
 * indicators are: a list typed by hand must not be able to stop the end of
 * period. An edition that throws is written as a FAILURE and the next report
 * is edited — one state that cannot be produced must not take the others
 * with it.
 *
 * <p>Blank list, nothing done: a shop that programmed nothing gets nothing,
 * which is the conduct of before.
 *
 * <p>Where the store names a network printer, every edition is PRINTED as soon
 * as it is edited (BO-09-01-10) — the « impression automatique » of the end of
 * period — and still kept for download.
 */
@ApplicationScoped
public class ScheduledReportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ScheduledReportService.class);

    /** The format used when the administered one names no known writer. */
    private static final String DEFAULT_FORMAT = "pdf";

    /** The parameters: which reports, in which format. */
    @Inject
    PosSettingsService posSettingsService;

    /** The report runner and its catalog. */
    @Inject
    ReportService reportService;

    /** The journal: an automatic edition is an event of the shop. */
    @Inject
    TechnicalEventService technicalEventService;

    /** The network printer the editions are sent to (BO-09-01-10). */
    @Inject
    ReportPrintService reportPrintService;

    /**
     * The reports the shop programmed, in the administered order
     * ({@code BO-09-01-11}).
     *
     * @return the definitions, empty when nothing is programmed, the list
     *         cannot be read, or no code names a known report
     */
    public List<ReportDefinition> programmed() {
        LOGGER.info("Entering method programmed");
        List<ReportDefinition> chosen = new ArrayList<>();
        String raw;
        try {
            raw = posSettingsService.periodReports();
        } catch (Exception unreadable) {
            LOGGER.debugf("Liste des rapports programmés illisible (%s)", unreadable.getMessage());
            LOGGER.info("Exiting method programmed");
            return chosen;
        }
        if (raw == null || raw.isBlank()) {
            LOGGER.info("Exiting method programmed");
            return chosen;
        }
        for (String token : raw.split(";")) {
            ReportDefinition definition = reportService.catalog().find(token.trim());
            if (definition != null && !chosen.contains(definition)) {
                chosen.add(definition);
            }
        }
        LOGGER.info("Exiting method programmed with " + chosen.size() + " report(s)");
        return chosen;
    }

    /**
     * The format the programmed reports are rendered in
     * ({@code BO-09-01-11}).
     *
     * @return a writer, the PDF one when the parameter names no known format
     */
    public ReportWriter format() {
        LOGGER.info("Entering method format");
        ReportWriter writer = null;
        try {
            writer = reportService.writer(posSettingsService.periodReportsFormat());
        } catch (Exception unreadable) {
            LOGGER.debugf("Format des rapports programmés illisible (%s)", unreadable.getMessage());
        }
        ReportWriter chosen = writer == null ? reportService.writer(DEFAULT_FORMAT) : writer;
        LOGGER.info("Exiting method format");
        return chosen;
    }

    /**
     * The most recent automatic editions, newest first
     * ({@code BO-09-01-10}).
     *
     * <p>Served from here rather than read statically by the screen, so the
     * screen injects ONE collaborator for this feature and the listing can be
     * stubbed like anything else.
     *
     * @param limit how many to return
     * @return the editions, possibly empty
     */
    public List<ScheduledReportRun> latest(int limit) {
        LOGGER.info("Entering method latest with limit: " + limit);
        LOGGER.info("Exiting method latest");
        return ScheduledReportRun.latest(limit);
    }

    /**
     * Edits every programmed report over the day that is closing
     * ({@code BO-09-01-10}).
     *
     * @param day the day whose period is ending
     * @return how many editions were written, failures included
     */
    @Transactional
    public int runEndOfPeriod(LocalDate day) {
        LOGGER.info("Entering method runEndOfPeriod with day: " + day);
        List<ReportDefinition> definitions = programmed();
        if (definitions.isEmpty()) {
            LOGGER.info("Exiting method runEndOfPeriod: nothing programmed");
            return 0;
        }
        ReportWriter writer = format();
        int written = 0;
        for (ReportDefinition definition : definitions) {
            written += edit(definition, writer, day) ? 1 : 0;
        }
        technicalEventService.log(TechnicalEvent.EventType.SCHEDULED_REPORTS_EDITED,
                written + " état(s) de fin de période édité(s) le " + day);
        LOGGER.info("Exiting method runEndOfPeriod with " + written + " edition(s)");
        return written;
    }

    /**
     * Prints a successful edition on the administered printer
     * (BO-09-01-10): the moment it was sent, or why it could not be, is kept
     * on the edition and written to the journal; a printer that fails takes
     * nothing else with it.
     *
     * @param run the edition, its document rendered
     */
    void print(ScheduledReportRun run) {
        String printer = posSettingsService.periodReportsPrinter();
        if (printer.isEmpty()) {
            return;
        }
        try {
            reportPrintService.print(printer, run.content);
            run.printedAt = LocalDateTime.now();
            technicalEventService.log(TechnicalEvent.EventType.SCHEDULED_REPORTS_EDITED,
                    "État de fin de période " + run.reportCode + " imprimé sur " + printer);
        } catch (Exception failure) {
            run.printFailure = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            technicalEventService.log(TechnicalEvent.EventType.SCHEDULED_REPORTS_EDITED,
                    "Impression de l'état " + run.reportCode + " impossible sur " + printer);
        }
    }

    /**
     * Edits one report and writes its edition, successful or not.
     *
     * @param definition the report to edit
     * @param writer the renderer
     * @param day the day whose period is ending
     * @return true when a row was written, which is always unless the write
     *         itself failed
     */
    private boolean edit(ReportDefinition definition, ReportWriter writer, LocalDate day) {
        ScheduledReportRun run = new ScheduledReportRun();
        run.reportCode = definition.code();
        run.reportLabel = definition.label();
        run.format = writer == null ? DEFAULT_FORMAT : writer.format();
        run.periodEnd = day;
        run.generatedAt = LocalDateTime.now();
        try {
            if (writer == null) {
                throw new IllegalStateException("aucun format de rendu disponible");
            }
            ReportCriteria criteria = new ReportCriteria();
            criteria.period = ReportPeriod.DAY;
            criteria.reference = day;
            ReportData data = reportService.run(definition, criteria);
            run.content = writer.write(data, criteria);
            run.rowCount = data.size();
            run.succeeded = true;
            print(run);
        } catch (Exception failure) {
            run.succeeded = false;
            run.failure = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            LOGGER.warnf("Édition programmée impossible (%s) : %s",
                    definition.code(), run.failure);
        }
        try {
            run.persist();
            return true;
        } catch (Exception unwritable) {
            LOGGER.warnf("Édition programmée non enregistrée (%s)", unwritable.getMessage());
            return false;
        }
    }
}
