package com.intermarche.edge.imports;

import com.intermarche.edge.domain.deploy.NodeIntegration;
import com.intermarche.edge.service.deploy.DeployPayloads;
import com.intermarche.edge.service.deploy.DeploymentPlanner;
import com.intermarche.edge.service.deploy.NodeStatusService;
import com.intermarche.edge.service.deploy.UpstreamReportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MACHINE surface of the supervision and deployment (BO-08-01-19,
 * BO-08-03-*, BO-08-04-15/16/17/20): where a register reports to its store
 * node and a store node to the central node, where they download the package
 * of the version they must run, and where the integration states are
 * answered to another system.
 *
 * <p>Gated like the other machine surfaces of the node — store or central
 * role, the same optional shared token: it is the same conversation with the
 * same callers.
 *
 * <p>The class is rooted at {@code /} and every method names its full
 * {@code /api/...} address: a class rooted at {@code /api} would win the
 * JAX-RS root-class match for EVERY {@code /api/...} request and shadow the
 * {@code /api/...} methods of the other {@code /}-rooted classes (the
 * register's supervisor call {@code /api/supervisor/call} answered 404).
 */
@Path("/")
public class NodeReportResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(NodeReportResource.class);

    /** The role of this node: register, store or central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** Writes the reports and builds their answers. */
    @Inject
    NodeStatusService nodeStatusService;

    /** The releases this node publishes. */
    @Inject
    DeploymentPlanner deploymentPlanner;

    /** The central node, for a package a store does not hold. */
    @Inject
    UpstreamReportService upstreamReportService;

    /**
     * Receives a node report and answers the versions to run and the orders
     * waiting.
     *
     * @param presentedToken the shared token presented
     * @param report the report
     * @return 200 with the answer, or 401/403 when gated
     */
    @POST
    @Path("/api/supervision/report")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response report(@HeaderParam("X-Sync-Token") String presentedToken,
                           DeployPayloads.NodeReport report) {
        Response gate = gate(presentedToken);
        if (gate != null) {
            return gate;
        }
        LOGGER.info("Entering method report from " + report.kind + " " + report.nodeId);
        return Response.ok(nodeStatusService.receive(report)).build();
    }

    /**
     * Serves the package of a release: this node's own, or on a store node
     * the central node's.
     *
     * @param presentedToken the shared token presented
     * @param product IMEDGE or IMPOS
     * @param version the version
     * @return 200 with the package, 404 when unknown, or 401/403 when gated
     */
    @GET
    @Path("/api/deployment/package/{product}/{version}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response downloadPackage(@HeaderParam("X-Sync-Token") String presentedToken,
                                    @PathParam("product") String product, @PathParam("version") String version) {
        Response gate = gate(presentedToken);
        if (gate != null) {
            return gate;
        }
        String content = deploymentPlanner.packageContent(product, version);
        if (content == null) {
            content = upstreamReportService.fetchFromCentral(product, version);
        }
        return content == null ? Response.status(Response.Status.NOT_FOUND).build() : Response.ok(content).build();
    }

    /**
     * Answers the integration state of every reported node, optionally for one
     * domain — on the central node, the reception status of a referential on
     * each point of sale (BO-08-01-19).
     *
     * @param presentedToken the shared token presented
     * @param domain the domain or feed code, or null for all
     * @return 200 with one entry per node and domain, or 401/403 when gated
     */
    @GET
    @Path("/api/supervision/integrations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response integrations(@HeaderParam("X-Sync-Token") String presentedToken,
                                 @QueryParam("domain") String domain) {
        Response gate = gate(presentedToken);
        if (gate != null) {
            return gate;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (NodeIntegration row : QuarkusTransaction.requiringNew().call(NodeIntegration::listOrdered)) {
            if (domain == null || domain.isBlank() || domain.trim().equalsIgnoreCase(row.domain)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("pdv", row.pdvNumber);
                entry.put("kind", row.kind);
                entry.put("node", row.nodeId);
                entry.put("domain", row.domain);
                entry.put("received", "APPLIED".equals(row.outcome));
                entry.put("at", row.integratedAt == null ? null : row.integratedAt.toString());
                entry.put("detail", row.detail);
                entries.add(entry);
            }
        }
        return Response.ok(entries).build();
    }

    /**
     * Refuses a caller this node must not serve.
     *
     * @param presentedToken the token the caller presented
     * @return the refusal, or null when the caller may proceed
     */
    private Response gate(String presentedToken) {
        if ("register".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ImporterCsvResource.errorAnswer("Ce nœud ne reçoit pas de rapports")).build();
        }
        if (token.isPresent() && !token.get().isBlank() && !token.get().equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ImporterCsvResource.errorAnswer("Jeton de synchronisation invalide")).build();
        }
        return null;
    }
}
