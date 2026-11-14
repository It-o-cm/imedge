package com.intermarche.edge.imports;

import com.intermarche.edge.domain.deploy.NodeIntegration;
import com.intermarche.edge.service.deploy.DeployPayloads;
import com.intermarche.edge.service.deploy.DeploymentPlanner;
import com.intermarche.edge.service.deploy.NodeStatusService;
import com.intermarche.edge.service.deploy.UpstreamReportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link NodeReportResource}. */
class NodeReportResourceTest {

    /**
     * Builds the resource.
     *
     * @param role the node's role
     * @param token the shared token, possibly null
     * @return the resource
     */
    private NodeReportResource resource(String role, String token) {
        NodeReportResource resource = new NodeReportResource();
        resource.role = role;
        resource.token = Optional.ofNullable(token);
        resource.nodeStatusService = mock(NodeStatusService.class);
        resource.deploymentPlanner = mock(DeploymentPlanner.class);
        resource.upstreamReportService = mock(UpstreamReportService.class);
        return resource;
    }

    /** A register refuses; a wrong token is refused; a blank configured token is open. */
    @Test
    void theGateRefusesOtherCallers() {
        assertEquals(403, resource("register", null).report(null, new DeployPayloads.NodeReport()).getStatus());
        assertEquals(401, resource("store", "s3cret").downloadPackage("bad", "IMPOS", "2.0").getStatus());
        assertEquals(401, resource("central", "s3cret").integrations(null, null).getStatus());
        NodeReportResource open = resource("store", " ");
        DeployPayloads.ReportAnswer answer = new DeployPayloads.ReportAnswer();
        DeployPayloads.NodeReport report = new DeployPayloads.NodeReport();
        when(open.nodeStatusService.receive(report)).thenReturn(answer);
        Response response = open.report(null, report);
        assertEquals(200, response.getStatus());
        assertSame(answer, response.getEntity());
    }

    /** BO-08-03-07: the package is this node's, else the central node's, else 404. */
    @Test
    void thePackageComesFromTheNearestHolder() {
        NodeReportResource resource = resource("store", "s3cret");
        when(resource.deploymentPlanner.packageContent("IMPOS", "2.0")).thenReturn("local");
        assertEquals("local", resource.downloadPackage("s3cret", "IMPOS", "2.0").getEntity());
        when(resource.upstreamReportService.fetchFromCentral("IMPOS", "2.1")).thenReturn("remote");
        assertEquals("remote", resource.downloadPackage("s3cret", "IMPOS", "2.1").getEntity());
        assertEquals(404, resource.downloadPackage("s3cret", "IMPOS", "9.9").getStatus());
        verify(resource.upstreamReportService, never()).fetchFromCentral("IMPOS", "2.0");
    }

    /** BO-08-01-19: the reception status of a domain on each node, or of every domain. */
    @Test
    @SuppressWarnings("unchecked")
    void integrationsAnswerTheReceptionStatus() throws Exception {
        NodeIntegration vat = new NodeIntegration();
        vat.pdvNumber = "07039";
        vat.kind = "STORE";
        vat.nodeId = "07039";
        vat.domain = "VAT_RATES";
        vat.outcome = "APPLIED";
        vat.integratedAt = LocalDateTime.of(2026, 10, 24, 8, 0);
        NodeIntegration products = new NodeIntegration();
        products.domain = "PRODUCTS";
        products.outcome = "FAILED";
        NodeReportResource resource = resource("central", null);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class);
             MockedStatic<NodeIntegration> integrations = mockStatic(NodeIntegration.class)) {
            TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
            tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
            when(runner.call(any(Callable.class))).thenAnswer(invocation -> ((Callable<Object>) invocation.getArgument(0)).call());
            integrations.when(NodeIntegration::listOrdered).thenReturn(List.of(vat, products));
            List<Map<String, Object>> vatOnly = (List<Map<String, Object>>) resource.integrations(null, " vat_rates ")
                    .getEntity();
            assertEquals(1, vatOnly.size());
            assertEquals(true, vatOnly.get(0).get("received"));
            assertEquals("2026-10-24T08:00", vatOnly.get(0).get("at"));
            assertEquals("07039", vatOnly.get(0).get("pdv"));
            List<Map<String, Object>> all = (List<Map<String, Object>>) resource.integrations(null, null).getEntity();
            assertEquals(2, all.size());
            assertEquals(false, all.get(1).get("received"));
            assertNull(all.get(1).get("at"));
            assertEquals(2, ((List<?>) resource.integrations(null, " ").getEntity()).size());
        }
    }
}
