package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One Portuguese fiscal document sent AT THE FLOW to one fiscal endpoint in
 * the SAF-T (PT) format ({@code BO-05-01-05}).
 *
 * <p>One row per document and per endpoint, so a document is never sent twice
 * to the same one and an endpoint down for a day finds its whole backlog when it
 * comes back. Nothing is dropped: a failed send is tried again after the
 * administered delay, and the operator relaunches the failures by hand.
 */
@Entity
@Table(name = "saft_transmissions",
        indexes = {
                @Index(name = "idx_saft_transmission_uid", columnList = "transmission_uid", unique = true),
                @Index(name = "idx_saft_transmission_status", columnList = "status")
        })
public class SaftTransmission extends PanacheEntity {

    /** Where a send stands. */
    public enum Status {

        /** Waiting for its send. */
        PENDING,

        /** Sent and acknowledged. */
        SENT,

        /** The send failed; it is tried again. */
        FAILED
    }

    /** The identity of the row: the endpoint's code, a bar, the document number. */
    @Column(name = "transmission_uid", nullable = false, length = 100)
    public String uid;

    /** The code of the fiscal endpoint (service provider of protocol SAFT). */
    @Column(name = "provider_code", nullable = false, length = 30)
    public String providerCode;

    /** The number of the fiscal document. */
    @Column(name = "document_number", nullable = false, length = 60)
    public String documentNumber;

    /** The type of the fiscal document. */
    @Column(name = "document_type", nullable = false, length = 2)
    public String documentType;

    /** When the document was issued. */
    @Column(name = "issued_at", nullable = false)
    public LocalDateTime issuedAt;

    /** The SAF-T file of the document, frozen when queued. */
    @Lob
    @Column(name = "payload", nullable = false)
    public String payload;

    /** Where the send stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    public Status status = Status.PENDING;

    /** How many sends failed. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** The last failure, or null. */
    @Column(name = "last_error", length = 255)
    public String lastError;

    /** When the next send may be tried. */
    @Column(name = "next_attempt_at")
    public LocalDateTime nextAttemptAt;

    /** When the document was queued. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    /** When it was sent, or null. */
    @Column(name = "sent_at")
    public LocalDateTime sentAt;

    /** What the endpoint answered, or null. */
    @Column(name = "acknowledgement", length = 255)
    public String acknowledgement;

    /**
     * The identity of the row of a document for an endpoint.
     *
     * @param providerCode the endpoint's code
     * @param documentNumber the document number
     * @return the identity
     */
    public static String uidOf(String providerCode, String documentNumber) {
        return providerCode + "|" + documentNumber;
    }

    /**
     * Finds the row of an identity.
     *
     * @param uid the identity
     * @return the row, or null
     */
    public static SaftTransmission findByUid(String uid) {
        return SaftTransmission.<SaftTransmission>find("uid", uid).firstResult();
    }

    /**
     * The sends due: pending, or failed with their delay elapsed.
     *
     * @param now the instant of the tick
     * @param limit how many at most
     * @return the rows, oldest first
     */
    public static List<SaftTransmission> due(LocalDateTime now, int limit) {
        return SaftTransmission.<SaftTransmission>find(
                "status in ?1 and (nextAttemptAt is null or nextAttemptAt <= ?2) order by id",
                List.of(Status.PENDING, Status.FAILED), now).page(0, limit).list();
    }

    /**
     * How many rows stand in a status.
     *
     * @param status the status
     * @return the count
     */
    public static long countIn(Status status) {
        return SaftTransmission.count("status", status);
    }

    /**
     * The latest rows, newest first.
     *
     * @param limit how many at most
     * @return the rows
     */
    public static List<SaftTransmission> latest(int limit) {
        return SaftTransmission.<SaftTransmission>find("order by id desc").page(0, limit).list();
    }
}
