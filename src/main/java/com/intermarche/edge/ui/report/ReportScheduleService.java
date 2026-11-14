package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.session.ReportSchedule;
import com.intermarche.edge.domain.session.ScheduledReportRun;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Plans and edits the PLANNED EXTRACTIONS of the reports (BO-06-04-06,
 * « possibilité de planifier l'extraction du rapport sur une journée ou une
 * plage de date ou une semaine spécifique »).
 *
 * <p>The report screen plans an extraction with the parameters it shows; a
 * loop of the node edits every extraction that came due, in a transaction of
 * its own per round — never reading the database outside one — and keeps the
 * document as a {@link ScheduledReportRun}, downloadable from the report's
 * screen. The schedules are read at EVERY round, never cached at startup: an
 * extraction planned a minute ago is edited at its moment.
 *
 * <p>An extraction that fails is kept as a failed edition with its reason and
 * marked done: the operator sees it failed, and the loop does not retry it for
 * ever.
 *
 * <p>Placement: ui.report, beside the screen whose extractions it plans.
 */
@ApplicationScoped
public class ReportScheduleService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReportScheduleService.class);

    /** The seconds between two rounds of the loop. */
    static final long TICK_SECONDS = 30;

    /** The report runner and its catalog. */
    @Inject ReportService reportService;

    /** The clock of the rounds. */
    Supplier<LocalDateTime> now = LocalDateTime::now;

    /** The loop executor, or null when the loop is not running. */
    private ScheduledExecutorService executor;

    /**
     * Starts the loop.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "report-schedules");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the loop.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Runs a round in a transaction of its own, never letting a failure stop
     * the loop.
     */
    void tick() {
        try {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(this::round);
        } catch (Exception e) {
            LOGGER.warnf("Extractions planifiées en erreur : %s", e.getMessage());
        }
    }

    /**
     * Plans one extraction.
     *
     * @param reportCode the report's code
     * @param query the edition parameters, as the screen's query string
     * @param format the format token
     * @param runAt when it is due
     * @param plannedBy who planned it, possibly null
     * @return the planned extraction
     */
    @jakarta.transaction.Transactional
    public ReportSchedule plan(String reportCode, String query, String format, LocalDateTime runAt, String plannedBy) {
        LOGGER.info("Entering method plan with reportCode: " + reportCode + ", runAt: " + runAt);
        ReportSchedule schedule = new ReportSchedule();
        schedule.reportCode = reportCode;
        schedule.query = query;
        schedule.format = format;
        schedule.runAt = runAt;
        schedule.plannedBy = plannedBy;
        schedule.plannedAt = now.get();
        schedule.persist();
        LOGGER.info("Exiting method plan");
        return schedule;
    }

    /**
     * Edits every extraction that came due.
     *
     * @return how many extractions were edited, failures included
     */
    int round() {
        int edited = 0;
        for (ReportSchedule schedule : ReportSchedule.due(now.get())) {
            edit(schedule);
            edited++;
        }
        return edited;
    }

    /**
     * Edits one extraction and keeps its document, or its failure.
     *
     * @param schedule the due extraction, marked done whatever happens
     */
    void edit(ReportSchedule schedule) {
        ScheduledReportRun run = new ScheduledReportRun();
        run.reportCode = schedule.reportCode;
        run.format = schedule.format;
        run.generatedAt = now.get();
        run.periodEnd = run.generatedAt.toLocalDate();
        ReportDefinition definition = reportService.catalog().find(schedule.reportCode);
        ReportWriter writer = reportService.writer(schedule.format);
        if (definition == null || writer == null) {
            run.failure = definition == null ? "Rapport inconnu : " + schedule.reportCode
                    : "Format inconnu : " + schedule.format;
        } else {
            run.reportLabel = definition.label();
            try {
                ReportCriteria criteria = ReportCriteria.fromParams(parse(schedule.query));
                reportService.prepare(criteria);
                ReportData data = reportService.run(definition, criteria);
                run.content = writer.write(data, criteria);
                run.rowCount = data.size();
                run.periodEnd = criteria.to().toLocalDate().minusDays(1);
                run.succeeded = true;
            } catch (RuntimeException failure) {
                run.failure = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            }
        }
        run.persist();
        schedule.done = true;
        schedule.runId = run.id;
        schedule.failure = run.failure == null || run.failure.length() <= 500 ? run.failure
                : run.failure.substring(0, 500);
        LOGGER.infof("Extraction planifiée %s éditée (%s)", schedule.reportCode,
                run.succeeded ? "succès" : run.failure);
    }

    /**
     * Reads a query string back into its parameters.
     *
     * @param query the query string, possibly null
     * @return the parameters, empty for a null or blank query
     */
    static MultivaluedMap<String, String> parse(String query) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            params.add(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }
}
