package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamEvent} (BO-11-02-02/03/04/05), targeting 100% branch
 * coverage.
 *
 * <p>The queries are one-liners and are asserted on their exact text, because that
 * text IS the contract the service depends on: a row ordered any other way would
 * hand a third party a transaction stream it cannot reconcile, and the test is the
 * only place that says so.
 */
class StreamEventTest {

    /** The identity joins subscriber, kind and reference, in that order. */
    @Test
    void theIdentityJoinsSubscriberKindAndReference() {
        Assertions.assertEquals("ANAVEO|TICKET|T1",
                StreamEvent.uidOf("ANAVEO", StreamEvent.Kind.TICKET, "T1"));
        Assertions.assertEquals("MONEX|MONETICS|T1",
                StreamEvent.uidOf("MONEX", StreamEvent.Kind.MONETICS, "T1"));
        // Two kinds of the same reference are two identities: a ticket and its
        // monetics slip are delivered separately, to different subscribers.
        Assertions.assertNotEquals(
                StreamEvent.uidOf("ANAVEO", StreamEvent.Kind.TICKET, "T1"),
                StreamEvent.uidOf("ANAVEO", StreamEvent.Kind.REFUND, "T1"));
    }

    /** A fresh event is pending and stamped, with no attempt and no error. */
    @Test
    void aFreshEventIsPending() {
        StreamEvent event = new StreamEvent();
        Assertions.assertNotNull(event.createdAt);
        Assertions.assertNull(event.deliveredAt);
        Assertions.assertNull(event.lastError);
        Assertions.assertEquals(0, event.attempts);
    }

    /** The identity lookup resolves one row, or nothing. */
    @Test
    @SuppressWarnings("unchecked")
    void theIdentityLookupResolvesOneRow() {
        StreamEvent row = new StreamEvent();
        PanacheQuery<StreamEvent> hit = mock(PanacheQuery.class);
        when(hit.firstResult()).thenReturn(row);
        PanacheQuery<StreamEvent> miss = mock(PanacheQuery.class);
        when(miss.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.find("eventUid", "ANAVEO|TICKET|T1")).thenReturn(hit);
            panache.when(() -> StreamEvent.find("eventUid", "ANAVEO|TICKET|T9")).thenReturn(miss);
            Assertions.assertSame(row, StreamEvent.findByUid("ANAVEO|TICKET|T1"));
            Assertions.assertNull(StreamEvent.findByUid("ANAVEO|TICKET|T9"));
        }
    }

    /** The batch is read OLDEST FIRST, which is the order the shop lived. */
    @Test
    @SuppressWarnings("unchecked")
    void theBatchIsReadOldestFirst() {
        StreamEvent row = new StreamEvent();
        PanacheQuery<StreamEvent> query = mock(PanacheQuery.class);
        when(query.page(0, 50)).thenReturn(query);
        when(query.list()).thenReturn(List.of(row));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.find("deliveredAt is null order by id"))
                    .thenReturn(query);
            Assertions.assertEquals(List.of(row), StreamEvent.nextPending(50));
        }
    }

    /** The pending counts answer on the whole stream and per subscriber. */
    @Test
    void thePendingCountsAnswerGloballyAndPerSubscriber() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.count("deliveredAt is null")).thenReturn(7L);
            panache.when(() -> StreamEvent.count(
                            "providerCode = ?1 and deliveredAt is null", "ANAVEO"))
                    .thenReturn(3L);
            Assertions.assertEquals(7L, StreamEvent.countPending());
            Assertions.assertEquals(3L, StreamEvent.countPendingFor("ANAVEO"));
        }
    }

    /** The healthcheck listing is grouped by subscriber, then by age. */
    @Test
    void theHealthcheckListingIsGroupedBySubscriber() {
        StreamEvent row = new StreamEvent();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StreamEvent.list("deliveredAt is null order by providerCode, id"))
                    .thenReturn(List.of(row));
            Assertions.assertEquals(List.of(row), StreamEvent.listPending());
        }
    }

    /** A delivered row carries its acknowledgement instant. */
    @Test
    void aDeliveredRowCarriesItsInstant() {
        StreamEvent event = new StreamEvent();
        event.deliveredAt = LocalDateTime.of(2026, 3, 17, 18, 31);
        Assertions.assertEquals(LocalDateTime.of(2026, 3, 17, 18, 31), event.deliveredAt);
    }

    /** The four kinds exist and are spelled as the identity uses them. */
    @Test
    void theFourKindsAreNamed() {
        Assertions.assertEquals(4, StreamEvent.Kind.values().length);
        Assertions.assertEquals("ECOMMERCE", StreamEvent.Kind.ECOMMERCE.name());
        Assertions.assertEquals("TICKET", StreamEvent.Kind.TICKET.name());
        Assertions.assertEquals("REFUND", StreamEvent.Kind.REFUND.name());
        Assertions.assertEquals("MONETICS", StreamEvent.Kind.MONETICS.name());
    }
}
