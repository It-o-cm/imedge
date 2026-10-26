package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.sync.RefState;
import com.intermarche.edge.domain.sync.ScheduledDelivery;
import com.intermarche.edge.service.sync.EngineFeedDeliveryService;
import com.intermarche.edge.service.sync.EngineFeedService;
import com.intermarche.edge.service.sync.TicketStreamService;
import com.intermarche.edge.service.sync.RefPullService;
import com.intermarche.edge.service.sync.ScheduledDeliveryService;
import com.intermarche.edge.service.sync.register.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SyncSupervisionService}.
 * <p>
 * The service is a read-only assembly over four collaborators (all plain
 * Mockito mocks) plus the {@link RefState} static finder, intercepted with
 * {@code mockStatic(PanacheEntityBase.class)}. Package-private fields are
 * assigned directly since the test lives in the production package. No database
 * and no Quarkus context is booted.
 * <p>
 * Branch enumeration — every arm: the {@code appliedVersion == null} ternary
 * (dash arm and shortHash arm), the {@code lastError != null} engine-error
 * accumulator (both arms across two feeds), {@code stamp} null and non-null
 * arms (an unacknowledged feed and a pulled fingerprint), the outbox count
 * accumulation (empty and non-empty), and the {@code healthy} composition
 * (an anomalous node with an error and a backlog, a clean node with neither).
 */
class SyncSupervisionServiceTest {

    /**
     * Builds a service wired to the four collaborator mocks and a push cadence.
     *
     * @param engine the engine feed keeper mock
     * @param pull the referential pull mock
     * @param delivery the engine delivery mock
     * @param outbox the store outbox mock
     * @return the wired service
     */
    private SyncSupervisionService service(EngineFeedService engine, RefPullService pull,
            EngineFeedDeliveryService delivery, SyncOutboxService outbox) {
        SyncSupervisionService service = new SyncSupervisionService();
        service.engineFeedService = engine;
        service.refPullService = pull;
        service.engineFeedDeliveryService = delivery;
        service.scheduledDeliveryService = mock(ScheduledDeliveryService.class);
        service.syncOutboxService = outbox;
        // BO-11-02-05: the at-the-flow healthcheck is part of the view. Caught up
        // by default, so every test written before it still asserts the same
        // verdict; the stream's own rows are proved in their own cases.
        service.ticketStreamService = mock(TicketStreamService.class);
        when(service.ticketStreamService.health()).thenReturn(List.of());
        // BO-10-08-23: the referential-update audit is disabled here, so the
        // view carries no audit rows and reads exactly as it did before the
        // dated-integrations card existed.
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(service.posSettingsService.referentialUpdateAuditEnabled()).thenReturn(false);
        service.pushIntervalSeconds = 10L;
        return service;
    }

    /**
     * Builds a real {@link ScheduledDelivery} POJO (no persistence, only field
     * reads) for the "Planifications" projection.
     *
     * @param id the booking id
     * @param feedCode the target feed code, or null for every feed
     * @param scheduledAt the booked minute
     * @param createdBy the author
     * @param status the lifecycle state
     * @param message the failure reason, or null
     * @return the populated row
     */
    private ScheduledDelivery scheduled(Long id, String feedCode, LocalDateTime scheduledAt,
            String createdBy, ScheduledDelivery.Status status, String message) {
        ScheduledDelivery row = new ScheduledDelivery();
        row.id = id;
        row.feedCode = feedCode;
        row.scheduledAt = scheduledAt;
        row.createdBy = createdBy;
        row.status = status;
        row.message = message;
        return row;
    }

    /**
     * Builds a real {@link RefState} POJO (no persistence, only field reads).
     *
     * @param domain the domain
     * @param fingerprint the applied fingerprint
     * @param appliedAt when it was applied
     * @return the populated row
     */
    private RefState refState(String domain, String fingerprint, LocalDateTime appliedAt) {
        RefState state = new RefState();
        state.domain = domain;
        state.fingerprint = fingerprint;
        state.appliedAt = appliedAt;
        return state;
    }

