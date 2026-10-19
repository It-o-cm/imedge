package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One report EDITED AUTOMATICALLY at the end of a period
 * ({@code BO-09-01-10}).
 *
 * <p>The document is kept, bytes and all, and not merely the fact that it was
 * produced: the point of editing a state at the end of the period is to hold
 * what the period looked like THEN, and the data behind it is purged by the
 * retention sweep that runs in the same pass. A row that only said "the
 * settlement state was produced" would be a row nobody could read.
 *
 * <p>TECHNICAL AND NOT ADMINISTERED, hence {@link PanacheEntity} and not the
 * audited base entity: nobody edits these rows, the end of period writes them
 * and the back office reads them.
 *
 * <p>A failed edition is written too, with its reason and no bytes: a state
 * that was programmed and did not come out is exactly what an operator needs
 * to see the next morning.
 */
@Entity
@Table(name = "scheduled_report_runs",
        indexes = {
                @Index(name = "idx_scheduled_report_period", columnList = "period_end"),
                @Index(name = "idx_scheduled_report_code", columnList = "report_code")
        }
)
public class ScheduledReportRun extends PanacheEntity {

    /** The code of the report that was edited. */
    @Column(name = "report_code", nullable = false, length = 40)
    public String reportCode;

    /** Its label, frozen: a report renamed later must not rename its archive. */
    @Column(name = "report_label", length = 120)
    public String reportLabel;

    /** The format it was rendered in: {@code xlsx}, {@code csv} or {@code pdf}. */
    @Column(name = "format", nullable = false, length = 8)
    public String format;

    /** The period whose end produced it. */
    @Column(name = "period_end", nullable = false)
    public LocalDate periodEnd;

    /** When it was produced. */
    @Column(name = "generated_at", nullable = false)
    public LocalDateTime generatedAt;

    /** How many rows it carried, zero on a failed edition. */
    @Column(name = "row_count", nullable = false)
    public int rowCount;

    /** True when the edition succeeded. */
    @Column(name = "succeeded", nullable = false)
    public boolean succeeded;

    /** Why it failed, or null when it did not. */
    @Column(name = "failure", length = 255)
    public String failure;

    /**
     * The rendered document, or null on a failed edition.
     *
     * <p>Lazily fetched: the listing screen reads every other column of every
     * row, and loading the documents to draw a list would be the one mistake
     * that makes this table unusable.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content")
    public byte[] content;

    /**
     * The editions of one period end, newest report first.
     *
     * @param periodEnd the period end to list
     * @return the editions, possibly empty
     */
    public static List<ScheduledReportRun> ofPeriod(LocalDate periodEnd) {
        return list("periodEnd = ?1 order by reportCode", periodEnd);
    }

    /**
     * The most recent editions, newest first.
     *
     * @param limit how many to return
     * @return the editions, possibly empty
     */
    public static List<ScheduledReportRun> latest(int limit) {
        return find("order by generatedAt desc, reportCode").page(0, Math.max(1, limit)).list();
    }
}
