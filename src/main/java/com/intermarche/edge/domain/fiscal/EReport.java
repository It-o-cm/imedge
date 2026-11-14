package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An E-REPORTING TRANSMISSION ({@code BO-05-01-03}): the transaction data of a
 * period that no electronic invoice carries — the sales to consumers and the
 * sales to operators established abroad — aggregated as the administration
 * asks, transmitted through the dematerialisation platform, and its
 * acknowledgement.
 *
 * <p>The payload is frozen when the period is extracted: what was transmitted
 * is what is shown, and a ticket pushed late by a register does not silently
 * change a period already acknowledged — it is caught by extracting the period
 * again, which the screen offers until the transmission is accepted.
 */
@Entity
@Table(name = "e_reports",
        indexes = {
                @Index(name = "idx_e_report_period", columnList = "period_start, period_end", unique = true)
        })
public class EReport extends PanacheEntity {

    /** Where a transmission stands. */
    public enum Status {

        /** Extracted, not yet transmitted. */
        PENDING(false),

        /** The transmission failed; it is tried again. */
        FAILED(false),

        /** Transmitted, waiting for its acknowledgement. */
        SENT(false),

        /** Acknowledged and accepted by the administration. */
        ACCEPTED(true),

        /** Acknowledged and rejected: to be corrected and extracted again. */
        REJECTED(false);

        /** Whether the period is closed for good. */
        private final boolean last;

        /**
         * Builds a status.
         *
         * @param last whether the period is closed for good
         */
        Status(boolean last) {
            this.last = last;
        }

        /**
         * Tells whether the period is closed for good.
         *
         * @return true once accepted
         */
        public boolean isLast() {
            return last;
        }
    }

    /** The first day of the period. */
    @Column(name = "period_start", nullable = false)
    public LocalDate periodStart;

    /** The last day of the period, inclusive. */
    @Column(name = "period_end", nullable = false)
    public LocalDate periodEnd;

    /** How many transactions the period counts. */
    @Column(name = "transaction_count", nullable = false)
    public int transactionCount;

    /** The total tax excluded. */
    @Column(name = "total_excluding_tax", nullable = false, precision = 19, scale = 2)
    public BigDecimal totalExcludingTax = BigDecimal.ZERO;

    /** The VAT. */
    @Column(name = "total_vat", nullable = false, precision = 19, scale = 2)
    public BigDecimal totalVat = BigDecimal.ZERO;

    /** The total tax included. */
    @Column(name = "total_including_tax", nullable = false, precision = 19, scale = 2)
    public BigDecimal totalIncludingTax = BigDecimal.ZERO;

    /** The transmitted data, frozen at extraction. */
    @Lob
    @Column(name = "payload", nullable = false)
    public String payload;

    /** Where the transmission stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    public Status status = Status.PENDING;

    /** The acknowledgement's identifier, or null. */
    @Column(name = "ack_id", length = 80)
    public String ackId;

    /** What the acknowledgement or the last failure said, or null. */
    @Column(name = "message", length = 255)
    public String message;

    /** How many transmissions failed. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** When the next transmission may be tried. */
    @Column(name = "next_attempt_at")
    public LocalDateTime nextAttemptAt;

    /** When the period was extracted. */
    @Column(name = "generated_at", nullable = false)
    public LocalDateTime generatedAt;

    /** When it was transmitted, or null. */
    @Column(name = "sent_at")
    public LocalDateTime sentAt;

    /** When it was acknowledged, or null. */
    @Column(name = "ack_at")
    public LocalDateTime ackAt;

    /**
     * The transmission of a period.
     *
     * @param start the first day
     * @param end the last day
     * @return the transmission, or null when the period was never extracted
     */
    public static EReport forPeriod(LocalDate start, LocalDate end) {
        return EReport.<EReport>find("periodStart = ?1 and periodEnd = ?2", start, end).firstResult();
    }

    /**
     * Finds a transmission by its acknowledgement's identifier.
     *
     * @param ackId the identifier
     * @return the transmission, or null
     */
    public static EReport findByAck(String ackId) {
        return EReport.<EReport>find("ackId", ackId).firstResult();
    }

    /**
     * The transmissions due: extracted or failed, their delay elapsed, and of a
     * period already over — a period extracted by hand before its end is sent
     * only by hand, never half-made by the automatic transmission.
     *
     * @param now the instant of the tick
     * @return the transmissions, oldest period first
     */
    public static List<EReport> due(LocalDateTime now) {
        return EReport.<EReport>find("status in ?1 and (nextAttemptAt is null or nextAttemptAt <= ?2) "
                + "and periodEnd < ?3 order by periodStart", List.of(Status.PENDING, Status.FAILED), now,
                now.toLocalDate()).list();
    }

    /**
     * The latest transmissions, newest period first.
     *
     * @param limit how many at most
     * @return the transmissions
     */
    public static List<EReport> latest(int limit) {
        return EReport.<EReport>find("order by periodStart desc").page(0, limit).list();
    }
}
