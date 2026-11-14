package com.intermarche.edge.service.fiscal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link FiscalScheduler} (BO-05-01): the loop starts on a node
 * only, each tick runs every step, and a failing step does not stop the
 * others.
 */
class FiscalSchedulerTest {

    /**
     * Builds the scheduler with its steps mocked.
     *
     * @param role the node's role
     * @return the scheduler
     */
    private FiscalScheduler scheduler(String role) {
        FiscalScheduler scheduler = new FiscalScheduler();
        scheduler.role = role;
        scheduler.tickSeconds = 3600;
        scheduler.fiscalArchiveService = mock(FiscalArchiveService.class);
        scheduler.eInvoicingService = mock(EInvoicingService.class);
        scheduler.eReportingService = mock(EReportingService.class);
        scheduler.saftStreamService = mock(SaftStreamService.class);
        return scheduler;
    }

    /**
     * The loop starts on a store node and stops with it; a register starts
     * none, and stopping it is harmless.
     */
    @Test
    void theloopStartsOnANodeOnly() {
        FiscalScheduler register = scheduler("register");
        register.onStart(null);
        assertNull(register.executor);
        register.stop();
        FiscalScheduler store = scheduler("store");
        store.onStart(null);
        assertNotNull(store.executor);
        store.stop();
        assertTrue(store.executor.isShutdown());
    }

    /** Runs the fiscal steps successfully and forwards one timestamp to each service. */
    @Test
    void successfulTickUsesOneTimestampForEveryStep() {
        FiscalScheduler scheduler = scheduler("store");
        assertEquals(0, scheduler.tick());
        var time = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(scheduler.eInvoicingService).tick(time.capture());
        verify(scheduler.fiscalArchiveService).autoArchive(time.getValue().toLocalDate());
        verify(scheduler.eReportingService).tick(time.getValue());
        verify(scheduler.saftStreamService).tick(time.getValue());
    }

    /**
     * A tick runs every step, each failure counted and passed over.
     */
    @Test
    void atickRunsEveryStep() {
        FiscalScheduler scheduler = scheduler("store");
        when(scheduler.eInvoicingService.tick(any())).thenThrow(new IllegalStateException("plateforme"));
        assertEquals(1, scheduler.tick());
        verify(scheduler.fiscalArchiveService).autoArchive(LocalDate.now());
        verify(scheduler.eReportingService).tick(any(LocalDateTime.class));
        verify(scheduler.saftStreamService).tick(any(LocalDateTime.class));
    }
}
