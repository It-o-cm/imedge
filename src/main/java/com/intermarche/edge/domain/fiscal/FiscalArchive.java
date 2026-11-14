package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A FISCAL FILE the point of sale keeps at the disposal of the tax control and
 * of its management controller: the two article-88 archives of a period
 * ({@code BO-05-01-01}) and the monthly SAF-T file extracted for the Portuguese
 * administration ({@code BO-05-01-04}).
 *
 * <p>The file is kept AS IT WAS PRODUCED, with its SHA-256 fingerprint: an
 * archive is evidence, and evidence that can be rebuilt differently the next
 * day is not evidence. Producing a period again writes a new row beside the
 * first, it never overwrites it.
 */
@Entity
@Table(name = "fiscal_archives",
        indexes = {
                @Index(name = "idx_fiscal_archive_period", columnList = "kind, period_from, period_to")
        })
public class FiscalArchive extends PanacheEntity {

    /** What the file is. */
    public enum Kind {

        /** TaxAuditExtracts: the detailed data of the period (BO-05-01-01). */
        TAX_AUDIT_EXTRACTS("TaxAuditExtracts"),

        /** TaxAuditReports: the aggregated reports of the period (BO-05-01-01). */
        TAX_AUDIT_REPORTS("TaxAuditReports"),

        /** The monthly SAF-T (PT) file of the point of sale (BO-05-01-04). */
        SAFT_MONTHLY("SAFT-PT");

        /** The prefix of the file name. */
        private final String prefix;

        /**
         * Builds a kind.
         *
         * @param prefix the prefix of the file name
         */
        Kind(String prefix) {
            this.prefix = prefix;
        }

        /**
         * Returns the prefix of the file name.
         *
         * @return the prefix
         */
        public String getPrefix() {
            return prefix;
        }
    }

    /** What the file is. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    public Kind kind;

    /** The point of sale the file belongs to, as numbered in the network. */
    @Column(name = "pdv_number", nullable = false, length = 20)
    public String pdvNumber;

    /** The first day the file covers. */
    @Column(name = "period_from", nullable = false)
    public LocalDate periodFrom;

    /** The last day the file covers, inclusive. */
    @Column(name = "period_to", nullable = false)
    public LocalDate periodTo;

    /** The name the file downloads under. */
    @Column(name = "file_name", nullable = false, length = 120)
    public String fileName;

    /** The file itself. */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false)
    public byte[] content;

    /** The SHA-256 fingerprint of the file, hexadecimal. */
    @Column(name = "sha256", nullable = false, length = 64)
    public String sha256;

    /** The size of the file, in bytes. */
    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    /** When the file was produced. */
    @Column(name = "generated_at", nullable = false)
    public LocalDateTime generatedAt;

    /** Who produced it: an operator, or the node itself. */
    @Column(name = "generated_by", nullable = false, length = 60)
    public String generatedBy;

    /** Whether the node produced it by itself. */
    @Column(name = "automatic", nullable = false)
    public boolean automatic;

    /** When the point of sale declared the file transmitted to the administration, or null. */
    @Column(name = "transmitted_at")
    public LocalDateTime transmittedAt;

    /** Who declared it transmitted, or null. */
    @Column(name = "transmitted_by", length = 60)
    public String transmittedBy;

    /**
     * The files of a kind covering exactly a period, newest first.
     *
     * @param kind the kind
     * @param from the first day
     * @param to the last day
     * @return the files, empty when the period was never produced
     */
    public static List<FiscalArchive> forPeriod(Kind kind, LocalDate from, LocalDate to) {
        return FiscalArchive.<FiscalArchive>find("kind = ?1 and periodFrom = ?2 and periodTo = ?3 order by id desc",
                kind, from, to).list();
    }

    /**
     * The latest files of some kinds, newest first.
     *
     * @param kinds the kinds listed
     * @param limit how many at most
     * @return the files
     */
    public static List<FiscalArchive> latest(List<Kind> kinds, int limit) {
        return FiscalArchive.<FiscalArchive>find("kind in ?1 order by generatedAt desc, id desc", kinds)
                .page(0, limit).list();
    }

    /**
     * The size of the file in kilobytes, for the screen.
     *
     * @return the size, rounded up
     */
    public long getSizeKb() {
        return (sizeBytes + 1023) / 1024;
    }
}
