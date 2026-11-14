package com.intermarche.edge.ui.deploy;

import com.intermarche.edge.domain.deploy.ApplicationRelease;
import com.intermarche.edge.domain.deploy.DeploymentLot;
import com.intermarche.edge.domain.deploy.DeploymentSelection;
import com.intermarche.edge.domain.deploy.DeploymentTarget;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.service.deploy.DeploymentPlanner;
import com.intermarche.edge.service.deploy.NodeStatusService;
import com.intermarche.edge.ui.supervision.ConsoleFilter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link DeploymentConsoleService}. */
class DeploymentConsoleServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @return the service, a three-minute offline tolerance
     */
    private DeploymentConsoleService service() {
        DeploymentConsoleService service = new DeploymentConsoleService();
        service.deploymentPlanner = mock(DeploymentPlanner.class);
        service.nodeStatusService = mock(NodeStatusService.class);
        when(service.nodeStatusService.toleranceSeconds()).thenReturn(180L);
        return service;
    }

    /**
     * Builds a lot.
     *
     * @param id its id
     * @param product its product
     * @param status its status
     * @return the lot
     */
    private DeploymentLot lot(long id, String product, DeploymentLot.Status status) {
        DeploymentLot lot = mock(DeploymentLot.class);
        lot.id = id;
        lot.code = "L" + id;
        lot.product = product;
        lot.version = "2.0";
        lot.status = status;
        lot.windowStart = LocalDateTime.of(2026, 10, 24, 20, 0);
        lot.windowEnd = LocalDateTime.of(2026, 10, 24, 23, 30);
        return lot;
    }

    /**
     * Builds a target.
     *
     * @param id its id
     * @param lotId its lot
     * @param kind its kind
     * @param pdv its point of sale
     * @param node its node
     * @param state its state
     * @return the target
     */
    private DeploymentTarget target(long id, long lotId, String kind, String pdv, String node,
                                    DeploymentTarget.State state) {
        DeploymentTarget target = mock(DeploymentTarget.class);
        target.id = id;
        target.lotId = lotId;
        target.kind = kind;
        target.pdvNumber = pdv;
        target.nodeId = node;
        target.nodeKey = kind + ":" + pdv + ":" + node;
        target.state = state;
        return target;
    }

    /**
     * Builds a reported node.
     *
     * @param kind its kind
     * @param pdv its point of sale
     * @param version its version
     * @param online whether it reported just now
     * @return the status
     */
    private NodeStatus node(String kind, String pdv, String version, boolean online) {
        NodeStatus status = new NodeStatus();
        status.kind = kind;
        status.pdvNumber = pdv;
        status.version = version;
        status.reportedAt = online ? LocalDateTime.now() : LocalDateTime.now().minusHours(2);
        return status;
    }

    /**
     * Places every point of sale in France, enseigne ITM; the unknown one nowhere.
     *
     * @param echelons the static mock
     */
    private void organisation(MockedStatic<NodeEchelon> echelons) {
        echelons.when(() -> NodeEchelon.of(anyString())).thenAnswer(invocation -> {
            String pdv = invocation.getArgument(0);
            return pdv.isEmpty() ? new NodeEchelon(null, null, null) : new NodeEchelon(pdv, "ITM", "FR");
        });
    }

    /**
     * Builds a filter.
     *
     * @param pairs name, value, name, value...
     * @return the filter
     */
    private ConsoleFilter filter(String... pairs) {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.putSingle(pairs[i], pairs[i + 1]);
        }
        return ConsoleFilter.from(params);
    }

    /** BO-08-01: the version level and the node's presence decide the prerequisites. */
    @Test
    void prerequisiteChecksVersionAndPresence() {
        DeploymentLot lot = lot(1, "IMPOS", DeploymentLot.Status.DRAFT);
        assertEquals("Nœud jamais vu : version inconnue", DeploymentConsoleService.prerequisite(lot, null, false));
        assertEquals("Nœud jamais vu : version inconnue",
                DeploymentConsoleService.prerequisite(lot, node("REGISTER", "07039", null, true), true));
        assertEquals("Déjà à jour", DeploymentConsoleService.prerequisite(lot, node("REGISTER", "07039", "2.0", true), true));
        lot.minVersion = "1.5";
        assertEquals("Version trop ancienne (minimum 1.5)",
                DeploymentConsoleService.prerequisite(lot, node("REGISTER", "07039", "1.0", true), true));
        assertEquals("Prérequis respectés",
                DeploymentConsoleService.prerequisite(lot, node("REGISTER", "07039", "1.8", true), true));
        lot.minVersion = null;
        assertEquals("Hors ligne : mise à jour au prochain démarrage",
                DeploymentConsoleService.prerequisite(lot, node("REGISTER", "07039", "1.0", false), false));
    }

    /**
     * BO-08-03-06: the console lists every target OK, KO or waiting, filtered by lot, product, status and
     * echelon, with the chart per group and the versions in service.
     */
    @Test
    void consoleFollowsEveryTarget() {
        DeploymentLot registers = lot(1, "IMPOS", DeploymentLot.Status.SCHEDULED);
        DeploymentLot stores = lot(2, "IMEDGE", DeploymentLot.Status.DRAFT);
        DeploymentTarget applied = target(10, 1, "REGISTER", "07039", "C04", DeploymentTarget.State.APPLIED);
        DeploymentTarget failed = target(11, 1, "REGISTER", "07039", "C05", DeploymentTarget.State.FAILED);
        DeploymentTarget rolled = target(12, 1, "REGISTER", "07039", "C06", DeploymentTarget.State.ROLLED_BACK);
        DeploymentTarget waiting = target(13, 2, "STORE", "07039", "07039", DeploymentTarget.State.PENDING);
        NodeStatus register = node("REGISTER", "07039", "2.0", true);
        NodeStatus store = node("STORE", "07039", "1.0", false);
        NodeStatus unversioned = node("REGISTER", null, null, true);
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class);
             MockedStatic<ApplicationRelease> releases = mockStatic(ApplicationRelease.class);
             MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            lots.when(DeploymentLot::listOrdered).thenReturn(List.of(registers, stores));
            releases.when(ApplicationRelease::listOrdered).thenReturn(List.of());
            targets.when(() -> DeploymentTarget.listByLot(1L)).thenReturn(List.of(applied, failed, rolled));
            targets.when(() -> DeploymentTarget.listByLot(2L)).thenReturn(List.of(waiting));
            statuses.when(() -> NodeStatus.findByKey("REGISTER:07039:C04")).thenReturn(register);
            statuses.when(() -> NodeStatus.findByKey("STORE:07039:07039")).thenReturn(store);
            statuses.when(NodeStatus::listOrdered).thenReturn(List.of(register, store, unversioned));
            DeploymentConsoleService service = service();
            DeploymentConsoleService.ConsoleData data = service.console(filter());
            assertEquals(4, data.rows.size());
            assertEquals(1, data.okCount);
            assertEquals(2, data.koCount);
            assertEquals(1, data.pendingCount);
            assertTrue(data.chart.startsWith("<svg"));
            assertEquals(2, data.versions.size());
            assertTrue(data.versionChart.contains("IMPOS 2.0"));
            assertTrue(data.rows.get(0).ok && data.rows.get(0).online);
            assertNull(data.rows.get(1).currentVersion);
            assertFalse(data.rows.get(1).online);
            List<List<Object>> lines = DeploymentConsoleService.lines(data);
            assertEquals("OK", lines.get(0).get(10));
            assertEquals("KO", lines.get(1).get(10));
            assertEquals("EN ATTENTE", lines.get(3).get(10));
            assertEquals("", lines.get(1).get(8));
            assertEquals(12, DeploymentConsoleService.columns().size());
            assertEquals(3, service.console(filter("lot", "L1")).rows.size());
            DeploymentConsoleService.ConsoleData edge = service.console(filter("product", "IMEDGE"));
            assertEquals(1, edge.rows.size());
            assertEquals(1, edge.versions.size());
            assertEquals(1, service.console(filter("status", "OK")).rows.size());
            DeploymentConsoleService.ConsoleData elsewhere = service.console(filter("pdv", "07040"));
            assertEquals(0, elsewhere.rows.size());
            assertTrue(elsewhere.versions.isEmpty());
            assertEquals("", elsewhere.versionChart);
        }
    }

    /** BO-08-03-01: the page of a lot lists its targets with their prerequisites; an unknown lot is null. */
    @Test
    void lotShowsThePrerequisites() {
        DeploymentLot lot = lot(1, "IMPOS", DeploymentLot.Status.DRAFT);
        DeploymentTarget ready = target(10, 1, "REGISTER", "07039", "C04", DeploymentTarget.State.PENDING);
        DeploymentTarget unseen = target(11, 1, "REGISTER", null, "C05", DeploymentTarget.State.PENDING);
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class);
             MockedStatic<ApplicationRelease> releases = mockStatic(ApplicationRelease.class);
             MockedStatic<DeploymentSelection> selections = mockStatic(DeploymentSelection.class);
             MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            lots.when(() -> DeploymentLot.findByCode("L1")).thenReturn(lot);
            targets.when(() -> DeploymentTarget.listByLot(1L)).thenReturn(List.of(ready, unseen));
            selections.when(DeploymentSelection::listOrdered).thenReturn(List.of());
            statuses.when(() -> NodeStatus.findByKey("REGISTER:07039:C04")).thenReturn(node("REGISTER", "07039", "1.0", true));
            DeploymentConsoleService service = service();
            assertNull(service.lot("NOPE"));
            DeploymentConsoleService.LotView view = service.lot("L1");
            assertEquals("24/10/2026 20:00", view.windowStart);
            assertEquals("24/10/2026 23:30", view.windowEnd);
            assertEquals(1, view.readyCount);
            assertEquals(1, view.blockedCount);
            assertTrue(view.rows.get(0).prerequisiteOk);
            assertNull(view.release);
        }
    }

    /** A release is published with its kind and notes; a bad product, version or a duplicate is refused. */
    @Test
    void createReleaseValidates() {
        DeploymentConsoleService service = service();
        try (MockedStatic<ApplicationRelease> releases = mockStatic(ApplicationRelease.class);
             MockedConstruction<ApplicationRelease> rows = mockConstruction(ApplicationRelease.class)) {
            releases.when(() -> ApplicationRelease.findRelease("IMPOS", "1.0")).thenReturn(new ApplicationRelease());
            assertEquals("Produit inconnu.", service.createRelease("OTHER", "2.0", "MAJOR", null, false));
            assertEquals("Version illisible.", service.createRelease("IMPOS", null, "MAJOR", null, false));
            assertEquals("Version illisible.", service.createRelease("IMEDGE", "v2", "MAJOR", null, false));
            assertEquals("Cette version est déjà publiée.", service.createRelease("IMPOS", " 1.0 ", "MAJOR", null, false));
            assertNull(service.createRelease("IMPOS", "2.0", "MAJOR", " Nouvelle caisse ", true));
            assertNull(service.createRelease("IMEDGE", "2.0", "WEIRD", " ", false));
            assertNull(service.createRelease("IMEDGE", "2.1", "PATCH", null, false));
            ApplicationRelease major = rows.constructed().get(1);
            assertEquals("MAJOR", major.releaseKind);
            assertEquals("Nouvelle caisse", major.notes);
            assertTrue(major.defective);
            ApplicationRelease weird = rows.constructed().get(2);
            assertEquals("MINOR", weird.releaseKind);
            assertNull(weird.notes);
            verify(major).persist();
        }
    }

    /** BO-08-03-04: a lot is created as a draft with its window; a bad code, version or window is refused. */
    @Test
    void createLotValidates() {
        DeploymentConsoleService service = service();
        DeploymentLot taken = new DeploymentLot();
        ApplicationRelease published = new ApplicationRelease();
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<ApplicationRelease> releases = mockStatic(ApplicationRelease.class);
             MockedConstruction<DeploymentLot> rows = mockConstruction(DeploymentLot.class)) {
            lots.when(() -> DeploymentLot.findByCode("TAKEN")).thenReturn(taken);
            releases.when(() -> ApplicationRelease.findRelease("IMPOS", "2.0")).thenReturn(published);
            String start = "24/10/2026 20:00";
            String end = "24/10/2026 23:30";
            assertEquals("Code de lot illisible.", service.createLot(null, null, "IMPOS", "2.0", false, null, start, end, "A"));
            assertEquals("Code de lot illisible.", service.createLot("a b", null, "IMPOS", "2.0", false, null, start, end, "A"));
            assertEquals("Ce code de lot existe déjà.", service.createLot("TAKEN", null, "IMPOS", "2.0", false, null, start, end, "A"));
            assertEquals("Version non publiée pour ce produit.",
                    service.createLot("L1", null, "IMPOS", null, false, null, start, end, "A"));
            assertEquals("Période illisible : attendu jj/mm/aaaa hh:mn, la fin après le début.",
                    service.createLot("L1", null, "IMPOS", "2.0", false, null, end, start, "A"));
            assertNull(service.createLot(" L1 ", " Pilote Nord ", "IMPOS", " 2.0 ", true, " 1.5 ", start, end, "A"));
            assertNull(service.createLot("L2", " ", "IMPOS", "2.0", false, " ", start, end, "A"));
            assertNull(service.createLot("L3", null, "IMPOS", "2.0", false, null, start, end, "A"));
            DeploymentLot pilot = rows.constructed().get(0);
            assertEquals("L1", pilot.code);
            assertEquals("Pilote Nord", pilot.label);
            assertEquals("1.5", pilot.minVersion);
            assertTrue(pilot.pilot);
            assertEquals(LocalDateTime.of(2026, 10, 24, 20, 0), pilot.windowStart);
            assertNull(rows.constructed().get(1).label);
            assertNull(rows.constructed().get(1).minVersion);
            verify(pilot).persist();
        }
    }

    /** BO-08-03-04: the window of an open lot changes; a closed or unknown lot, or a bad window, is refused. */
    @Test
    void updateWindowValidates() {
        DeploymentLot open = lot(1, "IMPOS", DeploymentLot.Status.SCHEDULED);
        DeploymentLot closed = lot(2, "IMPOS", DeploymentLot.Status.CLOSED);
        DeploymentConsoleService service = service();
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class)) {
            lots.when(() -> DeploymentLot.findByCode("L1")).thenReturn(open);
            lots.when(() -> DeploymentLot.findByCode("L2")).thenReturn(closed);
            assertEquals("Lot introuvable ou clôturé.", service.updateWindow("L2", "x", "y"));
            assertEquals("Lot introuvable ou clôturé.", service.updateWindow("L9", "x", "y"));
            assertEquals("Période illisible : attendu jj/mm/aaaa hh:mn, la fin après le début.",
                    service.updateWindow("L1", null, "y"));
            assertEquals("Période illisible : attendu jj/mm/aaaa hh:mn, la fin après le début.",
                    service.updateWindow("L1", "31/02/2026 10:00", "01/03/2026 10:00"));
            assertNull(service.updateWindow("L1", "25/10/2026 06:00", "25/10/2026 08:00"));
        }
        assertEquals(LocalDateTime.of(2026, 10, 25, 6, 0), open.windowStart);
        verify(open).persist();
    }

    /** BO-08-03-02/03: every selection mode resolves into targets, new ones only, the unknown named. */
    @Test
    void addTargetsResolvesEveryMode() {
        DeploymentLot lot = lot(1, "IMPOS", DeploymentLot.Status.DRAFT);
        DeploymentSelection saved = new DeploymentSelection();
        saved.lines = "07039;C04\n07039;C05";
        DeploymentConsoleService service = service();
        DeploymentPlanner.TargetRef known = new DeploymentPlanner.TargetRef("REGISTER", "07039", "C04");
        DeploymentPlanner.TargetRef fresh = new DeploymentPlanner.TargetRef("REGISTER", "07039", "C05");
        when(service.deploymentPlanner.resolve(eq("IMPOS"), any(), anyString()))
                .thenReturn(new DeploymentPlanner.Resolution(List.of(known, fresh), List.of()));
        when(service.deploymentPlanner.resolve(eq("IMPOS"), eq(List.of("99999")), eq("ALL")))
                .thenReturn(new DeploymentPlanner.Resolution(List.of(), List.of("99999")));
        when(service.deploymentPlanner.linesOf("ENSEIGNE", "ITM", "IMPOS")).thenReturn(List.of("07039"));
        when(service.deploymentPlanner.linesOf("", "", "IMPOS")).thenReturn(List.of());
        DeploymentTarget existing = new DeploymentTarget();
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class);
             MockedStatic<DeploymentSelection> selections = mockStatic(DeploymentSelection.class);
             MockedConstruction<DeploymentTarget> rows = mockConstruction(DeploymentTarget.class)) {
            lots.when(() -> DeploymentLot.findByCode("L1")).thenReturn(lot);
            targets.when(() -> DeploymentTarget.findKey(1L, known.key())).thenReturn(existing);
            selections.when(() -> DeploymentSelection.findByName("pilotes")).thenReturn(saved);
            assertEquals("Lot introuvable ou clôturé.", service.addTargets("L9", "UNIT", null, "07039", null, null));
            assertEquals("Sélection introuvable.", service.addTargets("L1", "RECALL", null, "absente", null, null));
            assertEquals("1 cible(s) ajoutée(s).", service.addTargets("L1", "UNIT", null, "07039;C05", "EVEN", null));
            assertEquals("0 cible(s) ajoutée(s), inconnu(s) : 99999.",
                    service.addTargets("L1", null, null, "99999", null, null));
            service.addTargets("L1", "ECHELON", "ENSEIGNE", "ITM", "ALL", null);
            service.addTargets("L1", "ATTRIBUTE", null, null, "ALL", null);
            service.addTargets("L1", "IMPORT", null, null, "ODD", "07039\n07040");
            service.addTargets("L1", "IMPORT", null, null, "ODD", null);
            service.addTargets("L1", "RECALL", null, " pilotes ", "ALL", null);
            DeploymentTarget created = rows.constructed().get(0);
            assertEquals("REGISTER:07039:C05", created.nodeKey);
            assertEquals(1L, created.lotId);
            verify(created).persist();
        }
        ArgumentCaptor<List<String>> lines = ArgumentCaptor.forClass(List.class);
        verify(service.deploymentPlanner, org.mockito.Mockito.atLeastOnce()).resolve(eq("IMPOS"), lines.capture(), anyString());
        assertTrue(lines.getAllValues().contains(List.of("07039", "07040")));
        assertTrue(lines.getAllValues().contains(List.of("07039;C04", "07039;C05")));
    }

    /** A target is removed from its own open lot only. */
    @Test
    void removeTargetChecksTheLot() {
        DeploymentLot lot = lot(1, "IMPOS", DeploymentLot.Status.DRAFT);
        DeploymentTarget mine = target(10, 1, "REGISTER", "07039", "C04", DeploymentTarget.State.PENDING);
        DeploymentTarget other = target(11, 2, "REGISTER", "07039", "C05", DeploymentTarget.State.PENDING);
        DeploymentConsoleService service = service();
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            lots.when(() -> DeploymentLot.findByCode("L1")).thenReturn(lot);
            panache.when(() -> PanacheEntityBase.findById(10L)).thenReturn(mine);
            panache.when(() -> PanacheEntityBase.findById(11L)).thenReturn(other);
            assertEquals("Cible introuvable.", service.removeTarget("L9", 10L));
            assertEquals("Cible introuvable.", service.removeTarget("L1", 12L));
            assertEquals("Cible introuvable.", service.removeTarget("L1", 11L));
            assertNull(service.removeTarget("L1", 10L));
        }
        verify(mine).delete();
        verify(other, never()).delete();
    }

    /** BO-08-03-02: the targets of a lot are saved as a selection, new or replacing one of the same name. */
    @Test
    void saveSelectionKeepsTheLines() {
        DeploymentLot lot = lot(1, "IMEDGE", DeploymentLot.Status.DRAFT);
        DeploymentLot empty = lot(2, "IMEDGE", DeploymentLot.Status.DRAFT);
        DeploymentTarget register = target(10, 1, "REGISTER", "07039", "C04", DeploymentTarget.State.PENDING);
        DeploymentTarget central = target(11, 1, "CENTRAL", null, "CENTRAL", DeploymentTarget.State.PENDING);
        DeploymentTarget store = target(12, 1, "STORE", "07039", "07039", DeploymentTarget.State.PENDING);
        DeploymentSelection existing = mock(DeploymentSelection.class);
        DeploymentConsoleService service = service();
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class);
             MockedStatic<DeploymentSelection> selections = mockStatic(DeploymentSelection.class);
             MockedConstruction<DeploymentSelection> rows = mockConstruction(DeploymentSelection.class)) {
            lots.when(() -> DeploymentLot.findByCode("L1")).thenReturn(lot);
            lots.when(() -> DeploymentLot.findByCode("L2")).thenReturn(empty);
            targets.when(() -> DeploymentTarget.listByLot(1L)).thenReturn(List.of(register, central, store));
            targets.when(() -> DeploymentTarget.listByLot(2L)).thenReturn(List.of());
            selections.when(() -> DeploymentSelection.findByName("old")).thenReturn(existing);
            assertEquals("Lot ou nom de sélection manquant.", service.saveSelection("L9", "x", "A"));
            assertEquals("Lot ou nom de sélection manquant.", service.saveSelection("L1", null, "A"));
            assertEquals("Lot ou nom de sélection manquant.", service.saveSelection("L1", " ", "A"));
            assertEquals("Le lot n'a aucune cible à enregistrer.", service.saveSelection("L2", "x", "A"));
            assertNull(service.saveSelection("L1", " new ", "A"));
            assertNull(service.saveSelection("L1", "old", "A"));
            DeploymentSelection created = rows.constructed().get(0);
            assertEquals("new", created.name);
            assertEquals("07039;C04\nCENTRAL\n07039", created.lines);
            verify(created).persist();
        }
        assertEquals("07039;C04\nCENTRAL\n07039", existing.lines);
        verify(existing).persist();
    }

    /** A lot is launched once it has targets, closed whenever it is open. */
    @Test
    void scheduleAndClose() {
        DeploymentLot lot = lot(1, "IMPOS", DeploymentLot.Status.DRAFT);
        DeploymentLot empty = lot(2, "IMPOS", DeploymentLot.Status.DRAFT);
        DeploymentConsoleService service = service();
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class)) {
            lots.when(() -> DeploymentLot.findByCode("L1")).thenReturn(lot);
            lots.when(() -> DeploymentLot.findByCode("L2")).thenReturn(empty);
            targets.when(() -> DeploymentTarget.listByLot(1L)).thenReturn(List.of(new DeploymentTarget()));
            targets.when(() -> DeploymentTarget.listByLot(2L)).thenReturn(List.of());
            assertEquals("Lot introuvable ou clôturé.", service.schedule("L9"));
            assertEquals("Le lot n'a aucune cible.", service.schedule("L2"));
            assertNull(service.schedule("L1"));
            assertEquals(DeploymentLot.Status.SCHEDULED, lot.status);
            assertEquals("Lot introuvable ou clôturé.", service.close("L9"));
            assertNull(service.close("L1"));
            assertEquals(DeploymentLot.Status.CLOSED, lot.status);
        }
    }

    /** BO-08-04-02: one target, or every failed and rolled-back one, is forced with a fresh token. */
    @Test
    void forceRenewsTheToken() {
        DeploymentLot lot = lot(1, "IMPOS", DeploymentLot.Status.SCHEDULED);
        DeploymentTarget failed = target(10, 1, "REGISTER", "07039", "C04", DeploymentTarget.State.FAILED);
        DeploymentTarget rolled = target(11, 1, "REGISTER", "07039", "C05", DeploymentTarget.State.ROLLED_BACK);
        DeploymentTarget fine = target(12, 1, "REGISTER", "07039", "C06", DeploymentTarget.State.APPLIED);
        DeploymentConsoleService service = service();
        try (MockedStatic<DeploymentLot> lots = mockStatic(DeploymentLot.class);
             MockedStatic<DeploymentTarget> targets = mockStatic(DeploymentTarget.class)) {
            lots.when(() -> DeploymentLot.findByCode("L1")).thenReturn(lot);
            targets.when(() -> DeploymentTarget.listByLot(1L)).thenReturn(List.of(failed, rolled, fine));
            assertEquals(-1, service.force("L9", null));
            assertEquals(2, service.force("L1", null));
            assertNotNull(failed.forceToken);
            assertEquals(DeploymentTarget.State.PENDING, rolled.state);
            assertNull(fine.forceToken);
            assertEquals(1, service.force("L1", 12L));
            assertEquals("Forçage demandé", fine.message);
        }
    }

    /** The window helper refuses an end not after the start. */
    @Test
    void windowNeedsAnEndAfterTheStart() {
        assertNull(DeploymentConsoleService.window("24/10/2026 20:00", "24/10/2026 20:00"));
        assertEquals(2, DeploymentConsoleService.window(" 24/10/2026 20:00 ", "24/10/2026 20:01").length);
        assertEquals("", DeploymentConsoleService.text(null));
    }
}
