package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.sync.ScheduledDelivery;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ScheduledDeliveryService}: booking (with the blank/null
 * feed-code normalisation), cancellation (pending, already-run, missing),
 * the due claim into detached snapshots, the outcome recorder (DONE, FAILED with
 * truncation, missing) and the listing. Pure unit — the entity statics resolve to
 * {@link PanacheEntityBase} and are intercepted with {@code mockStatic}; the
 * created row is intercepted with {@code mockConstruction}.
 */
class ScheduledDeliveryServiceTest {

    /** The service under test — stateless, one instance suffices. */
    private final ScheduledDeliveryService service = new ScheduledDeliveryService();

    /**
     * {@code schedule} with a feed code books that feed alone (non-blank arm):
     * the row carries the code, the date, the author, a creation stamp and the
     * PENDING state, and it is persisted and returned.
     */
    @Test
    void scheduleBooksOneFeed() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 14, 30);
        try (MockedConstruction<ScheduledDelivery> constructed = mockConstruction(ScheduledDelivery.class)) {
            ScheduledDelivery booked = service.schedule("OFFERS", when, "admin");
            ScheduledDelivery created = constructed.constructed().get(0);
            assertSame(created, booked);
            assertEquals("OFFERS", created.feedCode);
            assertEquals(when, created.scheduledAt);
            assertEquals("admin", created.createdBy);
            assertNotNull(created.createdAt);
            assertEquals(ScheduledDelivery.Status.PENDING, created.status);
            verify(created).persist();
        }
    }

    /**
     * {@code schedule} with a BLANK feed code books every feed: the compound
     * {@code feedCode == null || feedCode.isBlank()} takes its false-then-true
     * legs, so the stored code is normalised to null.
     */
    @Test
    void scheduleWithBlankCodeBooksEveryFeed() {
        try (MockedConstruction<ScheduledDelivery> constructed = mockConstruction(ScheduledDelivery.class)) {
            service.schedule("   ", LocalDateTime.of(2026, 9, 26, 14, 30), "admin");
            assertNull(constructed.constructed().get(0).feedCode);
        }
    }

    /**
     * {@code schedule} with a NULL feed code books every feed: the compound takes
     * its first true leg (short-circuit), so the stored code stays null.
     */
    @Test
    void scheduleWithNullCodeBooksEveryFeed() {
        try (MockedConstruction<ScheduledDelivery> constructed = mockConstruction(ScheduledDelivery.class)) {
            service.schedule(null, LocalDateTime.of(2026, 9, 26, 14, 30), "admin");
            assertNull(constructed.constructed().get(0).feedCode);
        }
    }

    /**
     * {@code cancel} deletes a still-pending booking (both guard legs false) and
     * reports success.
     */
    @Test
    void cancelDeletesPendingBooking() {
        ScheduledDelivery booked = mock(ScheduledDelivery.class);
        booked.status = ScheduledDelivery.Status.PENDING;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.findById(1L)).thenReturn(booked);
            assertTrue(service.cancel(1L));
            verify(booked).delete();
        }
    }

    /**
     * {@code cancel} refuses an already-run booking (first leg false, second leg
     * true): the DONE row is left untouched and success is false.
     */
    @Test
    void cancelRefusesRunBooking() {
        ScheduledDelivery booked = mock(ScheduledDelivery.class);
        booked.status = ScheduledDelivery.Status.DONE;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.findById(1L)).thenReturn(booked);
            assertFalse(service.cancel(1L));
            verify(booked, never()).delete();
        }
    }

    /**
     * {@code cancel} of a missing booking (first leg true, short-circuit) reports
     * failure without touching anything.
     */
    @Test
    void cancelOfMissingBookingReportsFailure() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.findById(9L)).thenReturn(null);
            assertFalse(service.cancel(9L));
        }
    }

    /**
     * {@code claimDue} snapshots the due bookings into detached {@link
     * ScheduledDeliveryService.Due} records, carrying id and feed code (one
     * targeted, one every-feed).
     */
    @Test
    void claimDueSnapshotsDueBookings() {
        ScheduledDelivery targeted = mock(ScheduledDelivery.class);
        targeted.id = 1L;
        targeted.feedCode = "OFFERS";
        ScheduledDelivery global = mock(ScheduledDelivery.class);
        global.id = 2L;
        global.feedCode = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.list(
                    eq("status = ?1 and scheduledAt <= ?2 order by scheduledAt"),
                    eq(ScheduledDelivery.Status.PENDING), any()))
                    .thenReturn(List.of(targeted, global));
            List<ScheduledDeliveryService.Due> due = service.claimDue();
            assertEquals(2, due.size());
            assertEquals(1L, due.get(0).id());
            assertEquals("OFFERS", due.get(0).feedCode());
            assertEquals(2L, due.get(1).id());
            assertNull(due.get(1).feedCode());
        }
    }

    /**
     * {@code claimDue} with nothing due returns an empty list (the loop runs zero
     * times).
     */
    @Test
    void claimDueReturnsEmptyWhenNothingDue() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.list(
                    eq("status = ?1 and scheduledAt <= ?2 order by scheduledAt"),
                    eq(ScheduledDelivery.Status.PENDING), any()))
                    .thenReturn(List.of());
            assertTrue(service.claimDue().isEmpty());
        }
    }

    /**
     * {@code markResult} on success (null error arm) marks the booking DONE and
     * clears any message.
     */
    @Test
    void markResultMarksDoneOnSuccess() {
        ScheduledDelivery booked = mock(ScheduledDelivery.class);
        booked.message = "old";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.findById(1L)).thenReturn(booked);
            service.markResult(1L, null);
            assertEquals(ScheduledDelivery.Status.DONE, booked.status);
            assertNull(booked.message);
            verify(booked).persist();
        }
    }

    /**
     * {@code markResult} on a short failure (error arm, {@code length() > 500}
     * false) marks the booking FAILED and stores the message unchanged.
     */
    @Test
    void markResultMarksFailedWithShortMessage() {
        ScheduledDelivery booked = mock(ScheduledDelivery.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.findById(1L)).thenReturn(booked);
            service.markResult(1L, "HTTP 503");
            assertEquals(ScheduledDelivery.Status.FAILED, booked.status);
            assertEquals("HTTP 503", booked.message);
            verify(booked).persist();
        }
    }

    /**
     * {@code markResult} on a long failure ({@code length() > 500} true) truncates
     * the message to the column size.
     */
    @Test
    void markResultTruncatesLongFailureMessage() {
        ScheduledDelivery booked = mock(ScheduledDelivery.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.findById(1L)).thenReturn(booked);
            service.markResult(1L, "x".repeat(600));
            assertEquals(500, booked.message.length());
            assertEquals(ScheduledDelivery.Status.FAILED, booked.status);
            verify(booked).persist();
        }
    }

    /**
     * {@code markResult} on a booking gone between the claim and the result (null
     * arm) is a harmless no-op.
     */
    @Test
    void markResultIgnoresMissingBooking() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.findById(9L)).thenReturn(null);
            service.markResult(9L, "boom");
        }
    }

    /**
     * {@code list} returns every booking newest first, straight from the finder.
     */
    @Test
    void listReturnsEveryBookingNewestFirst() {
        List<ScheduledDelivery> expected = List.of(mock(ScheduledDelivery.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.list("order by scheduledAt desc")).thenReturn(expected);
            assertSame(expected, service.list());
        }
    }
}
