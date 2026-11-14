package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link FidEvent}.
 * <p>
 * A persisted row with no behaviour of its own beyond its defaults and one
 * finder, so the tests are short — but two properties are worth pinning: a
 * fresh row starts PENDING with zero attempts (nothing is presumed sent), and
 * {@code findPending} selects on {@code sentAt} being null and orders by id,
 * which is what makes a drain replay the oldest events first. Under plain
 * {@code mvn test} entities are un-enhanced, so the finder is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}.
 */
class FidEventTest {

    /**
     * A fresh row is PENDING: never sent, never attempted, no error. The
     * defaults matter — an event whose {@code sentAt} were preset would be
     * dropped by the very first drain without ever reaching imfid.
     */
    @Test
    void freshEventIsPendingWithNoAttempt() {
        FidEvent event = new FidEvent();
        assertNull(event.sentAt);
        assertEquals(0, event.attempts);
        assertNull(event.lastError);
        assertNull(event.payloadJson);
        assertNull(event.eventType);
    }

    /**
     * The row carries what the drain needs and nothing more: the kind (which
     * picks the endpoint), the FROZEN payload (posted as-is, since the
     * verbatim valuation couple only exists in memory at the fiscal moment)
     * and the enqueue instant.
     */
    @Test
    void eventCarriesKindPayloadAndCreationInstant() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 10, 30);
        FidEvent event = new FidEvent();
        event.eventType = FidEvent.EventType.TICKET_CLOSED;
        event.payloadJson = "{\"ticketRef\":\"2026-C04-000001\"}";
        event.createdAt = now;

        assertEquals(FidEvent.EventType.TICKET_CLOSED, event.eventType);
        assertEquals("{\"ticketRef\":\"2026-C04-000001\"}", event.payloadJson);
        assertEquals(now, event.createdAt);
    }

    /**
     * The two event kinds are the two fiscal declarations of the loyalty
     * integration — a close and a return — and nothing else: any third kind
     * would need its own endpoint in the drain.
     */
    @Test
    void thereAreExactlyTwoEventKinds() {
        assertEquals(2, FidEvent.EventType.values().length);
        assertEquals(FidEvent.EventType.TICKET_CLOSED, FidEvent.EventType.valueOf("TICKET_CLOSED"));
        assertEquals(FidEvent.EventType.TICKET_RETURN, FidEvent.EventType.valueOf("TICKET_RETURN"));
    }

    /**
     * {@code findPending} selects the UNSENT rows, OLDEST FIRST: the query is
     * the contract of the drain — filtering on {@code sentAt} keeps an
     * accepted event from being posted twice, and ordering by id replays a
     * backlog in the order the sales happened.
     */
    @Test
    void findPendingSelectsUnsentRowsOldestFirst() {
        FidEvent first = new FidEvent();
        FidEvent second = new FidEvent();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenReturn(List.of(first, second));

            List<FidEvent> pending = FidEvent.findPending();

            assertEquals(2, pending.size());
            assertSame(first, pending.get(0));
            assertSame(second, pending.get(1));
        }
    }

    /**
     * An empty registry yields an empty list rather than null, so the drain
     * loop can iterate without a guard.
     */
    @Test
    void findPendingYieldsAnEmptyListWhenNothingIsPending() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenReturn(List.of());
            assertTrue(FidEvent.findPending().isEmpty());
        }
    }
}
