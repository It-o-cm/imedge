package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sync.SyncOutbox;
import com.intermarche.edge.service.sync.register.SyncOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;
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
            assertFalse(event.eventDate.isBefore(before));
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
            assertFalse(first.eventUid.equals(second.eventUid));
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

    /**
     * {@code record} stores the searchable facts beside the event and cuts a
     * long detail and a long code to their columns (BO-04-01-19/34).
     */
    @Test
    void recordStoresTheSearchableFacts() {
        TechnicalEventService service = newService();
        try (MockedConstruction<TechnicalEvent> created = mockConstruction(TechnicalEvent.class,
                (mock, context) -> mock.id = EVENT_ID)) {
            TechnicalEvent event = service.record(TechnicalEvent.EventType.PAYMENT_CANCELLED, "T1 CHEQUE", "1",
                    "T1", java.math.BigDecimal.TEN, "CHEQUE");
            assertSame(created.constructed().get(0), event);
            assertEquals("T1", event.ticketNumber);
            assertEquals(java.math.BigDecimal.TEN, event.amount);
            assertEquals("CHEQUE", event.code);
            assertEquals("1", event.operatorBadgeId);
            assertEquals(TERMINAL, event.terminalId);
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.EVENT, EVENT_ID);
            TechnicalEvent cut = service.record(TechnicalEvent.EventType.CONTROL_ALERT, "d".repeat(300), null,
                    null, null, "c".repeat(200));
            assertEquals(255, cut.detail.length());
            assertEquals(120, cut.code.length());
            TechnicalEvent bare = service.record(TechnicalEvent.EventType.DRAWER_OPENED, null, null, null, null, null);
            assertNull(bare.detail);
            assertNull(bare.code);
        }
    }

    /**
     * {@code attachTicket} names the ticket of an event that had none and pushes
     * it again; an unknown event or one already named is left alone (BO-04-01-34).
     */
    @Test
    void attachTicketNamesAnUnnamedEvent() {
        TechnicalEventService service = newService();
        TechnicalEvent unnamed = mock(TechnicalEvent.class);
        unnamed.id = EVENT_ID;
        TechnicalEvent named = mock(TechnicalEvent.class);
        named.ticketNumber = "T0";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.findById(1L)).thenReturn(unnamed);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.findById(2L)).thenReturn(named);
            service.attachTicket(1L, "T9");
            service.attachTicket(2L, "T9");
            service.attachTicket(3L, "T9");
        }
        assertEquals("T9", unnamed.ticketNumber);
        verify(unnamed).persist();
        assertEquals("T0", named.ticketNumber);
        verify(named, never()).persist();
        verify(service.syncOutboxService, times(1)).enqueue(SyncOutbox.EntityType.EVENT, EVENT_ID);
    }
}
