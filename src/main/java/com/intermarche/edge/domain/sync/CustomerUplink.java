package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One customer file awaiting the ACKNOWLEDGMENT of the commercial management
 * (BO-02-04-13): the state of a customer in account as the hostess left it at a
 * register or in the back office, to be sent up until the commercial
 * management confirms it holds it.
 *
 * <p>ONE ROW PER ACCOUNT. A file corrected three times before the link comes
 * back is one pending row carrying the latest state, not three calls the
 * commercial management would have to replay in order: what it must end up
 * holding is the file of now. The row is kept once acknowledged — the date of
 * the acknowledgment is what the back office shows beside the file — and a new
 * correction simply makes it pending again.
 *
 * <p>The PAYLOAD is frozen at enqueue time, like the at-the-flow stream: the
 * commercial management is told what the hostess typed, and the delivery is a
 * pure write with nothing lazy to re-read. Nothing is ever dropped: there is no
 * attempt ceiling, the requirement asks for the call to be replayed until the
 * update is done.
 */
@Entity
@Table(name = "customer_uplink",
        indexes = {
                @Index(name = "idx_customer_uplink_account", columnList = "account_number", unique = true),
                @Index(name = "idx_customer_uplink_pending", columnList = "acknowledged_at, id")
        })
public class CustomerUplink extends PanacheEntity {

    /** Where a correction was made. */
    public enum Source {

        /** On the customer screen of the back office. */
        BACK_OFFICE,

        /** At a register, on the invoice customer screen. */
        REGISTER
    }

    /** The account number of the customer, the identity of the row. */
    @Column(name = "account_number", nullable = false, length = 50)
    public String accountNumber;

    /** Where the last correction was made. */
    @Column(name = "source", nullable = false, length = 12)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    public Source source;

    /** The frozen JSON body to POST, as it was at enqueue time. */
    @Lob
    @Column(name = "payload", nullable = false)
    public String payload;

    /** When the latest correction was enqueued. */
    @Column(name = "changed_at", nullable = false)
    public LocalDateTime changedAt;

    /** Number of failed delivery attempts of the latest correction. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** When the last attempt was made, or null while none was. */
    @Column(name = "last_attempt_at")
    public LocalDateTime lastAttemptAt;

    /** The last delivery error, or null while none happened. */
    @Column(name = "last_error", length = 255)
    public String lastError;

    /** When the commercial management acknowledged the latest correction, or null while pending. */
    @Column(name = "acknowledged_at")
    public LocalDateTime acknowledgedAt;

    /**
     * Finds the row of one account.
     *
     * @param accountNumber the account number, possibly null
     * @return the row, or null when the account never was sent up
     */
    public static CustomerUplink findByAccount(String accountNumber) {
        if (accountNumber == null) {
            return null;
        }
        return find("accountNumber", accountNumber).firstResult();
    }

    /**
     * Reads the next rows awaiting acknowledgment, oldest first.
     *
     * @param batchSize how many to take at most
     * @return the pending rows, oldest first
     */
    public static List<CustomerUplink> nextPending(int batchSize) {
        return find("acknowledgedAt is null order by id").page(0, batchSize).list();
    }

    /**
     * Counts the rows awaiting acknowledgment.
     *
     * @return the pending count, zero when the commercial management is up to date
     */
    public static long countPending() {
        return count("acknowledgedAt is null");
    }

    /**
     * Tells whether the latest correction still awaits its acknowledgment.
     *
     * @return true while pending
     */
    public boolean isPending() {
        return acknowledgedAt == null;
    }
}
