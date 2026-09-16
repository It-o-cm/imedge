package com.intermarche.pos.service;

import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import org.jboss.logging.Logger;

/**
 * Appends entries to the technical event journal, stamped with this register's
 * identifier. Joins the caller's transaction when one is active, so an event
 * and the state change it documents commit or roll back together.
 * <p>
 * The event uid (sync idempotency key) is minted HERE, at creation — the
 * journal has no natural business number, and the uid must exist before the
 * outbox row referencing the event is written in the same transaction. A
 * rolled-back business change therefore leaves neither the event nor its
 * outbox row: the journal never documents something that did not happen.
 */
@ApplicationScoped
public class TechnicalEventService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TechnicalEventService.class);

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Records a technical event that is about no particular operator.
     *
     * @param type the event type
     * @param detail a short human-readable detail (ticket number, count...)
     */
    @Transactional
    public void log(TechnicalEvent.EventType type, String detail) {
        LOGGER.info("Entering method log with type: " + type + ", detail: " + detail);
        log(type, detail, null);
        LOGGER.info("Exiting method log");
    }

    /**
     * Records a technical event in the journal, naming the operator it is
     * about. The badge is stored in its own column so the journal can search
     * operator ranges exactly; it never travels inside the free-text detail.
     *
     * @param type the event type
     * @param detail a short human-readable detail (ticket number, count...)
     * @param operatorBadgeId the badge of the operator the event is about, or
     *        null when the event has no operator
     */
    @Transactional
    public void log(TechnicalEvent.EventType type, String detail, String operatorBadgeId) {
        LOGGER.info("Entering method log with type: " + type + ", detail: " + detail + ", operatorBadgeId: " + operatorBadgeId);
        TechnicalEvent event = new TechnicalEvent();
        event.eventType = type;
        event.terminalId = ticketNumberService.getTerminalId();
        event.eventDate = LocalDateTime.now();
        event.detail = detail;
        event.operatorBadgeId = operatorBadgeId;
        event.eventUid = java.util.UUID.randomUUID().toString();
        event.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.EVENT, event.id);
        LOGGER.info("Exiting method log");
    }
}