    /**
     * An anomalous node: one acknowledged feed and one lagging feed with an
     * error and no applied timestamp (dash arm of appliedVersion and stamp,
     * error arm of the accumulator), a pulled domain, and a non-empty backlog —
     * so {@code engineError} is true, {@code outboxCount} sums the rows and
     * {@code healthy} is false. Hashes are shortened to twelve characters.
     */
    @Test
    void buildAssemblesAnomalousNode() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(engine.feedStates()).thenReturn(List.of(
                new EngineFeedService.FeedState("PRODUCTS", "v2v2v2v2v2v2LONG", "v2v2v2v2v2v2LONG",
                        true, null, LocalDateTime.of(2026, 9, 11, 8, 0), LocalDateTime.of(2026, 9, 11, 8, 1)),
                new EngineFeedService.FeedState("OFFERS", "vNEWvNEWvNEWv", null,
                        false, "boom", LocalDateTime.of(2026, 9, 11, 9, 0), null)));
        when(pull.getLastSuccessfulPull()).thenReturn(LocalDateTime.of(2026, 9, 11, 7, 30));
        when(pull.getPullSeconds()).thenReturn(300L);
        when(delivery.getDeliverySeconds()).thenReturn(30L);
        when(outbox.backlog()).thenReturn(List.of(
                new SyncOutboxService.BacklogRow("TICKET", 3L, 5, "err"),
                new SyncOutboxService.BacklogRow("EVENT", 2L, 0, null)));
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.list("order by domain"))
                    .thenReturn(List.of(refState("FAMILIES", "ffffffffffffFF", LocalDateTime.of(2026, 9, 11, 7, 0))));
            SyncSupervisionService.SyncView view = service.build();
            assertEquals(2, view.feeds.size());
            SyncSupervisionService.FeedRow products = view.feeds.get(0);
            assertEquals("PRODUCTS", products.code);
            assertEquals("v2v2v2v2v2v2", products.version);
            assertEquals("v2v2v2v2v2v2", products.appliedVersion);
            assertTrue(products.applied);
            assertEquals("11/09 08:00:00", products.receivedAt);
            assertEquals("11/09 08:01:00", products.appliedAt);
            SyncSupervisionService.FeedRow offers = view.feeds.get(1);
            // Two DISTINCT codes asserted: the screen names each feed, it does
            // not print one label for all of them (BO-08-01-07/08/16).
            assertEquals("OFFERS", offers.code);
            assertEquals("—", offers.appliedVersion);
            assertEquals("—", offers.appliedAt);
            assertEquals("boom", offers.lastError);
            assertEquals(1, view.pulls.size());
            assertEquals("FAMILIES", view.pulls.get(0).domain);
            assertEquals("ffffffffffff", view.pulls.get(0).fingerprint);
            assertEquals("11/09 07:00:00", view.pulls.get(0).appliedAt);
            assertEquals(2, view.backlog.size());
            assertEquals(5L, view.outboxCount);
            assertEquals("11/09 07:30:00", view.lastPull);
            assertEquals(300L, view.pullSeconds);
            assertEquals(30L, view.deliverySeconds);
            assertEquals(10L, view.pushSeconds);
            assertTrue(view.engineError);
            assertFalse(view.healthy);
        }
    }

    /**
     * A clean node: every feed acknowledged with no error (false arm of the
     * accumulator), no pull yet (null arm of stamp for the last pull), and an
     * empty backlog (the outbox loop runs zero times) — so {@code engineError}
     * is false, {@code outboxCount} is zero and {@code healthy} is true. A short
     * version shortens to itself.
     */
    @Test
    void buildAssemblesCleanNode() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(engine.feedStates()).thenReturn(List.of(
                new EngineFeedService.FeedState("PRODUCTS", "v2", "v2",
                        true, null, LocalDateTime.of(2026, 9, 11, 8, 0), LocalDateTime.of(2026, 9, 11, 8, 1))));
        when(pull.getLastSuccessfulPull()).thenReturn(null);
        when(pull.getPullSeconds()).thenReturn(300L);
        when(delivery.getDeliverySeconds()).thenReturn(30L);
        when(outbox.backlog()).thenReturn(List.of());
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.list("order by domain")).thenReturn(List.of());
            SyncSupervisionService.SyncView view = service.build();
            assertEquals("v2", view.feeds.get(0).version);
            assertTrue(view.pulls.isEmpty());
            assertEquals(0L, view.outboxCount);
            assertEquals("—", view.lastPull);
            assertFalse(view.engineError);
            assertTrue(view.healthy);
        }
    }

    /**
     * A node with no engine error but a non-empty outbox is NOT healthy: the
     * {@code healthy = !engineError && outboxCount == 0} compound takes its
     * {@code !engineError} true leg and its {@code outboxCount == 0} false leg
     * — the arm neither the anomalous (error short-circuit) nor the clean
     * (empty outbox) case reaches.
     */
    @Test
    void buildFlagsBacklogWithoutEngineErrorAsUnhealthy() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(engine.feedStates()).thenReturn(List.of(
                new EngineFeedService.FeedState("PRODUCTS", "v2", "v2",
                        true, null, LocalDateTime.of(2026, 9, 11, 8, 0), LocalDateTime.of(2026, 9, 11, 8, 1))));
        when(pull.getLastSuccessfulPull()).thenReturn(null);
        when(pull.getPullSeconds()).thenReturn(300L);
        when(delivery.getDeliverySeconds()).thenReturn(30L);
        when(outbox.backlog()).thenReturn(List.of(
                new SyncOutboxService.BacklogRow("TICKET", 4L, 0, null)));
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.list("order by domain")).thenReturn(List.of());
            SyncSupervisionService.SyncView view = service.build();
            assertFalse(view.engineError);
            assertEquals(4L, view.outboxCount);
            assertFalse(view.healthy);
        }
    }

    /**
     * A node whose engine and outbox are clean but which still OWES a third party
     * an event is not synchronized: the at-the-flow backlog joins the verdict
     * instead of hiding under it (BO-11-02-05) — the third leg of the
     * {@code healthy} compound.
     */
    @Test
    void buildFlagsAnUndeliveredStreamEventAsUnhealthy() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(engine.feedStates()).thenReturn(List.of());
        when(pull.getLastSuccessfulPull()).thenReturn(null);
        when(pull.getPullSeconds()).thenReturn(300L);
        when(delivery.getDeliverySeconds()).thenReturn(30L);
        when(outbox.backlog()).thenReturn(List.of());
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        when(service.ticketStreamService.isEnabled()).thenReturn(true);
        when(service.ticketStreamService.getDeliverySeconds()).thenReturn(10L);
        when(service.ticketStreamService.health()).thenReturn(List.of(
                new TicketStreamService.StreamRow("ANAVEO", "Anaveo", 3L, 5,
                        "injoignable", false)));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.list("order by domain")).thenReturn(List.of());
            SyncSupervisionService.SyncView view = service.build();
            assertFalse(view.engineError);
            assertEquals(0L, view.outboxCount);
            assertEquals(3L, view.streamPending);
            assertTrue(view.streamError);
            assertTrue(view.streamEnabled);
            assertEquals(10L, view.streamSeconds);
            assertEquals(1, view.stream.size());
            assertFalse(view.healthy);
        }
    }

    /**
     * A subscriber with a backlog but NO error yet — the other leg of the stream's
     * own red flag: pending events without a failure are a stream still catching
     * up, not a stream in error.
     */
    @Test
    void buildSeparatesAStreamBacklogFromAStreamError() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(engine.feedStates()).thenReturn(List.of());
        when(pull.getLastSuccessfulPull()).thenReturn(null);
        when(pull.getPullSeconds()).thenReturn(300L);
        when(delivery.getDeliverySeconds()).thenReturn(30L);
        when(outbox.backlog()).thenReturn(List.of());
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        when(service.ticketStreamService.health()).thenReturn(List.of(
                new TicketStreamService.StreamRow("ANAVEO", "Anaveo", 2L, 0, null, true)));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.list("order by domain")).thenReturn(List.of());
            SyncSupervisionService.SyncView view = service.build();
            assertEquals(2L, view.streamPending);
            assertFalse(view.streamError);
            assertFalse(view.healthy);
        }
    }

    /**
     * The "Planifications" projection: a pending every-feed booking (null feedCode
     * arm → "Tous les flux" label, {@code pending} true) and a failed targeted
     * booking (non-null feedCode arm, {@code pending} false, message carried). The
     * schedule form's feed-code list mirrors the delivery catalog.
     */
    @Test
    void buildProjectsScheduledDeliveries() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(engine.feedStates()).thenReturn(List.of());
        when(pull.getLastSuccessfulPull()).thenReturn(null);
        when(pull.getPullSeconds()).thenReturn(300L);
        when(delivery.getDeliverySeconds()).thenReturn(30L);
        when(outbox.backlog()).thenReturn(List.of());
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        ScheduledDeliveryService scheduledService = mock(ScheduledDeliveryService.class);
        service.scheduledDeliveryService = scheduledService;
        when(scheduledService.list()).thenReturn(List.of(
                scheduled(1L, null, LocalDateTime.of(2026, 9, 26, 15, 0), "admin",
                        ScheduledDelivery.Status.PENDING, null),
                scheduled(2L, "OFFERS", LocalDateTime.of(2026, 9, 26, 16, 0), "admin",
                        ScheduledDelivery.Status.FAILED, "HTTP 503")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.list("order by domain")).thenReturn(List.of());
            SyncSupervisionService.SyncView view = service.build();
            assertEquals(2, view.schedules.size());
            SyncSupervisionService.ScheduledRow every = view.schedules.get(0);
            assertEquals(1L, every.id);
            assertEquals("Tous les flux", every.feedCode);
            assertEquals("26/09 15:00:00", every.scheduledAt);
            assertEquals("admin", every.createdBy);
            assertEquals("PENDING", every.status);
            assertTrue(every.pending);
            assertNull(every.message);
            SyncSupervisionService.ScheduledRow targeted = view.schedules.get(1);
            assertEquals("OFFERS", targeted.feedCode);
            assertEquals("FAILED", targeted.status);
            assertFalse(targeted.pending);
            assertEquals("HTTP 503", targeted.message);
            assertEquals(EngineFeedService.CATALOG.size(), view.feedCodes.size());
            assertEquals("STORES", view.feedCodes.get(0));
        }
    }

    /**
     * The pull-only failure back-fill: {@code getLastErrors()} carries a domain
     * that already produced a {@code RefState} row (FAMILIES — the equals guard
     * matches, {@code alreadyListed} becomes true, the {@code !alreadyListed}
     * false arm skips it so no duplicate row is added) and a pull-only domain
     * that never applied (CUSTOMERS — the equals guard never matches across the
     * listed rows, {@code alreadyListed} stays false, the {@code !alreadyListed}
     * true arm appends a dash-fingerprint row carrying only its error). This
     * exercises the whole 192-207 back-fill block both arms of both guards.
     */
    @Test
    void buildBackfillsPullOnlyFailures() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(engine.feedStates()).thenReturn(List.of());
        when(pull.getLastSuccessfulPull()).thenReturn(null);
        when(pull.getPullSeconds()).thenReturn(300L);
        when(delivery.getDeliverySeconds()).thenReturn(30L);
        when(outbox.backlog()).thenReturn(List.of());
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put("FAMILIES", "stale");
        failures.put("CUSTOMERS", "HTTP 500");
        when(pull.getLastErrors()).thenReturn(failures);
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.list("order by domain"))
                    .thenReturn(List.of(refState("FAMILIES", "ffffffffffffFF", LocalDateTime.of(2026, 9, 11, 7, 0))));
            SyncSupervisionService.SyncView view = service.build();
            assertEquals(2, view.pulls.size());
            SyncSupervisionService.PullRow families = view.pulls.get(0);
            assertEquals("FAMILIES", families.domain);
            assertEquals("ffffffffffff", families.fingerprint);
            SyncSupervisionService.PullRow customers = view.pulls.get(1);
            assertEquals("CUSTOMERS", customers.domain);
            assertEquals("—", customers.fingerprint);
            assertEquals("—", customers.appliedAt);
            assertEquals("HTTP 500", customers.lastError);
        }
    }
    /** BO-10-08-23: integration audits retain failed/applied outcomes and tolerate absent fingerprints. */
    @Test
    void buildProjectsEveryAuditOutcomeAndMissingFingerprint() {
        EngineFeedService engine = mock(EngineFeedService.class);
        RefPullService pull = mock(RefPullService.class);
        EngineFeedDeliveryService delivery = mock(EngineFeedDeliveryService.class);
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        SyncSupervisionService service = service(engine, pull, delivery, outbox);
        when(service.posSettingsService.referentialUpdateAuditEnabled()).thenReturn(true);
        var failed = new com.intermarche.edge.domain.sync.RefApplyAudit();
        failed.domain = "PRODUCTS";
        failed.outcome = com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.FAILED;
        failed.detail = "invalid snapshot";
        var applied = new com.intermarche.edge.domain.sync.RefApplyAudit();
        applied.domain = "FAMILIES";
        applied.outcome = com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED;
        applied.fingerprint = "1234567890123456";
        applied.appliedAt = LocalDateTime.of(2026, 10, 20, 12, 30);
        var pending = new com.intermarche.edge.domain.sync.RefApplyAudit();
        pending.domain = "CUSTOMERS";
        pending.fingerprint = "short";
        try (var base = mockStatic(PanacheEntityBase.class);
             var audits = mockStatic(com.intermarche.edge.domain.sync.RefApplyAudit.class)) {
            audits.when(() -> com.intermarche.edge.domain.sync.RefApplyAudit.latest(50)).thenReturn(List.of(failed, applied, pending));
            SyncSupervisionService.SyncView view = service.build();
            assertTrue(view.auditEnabled);
            assertEquals(3, view.audits.size());
            assertEquals("—", view.audits.get(0).fingerprint);
            assertEquals("FAILED", view.audits.get(0).outcome);
            assertEquals("invalid snapshot", view.audits.get(0).detail);
            assertTrue(view.audits.get(0).failed);
            assertEquals("123456789012", view.audits.get(1).fingerprint);
            assertEquals("APPLIED", view.audits.get(1).outcome);
            assertFalse(view.audits.get(1).failed);
            assertEquals("short", view.audits.get(2).fingerprint);
            assertEquals("", view.audits.get(2).outcome);
        }
    }

}
