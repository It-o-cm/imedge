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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // LC-01-08-05: the message acknowledgement consumes the serving memory
        // so the notice is never served to the acknowledging lane again.
        resource.storeMessageServing = mock(com.intermarche.edge.imports.StoreMessageServing.class);
        // BO-04-03-05: every lane is speaking unless a test silences one.
        resource.registerPresenceService =
                mock(com.intermarche.edge.service.sync.RegisterPresenceService.class);
        when(resource.registerPresenceService.offline()).thenReturn(List.of());
        // BO-04-03-01/-03/-15: no administrable tile unless a test asks for one.
        resource.dashboardIndicatorService = mock(DashboardIndicatorService.class);
        when(resource.dashboardIndicatorService.indicators()).thenReturn(List.of());
        resource.i18n = mock(com.intermarche.edge.ui.admin.BackOfficeMessages.class);
        // A code without a label reads as itself, as the bundles answer it.
        when(resource.i18n.code(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> String.valueOf((Object) invocation.getArgument(1)));
        // BO-04-03-01/07/14: the lot 21 collaborators.
        resource.supervisionJournal = mock(SupervisionJournal.class);
        resource.controlIndicatorService = mock(ControlIndicatorService.class);
        resource.liveActivityService = mock(LiveActivityService.class);
        resource.dashboardControl = mock(Template.class);
        resource.dashboardLive = mock(Template.class);
        return resource;
    }

    /**
     * Builds one offline-register presence.
     *
     * @param terminalId the silent register
     * @param silentMinutes how long it has been silent
     * @return the presence record
     */
    private com.intermarche.edge.service.sync.RegisterPresenceService.Presence absent(
            String terminalId, long silentMinutes) {
        return new com.intermarche.edge.service.sync.RegisterPresenceService.Presence(
                terminalId, java.time.LocalDateTime.now(), silentMinutes, false);
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
     * BO-04-03-05: a lane that has stopped speaking is carried in its own
     * list, named and timed (the offline loop entered).
     */
    @Test
    void dashboardDataCarriesTheSilentRegisters() {
        DashboardResource resource = newResource();
        when(resource.dashboardService.buildData()).thenReturn(new HashMap<>());
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of());
        when(resource.registerPresenceService.offline())
                .thenReturn(List.of(absent("CAISSE-01", 9), absent("CAISSE-04", 31)));
        Map<String, Object> result = resource.dashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> offline = (List<Map<String, Object>>) result.get("offlineRegisters");
        assertEquals(2, offline.size());
        assertEquals("CAISSE-01", offline.get(0).get("terminal"));
        assertEquals(9L, offline.get(0).get("silent"));
        assertEquals("CAISSE-04", offline.get(1).get("terminal"));
        assertEquals(31L, offline.get(1).get("silent"));
    }

    /**
     * BO-04-03-05: every lane speaking carries an empty list rather than no
     * key at all (the offline loop not entered).
     */
    @Test
    void dashboardDataCarriesNoSilentRegisterWhenEveryLaneSpeaks() {
        DashboardResource resource = newResource();
        when(resource.dashboardService.buildData()).thenReturn(new HashMap<>());
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of());
        Map<String, Object> result = resource.dashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> offline = (List<Map<String, Object>>) result.get("offlineRegisters");
        assertTrue(offline.isEmpty());
    }

    /**
     * BO-04-03-01/-03/-15: the administrable tiles are handed through as the
     * indicator service computed them, their label read in the page's
     * language — the resource formats nothing else.
     */
    @Test
    void dashboardDataCarriesTheAdministeredIndicators() {
        DashboardResource resource = newResource();
        when(resource.dashboardService.buildData()).thenReturn(new HashMap<>());
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of());
        Map<String, Object> tile = new HashMap<>();
        tile.put("code", "endorsements");
        tile.put("label", "Avals manager");
        tile.put("value", "4");
        tile.put("unit", "");
        when(resource.dashboardIndicatorService.indicators()).thenReturn(List.of(tile));
        when(resource.i18n.text("Avals manager")).thenReturn("Goedkeuringen manager");
        Map<String, Object> result = resource.dashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indicators = (List<Map<String, Object>>) result.get("indicators");
        assertEquals(1, indicators.size());
        assertSame(tile, indicators.get(0));
        assertEquals("Goedkeuringen manager", tile.get("label"));
        assertEquals("4", tile.get("value"));
    }

    /**
     * BO-04-03-03: the code a tile's label carries is shown by its own label
     * in the page's language; a code named without its labels is kept.
     */
    @Test
    void dashboardDataNamesTheCodesOfTheTiles() {
        DashboardResource resource = newResource();
        when(resource.dashboardService.buildData()).thenReturn(new HashMap<>());
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of());
        Map<String, Object> event = new HashMap<>();
        event.put("label", "Événements CONTROL_ALERT");
        event.put("subjectPrefix", "t.s21-ev-");
        event.put("subject", "CONTROL_ALERT");
        Map<String, Object> orphan = new HashMap<>();
        orphan.put("label", "Mouvements WITHDRAWAL");
        orphan.put("subject", "WITHDRAWAL");
        when(resource.dashboardIndicatorService.indicators()).thenReturn(List.of(event, orphan));
        when(resource.i18n.text("Événements CONTROL_ALERT")).thenReturn("Gebeurtenissen CONTROL_ALERT");
        when(resource.i18n.text("Mouvements WITHDRAWAL")).thenReturn("Bewegingen WITHDRAWAL");
        when(resource.i18n.code("t.s21-ev-", "CONTROL_ALERT")).thenReturn("Kassawaarschuwing");
        resource.dashboardData();
        assertEquals("Gebeurtenissen Kassawaarschuwing", event.get("label"));
        assertFalse(event.containsKey("subject"));
        assertFalse(event.containsKey("subjectPrefix"));
        assertEquals("Bewegingen WITHDRAWAL", orphan.get("label"));
    }

    /**
     * BO-04-03-01/-03/-15: a blank parameter carries an empty list rather
     * than no key at all, so the page hides its card instead of failing.
     */
    @Test
    void dashboardDataCarriesNoIndicatorWhenNoneIsAdministered() {
        DashboardResource resource = newResource();
        when(resource.dashboardService.buildData()).thenReturn(new HashMap<>());
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of());
        Map<String, Object> result = resource.dashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indicators = (List<Map<String, Object>>) result.get("indicators");
        assertTrue(indicators.isEmpty());
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
     * LC-01-07-04: acknowledging a demand TELLS THE CALLING LANE — the order
     * travels down the register-command channel so the register switches its
     * own indicator to the distinct acknowledged one, and the cashier closes
     * the cycle by validating the handling (LC-01-07-06).
     */
    @Test
    void acknowledgeTellsTheCallingLane() {
        DashboardResource resource = newResource();
        SupervisorCallRegistry.Call call =
                new SupervisorCallRegistry.Call(9L, "CAISSE1", "Alice", "S\u00e9curit\u00e9", null);
        when(resource.supervisorCallRegistry.find(9L)).thenReturn(call);
        Response response = resource.acknowledge(9L);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        verify(resource.supervisorCallRegistry).acknowledge(9L);
        verify(resource.registerCommandService).issue("CAISSE1",
                RegisterCommand.CommandType.SUPERVISOR_ACK, "Marie Curie",
                "acquitt\u00e9e par Marie Curie");
    }

    /**
     * LC-01-07-04: an acknowledgement of an id the board does not know —
     * somebody else removed it first — issues nothing: there is no lane to
     * tell.
     */
    @Test
    void acknowledgeOfAnUnknownDemandIssuesNothing() {
        DashboardResource resource = newResource();
        when(resource.supervisorCallRegistry.find(9L)).thenReturn(null);
        resource.acknowledge(9L);
        verify(resource.supervisorCallRegistry).acknowledge(9L);
        verify(resource.registerCommandService, never()).issue(any(), any(), any(), any());
    }

    /**
     * LC-01-07-05: the register's cancellation withdraws its PENDING demands
     * from the board; the two gates refuse an off-role node and a bad token
     * without touching it.
     */
    @Test
    void theRegisterCancelsItsPendingDemands() {
        DashboardResource resource = newResource();
        assertEquals(Response.Status.OK.getStatusCode(),
                resource.receiveCancel("ignored", dto("CAISSE1", null, null)).getStatus());
        verify(resource.supervisorCallRegistry).cancelPendingFor("CAISSE1");
        resource.token = Optional.of("secret");
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(),
                resource.receiveCancel("wrong", dto("CAISSE1", null, null)).getStatus());
        DashboardResource offRole = newResource();
        offRole.role = "register";
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(),
                offRole.receiveCancel("ignored", dto("CAISSE1", null, null)).getStatus());
        verify(resource.supervisorCallRegistry, org.mockito.Mockito.times(1))
                .cancelPendingFor(any());
    }

    /**
     * LC-01-08-05: a cashier's acknowledgement files WHO read the notice and
     * consumes the message for that lane; the two gates refuse an off-role
     * node and a bad token.
     */
    @Test
    void theCashiersAcknowledgementFilesAndConsumes() {
        DashboardResource resource = newResource();
        com.intermarche.edge.domain.setting.StoreMessage message =
                new com.intermarche.edge.domain.setting.StoreMessage();
        message.id = 42L;
        message.text = "Consigne du responsable";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class);
                org.mockito.MockedConstruction<com.intermarche.edge.domain.setting.StoreMessageAck> acks =
                     org.mockito.Mockito.mockConstruction(
                             com.intermarche.edge.domain.setting.StoreMessageAck.class)) {
            panache.when(() -> com.intermarche.edge.domain.setting.StoreMessage.listServable())
                    .thenReturn(java.util.List.of(message));
            DashboardResource.AckMessageDto dto = new DashboardResource.AckMessageDto();
            dto.terminalId = "CAISSE1";
            dto.operatorCode = "12341234";
            dto.operatorName = "Alice";
            dto.text = " Consigne du responsable ";
            assertEquals(Response.Status.OK.getStatusCode(),
                    resource.receiveMessageAck("ignored", dto).getStatus());
            com.intermarche.edge.domain.setting.StoreMessageAck filed = acks.constructed().get(0);
            assertEquals(42L, filed.messageId);
            assertEquals("CAISSE1", filed.terminalId);
            assertEquals("12341234", filed.operatorCode);
            assertEquals("Alice", filed.operatorName);
            verify(resource.storeMessageServing).consume("CAISSE1");
        }
    }

    /**
     * LC-01-08-05: an acknowledgement naming a notice the store is no longer
     * serving — reworded or taken down while the cashier was reading it —
     * files nothing rather than filing against the wrong message; a null body
     * is refused by the gates.
     */
    @Test
    void anAcknowledgementOfAnUnknownNoticeFilesNothing() {
        DashboardResource resource = newResource();
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class);
                org.mockito.MockedConstruction<com.intermarche.edge.domain.setting.StoreMessageAck> acks =
                     org.mockito.Mockito.mockConstruction(
                             com.intermarche.edge.domain.setting.StoreMessageAck.class)) {
            panache.when(() -> com.intermarche.edge.domain.setting.StoreMessage.listServable())
                    .thenReturn(java.util.List.of());
            DashboardResource.AckMessageDto dto = new DashboardResource.AckMessageDto();
            dto.terminalId = "CAISSE1";
            dto.text = "Ancienne consigne";
            assertEquals(Response.Status.OK.getStatusCode(),
                    resource.receiveMessageAck("ignored", dto).getStatus());
            assertTrue(acks.constructed().isEmpty());
            verify(resource.storeMessageServing, never()).consume(any());
        }
        assertEquals(Response.Status.OK.getStatusCode(),
                resource.receiveMessageAck("ignored", null).getStatus());
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
     * BO-06-03-02: a survey answer coming up the call road is filed as the
     * customer's answer and never stands on the line board as a call.
     */
    @Test
    void receiveCallFilesASurveyAnswerWithoutCalling() {
        DashboardResource resource = newResource();
        resource.token = Optional.empty();
        DashboardResource.CallDto call = dto("CAISSE1", "Alice", "SONDAGE");
        Response response = resource.receiveCall("ignored", call);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(resource.supervisorCallRegistry, never()).add(any(), any(), any(), any(), anyInt());
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
        when(resource.i18n.text("refusée sur le BackOffice")).thenReturn("geweigerd in de back-office");
        Map<String, Object> data = resource.dashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) data.get("calls");
        assertEquals(1, calls.size());
        assertEquals("REFUSED", calls.get(0).get("status"));
        assertEquals("geweigerd in de back-office", calls.get(0).get("statusLabel"));
        assertEquals("Geste non autorise", calls.get(0).get("refusalReason"));
        assertEquals(2, calls.get(0).get("severity"));
    }

    /**
     * BO-04-02-02: an authorisation marks the demand AND leaves the register
     * the order that lets the held gesture through.
     */
    @Test
    void anAuthorisationMarksTheDemandAndTellsTheRegister() {
        DashboardResource resource = newResource();
        SupervisorCallRegistry.Call call =
                new SupervisorCallRegistry.Call(7L, "CAISSE1", "Alice", "Remise", null);
        when(resource.supervisorCallRegistry.settle(7L,
                SupervisorCallRegistry.CallStatus.AUTHORISED, null, true)).thenReturn(call);
        Response response = resource.authorise(7L);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        verify(resource.registerCommandService).issue("CAISSE1",
                RegisterCommand.CommandType.SUPERVISOR_GRANTED, "Marie Curie",
                "Marie Curie");
    }

    /**
     * BO-04-02-02: an authorisation on a demand nothing answers is a 404
     * that leaves no order behind.
     */
    @Test
    void anAuthorisationOnAnUnknownDemandLeavesNoOrder() {
        DashboardResource resource = newResource();
        Response response = resource.authorise(8L);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(resource.registerCommandService, never())
                .issue(any(), eq(RegisterCommand.CommandType.SUPERVISOR_GRANTED), any(), any());
    }
    /** BO-04: settled/cancel/ack endpoints independently enforce role, optional token and null DTO guards. */
    @Test
    void coverageSupervisorEndpointGuards() {
        var resource = newResource();
        resource.role = "register";
        assertEquals(403, resource.receiveSettled(null, null).getStatus());
        assertEquals(403, resource.receiveCancel(null, null).getStatus());
        assertEquals(403, resource.receiveMessageAck(null, null).getStatus());
        resource.role = "store";
        resource.token = java.util.Optional.of("TOKEN");
        assertEquals(401, resource.receiveSettled("wrong", null).getStatus());
        assertEquals(401, resource.receiveCancel("wrong", null).getStatus());
        assertEquals(401, resource.receiveMessageAck("wrong", null).getStatus());
        assertEquals(200, resource.receiveSettled("TOKEN", null).getStatus());
        assertEquals(200, resource.receiveCancel("TOKEN", null).getStatus());
        assertEquals(200, resource.receiveMessageAck("TOKEN", null).getStatus());
        resource.token = java.util.Optional.empty();
        assertEquals(200, resource.receiveSettled(null, null).getStatus());
        assertEquals(200, resource.receiveCancel(null, null).getStatus());
        assertEquals(200, resource.receiveMessageAck(null, new DashboardResource.AckMessageDto()).getStatus());
    }

    /** BO-03-08: absent and blank acknowledgement text never scans the stored message table. */
    @Test
    void coverageMessageAcknowledgementTextGuards() {
        var resource = newResource();
        var dto = new DashboardResource.AckMessageDto();
        try (var messages = org.mockito.Mockito.mockStatic(com.intermarche.edge.domain.setting.StoreMessage.class)) {
            resource.recordMessageAck(dto);
            dto.text = " ";
            resource.recordMessageAck(dto);
            messages.verifyNoInteractions();
        }
    }

    /** LC-03-07: non-surveys, incomplete answers and disappeared questions never fabricate acknowledgement rows. */
    @Test
    void coverageSurveyConfirmationGuardsAndPersistence() {
        var resource = newResource();
        var dto = new DashboardResource.CallDto();
        resource.recordMessageConfirmation(null);
        dto.reason = "OTHER";
        resource.recordMessageConfirmation(dto);
        dto.reason = "SONDAGE";
        resource.recordMessageConfirmation(dto);
        dto.message = "no separator";
        resource.recordMessageConfirmation(dto);
        dto.message = "QUESTION : ANSWER";
        var query = org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        try (var base = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class);
             var created = org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.setting.StoreMessageAck.class)) {
            base.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find("question", "QUESTION")).thenReturn(query);
            resource.recordMessageConfirmation(dto);
            assertTrue(created.constructed().isEmpty());
            var message = new com.intermarche.edge.domain.setting.StoreMessage();
            message.id = 8L;
            org.mockito.Mockito.when(query.firstResult()).thenReturn(message);
            resource.recordMessageConfirmation(dto);
            var ack = created.constructed().getFirst();
            assertEquals(8L, ack.messageId);
            assertEquals("ANSWER", ack.answer);
            org.mockito.Mockito.verify(ack).persist();
        }
    }


    /** LC-01-08-05: an acknowledgement for another notice does not consume or file the still-served message. */
    @Test
    void unmatchedAcknowledgementDoesNotConsumeAnotherNotice() {
        var resource = newResource();
        var message = new com.intermarche.edge.domain.setting.StoreMessage();
        message.id = 42L;
        message.text = "CURRENT";
        var dto = new DashboardResource.AckMessageDto();
        dto.terminalId = "C04";
        dto.text = "OTHER";
        try (var messages = mockStatic(com.intermarche.edge.domain.setting.StoreMessage.class);
             var created = org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.setting.StoreMessageAck.class)) {
            messages.when(com.intermarche.edge.domain.setting.StoreMessage::listServable).thenReturn(java.util.List.of(message));
            assertEquals(200, resource.receiveMessageAck("ignored", dto).getStatus());
            assertTrue(created.constructed().isEmpty());
        }
    }


    /**
     * Every decision of the back office is journalled under the register it
     * is about (BO-04-03-14); an unknown demand journals nothing.
     */
    @Test
    void decisionsAreJournalled() {
        DashboardResource resource = newResource();
        SupervisorCallRegistry.Call call = new SupervisorCallRegistry.Call(7L, "C04", "Jean", "Monnaie", "vite");
        SupervisorCallRegistry.Call bare = new SupervisorCallRegistry.Call(8L, "C05", "Jean", "Monnaie", null);
        when(resource.supervisorCallRegistry.settle(eq(7L), any(), any(), anyBoolean())).thenReturn(call);
        when(resource.supervisorCallRegistry.settle(eq(8L), any(), any(), anyBoolean())).thenReturn(bare);
        resource.sendBackToTill(7L);
        resource.sendBackToTill(9L);
        resource.authorise(7L);
        resource.authorise(8L);
        resource.refuse(7L, "Non");
        org.mockito.Mockito.verify(resource.supervisionJournal).handled("C04", "A_TRAITER_EN_CAISSE", "Marie Curie", "Monnaie");
        org.mockito.Mockito.verify(resource.supervisionJournal).handled("C04", "AUTORISATION", "Marie Curie", "Monnaie vite");
        org.mockito.Mockito.verify(resource.supervisionJournal).handled("C05", "AUTORISATION", "Marie Curie", "Monnaie");
        org.mockito.Mockito.verify(resource.supervisionJournal).handled("C04", "REFUS", "Marie Curie", "Monnaie : Non");
        when(resource.supervisorCallRegistry.find(7L)).thenReturn(call);
        resource.acknowledge(7L);
        org.mockito.Mockito.verify(resource.supervisionJournal).handled("C04", "ACQUITTEMENT", "Marie Curie", "Monnaie");
        resource.forceClose("C06");
        org.mockito.Mockito.verify(resource.supervisionJournal).handled("C06", "FERMETURE_FORCEE", "Marie Curie", null);
    }

    /**
     * The alert every screen shows carries the PENDING demands only, and
     * nothing when either switch is off (BO-04-02-12).
     */
    @Test
    void alertsCarryThePendingDemands() {
        DashboardResource resource = newResource();
        SupervisorCallRegistry.Call pending = new SupervisorCallRegistry.Call(1L, "C04", "Jean", "Monnaie", null);
        SupervisorCallRegistry.Call settled = new SupervisorCallRegistry.Call(2L, "C05", "Jean", "Pause", "m");
        settled.status = SupervisorCallRegistry.CallStatus.REFUSED;
        SupervisorCallRegistry.Call messaged = new SupervisorCallRegistry.Call(3L, "C06", "Jean", "Aval", "REMISE 2");
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of(pending, settled, messaged));
        when(resource.posSettingsService.globalAlertsEnabled()).thenReturn(true);
        Map<String, Object> data = resource.alerts();
        assertEquals(true, data.get("enabled"));
        List<?> calls = (List<?>) data.get("calls");
        assertEquals(2, calls.size());
        assertEquals("", ((Map<?, ?>) calls.get(0)).get("message"));
        assertEquals("REMISE 2", ((Map<?, ?>) calls.get(1)).get("message"));
        assertEquals("REMISE 2", ((Map<?, ?>) calls.get(1)).get("gesture"));
        when(resource.i18n.code("t.s21-act-", "REMISE")).thenReturn("Korting in bedrag");
        when(resource.i18n.text("Aval")).thenReturn("Goedkeuring");
        Map<?, ?> worded = (Map<?, ?>) ((List<?>) resource.alerts().get("calls")).get(1);
        assertEquals("Korting in bedrag 2", worded.get("gesture"));
        assertEquals("Goedkeuring", worded.get("reasonLabel"));
        when(resource.posSettingsService.dashboardAlertsEnabled()).thenReturn(false);
        assertEquals(false, resource.alerts().get("enabled"));
        assertTrue(((List<?>) resource.alerts().get("calls")).isEmpty());
        when(resource.posSettingsService.globalAlertsEnabled()).thenReturn(false);
        assertEquals(false, resource.alerts().get("enabled"));
    }

    /**
     * The control indicators page and data; the detail labels are shown in
     * the page's language (BO-04-03-01).
     */
    @Test
    void controlIndicatorsPageAndData() {
        DashboardResource resource = newResource();
        Map<String, Object> data = new java.util.HashMap<>();
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("label", "EPICERIE");
        data.put("detail", List.of(row));
        when(resource.controlIndicatorService.data()).thenReturn(data);
        io.quarkus.qute.TemplateInstance instance = mock(io.quarkus.qute.TemplateInstance.class);
        when(resource.dashboardControl.data("control", data)).thenReturn(instance);
        assertEquals(instance, resource.controlPage());
        when(resource.i18n.text("EPICERIE")).thenReturn("KRUIDENIERS");
        assertEquals("KRUIDENIERS", ((Map<?, ?>) ((List<?>) resource.controlData().get("detail")).get(0)).get("label"));
    }

    /**
     * The real-time watch page and its operations, labelled (BO-04-03-07).
     */
    @Test
    void liveWatchPageAndData() {
        DashboardResource resource = newResource();
        io.quarkus.qute.TemplateInstance instance = mock(io.quarkus.qute.TemplateInstance.class);
        when(resource.dashboardLive.instance()).thenReturn(instance);
        assertEquals(instance, resource.livePage());
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("type", "TICKET_CLOSED");
        row.put("detail", "Ouverture du tiroir");
        row.put("terminal", "C04");
        Map<String, Object> signIn = new java.util.HashMap<>();
        signIn.put("type", "BACK_OFFICE_SIGNED_IN");
        signIn.put("detail", "connexion admin");
        signIn.put("terminal", "POS01");
        when(resource.liveActivityService.after(4L, "C04")).thenReturn(List.of(row, signIn));
        when(resource.i18n.code(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> String.valueOf((Object) call.getArgument(1)));
        when(resource.i18n.code("t.s21-ev-", "TICKET_CLOSED")).thenReturn("Vente");
        when(resource.i18n.text("Ouverture du tiroir")).thenReturn("Lade geopend");
        when(resource.i18n.get("t.rs21-back-office")).thenReturn("Back-office");
        when(resource.i18n.get("t.rs21-connexion")).thenReturn("Aanmelding");
        List<Map<String, Object>> rows = resource.liveData(4L, "C04");
        Map<String, Object> shown = rows.get(0);
        assertEquals("Vente", shown.get("label"));
        assertEquals("Lade geopend", shown.get("detail"));
        assertEquals("C04", shown.get("terminal"));
        assertEquals("Back-office", rows.get(1).get("terminal"));
        assertEquals("Aanmelding admin", rows.get(1).get("detail"));
    }
}
