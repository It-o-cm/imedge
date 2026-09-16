package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.domain.sync.RefState;
import com.intermarche.pos.service.sync.EngineFeedDeliveryService;
import com.intermarche.pos.service.sync.EngineFeedService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        service.syncOutboxService = outbox;
        service.pushIntervalSeconds = 10L;
        return service;
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
}
