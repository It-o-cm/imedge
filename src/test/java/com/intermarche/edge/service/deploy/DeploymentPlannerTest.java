package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.ApplicationRelease;
import com.intermarche.edge.domain.deploy.DeploymentLot;
import com.intermarche.edge.domain.deploy.DeploymentTarget;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.domain.store.Enseigne;
import com.intermarche.edge.domain.store.Pdv;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link DeploymentPlanner}. */
class DeploymentPlannerTest {

    /**
     * Builds a lot.
     *
     * @param id its id
     * @param version its version
     * @param status its status
     * @param startOffsetHours the window's start relative to now
     * @return the lot, its window two hours long
     */
    private DeploymentLot lot(long id, String version, DeploymentLot.Status status, int startOffsetHours) {
        DeploymentLot lot = new DeploymentLot();
        lot.id = id;
        lot.code = "L" + id;
        lot.product = ApplicationRelease.IMPOS;
        lot.version = version;
        lot.status = status;
        lot.windowStart = LocalDateTime.now().plusHours(startOffsetHours);
        lot.windowEnd = lot.windowStart.plusHours(2);
        return lot;
    }

    /**
     * Builds a node status.
     *
     * @param kind its kind
     * @param pdv its point of sale
     * @param id its identifier
     * @param version its version
     * @return the status
     */
    private NodeStatus status(String kind, String pdv, String id, String version) {
        NodeStatus status = new NodeStatus();
        status.kind = kind;
        status.pdvNumber = pdv;
        status.nodeId = id;
        status.version = version;
        status.nodeKey = DeployPayloads.nodeKey(kind, pdv, id);
        return status;
    }

    /** A register runs impos, every other node imedge. */
    @Test
    void productOfFollowsTheKind() {
        assertEquals("IMPOS", DeploymentPlanner.productOf("REGISTER"));
        assertEquals("IMEDGE", DeploymentPlanner.productOf("STORE"));
        assertEquals("REGISTER:07039:C04", new DeploymentPlanner.TargetRef("REGISTER", "07039", "C04").key());
    }

