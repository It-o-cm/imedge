package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One PLANNED extraction of a report (BO-06-04-06): which report, over which
 * edition parameters, in which format, and when the node must edit it.
 *
 * <p>The parameters are kept as the very query string of the report screen,
 * so a planned extraction measures exactly what the screen showed when it was
 * planned — a day, a range of dates or a specific week, with every filter —
 * and the edition, once produced, is kept as a {@link ScheduledReportRun} the
 * operator downloads from the report's screen.
 */
@Entity
@Table(name = "report_schedules",
        indexes = @Index(name = "idx_report_schedule_due", columnList = "done, run_at"))
public class ReportSchedule extends PanacheEntity {

    /** The code of the report to edit. */
    @Column(name = "report_code", nullable = false, length = 40)
    public String reportCode;

    /** The edition parameters, as the report screen's query string. */
    @Column(name = "query", length = 2000)
    public String query;

    /** The format to render: {@code xlsx}, {@code csv} or {@code pdf}. */
    @Column(name = "format", nullable = false, length = 8)
    public String format;

    /** When the extraction is due. */
    @Column(name = "run_at", nullable = false)
    public LocalDateTime runAt;

    /** Who planned it. */
    @Column(name = "planned_by", length = 60)
    public String plannedBy;

    /** When it was planned. */
    @Column(name = "planned_at", nullable = false)
    public LocalDateTime plannedAt;

    /** Whether the extraction was run, successfully or not. */
    @Column(name = "done", nullable = false)
    public boolean done;

    /** The edition the run produced, or null before it ran. */
    @Column(name = "run_id")
    public Long runId;

    /** Why the run failed, or null when it succeeded or has not run yet. */
    @Column(name = "failure", length = 500)
    public String failure;

    /**
     * Returns the due moment as the screens show it.
     *
     * @return the moment, {@code dd/MM/yyyy HH:mm}, or an empty text when unset
     */
    public String getRunAtFormatted() {
        return runAt == null ? "" : runAt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    /**
     * The extractions due at a moment and not yet run, oldest first.
     *
     * @param now the moment
     * @return the due extractions, possibly empty
     */
    public static List<ReportSchedule> due(LocalDateTime now) {
        return list("done = false and runAt <= ?1 order by runAt", now);
    }

    /**
     * The extractions planned for one report, most recent first.
     *
     * @param reportCode the report's code
     * @return the extractions, possibly empty
     */
    public static List<ReportSchedule> ofReport(String reportCode) {
        return list("reportCode = ?1 order by runAt desc", reportCode);
    }
}
