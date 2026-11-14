package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.NodeUpdateLog;
import com.intermarche.edge.domain.deploy.NodeVersion;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NodeUpdateService}: the gates (no target, up to date,
 * window, start-up, forcing, busy, attempts) and the run (download failure,
 * empty or tampered package, applied, failed self-test with and without
 * rollback).
 */
class NodeUpdateServiceTest {

    /** A healthy package. */
    private static final String HEALTHY = "product=IMPOS\nversion=2.0\nselftest=ok\n";

    /** A package whose self-test fails. */
    private static final String DEFECTIVE = "product=IMPOS\nversion=2.0\nselftest=ko\n";

    /**
     * Builds the service over mocked settings.
     *
     * @param maxAttempts the administered attempts
     * @param rollback whether the rollback is active
     * @param atStartup whether the start-up update is active
     * @return the service
     */
    private NodeUpdateService service(int maxAttempts, boolean rollback, boolean atStartup) {
        NodeUpdateService service = new NodeUpdateService();
        service.buildVersion = "1.0-SNAPSHOT";
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.deploymentMaxAttempts()).thenReturn(maxAttempts);
        when(service.posSettingsService.deploymentRollbackEnabled()).thenReturn(rollback);
        when(service.posSettingsService.deploymentAutoUpdateAtStartup()).thenReturn(atStartup);
        return service;
    }

    /**
     * Builds a stored row.
     *
     * @param installed the version it runs
     * @return the row, persist neutralized
     */
    private NodeVersion row(String installed) {
        NodeVersion row = mock(NodeVersion.class);
        row.installedVersion = installed;
        row.state = NodeVersion.State.IDLE;
        return row;
    }

    /**
     * Builds a target.
     *
     * @param version the version
     * @param content the package whose checksum is announced
     * @param inWindow whether the window is open
     * @param force the forcing token, possibly null
     * @return the target
     */
    private DeployPayloads.Target target(String version, String content, boolean inWindow, String force) {
        DeployPayloads.Target target = new DeployPayloads.Target();
        target.product = "IMPOS";
        target.version = version;
        target.checksum = AppVersion.sha256(content);
        target.inWindow = inWindow;
        target.forceToken = force;
        return target;
    }

    /**
     * Makes the node's row answer a given row.
     *
     * @param panache the static mock
     * @param row the row, possibly null
     */
    @SuppressWarnings("unchecked")
    private void stored(MockedStatic<PanacheEntityBase> panache, NodeVersion row) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(PanacheEntityBase::findAll).thenReturn(query);
    }

    /** No target, a target with no version or a blank one: nothing is considered. */
    @Test
    void noTargetIsNothingToConsider() {
        NodeUpdateService service = service(3, true, true);
        assertEquals(NodeUpdateService.Decision.NO_TARGET, service.consider(null, true, true, t -> HEALTHY));
        assertEquals(NodeUpdateService.Decision.NO_TARGET,
                service.consider(target(null, HEALTHY, true, null), true, true, t -> HEALTHY));
        assertEquals(NodeUpdateService.Decision.NO_TARGET,
                service.consider(target(" ", HEALTHY, true, null), true, true, t -> HEALTHY));
    }

    /** A node never updated runs its build version; the snapshot and history read the store. */
    @Test
    @SuppressWarnings("unchecked")
    void aFreshNodeRunsItsBuildVersion() {
        NodeUpdateService service = service(3, true, true);
        List<Object> steps = List.of(new NodeUpdateLog());
        PanacheQuery<Object> logs = mock(PanacheQuery.class);
        PanacheQuery<Object> page = mock(PanacheQuery.class);
        when(logs.page(0, 4)).thenReturn(page);
        when(page.list()).thenReturn(steps);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, null);
            panache.when(() -> PanacheEntityBase.find("order by loggedAt desc, id desc")).thenReturn(logs);
            assertEquals("1.0-SNAPSHOT", service.installedVersion());
            assertEquals(NodeVersion.State.IDLE, service.snapshot().state);
            assertSame(steps, service.history(4));
        }
    }

    /** BO-08-03-07: a node already at the target version or above does nothing, its row is kept. */
    @Test
    void anUpToDateNodeDoesNothing() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("2.0");
        row.targetVersion = "2.0";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.UP_TO_DATE,
                    service.consider(target("2.0", HEALTHY, true, null), false, true, t -> HEALTHY));
            assertEquals("2.0", service.installedVersion());
        }
        verify(row).persist();
    }

    /**
     * A new target clears the state and the error left by the previous one,
     * so that a rolled-back version is not reported against the next.
     */
    @Test
    void aNewTargetStartsClean() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        row.targetVersion = "2.1";
        row.state = NodeVersion.State.ROLLED_BACK;
        row.lastError = "Autotest de la version 2.1 en échec : retour à la version 1.0";
        row.attempts = 3;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.WAITING_WINDOW,
                    service.consider(target("2.0", HEALTHY, false, null), false, true, t -> HEALTHY));
        }
        assertEquals("2.0", row.targetVersion);
        assertEquals(NodeVersion.State.IDLE, row.state);
        assertNull(row.lastError);
        assertEquals(0, row.attempts);
    }

    /** BO-08-03-04: outside the window and with no start-up, the node waits. */
    @Test
    void outsideTheWindowTheNodeWaits() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.WAITING_WINDOW,
                    service.consider(target("2.0", HEALTHY, false, null), false, true, t -> HEALTHY));
        }
        assertEquals("2.0", row.targetVersion);
        assertEquals(0, row.attempts);
    }

    /** BO-08-03-07: a start-up outside the window does not update when the start-up update is off. */
    @Test
    void aStartUpWaitsWhenTheStartUpUpdateIsOff() {
        NodeUpdateService service = service(3, true, false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, row("1.0"));
            assertEquals(NodeUpdateService.Decision.WAITING_WINDOW,
                    service.consider(target("2.0", HEALTHY, false, null), true, true, t -> HEALTHY));
        }
    }

    /** BO-08-03-07: at start-up, outside the window, a lower node updates and the steps are journaled. */
    @Test
    void aStartUpUpdatesOutsideTheWindow() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<NodeUpdateLog> logs = mockConstruction(NodeUpdateLog.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.APPLIED,
                    service.consider(target("2.0", HEALTHY, false, null), true, true, t -> HEALTHY));
            assertEquals(4, logs.constructed().size());
            assertEquals("APPLIED", logs.constructed().get(3).step);
            assertTrue(logs.constructed().get(3).success);
        }
        assertEquals("2.0", row.installedVersion);
        assertEquals("1.0", row.previousVersion);
        assertEquals(NodeVersion.State.APPLIED, row.state);
        assertEquals(0, row.attempts);
        assertNull(row.lastError);
    }

    /** BO-08-04-02: a new forcing runs outside the window, resets the attempts and is remembered. */
    @Test
    void aForcingRunsOutsideTheWindowAndResetsTheAttempts() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        row.targetVersion = "2.0";
        row.attempts = 3;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<NodeUpdateLog> logs = mockConstruction(NodeUpdateLog.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.APPLIED,
                    service.consider(target("2.0", HEALTHY, false, "tok"), false, true, t -> HEALTHY));
        }
        assertEquals("tok", row.lastForceToken);
    }

    /** BO-08-04-01: a forcing already obeyed does not reset the attempts again: the node abandons. */
    @Test
    void aSpentForcingLeavesTheNodeAbandoned() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        row.targetVersion = "2.0";
        row.attempts = 3;
        row.lastForceToken = "tok";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.ABANDONED,
                    service.consider(target("2.0", HEALTHY, true, "tok"), false, true, t -> HEALTHY));
        }
        assertEquals(3, row.attempts);
    }

    /** A busy node defers; a new target version resets the attempts of the previous one. */
    @Test
    void aBusyNodeDefers() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        row.targetVersion = "1.5";
        row.attempts = 2;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.DEFERRED_BUSY,
                    service.consider(target("2.0", HEALTHY, true, null), false, false, t -> HEALTHY));
        }
        assertEquals(0, row.attempts);
    }

    /** BO-08-04-01: a download failure is a failed attempt, retried at the next report; a long error is cut. */
    @Test
    void aDownloadFailureIsAFailedAttempt() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        Function<DeployPayloads.Target, String> broken = t -> {
            throw new IllegalStateException("x".repeat(400));
        };
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<NodeUpdateLog> logs = mockConstruction(NodeUpdateLog.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.FAILED,
                    service.consider(target("2.0", HEALTHY, true, null), false, true, broken));
        }
        assertEquals(NodeVersion.State.FAILED, row.state);
        assertEquals(300, row.lastError.length());
        assertEquals(1, row.attempts);
        assertEquals("1.0", row.installedVersion);
    }

    /** An empty package, or one whose checksum differs from the announced one, fails the verification. */
    @Test
    void aTamperedPackageFailsTheVerification() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<NodeUpdateLog> logs = mockConstruction(NodeUpdateLog.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.FAILED,
                    service.consider(target("2.0", HEALTHY, true, null), false, true, t -> null));
            assertEquals(NodeUpdateService.Decision.FAILED,
                    service.consider(target("2.0", HEALTHY, true, null), false, true, t -> "tampered"));
        }
        assertEquals("Empreinte du paquet invalide", row.lastError);
        assertEquals(2, row.attempts);
    }

    /** BO-08-04-14: a failed self-test restores the previous version. */
    @Test
    void aFailedSelfTestIsRolledBack() {
        NodeUpdateService service = service(3, true, true);
        NodeVersion row = row("1.0");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<NodeUpdateLog> logs = mockConstruction(NodeUpdateLog.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.ROLLED_BACK,
                    service.consider(target("2.0", DEFECTIVE, true, null), false, true, t -> DEFECTIVE));
        }
        assertEquals("1.0", row.installedVersion);
        assertEquals("1.0", row.previousVersion);
        assertEquals(NodeVersion.State.ROLLED_BACK, row.state);
        assertEquals("Autotest de la version 2.0 en échec : retour à la version 1.0", row.lastError);
    }

    /** BO-08-04-14: with the rollback off, the failing version stays and the update reads failed. */
    @Test
    void withoutRollbackTheFailingVersionStays() {
        NodeUpdateService service = service(3, false, true);
        NodeVersion row = row("1.0");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<NodeUpdateLog> logs = mockConstruction(NodeUpdateLog.class)) {
            stored(panache, row);
            assertEquals(NodeUpdateService.Decision.FAILED,
                    service.consider(target("2.0", DEFECTIVE, true, null), false, true, t -> DEFECTIVE));
        }
        assertEquals("2.0", row.installedVersion);
        assertEquals(NodeVersion.State.FAILED, row.state);
    }

    /** A fresh node updating creates its row on the build version first. */
    @Test
    void aFreshNodeCreatesItsRow() {
        NodeUpdateService service = service(3, true, true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<NodeVersion> rows = mockConstruction(NodeVersion.class);
             MockedConstruction<NodeUpdateLog> logs = mockConstruction(NodeUpdateLog.class)) {
            stored(panache, null);
            assertEquals(NodeUpdateService.Decision.APPLIED,
                    service.consider(target("2.0", HEALTHY, true, null), false, true, t -> HEALTHY));
            NodeVersion created = rows.constructed().get(0);
            assertEquals("1.0-SNAPSHOT", created.previousVersion);
            verify(created).persist();
            verify(logs.constructed().get(0)).persist();
        }
    }

    /** The busy gate never reaches the download. */
    @Test
    @SuppressWarnings("unchecked")
    void theGatesNeverDownload() {
        NodeUpdateService service = service(3, true, true);
        Function<DeployPayloads.Target, String> downloader = mock(Function.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stored(panache, row("1.0"));
            service.consider(target("2.0", HEALTHY, true, null), false, false, downloader);
        }
        verify(downloader, never()).apply(org.mockito.ArgumentMatchers.any());
    }
}
