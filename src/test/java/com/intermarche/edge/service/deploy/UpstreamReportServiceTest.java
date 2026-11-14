package com.intermarche.edge.service.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.deploy.NodeVersion;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.domain.sync.RefState;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import com.intermarche.edge.service.sync.RefPullService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link UpstreamReportService}. */
class UpstreamReportServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @param role the node's role
     * @param central the central URL, possibly null
     * @param token the shared token, possibly null
     * @return the service
     */
    private UpstreamReportService service(String role, String central, String token) {
        UpstreamReportService service = new UpstreamReportService();
        service.role = role;
        service.centralUrl = Optional.ofNullable(central);
        service.token = Optional.ofNullable(token);
        service.nodeEchelonService = mock(NodeEchelonService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        service.nodeUpdateService = mock(NodeUpdateService.class);
        service.nodeStatusService = mock(NodeStatusService.class);
        service.deploymentPlanner = mock(DeploymentPlanner.class);
        service.maintenanceService = mock(MaintenanceService.class);
        service.edgeMaintenanceService = mock(EdgeMaintenanceService.class);
        service.functionUsageService = mock(FunctionUsageService.class);
        service.integrationRetryService = mock(IntegrationRetryService.class);
        service.refPullService = mock(RefPullService.class);
        service.objectMapper = new ObjectMapper();
        service.httpClient = mock(HttpClient.class);
        NodeVersion version = new NodeVersion();
        version.installedVersion = "1.0";
        when(service.nodeUpdateService.snapshot()).thenReturn(version);
        when(service.posSettingsService.supervisionReportSeconds()).thenReturn(60);
        return service;
    }

    /**
     * Runs every transaction in place.
     *
     * @param tx the static mock of the transaction API
     * @throws Exception never
     */
    @SuppressWarnings("unchecked")
    private void inPlace(MockedStatic<QuarkusTransaction> tx) throws Exception {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        when(runner.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<Object>) invocation.getArgument(0)).call());
    }

    /**
     * Makes the HTTP client answer.
     *
     * @param service the service
     * @param status the status
     * @param body the body
     * @return the request captor
     * @throws Exception never
     */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<HttpRequest> answer(UpstreamReportService service, int status, String body) throws Exception {
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        when(service.httpClient.send(requests.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return requests;
    }

    /** The cycle starts on a store or central node only, and stops cleanly. */
    @Test
    void theCycleRunsOffTheRegisters() {
        UpstreamReportService register = service("register", null, null);
        register.onStart(null);
        register.stop();
        verify(register.posSettingsService, never()).supervisionReportSeconds();
        UpstreamReportService store = service("store", null, null);
        store.onStart(null);
        store.stop();
        verify(store.posSettingsService).supervisionReportSeconds();
    }

    /** Only a store node with a central URL reports upwards. */
    @Test
    void relayedNeedsAStoreAndACentral() {
        assertTrue(service("store", "http://central", null).relayed());
        assertFalse(service("central", "http://central", null).relayed());
        assertFalse(service("store", null, null).relayed());
        assertFalse(service("store", " ", null).relayed());
    }

    /** BO-08-04-15: a failing cycle is swallowed; the next one runs. */
    @Test
    void aFailingCycleIsSwallowed() throws Exception {
        UpstreamReportService service = service("store", "http://central/", "s3cret");
        answer(service, 503, "");
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            service.cycleSafely();
        }
        assertTrue(service.started);
        verify(service.nodeUpdateService, never()).consider(any(), anyBoolean(), anyBoolean(), any());
    }

    /**
     * BO-08-03-05/07: the central node (or a store without one) asks its own lots at start-up, carries out
     * its orders, answers the version check with the update's decision, and writes its own status.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theAuthorityConsidersItsOwnLots() throws Exception {
        UpstreamReportService service = service("central", null, null);
        DeployPayloads.Target own = new DeployPayloads.Target();
        when(service.deploymentPlanner.targetFor("CENTRAL", null, "CENTRAL")).thenReturn(own);
        DeployPayloads.Action pull = new DeployPayloads.Action();
        pull.type = "REFERENTIAL_PULL";
        DeployPayloads.Action check = new DeployPayloads.Action();
        check.uid = "c1";
        check.type = "UPDATE_CHECK";
        when(service.maintenanceService.handOut("CENTRAL::CENTRAL")).thenReturn(List.of(pull, check));
        DeployPayloads.ActionResult pulled = new DeployPayloads.ActionResult();
        when(service.edgeMaintenanceService.execute(pull)).thenReturn(pulled);
        when(service.nodeUpdateService.consider(eq(own), eq(true), eq(true), any(Function.class)))
                .thenReturn(NodeUpdateService.Decision.UP_TO_DATE);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            service.cycleSafely();
            service.cycle();
        }
        ArgumentCaptor<List<DeployPayloads.ActionResult>> results = ArgumentCaptor.forClass(List.class);
        verify(service.maintenanceService, org.mockito.Mockito.times(2)).recordResults(results.capture());
        assertEquals(pulled, results.getValue().get(0));
        assertEquals("Contrôle de mise à jour : UP_TO_DATE", results.getValue().get(1).result);
        verify(service.nodeStatusService, org.mockito.Mockito.times(2)).write(any());
    }

    /** A store node without central and without orders considers its target with no start-up the second time. */
    @Test
    @SuppressWarnings("unchecked")
    void aStoreWithoutCentralIsItsOwnAuthority() throws Exception {
        UpstreamReportService service = service("store", null, null);
        when(service.nodeEchelonService.currentPdv()).thenReturn(null);
        service.started = true;
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            service.cycle();
        }
        verify(service.deploymentPlanner).targetFor("STORE", null, "EDGE");
        verify(service.nodeUpdateService).consider(any(), eq(false), eq(true), any(Function.class));
    }

    /**
     * BO-08-03-*, BO-08-04-17: a store node reports to the central node with its registers, counters and
     * outcomes; keeps the registers' targets, relays the orders, and takes its own target.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aStoreReportsToTheCentral() throws Exception {
        UpstreamReportService service = service("store", "http://central", "s3cret");
        when(service.nodeEchelonService.currentPdv()).thenReturn("07039");
        DeployPayloads.ReportAnswer answer = new DeployPayloads.ReportAnswer();
        DeployPayloads.Target own = new DeployPayloads.Target();
        own.kind = "STORE";
        own.pdvNumber = "07039";
        own.nodeId = "07039";
        own.version = "2.0";
        DeployPayloads.Target register = new DeployPayloads.Target();
        register.kind = "REGISTER";
        register.pdvNumber = "07039";
        register.nodeId = "C04";
        DeployPayloads.Action order = new DeployPayloads.Action();
        order.uid = "u1";
        answer.targets = List.of(own, register);
        answer.actions = List.of(order);
        ArgumentCaptor<HttpRequest> requests = answer(service, 200, new ObjectMapper().writeValueAsString(answer));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            service.cycle();
        }
        HttpRequest sent = requests.getValue();
        assertEquals("http://central/api/supervision/report", sent.uri().toString());
        assertEquals(Optional.of("s3cret"), sent.headers().firstValue("X-Sync-Token"));
        verify(service.nodeStatusService).registerReports();
        verify(service.functionUsageService).recent(1);
        verify(service.maintenanceService).unforwarded();
        ArgumentCaptor<DeployPayloads.Target> kept = ArgumentCaptor.forClass(DeployPayloads.Target.class);
        verify(service.nodeStatusService).keepRelayedTarget(kept.capture());
        assertEquals("C04", kept.getValue().nodeId);
        verify(service.maintenanceService).relay(any(DeployPayloads.Action.class));
        ArgumentCaptor<DeployPayloads.Target> considered = ArgumentCaptor.forClass(DeployPayloads.Target.class);
        verify(service.nodeUpdateService).consider(considered.capture(), eq(true), eq(true), any(Function.class));
        assertEquals("2.0", considered.getValue().version);
    }

    /**
     * BO-08-01-17: the node's integrations are its latest deposit per feed, then its pulled domains, a pull
     * error failing its domain, an error with no applied domain listed alone.
     */
    @Test
    void integrationsListDepositsThenPulls() throws Exception {
        UpstreamReportService service = service("store", null, null);
        IntegrationDeposit latest = new IntegrationDeposit();
        latest.code = "PRODUCTS";
        latest.status = IntegrationDeposit.Status.PARTIAL;
        latest.lastResult = "1 erreur";
        latest.lastAttemptAt = LocalDateTime.of(2026, 10, 24, 8, 0);
        IntegrationDeposit older = new IntegrationDeposit();
        older.code = "PRODUCTS";
        older.status = IntegrationDeposit.Status.APPLIED;
        IntegrationDeposit vat = new IntegrationDeposit();
        vat.code = "VAT_RATES";
        vat.status = IntegrationDeposit.Status.APPLIED;
        when(service.integrationRetryService.latest(200)).thenReturn(List.of(latest, older, vat));
        RefState pulled = new RefState();
        pulled.domain = "PDVS";
        pulled.appliedAt = LocalDateTime.of(2026, 10, 24, 7, 0);
        RefState failing = new RefState();
        failing.domain = "ENSEIGNES";
        RefState shadowed = new RefState();
        shadowed.domain = "PRODUCTS";
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("ENSEIGNES", "boom");
        errors.put("COUNTRIES", "down");
        errors.put("PRODUCTS", "x");
        when(service.refPullService.getLastErrors()).thenReturn(errors);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(pulled, failing, shadowed));
            List<DeployPayloads.Integration> integrations = service.integrations();
            assertEquals(5, integrations.size());
            assertEquals("FAILED", integrations.get(0).outcome);
            assertEquals("2026-10-24T08:00", integrations.get(0).at);
            assertEquals("APPLIED", integrations.get(1).outcome);
            assertEquals("PDVS", integrations.get(2).domain);
            assertEquals("APPLIED", integrations.get(2).outcome);
            assertEquals("FAILED", integrations.get(3).outcome);
            assertEquals("COUNTRIES", integrations.get(4).domain);
            assertNull(integrations.get(4).at);
        }
    }

    /** BO-08-04-20: the node's inventory names its application, runtime, system, processors and memory. */
    @Test
    void inventoryDescribesTheNode() {
        List<DeployPayloads.InventoryItem> items = UpstreamReportService.inventory("1.0");
        assertEquals(5, items.size());
        assertEquals("Application imedge", items.get(0).name);
        assertEquals("1.0", items.get(0).detail);
        assertEquals("HARDWARE", items.get(3).category);
    }

    /** BO-08-03-07: the own package comes from the central node when relayed, from the own lots otherwise. */
    @Test
    void downloadReadsTheRightSource() throws Exception {
        DeployPayloads.Target target = new DeployPayloads.Target();
        target.product = "IMEDGE";
        target.version = "2.0";
        UpstreamReportService local = service("central", null, null);
        when(local.deploymentPlanner.packageContent("IMEDGE", "2.0")).thenReturn("pkg");
        assertEquals("pkg", local.download(target));
        UpstreamReportService empty = service("central", null, null);
        assertThrows(IllegalStateException.class, () -> empty.download(target));
        UpstreamReportService relayed = service("store", "http://central", null);
        ArgumentCaptor<HttpRequest> requests = answer(relayed, 200, "remote");
        assertEquals("remote", relayed.download(target));
        assertEquals("http://central/api/deployment/package/IMEDGE/2.0", requests.getValue().uri().toString());
        assertFalse(requests.getValue().headers().firstValue("X-Sync-Token").isPresent());
    }

    /** A package the central node does not have, or cannot send, is null; without a central node, null too. */
    @Test
    @SuppressWarnings("unchecked")
    void fetchFromCentralAnswersNullOnTrouble() throws Exception {
        assertNull(service("store", null, null).fetchFromCentral("IMPOS", "2.0"));
        UpstreamReportService missing = service("store", "http://central", "");
        answer(missing, 404, "");
        assertNull(missing.fetchFromCentral("IMPOS", "2.0"));
        UpstreamReportService broken = service("store", "http://central", null);
        when(broken.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("refused"));
        assertNull(broken.fetchFromCentral("IMPOS", "2.0"));
    }
    /**
     * BO-02-05-05: a store states the revenue of its last days and, being its
     * own authority, files it itself; the central node states none and files
     * none.
     *
     * @throws Exception never
     */
    @Test
    @SuppressWarnings("unchecked")
    void aStoreStatesAndFilesItsRevenue() throws Exception {
        UpstreamReportService store = service("store", null, null);
        store.pdvRevenueService = mock(PdvRevenueService.class);
        when(store.nodeEchelonService.currentPdv()).thenReturn("07039");
        List<DeployPayloads.DayRevenue> days = List.of(new DeployPayloads.DayRevenue());
        when(store.pdvRevenueService.dayRevenues(any())).thenReturn(days);
        UpstreamReportService central = service("central", null, null);
        central.pdvRevenueService = mock(PdvRevenueService.class);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            inPlace(tx);
            store.cycle();
            central.cycle();
        }
        verify(store.pdvRevenueService).record("07039", days);
        verify(central.pdvRevenueService, org.mockito.Mockito.never()).dayRevenues(any());
        verify(central.pdvRevenueService, org.mockito.Mockito.never()).record(any(), any());
    }
}
