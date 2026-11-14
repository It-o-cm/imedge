package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.deploy.FunctionUsage;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.deploy.FunctionUsageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;

/**
 * Counts every use of a BACK-OFFICE function (BO-08-02-02).
 *
 * <p>A function is a screen handler declaring its feature: the count is
 * filed under the feature key and the handler, {@code sync:relaunchPull}.
 * What counts as a use: a GESTURE (every POST) and a page OPENED by a person
 * (a GET the browser navigates to, asking for HTML). The polls a page makes
 * on its own every few seconds are not uses and are not counted.
 *
 * <p>Never in the way: a count that cannot be written is logged and the
 * request proceeds.
 */
@Provider
@Priority(Priorities.USER + 50)
public class FunctionUsageFilter implements ContainerRequestFilter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FunctionUsageFilter.class);

    /** The handler being called. */
    @Context
    ResourceInfo resourceInfo;

    /** The counters. */
    @Inject
    FunctionUsageService functionUsageService;

    /** This node's point of sale. */
    @Inject
    NodeEchelonService nodeEchelonService;

    /** The role of this node, naming the central node's counters. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /**
     * Counts the call when it is a back-office function used by a person.
     *
     * @param request the request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        FeatureAccess access = method == null ? null : method.getAnnotation(FeatureAccess.class);
        if (access == null || !counted(request)) {
            return;
        }
        try {
            boolean central = "central".equalsIgnoreCase(role);
            String pdv = central ? null : QuarkusTransaction.requiringNew().call(nodeEchelonService::currentPdv);
            String node = central ? "CENTRAL" : pdv == null ? "EDGE" : pdv;
            functionUsageService.record(node, pdv, FunctionUsage.BACK_OFFICE,
                    access.value().getKey() + ":" + method.getName());
        } catch (RuntimeException failure) {
            LOGGER.warnf("Usage de fonction non compté (%s)", failure.getMessage());
        }
    }

    /**
     * Whether a request is a use by a person: a gesture, or a page navigated to.
     *
     * @param request the request
     * @return true for a POST, or a GET accepting HTML
     */
    static boolean counted(ContainerRequestContext request) {
        if (HttpMethod.POST.equals(request.getMethod())) {
            return true;
        }
        String accept = request.getHeaderString("Accept");
        return HttpMethod.GET.equals(request.getMethod()) && accept != null && accept.contains("text/html");
    }
}
