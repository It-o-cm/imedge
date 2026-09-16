package com.intermarche.pos.ui.dashboard;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardResource}.
 * <p>
 * The resource is a thin JAX-RS facade over a {@link DashboardService}, a
 * {@link SupervisorCallRegistry} and a {@code dashboard} Qute {@link Template};
 * every collaborator is a Mockito mock and the two config fields ({@code role},
 * {@code token}) are set directly. Tests assert absolute expected values and
 * verify delegation, covering the empty/non-empty arms of the call-collecting
 * loop and both arms of the off-role guard and of each half of the token
 * short-circuit in {@code receiveCall}.
 */
class DashboardResourceTest {

    /**
     * Builds a {@link DashboardResource} whose collaborators are fresh mocks
     * wired onto its package-private fields, defaulting to the store role with
     * no token so the machine-facing endpoint is open unless a test tightens it.
     *
     * @return a resource with mocked service, registry and template
     */
    private DashboardResource newResource() {
        DashboardResource resource = new DashboardResource();
        resource.dashboard = mock(Template.class);
        resource.dashboardService = mock(DashboardService.class);
        resource.supervisorCallRegistry = mock(SupervisorCallRegistry.class);
        resource.role = "store";
        resource.token = Optional.empty();
        // The dashboard alerts default to ON, the pre-existing behavior the
        // call-collecting cases rely on (BO-10-08-01).
        resource.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(resource.posSettingsService.dashboardAlertsEnabled()).thenReturn(true);
        return resource;
    }

    /**
     * Builds a {@link DashboardResource.CallDto} with the given fields.
     *
     * @param terminalId the calling register identifier
     * @param operator the calling operator display name
     * @param reason the call reason
     * @return the populated DTO
     */
    private DashboardResource.CallDto dto(String terminalId, String operator, String reason) {
        DashboardResource.CallDto dto = new DashboardResource.CallDto();
        dto.terminalId = terminalId;
        dto.operator = operator;
        dto.reason = reason;
        return dto;
    }

    /**
     * {@code dashboardPage()} returns the template instance produced by the
     * dashboard template.
     */
    @Test
    void dashboardPageRendersDashboard() {
        DashboardResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.dashboard.instance()).thenReturn(view);
        assertSame(view, resource.dashboardPage());
    }

    /**
     * {@code dashboardData()} returns the service aggregations with an empty
     * calls list when no supervisor call is pending (loop not entered).
     */
    @Test
    void dashboardDataWithNoPendingCalls() {
        DashboardResource resource = newResource();
        Map<String, Object> data = new HashMap<>();
        data.put("total", 42);
        when(resource.dashboardService.buildData()).thenReturn(data);
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of());
        Map<String, Object> result = resource.dashboardData();
        assertEquals(42, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) result.get("calls");
        assertTrue(calls.isEmpty());
    }

    /**
     * {@code dashboardData()} appends one map entry per pending call, copying
     * every field, when the registry returns calls (loop entered).
     */
    @Test
    void dashboardDataWithPendingCalls() {
        DashboardResource resource = newResource();
        Map<String, Object> data = new HashMap<>();
        when(resource.dashboardService.buildData()).thenReturn(data);
        SupervisorCallRegistry.Call call = new SupervisorCallRegistry.Call(7L, "CAISSE1", "Alice", "no-change");
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of(call));
        Map<String, Object> result = resource.dashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) result.get("calls");
        assertEquals(1, calls.size());
        Map<String, Object> entry = calls.get(0);
        assertEquals(7L, entry.get("id"));
        assertEquals("CAISSE1", entry.get("terminal"));
        assertEquals("Alice", entry.get("operator"));
        assertEquals("no-change", entry.get("reason"));
        assertEquals(call.time, entry.get("time"));
    }

    /**
     * BO-10-08-01: with the dashboard alerts DISABLED, the poll carries an
     * empty calls list and the registry is never consulted (the
     * {@code dashboardAlertsEnabled()} false arm).
     */
    @Test
    void dashboardDataAlertsDisabledYieldsEmptyCalls() {
        DashboardResource resource = newResource();
        when(resource.posSettingsService.dashboardAlertsEnabled()).thenReturn(false);
        Map<String, Object> data = new HashMap<>();
        data.put("total", 42);
        when(resource.dashboardService.buildData()).thenReturn(data);
        Map<String, Object> result = resource.dashboardData();
        assertEquals(42, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) result.get("calls");
        assertTrue(calls.isEmpty());
        verify(resource.supervisorCallRegistry, never()).getPending();
    }

    /**
     * {@code acknowledge()} delegates to the registry and returns 204.
     */
    @Test
    void acknowledgeDelegatesAndReturnsNoContent() {
        DashboardResource resource = newResource();
        Response response = resource.acknowledge(9L);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        verify(resource.supervisorCallRegistry).acknowledge(9L);
    }

    /**
     * {@code receiveCall()} returns 403 without registering when this node is not
     * the store node (off-role guard true).
     */
    @Test
    void receiveCallForbiddenWhenNotStoreRole() {
        DashboardResource resource = newResource();
        resource.role = "register";
        Response response = resource.receiveCall("any", dto("CAISSE1", "Alice", "no-change"));
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        assertEquals("Ce nœud n'a pas le rôle store", response.getEntity());
        verify(resource.supervisorCallRegistry, never()).add(any(), any(), any());
    }

    /**
     * {@code receiveCall()} registers the call and returns 200 when the store
     * node has no configured token (off-role guard false, blank half true so the
     * mismatch half is short-circuited).
     */
    @Test
    void receiveCallRegistersWhenTokenAbsent() {
        DashboardResource resource = newResource();
        resource.token = Optional.empty();
        Response response = resource.receiveCall("ignored", dto("CAISSE1", "Alice", "no-change"));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(resource.supervisorCallRegistry).add("CAISSE1", "Alice", "no-change");
    }

    /**
     * {@code receiveCall()} registers the call and returns 200 when the presented
     * token matches the configured one (blank half false, mismatch half false).
     */
    @Test
    void receiveCallRegistersWhenTokenMatches() {
        DashboardResource resource = newResource();
        resource.token = Optional.of("secret");
        Response response = resource.receiveCall("secret", dto("CAISSE2", "Bob", "price"));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(resource.supervisorCallRegistry).add("CAISSE2", "Bob", "price");
    }

    /**
     * {@code receiveCall()} returns 401 without registering when the presented
     * token does not match the configured one (blank half false, mismatch half
     * true).
     */
    @Test
    void receiveCallUnauthorizedWhenTokenMismatch() {
        DashboardResource resource = newResource();
        resource.token = Optional.of("secret");
        Response response = resource.receiveCall("wrong", dto("CAISSE3", "Carol", "help"));
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        assertEquals("Jeton invalide", response.getEntity());
        verify(resource.supervisorCallRegistry, never()).add(any(), any(), any());
    }
}
