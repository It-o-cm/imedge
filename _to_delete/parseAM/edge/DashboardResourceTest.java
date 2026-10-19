package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.sync.RegisterCommandService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        resource.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        when(resource.posSettingsService.dashboardAlertsEnabled()).thenReturn(true);
        // BO-04-02-03/-04/-05: a settled demand stays displayed, the catalogue
        // default — which is also the only setting under which the board can
        // be asserted on after a settlement.
        when(resource.posSettingsService.settledCallsKept()).thenReturn(true);
        resource.registerCommandService = mock(RegisterCommandService.class);
        resource.currentUser = mock(CurrentUser.class);
        when(resource.currentUser.getFullName()).thenReturn("Marie Curie");
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
        SupervisorCallRegistry.Call call = new SupervisorCallRegistry.Call(7L, "CAISSE1", "Alice", "no-change", null);
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
     * {@code forceClose()} LEAVES an order for the register — signed by the
     * back-office user who asked for it — and answers 204: the close itself
     * happens on the register, on its next collection.
     */
    @Test
    void forceCloseLeavesASignedOrderAndReturnsNoContent() {
        DashboardResource resource = newResource();
        Response response = resource.forceClose("CAISSE1");
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        verify(resource.registerCommandService).issue("CAISSE1",
                RegisterCommand.CommandType.CLOSE_SESSION, "Marie Curie",
                "Fermeture demandée depuis le back-office");
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
        verify(resource.supervisorCallRegistry, never())
                .add(any(), any(), any(), any(), anyInt());
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
        Response response;
        // BO-04-02-07: the reason is resolved against the administered
        // service-request types, and no list is distributed here — so the call
        // is filed with no priority, the behaviour of every lane that still
        // reads the semicolon setting.
        try (org.mockito.MockedStatic<com.intermarche.edge.domain.setting.PosList> lists =
                     mockStatic(com.intermarche.edge.domain.setting.PosList.class)) {
            lists.when(() -> com.intermarche.edge.domain.setting.PosList.findActiveByKind(
                    com.intermarche.edge.domain.setting.PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(List.of());
            response = resource.receiveCall("ignored", dto("CAISSE1", "Alice", "no-change"));
        }
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(resource.supervisorCallRegistry).add("CAISSE1", "Alice", "no-change", null, 0);
    }

    /**
     * {@code receiveCall()} registers the call and returns 200 when the presented
     * token matches the configured one (blank half false, mismatch half false).
     */
    @Test
    void receiveCallRegistersWhenTokenMatches() {
        DashboardResource resource = newResource();
        resource.token = Optional.of("secret");
        Response response;
        // BO-04-02-07: one administered type carries the reason, so the call
        // is filed with the priority that type names.
        com.intermarche.edge.domain.setting.PosListItem item =
                new com.intermarche.edge.domain.setting.PosListItem();
        item.code = "PRIX";
        item.label = "price";
        item.severity = 3;
        item.active = true;
        com.intermarche.edge.domain.setting.PosList administered =
                mock(com.intermarche.edge.domain.setting.PosList.class);
        administered.items = List.of(item);
        try (org.mockito.MockedStatic<com.intermarche.edge.domain.setting.PosList> lists =
                     mockStatic(com.intermarche.edge.domain.setting.PosList.class)) {
            lists.when(() -> com.intermarche.edge.domain.setting.PosList.findActiveByKind(
                    com.intermarche.edge.domain.setting.PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(List.of(administered));
            response = resource.receiveCall("secret", dto("CAISSE2", "Bob", "price"));
        }
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("OK", response.getEntity());
        verify(resource.supervisorCallRegistry).add("CAISSE2", "Bob", "price", null, 3);
    }

    /**
     * BO-04-02-07: {@code severityOf} answers the priority of the
     * administered type the reason names — by label or by code — and zero for
     * a null reason, a blank one, an inactive entry and one nothing matches.
     */
    @Test
    void severityOfReadsTheAdministeredTypes() {
        DashboardResource resource = newResource();
        com.intermarche.edge.domain.setting.PosListItem active =
                new com.intermarche.edge.domain.setting.PosListItem();
        active.code = "ANNUL";
        active.label = "Annulation";
        active.severity = 2;
        active.active = true;
        com.intermarche.edge.domain.setting.PosListItem retired =
                new com.intermarche.edge.domain.setting.PosListItem();
        retired.code = "VIEUX";
        retired.label = "Motif retiré";
        retired.severity = 5;
        retired.active = false;
        com.intermarche.edge.domain.setting.PosList administered =
                mock(com.intermarche.edge.domain.setting.PosList.class);
        administered.items = List.of(active, retired);
        try (org.mockito.MockedStatic<com.intermarche.edge.domain.setting.PosList> lists =
                     mockStatic(com.intermarche.edge.domain.setting.PosList.class)) {
            lists.when(() -> com.intermarche.edge.domain.setting.PosList.findActiveByKind(
                    com.intermarche.edge.domain.setting.PosList.Kind.SERVICE_REQUEST))
                    .thenReturn(List.of(administered));
            assertEquals(2, resource.severityOf(" annulation "));
            assertEquals(2, resource.severityOf("ANNUL"));
            assertEquals(0, resource.severityOf("Motif retiré"));
            assertEquals(0, resource.severityOf("SONDAGE"));
        }
        assertEquals(0, resource.severityOf(null));
        assertEquals(0, resource.severityOf("   "));
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
        verify(resource.supervisorCallRegistry, never())
                .add(any(), any(), any(), any(), anyInt());
    }

    /**
     * BO-04-02-04: sending a demand back to the till marks it and sends
     * NOTHING down — the manager tells the lane, the board records it.
     */
    @Test
    void sendingBackToTheTillMarksAndSendsNothing() {
        DashboardResource resource = newResource();
        Response response = resource.sendBackToTill(7L);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        verify(resource.supervisorCallRegistry).settle(7L,
                SupervisorCallRegistry.CallStatus.TO_HANDLE_AT_TILL, null, true);
        verify(resource.registerCommandService, never()).issue(any(), any(), any(), any());
    }

    /**
     * BO-04-02-05: a refusal marks the demand AND leaves the register the
     * order carrying the wording the cashier will read.
     */
    @Test
    void aRefusalMarksTheDemandAndTellsTheRegister() {
        DashboardResource resource = newResource();
        SupervisorCallRegistry.Call call =
                new SupervisorCallRegistry.Call(7L, "CAISSE1", "Alice", "Remise", null);
        when(resource.supervisorCallRegistry.settle(7L,
                SupervisorCallRegistry.CallStatus.REFUSED, "Geste non autorise", true))
                .thenReturn(call);
        Response response = resource.refuse(7L, "  Geste non autorise  ");
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        verify(resource.registerCommandService).issue("CAISSE1",
                RegisterCommand.CommandType.SUPERVISOR_REFUSED, "Marie Curie",
                "Geste non autorise");
    }

    /**
     * BO-04-02-05: a refusal with no wording carries the default one, and a
     * demand nothing answers is a 404 that leaves no order behind.
     */
    @Test
    void aRefusalWithoutWordingAndOnAnUnknownDemand() {
        DashboardResource resource = newResource();
        SupervisorCallRegistry.Call call =
                new SupervisorCallRegistry.Call(7L, "CAISSE1", "Alice", "Remise", null);
        when(resource.supervisorCallRegistry.settle(7L,
                SupervisorCallRegistry.CallStatus.REFUSED,
                "Demande refusée par le back-office", true)).thenReturn(call);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(),
                resource.refuse(7L, "   ").getStatus());
        verify(resource.registerCommandService).issue("CAISSE1",
                RegisterCommand.CommandType.SUPERVISOR_REFUSED, "Marie Curie",
                "Demande refusée par le back-office");
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(),
                resource.refuse(8L, null).getStatus());
        verify(resource.registerCommandService, never()).issue(eq("CAISSE2"), any(), any(), any());
    }

    /**
     * BO-04-02-03: the register's notice marks that lane's open demands as
     * settled at the till; the two gates refuse an off-role node and a bad
     * token without touching the board.
     */
    @Test
    void theRegistersNoticeSettlesItsOpenDemands() {
        DashboardResource resource = newResource();
        assertEquals(Response.Status.OK.getStatusCode(),
                resource.receiveSettled("ignored", dto("CAISSE1", null, null)).getStatus());
        verify(resource.supervisorCallRegistry).settleAtTill("CAISSE1", true);
        resource.role = "register";
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(),
                resource.receiveSettled("ignored", dto("CAISSE1", null, null)).getStatus());
        resource.role = "store";
        resource.token = Optional.of("secret");
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(),
                resource.receiveSettled("wrong", dto("CAISSE1", null, null)).getStatus());
        verify(resource.supervisorCallRegistry, times(1)).settleAtTill(any(), anyBoolean());
    }

    /**
     * BO-04-02-03/-04/-05: a call carries its outcome to the screen — its
     * status, the wording of that status and the refusal reason.
     */
    @Test
    void theBoardCarriesTheOutcomeOfEachCall() {
        DashboardResource resource = newResource();
        when(resource.dashboardService.buildData()).thenReturn(new HashMap<>());
        SupervisorCallRegistry.Call call =
                new SupervisorCallRegistry.Call(7L, "CAISSE1", "Alice", "Remise", null, 2);
        call.status = SupervisorCallRegistry.CallStatus.REFUSED;
        call.refusalReason = "Geste non autorise";
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of(call));
        Map<String, Object> data = resource.dashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) data.get("calls");
        assertEquals(1, calls.size());
        assertEquals("REFUSED", calls.get(0).get("status"));
        assertEquals("refusée sur le BackOffice", calls.get(0).get("statusLabel"));
        assertEquals("Geste non autorise", calls.get(0).get("refusalReason"));
        assertEquals(2, calls.get(0).get("severity"));
    }
}
