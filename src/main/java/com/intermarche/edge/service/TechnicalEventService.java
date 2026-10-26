package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sync.SyncOutbox;
import com.intermarche.edge.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

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

    /**
     * Records a technical event carrying the searchable facts the journal and
     * the dashboards filter on — the ticket it is about, its amount and its
     * code (BO-04-01-19/34, BO-04-03-04/07/08/14). The detail is cut to the
     * column's 255 characters rather than failing the gesture it documents.
     *
     * @param type the event type
     * @param detail a short human-readable detail, or null
     * @param operatorBadgeId the badge of the operator the event is about, or null
     * @param ticketNumber the number of the ticket the event is about, or null
     * @param amount the amount the event bears, or null
     * @param code the searchable code the event carries, or null
     * @return the recorded event
     */
    @Transactional
    public TechnicalEvent record(TechnicalEvent.EventType type, String detail, String operatorBadgeId,
                                 String ticketNumber, java.math.BigDecimal amount, String code) {
        LOGGER.info("Entering method record with type: " + type + ", detail: " + detail);
        TechnicalEvent event = new TechnicalEvent();
        event.eventType = type;
        event.terminalId = ticketNumberService.getTerminalId();
        event.eventDate = LocalDateTime.now();
        event.detail = detail != null && detail.length() > 255 ? detail.substring(0, 255) : detail;
        event.operatorBadgeId = operatorBadgeId;
        event.ticketNumber = ticketNumber;
        event.amount = amount;
        event.code = code != null && code.length() > 120 ? code.substring(0, 120) : code;
        event.eventUid = java.util.UUID.randomUUID().toString();
        event.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.EVENT, event.id);
        LOGGER.info("Exiting method record");
        return event;
    }

    /**
     * Names, after the fact, the ticket an already-recorded event is about
     * (BO-04-01-34): an unknown code scanned before the first article has no
     * ticket yet; once the ticket exists, the event is stamped with its number
     * and pushed again — the store node upserts it by its uid.
     *
     * @param eventId the id of the event to stamp
     * @param ticketNumber the number of its ticket
     */
    @Transactional
    public void attachTicket(Long eventId, String ticketNumber) {
        LOGGER.info("Entering method attachTicket with eventId: " + eventId + ", ticketNumber: " + ticketNumber);
        TechnicalEvent event = TechnicalEvent.findById(eventId);
        if (event != null && event.ticketNumber == null) {
            event.ticketNumber = ticketNumber;
            event.persist();
            syncOutboxService.enqueue(SyncOutbox.EntityType.EVENT, event.id);
        }
        LOGGER.info("Exiting method attachTicket");
    }
}