    /**
     * BO-08-03-09/10: a node gets the version of the most recent started lot that names it and whose release
     * is published; a lot not started, not naming it, or without release is skipped.
     */
    @Test
    void targetForPicksTheFirstStartedLotNamingTheNode() {
        DeploymentLot future = lot(1, "3.0", DeploymentLot.Status.SCHEDULED, 5);
        DeploymentLot other = lot(2, "2.5", DeploymentLot.Status.SCHEDULED, -1);
        DeploymentLot unpublished = lot(3, "2.4", DeploymentLot.Status.SCHEDULED, -1);
        DeploymentLot pilot = lot(4, "2.0", DeploymentLot.Status.SCHEDULED, -3);
        DeploymentTarget entry = new DeploymentTarget();
        entry.forceToken = "tok";
        ApplicationRelease release = new ApplicationRelease();
        release.product = "IMPOS";
        release.version = "2.0";
        String key = "REGISTER:07039:C04";
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class);
             MockedStatic<ApplicationRelease> releases = mockStatic(ApplicationRelease.class)) {
            lots.when(() -> DeploymentLot.listScheduled("IMPOS")).thenReturn(List.of(future, other, unpublished, pilot));
            targets.when(() -> DeploymentTarget.findKey(2L, key)).thenReturn(null);
            targets.when(() -> DeploymentTarget.findKey(3L, key)).thenReturn(entry);
            targets.when(() -> DeploymentTarget.findKey(4L, key)).thenReturn(entry);
            releases.when(() -> ApplicationRelease.findRelease("IMPOS", "2.4")).thenReturn(null);
            releases.when(() -> ApplicationRelease.findRelease("IMPOS", "2.0")).thenReturn(release);
            DeployPayloads.Target target = new DeploymentPlanner().targetFor("REGISTER", "07039", "C04");
            assertEquals("2.0", target.version);
            assertEquals("L4", target.lotCode);
            assertEquals("tok", target.forceToken);
            assertEquals(release.checksum(), target.checksum);
            assertFalse(target.inWindow);
            assertEquals("IMPOS", target.product);
            targets.verify(() -> DeploymentTarget.findKey(1L, key), never());
        }
    }

    /** A node no lot names is told nothing. */
    @Test
    void targetForAnswersNothingWithoutLot() {
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class)) {
            lots.when(() -> DeploymentLot.listScheduled("IMEDGE")).thenReturn(List.of());
            assertNull(new DeploymentPlanner().targetFor("STORE", "07039", "07039"));
        }
    }

    /** The package is the release's, none without release. */
    @Test
    void packageContentReadsTheRelease() {
        ApplicationRelease release = new ApplicationRelease();
        release.product = "IMEDGE";
        release.version = "2.0";
        try (MockedStatic<ApplicationRelease> releases = mockStatic(ApplicationRelease.class)) {
            releases.when(() -> ApplicationRelease.findRelease("IMEDGE", "2.0")).thenReturn(release);
            assertEquals(release.packageContent(), new DeploymentPlanner().packageContent("IMEDGE", "2.0"));
            assertNull(new DeploymentPlanner().packageContent("IMEDGE", "9.9"));
        }
    }

    /** BO-08-03-06: the reports move the lot entries along; a vanished lot is skipped. */
    @Test
    void recordProgressFollowsTheReports() {
        NodeStatus status = status("REGISTER", "07039", "C04", "1.0");
        status.updateTarget = "2.0";
        status.updateState = "ROLLED_BACK";
        status.updateMessage = "autotest";
        DeploymentTarget gone = mock(DeploymentTarget.class);
        gone.lotId = 9L;
        DeploymentTarget failing = mock(DeploymentTarget.class);
        failing.lotId = 4L;
        DeploymentTarget silent = mock(DeploymentTarget.class);
        silent.lotId = 5L;
        silent.state = DeploymentTarget.State.PENDING;
        DeploymentTarget applied = mock(DeploymentTarget.class);
        applied.lotId = 6L;
        applied.message = "old";
        DeploymentLot target = lot(4, "2.0", DeploymentLot.Status.SCHEDULED, -1);
        DeploymentLot older = lot(5, "1.5", DeploymentLot.Status.SCHEDULED, -1);
        DeploymentLot same = lot(6, "1.0", DeploymentLot.Status.SCHEDULED, -1);
        try (MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            targets.when(() -> DeploymentTarget.listByNode(status.nodeKey))
                    .thenReturn(List.of(gone, failing, silent, applied));
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(null);
            panache.when(() -> PanacheEntityBase.findById(4L)).thenReturn(target);
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(older);
            panache.when(() -> PanacheEntityBase.findById(6L)).thenReturn(same);
            new DeploymentPlanner().recordProgress(status);
        }
        verify(gone, never()).persist();
        assertEquals(DeploymentTarget.State.ROLLED_BACK, failing.state);
        assertEquals("autotest", failing.message);
        assertEquals("1.0", failing.reportedVersion);
        assertEquals(DeploymentTarget.State.PENDING, silent.state);
        verify(silent).persist();
        assertEquals(DeploymentTarget.State.APPLIED, applied.state);
        assertNull(applied.message);
    }

    /** BO-08-03-06: every update state of the lot's version maps to a target state. */
    @Test
    void stateOfMapsTheUpdateStates() {
        DeploymentLot lot = lot(1, "2.0", DeploymentLot.Status.SCHEDULED, -1);
        NodeStatus status = status("REGISTER", "07039", "C04", "1.0");
        assertNull(DeploymentPlanner.stateOf(lot, status));
        status.updateTarget = "1.5";
        status.updateState = "FAILED";
        assertNull(DeploymentPlanner.stateOf(lot, status));
        status.updateTarget = "2.0";
        status.updateState = null;
        assertNull(DeploymentPlanner.stateOf(lot, status));
        status.updateState = "FAILED";
        assertEquals(DeploymentTarget.State.FAILED, DeploymentPlanner.stateOf(lot, status));
        status.updateState = "ROLLED_BACK";
        assertEquals(DeploymentTarget.State.ROLLED_BACK, DeploymentPlanner.stateOf(lot, status));
        for (String moving : List.of("DOWNLOADING", "VERIFYING", "INSTALLING")) {
            status.updateState = moving;
            assertEquals(DeploymentTarget.State.IN_PROGRESS, DeploymentPlanner.stateOf(lot, status));
        }
        status.updateState = "IDLE";
        assertNull(DeploymentPlanner.stateOf(lot, status));
        status.version = "2.0";
        assertEquals(DeploymentTarget.State.APPLIED, DeploymentPlanner.stateOf(lot, status));
    }

    /** BO-08-03-03: the logical rules keep all, the even or the odd registers; a register without number only ALL. */
    @Test
    void matchesAppliesTheLogicalRule() {
        assertTrue(DeploymentPlanner.matches("C04", "ALL"));
        assertTrue(DeploymentPlanner.matches("C04", null));
        assertTrue(DeploymentPlanner.matches("C04", "EVEN"));
        assertFalse(DeploymentPlanner.matches("C03", "EVEN"));
        assertTrue(DeploymentPlanner.matches("C03", "ODD"));
        assertFalse(DeploymentPlanner.matches("C04", "ODD"));
        assertFalse(DeploymentPlanner.matches("CAISSE", "EVEN"));
    }

    /**
     * BO-08-03-02/03: a selection resolves the central brick, store nodes and registers — one, or all, even
     * or odd of a point of sale — skipping blank lines and naming the unknown ones.
     */
    @Test
    void resolveTurnsLinesIntoNodes() {
        Pdv known = new Pdv();
        NodeStatus even = status("REGISTER", "07039", "C04", "1.0");
        NodeStatus odd = status("REGISTER", "07039", "C03", "1.0");
        NodeStatus elsewhere = status("REGISTER", "07040", "C02", "1.0");
        NodeStatus store = status("STORE", "07041", "07041", "1.0");
        try (MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class)) {
            pdvs.when(() -> Pdv.findByNumber(anyString())).thenReturn(null);
            pdvs.when(() -> Pdv.findByNumber("07039")).thenReturn(known);
            statuses.when(NodeStatus::listOrdered).thenReturn(List.of(store));
            statuses.when(() -> NodeStatus.listByKind("REGISTER")).thenReturn(List.of(even, odd, elsewhere));
            DeploymentPlanner planner = new DeploymentPlanner();
            DeploymentPlanner.Resolution edges = planner.resolve("IMEDGE",
                    List.of("central", " 07039 ", "", "07041", "99999"), "ALL");
            assertEquals(List.of(new DeploymentPlanner.TargetRef("CENTRAL", null, "CENTRAL"),
                    new DeploymentPlanner.TargetRef("STORE", "07039", "07039"),
                    new DeploymentPlanner.TargetRef("STORE", "07041", "07041")), edges.targets());
            assertEquals(List.of("99999"), edges.unknown());
            DeploymentPlanner.Resolution registers = planner.resolve("IMPOS",
                    List.of("07039", "07039;C09", "99999;C01", "07039;C04"), "EVEN");
            assertEquals(List.of(new DeploymentPlanner.TargetRef("REGISTER", "07039", "C04"),
                    new DeploymentPlanner.TargetRef("REGISTER", "07039", "C09")), registers.targets());
            assertEquals(List.of("99999;C01"), registers.unknown());
            assertEquals(2, planner.resolve("IMPOS", List.of("07039"), "ALL").targets().size());
        }
    }

    /** BO-08-03-02: the echelon and attribute groupings list their points of sale, or their registers by version. */
    @Test
    void linesOfListsTheGroup() {
        Enseigne itm = new Enseigne();
        itm.code = "ITM";
        itm.countryCode = "FR";
        Enseigne other = new Enseigne();
        other.code = "BRI";
        other.countryCode = "PT";
        Pdv first = new Pdv();
        first.pdvNumber = "07039";
        first.adherentCode = "A1";
        Pdv second = new Pdv();
        second.pdvNumber = "07040";
        NodeStatus register = status("REGISTER", "07039", "C04", "1.0");
        NodeStatus stale = status("REGISTER", "07039", "C05", "0.9");
        NodeStatus store = status("STORE", "07039", "07039", "1.0");
        try (MockedStatic<Enseigne> enseignes = mockStatic(Enseigne.class);
             MockedStatic<Pdv> pdvs = mockStatic(Pdv.class);
             MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class)) {
            enseignes.when(Enseigne::listAllOrdered).thenReturn(List.of(itm, other));
            pdvs.when(() -> Pdv.listByEnseigne("ITM")).thenReturn(List.of(first, second));
            pdvs.when(Pdv::listAllOrdered).thenReturn(List.of(first, second));
            statuses.when(() -> NodeStatus.listByKind("REGISTER")).thenReturn(List.of(register, stale));
            statuses.when(() -> NodeStatus.listByKind("STORE")).thenReturn(List.of(store));
            DeploymentPlanner planner = new DeploymentPlanner();
            assertEquals(List.of("07039", "07040"), planner.linesOf("COUNTRY", " FR ", "IMPOS"));
            assertEquals(List.of("07039", "07040"), planner.linesOf("ENSEIGNE", "ITM", "IMPOS"));
            assertEquals(List.of("07039"), planner.linesOf("ADHERENT", "A1", "IMPOS"));
            assertEquals(List.of("07039;C04"), planner.linesOf("VERSION", "1.0", "IMPOS"));
            assertEquals(List.of("07039"), planner.linesOf("VERSION", "1.0", "IMEDGE"));
            assertEquals(List.of(), planner.linesOf("ADHERENT", null, "IMPOS"));
        }
    }
}
