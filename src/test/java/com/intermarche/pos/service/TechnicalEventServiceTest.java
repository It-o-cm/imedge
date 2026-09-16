package com.intermarche.pos.service;

import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TechnicalEventService}.
 * <p>
 * The single {@code log} method has no branches: it is a straight-line
 * append that constructs a {@link TechnicalEvent}, stamps it, persists it and
 * enqueues an outbox row. The entity construction is intercepted with
 * {@link org.mockito.Mockito#mockConstruction} so its {@code persist()} is a
 * no-op and so the local id can be seeded for the enqueue assertion; the two
 * collaborators ({@link TicketNumberService} and {@link SyncOutboxService})
 * are Mockito mocks assigned to the package-private injection fields. No
 * database and no Quarkus context are booted.
 */
class TechnicalEventServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /** The local id seeded on the constructed event for the enqueue check. */
    private static final Long EVENT_ID = 42L;

    /**
     * Builds a service instance with both collaborators mocked and its terminal
     * id resolved through the ticket number service.
     *
     * @return a ready-to-use service with mocked collaborators
     */
    private TechnicalEventService newService() {
        TechnicalEventService service = new TechnicalEventService();
        service.ticketNumberService = mock(TicketNumberService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Covers the whole (branch-free) logging path: the event is constructed,
     * stamped with the type, terminal, current date, detail and a minted uid,
     * persisted once, and enqueued once as an EVENT carrying the event id.
     */
    @Test
    void logStampsPersistsAndEnqueuesEvent() {
        TechnicalEventService service = newService();
        LocalDateTime before = LocalDateTime.now();
        try (MockedConstruction<TechnicalEvent> created = mockConstruction(TechnicalEvent.class,
                (mock, context) -> mock.id = EVENT_ID)) {
            service.log(TechnicalEvent.EventType.TICKET_CLOSED, "C04-00000123");
            assertEquals(1, created.constructed().size());
            TechnicalEvent event = created.constructed().get(0);
            assertEquals(TechnicalEvent.EventType.TICKET_CLOSED, event.eventType);
            assertEquals(TERMINAL, event.terminalId);
            assertEquals("C04-00000123", event.detail);
            assertNull(event.operatorBadgeId);
            assertNotNull(event.eventDate);
            assertTrue(!event.eventDate.isBefore(before));
            assertNotNull(event.eventUid);
            assertEquals(36, event.eventUid.length());
            verify(event, times(1)).persist();
            verify(service.syncOutboxService, times(1))
                    .enqueue(SyncOutbox.EntityType.EVENT, EVENT_ID);
            verify(service.ticketNumberService, times(1)).getTerminalId();
        }
    }

    /**
     * Covers the operator-bearing overload: the badge is stored in its own
     * column, never inside the detail, so an operator-range search compares a
     * badge against badges.
     */
    @Test
    void logStoresTheOperatorBadgeInItsOwnColumn() {
        TechnicalEventService service = newService();
        try (MockedConstruction<TechnicalEvent> created = mockConstruction(TechnicalEvent.class,
                (mock, context) -> mock.id = EVENT_ID)) {
            service.log(TechnicalEvent.EventType.PASSWORD_FAILED, null, "12341234");
            TechnicalEvent event = created.constructed().get(0);
            assertEquals(TechnicalEvent.EventType.PASSWORD_FAILED, event.eventType);
            assertNull(event.detail);
            assertEquals("12341234", event.operatorBadgeId);
            verify(event, times(1)).persist();
            verify(service.syncOutboxService, times(1))
                    .enqueue(SyncOutbox.EntityType.EVENT, EVENT_ID);
        }
    }

    /**
     * Confirms that two successive logs mint distinct uids, so the sync
     * idempotency key is unique per journal entry rather than a shared constant.
     */
    @Test
    void logMintsDistinctUidPerEvent() {
        TechnicalEventService service = newService();
        try (MockedConstruction<TechnicalEvent> created = mockConstruction(TechnicalEvent.class)) {
            service.log(TechnicalEvent.EventType.SESSION_OPENED, "first");
            service.log(TechnicalEvent.EventType.SESSION_CLOSED, "second");
            assertEquals(2, created.constructed().size());
            TechnicalEvent first = created.constructed().get(0);
            TechnicalEvent second = created.constructed().get(1);
            assertNotNull(first.eventUid);
            assertNotNull(second.eventUid);
            assertTrue(!first.eventUid.equals(second.eventUid));
        }
    }

    /**
     * Confirms a null detail is stored verbatim: the detail column is nullable
     * and {@code log} performs no substitution.
     */
    @Test
    void logAcceptsNullDetail() {
        TechnicalEventService service = newService();
        try (MockedConstruction<TechnicalEvent> created = mockConstruction(TechnicalEvent.class,
                (mock, context) -> mock.id = EVENT_ID)) {
            service.log(TechnicalEvent.EventType.DRAFT_RECOVERED, null);
            TechnicalEvent event = created.constructed().get(0);
            assertSame(null, event.detail);
            verify(service.syncOutboxService, times(1))
                    .enqueue(SyncOutbox.EntityType.EVENT, EVENT_ID);
        }
    }
}
