package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One run of a TECHNICAL ENTRY POINT of the end of period (BO-09-02-04): the
 * script the store administers to run before or after its end-of-period
 * treatment, with what it answered.
 */
@Entity
@Table(name = "period_hook_runs")
public class PeriodHookRun extends PanacheEntity {

    /** When, around the treatment, the script runs. */
    public enum Phase {

        /** Before the end-of-period treatment. */
        BEFORE,

        /** After the end-of-period treatment. */
        AFTER
    }

    /** The day whose end ran the script. */
    @Column(name = "period_day", nullable = false)
    public LocalDate periodDay;

    /** Before or after the treatment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 8)
    public Phase phase;

    /** The script's name, in the entry-point directory. */
    @Column(name = "script", nullable = false, length = 120)
    public String script;

    /** Who launched the end of period: the automatic one or the operator. */
    @Column(name = "launched_by", nullable = false, length = 80)
    public String launchedBy;

    /** When the script started. */
    @Column(name = "started_at", nullable = false)
    public LocalDateTime startedAt;

    /** How long it ran, in milliseconds. */
    @Column(name = "duration_ms", nullable = false)
    public long durationMs;

    /** Its exit code, or -1 when it could not run or was stopped. */
    @Column(name = "exit_code", nullable = false)
    public int exitCode;

    /** Whether it ended with a zero exit code. */
    @Column(name = "succeeded", nullable = false)
    public boolean succeeded;

    /** The head of what it printed, or the reason it could not run. */
    @Column(name = "output", length = 2000)
    public String output;

    /**
     * The latest runs, most recent first, for the end-of-day screen.
     *
     * @param limit how many
     * @return the runs
     */
    public static List<PeriodHookRun> latest(int limit) {
        return find("order by startedAt desc, id desc").page(0, limit).list();
    }
}
