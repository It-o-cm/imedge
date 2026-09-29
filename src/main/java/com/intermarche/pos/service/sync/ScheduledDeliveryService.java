package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.sync.ScheduledDelivery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeper of the DEFERRED engine-feed deliveries booked from the supervision
 * screen ({@link ScheduledDelivery}, GB-01-01-02): it books a row, lists the
 * bookings, cancels a pending one and records the outcome once the delivery
 * loop has run it.
 * <p>
 * It owns the persistence boundary so the delivery loop — a bare thread with no
 * request context of its own — can claim the due bookings as detached snapshots
 * ({@link Due}) and report each result through a fresh transaction. The service
 * never delivers anything itself; the loop does, then calls
 * {@link #markResult(Long, String)}.
 */
@ApplicationScoped
public class ScheduledDeliveryService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ScheduledDeliveryService.class);

    /**
     * A due booking, detached from the persistence context so the loop thread
     * can use it freely.
     *
     * @param id the booking identifier
     * @param feedCode the feed to deliver, or null for every feed
     */
    public record Due(Long id, String feedCode) {
    }

    /**
     * Books a deferred delivery. A blank feed code is normalised to null (every
     * feed); the row starts {@code PENDING}.
     *
     * @param feedCode the feed to deliver, blank or null for every feed
     * @param scheduledAt the minute at or after which the loop should run it
     * @param createdBy the login of the administrator booking it
     * @return the persisted booking
     */
    @Transactional
    public ScheduledDelivery schedule(String feedCode, LocalDateTime scheduledAt, String createdBy) {
        LOGGER.info("Entering method schedule with feedCode: " + feedCode + ", scheduledAt: " + scheduledAt);
        ScheduledDelivery scheduled = new ScheduledDelivery();
        scheduled.feedCode = feedCode == null || feedCode.isBlank() ? null : feedCode;
        scheduled.scheduledAt = scheduledAt;
        scheduled.createdBy = createdBy;
        scheduled.createdAt = LocalDateTime.now();
        scheduled.status = ScheduledDelivery.Status.PENDING;
        scheduled.persist();
        LOGGER.info("Exiting method schedule");
        return scheduled;
    }

    /**
     * Cancels a booking while it is still {@code PENDING} by deleting it; a
     * missing row or an already-run row (DONE/FAILED) is left untouched.
     *
     * @param id the booking identifier
     * @return true when a pending booking was cancelled, false otherwise
     */
    @Transactional
    public boolean cancel(Long id) {
        LOGGER.info("Entering method cancel with id: " + id);
        ScheduledDelivery scheduled = ScheduledDelivery.findById(id);
        if (scheduled == null || scheduled.status != ScheduledDelivery.Status.PENDING) {
            LOGGER.info("Exiting method cancel");
            return false;
        }
        scheduled.delete();
        LOGGER.info("Exiting method cancel");
        return true;
    }

    /**
     * Claims the due bookings as detached snapshots so the delivery loop can run
     * them outside any persistence context.
     *
     * @return the due bookings, oldest first, never null
     */
    @Transactional
    public List<Due> claimDue() {
        LOGGER.info("Entering method claimDue");
        List<Due> due = new ArrayList<>();
        for (ScheduledDelivery scheduled : ScheduledDelivery.findDuePending(LocalDateTime.now())) {
            due.add(new Due(scheduled.id, scheduled.feedCode));
        }
        LOGGER.info("Exiting method claimDue");
        return due;
    }

    /**
     * Records the outcome of a run booking: {@code DONE} when the delivery
     * surfaced no error, {@code FAILED} (with the truncated message) otherwise.
     * A booking cancelled between the claim and the result is gone, so a missing
     * row is a no-op.
     *
     * @param id the booking identifier
     * @param error the surfaced error, or null on success
     */
    @Transactional
    public void markResult(Long id, String error) {
        LOGGER.info("Entering method markResult with id: " + id + ", error: " + error);
        ScheduledDelivery scheduled = ScheduledDelivery.findById(id);
        if (scheduled == null) {
            LOGGER.info("Exiting method markResult");
            return;
        }
        if (error == null) {
            scheduled.status = ScheduledDelivery.Status.DONE;
            scheduled.message = null;
        } else {
            scheduled.status = ScheduledDelivery.Status.FAILED;
            scheduled.message = error.length() > 500 ? error.substring(0, 500) : error;
        }
        scheduled.persist();
        LOGGER.info("Exiting method markResult");
    }

    /**
     * Lists every booking, newest first, for the supervision screen.
     *
     * @return all bookings, newest first, never null
     */
    @Transactional
    public List<ScheduledDelivery> list() {
        LOGGER.info("Entering method list");
        LOGGER.info("Exiting method list");
        return ScheduledDelivery.listRecent();
    }
}
