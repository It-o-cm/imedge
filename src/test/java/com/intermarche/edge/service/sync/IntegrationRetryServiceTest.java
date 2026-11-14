package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.imports.EngineFeedRelayResource;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link IntegrationRetryService}. */
class IntegrationRetryServiceTest {

    /** The entity manager the writes go through. */
    private final EntityManager entityManager = mock(EntityManager.class);

    /**
     * Builds the service over mocked collaborators.
     *
     * @param enabled whether the automatic replay is active
     * @return the service
     */
    private IntegrationRetryService service(boolean enabled) {
        IntegrationRetryService service = new IntegrationRetryService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.engineFeedRelayResource = mock(EngineFeedRelayResource.class);
        when(service.posSettingsService.integrationRetryEnabled()).thenReturn(enabled);
        when(service.posSettingsService.integrationRetryMinutes()).thenReturn(10);
        when(service.posSettingsService.integrationRetryMaxAttempts()).thenReturn(5);
        return service;
    }

    /**
     * Runs every transaction in place and wires the entity manager.
     *
     * @param tx the static mock of the transaction API
     * @param panache the static mock of the Panache base
     * @throws Exception never
     */
    @SuppressWarnings("unchecked")
    private void inPlace(MockedStatic<QuarkusTransaction> tx, MockedStatic<PanacheEntityBase> panache) throws Exception {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        when(runner.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<Object>) invocation.getArgument(0)).call());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(runner).run(any(Runnable.class));
        panache.when(PanacheEntityBase::getEntityManager).thenReturn(entityManager);
    }

    /**
     * Builds a kept deposit.
     *
     * @param status its status
     * @param attempts how many times it was integrated
     * @param minutesAgo how long ago it was last integrated
     * @return the deposit
     */
    private IntegrationDeposit deposit(IntegrationDeposit.Status status, int attempts, int minutesAgo) {
        IntegrationDeposit deposit = new IntegrationDeposit();
        deposit.id = 7L;
        deposit.code = "PRICES";
        deposit.content = "EAN|PRICE\n1|2\n";
        deposit.status = status;
        deposit.attempts = attempts;
        deposit.lastAttemptAt = LocalDateTime.now().minusMinutes(minutesAgo);
        return deposit;
    }

    /** The loop starts and stops cleanly, twice stopping being harmless. */
    @Test
    void theLoopStartsAndStops() {
        IntegrationRetryService service = service(true);
        service.stop();
        service.onStart(null);
        service.stop();
    }

    /** BO-08-04-03 to -08: the four kinds of answers classify the integration. */
    @Test
    void statusOfClassifiesTheAnswer() {
        assertEquals(IntegrationDeposit.Status.FAILED, IntegrationRetryService.statusOf(500, "x"));
        assertEquals(IntegrationDeposit.Status.REJECTED, IntegrationRetryService.statusOf(400, "x"));
        assertEquals(IntegrationDeposit.Status.PARTIAL,
                IntegrationRetryService.statusOf(200, "{\"createdCount\":0, \"errors\":[\"Line 2\"]}"));
        assertEquals(IntegrationDeposit.Status.APPLIED, IntegrationRetryService.statusOf(200, "{\"createdCount\":1}"));
        assertEquals(IntegrationDeposit.Status.APPLIED,
                IntegrationRetryService.statusOf(200, "{\"createdCount\":1, \"levelCount\":1, \"errors\":[]}"));
    }

    /**
     * BO-08-01-17: a deposit is recorded with the fingerprint of its file
     * (BO-11-01-01, none without a file); a successful one drops its content,
     * a long answer is cut.
     */
    @Test
    void recordKeepsTheOutcome() throws Exception {
        IntegrationRetryService service = service(true);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<IntegrationDeposit> rows = mockConstruction(IntegrationDeposit.class)) {
            inPlace(tx, panache);
            IntegrationDeposit applied = service.record("PRODUCTS", "full", "S", "L", "ITM", "file",
                    Response.ok("{\"createdCount\":1}").build());
            assertEquals(IntegrationDeposit.Status.APPLIED, applied.status);
            assertNull(applied.content);
            assertEquals(EngineFeedService.sha256("file"), applied.contentHash);
            assertEquals(1, applied.attempts);
            assertEquals("full", applied.mode);
            assertEquals("ITM", applied.enseigne);
            verify(applied).persist();
            IntegrationDeposit failed = service.record("PRICES", null, null, null, null, "file",
                    Response.serverError().entity("x".repeat(600)).build());
            assertEquals(IntegrationDeposit.Status.FAILED, failed.status);
            assertEquals("file", failed.content);
            assertEquals(500, failed.lastResult.length());
            IntegrationDeposit empty = service.record("PRICES", null, null, null, null, null,
                    Response.serverError().entity("x").build());
            assertNull(empty.contentHash);
        }
    }

    /** BO-08-04-03 to -08: the replay is off when disabled. */
    @Test
    void retryDueDoesNothingWhenDisabled() {
        IntegrationRetryService service = service(false);
        assertEquals(0, service.retryDue());
        verify(service.engineFeedRelayResource, never()).integrate(any(), any(), any(), any(), any(), any());
    }

    /**
     * BO-08-04-03 to -08: a deposit past its delay is replayed and goes in; one too recent waits; one past
     * its attempts is abandoned.
     */
    @Test
    void retryDueReplaysAbandonsOrWaits() throws Exception {
        IntegrationRetryService service = service(true);
        IntegrationDeposit due = deposit(IntegrationDeposit.Status.PARTIAL, 2, 30);
        IntegrationDeposit recent = deposit(IntegrationDeposit.Status.FAILED, 1, 2);
        IntegrationDeposit spent = deposit(IntegrationDeposit.Status.FAILED, 6, 30);
        when(service.engineFeedRelayResource.integrate("PRICES", null, null, null, null, "EAN|PRICE\n1|2\n"))
                .thenReturn(Response.ok("{\"createdCount\":1}").build());
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx, panache);
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenReturn(List.of(due, recent, spent));
            assertEquals(1, service.retryDue());
        }
        assertEquals(IntegrationDeposit.Status.APPLIED, due.status);
        assertNull(due.content);
        assertEquals(3, due.attempts);
        assertEquals(IntegrationDeposit.Status.FAILED, recent.status);
        assertEquals(IntegrationDeposit.Status.ABANDONED, spent.status);
        verify(entityManager, times(2)).merge(any());
    }

    /** BO-08-04-02: every failed or partial deposit is replayed now, and a failed loop pass is swallowed. */
    @Test
    void retryAllReplaysEverything() throws Exception {
        IntegrationRetryService service = service(true);
        IntegrationDeposit recent = deposit(IntegrationDeposit.Status.FAILED, 1, 0);
        when(service.engineFeedRelayResource.integrate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Response.serverError().entity("down").build());
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx, panache);
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class))).thenReturn(List.of(recent));
            assertEquals(1, service.retryAll());
            service.retryDueSafely();
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenThrow(new IllegalStateException("db"));
            service.retryDueSafely();
        }
        assertEquals(IntegrationDeposit.Status.FAILED, recent.status);
        assertEquals(2, recent.attempts);
    }

    /** BO-08-04-09 to -13: a deposit is replayed on demand; an unknown or integrated one is not. */
    @Test
    void replayNowForcesOneDeposit() throws Exception {
        IntegrationRetryService service = service(true);
        IntegrationDeposit kept = deposit(IntegrationDeposit.Status.ABANDONED, 6, 0);
        IntegrationDeposit integrated = deposit(IntegrationDeposit.Status.APPLIED, 1, 0);
        integrated.content = null;
        when(service.engineFeedRelayResource.integrate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Response.ok("{}").build());
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx, panache);
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(null);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(integrated);
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(kept);
            assertNull(service.replayNow(1L));
            assertNull(service.replayNow(2L));
            assertSame(kept, service.replayNow(7L));
        }
        assertEquals(IntegrationDeposit.Status.APPLIED, kept.status);
    }

    /** BO-08-01-17: the console reads the latest deposits. */
    @Test
    @SuppressWarnings("unchecked")
    void latestReadsTheDeposits() throws Exception {
        IntegrationRetryService service = service(true);
        List<Object> rows = List.of(new IntegrationDeposit());
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> ordered = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> page = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(ordered.page(0, 3)).thenReturn(page);
        when(page.list()).thenReturn(rows);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx, panache);
            panache.when(() -> PanacheEntityBase.find("order by depositedAt desc, id desc")).thenReturn(ordered);
            assertSame(rows, service.latest(3));
        }
    }
}
