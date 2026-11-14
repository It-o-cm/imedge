package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link MobileAlertResource}. */
class MobileAlertResourceTest {

    /**
     * Builds the resource over mocked collaborators.
     *
     * @param mobile whether the mobile page is activated
     * @param alerts whether the dashboard alerts are activated
     * @return the resource
     */
    private MobileAlertResource resource(boolean mobile, boolean alerts) {
        MobileAlertResource resource = new MobileAlertResource();
        resource.mobilePage = mock(Template.class);
        resource.supervisorCallRegistry = mock(SupervisorCallRegistry.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.i18n = mock(com.intermarche.edge.ui.admin.BackOfficeMessages.class);
        when(resource.i18n.text("Prix")).thenReturn("Prijs");
        when(resource.i18n.text("en attente")).thenReturn("wachtend");
        when(resource.i18n.text("refusée sur le BackOffice")).thenReturn("geweigerd");
        when(resource.posSettingsService.mobileAlertsEnabled()).thenReturn(mobile);
        when(resource.posSettingsService.dashboardAlertsEnabled()).thenReturn(alerts);
        return resource;
    }

    /** BO-10-08-02: the page knows whether the mobile alerts are activated. */
    @Test
    void thePageCarriesTheActivation() {
        MobileAlertResource resource = resource(true, true);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.mobilePage.data("enabled", true)).thenReturn(instance);
        assertSame(instance, resource.page());
    }

    /** BO-10-08-02: the pending calls reach the telephone only when both activations hold. */
    @Test
    @SuppressWarnings("unchecked")
    void theCallsFollowTheActivation() {
        MobileAlertResource active = resource(true, true);
        SupervisorCallRegistry.Call call = new SupervisorCallRegistry.Call(3, "C04", "Jean", "Prix", "vite");
        when(active.supervisorCallRegistry.getPending()).thenReturn(List.of(call));
        Map<String, Object> data = active.data();
        assertEquals(true, data.get("enabled"));
        List<Map<String, Object>> calls = (List<Map<String, Object>>) data.get("calls");
        assertEquals("C04", calls.get(0).get("terminal"));
        assertEquals("vite", calls.get(0).get("message"));
        assertEquals("vite", calls.get(0).get("gesture"));
        assertEquals("Prijs", calls.get(0).get("reasonLabel"));
        assertEquals(3L, ((Number) calls.get(0).get("id")).longValue());
        assertEquals("PENDING", calls.get(0).get("status"));
        assertEquals("wachtend", calls.get(0).get("statusLabel"));
        assertEquals("", calls.get(0).get("refusalReason"));
        MobileAlertResource mobileOff = resource(false, true);
        assertTrue(((List<?>) mobileOff.data().get("calls")).isEmpty());
        assertEquals(false, mobileOff.data().get("enabled"));
        MobileAlertResource alertsOff = resource(true, false);
        assertTrue(((List<?>) alertsOff.data().get("calls")).isEmpty());
        verify(alertsOff.supervisorCallRegistry, never()).getPending();
    }

    /**
     * LS-04-04-09: a settled call reaches the telephone with what it became and why, so
     * the page shows it as the dashboard does.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSettledCallSaysWhatItBecame() {
        MobileAlertResource resource = resource(true, true);
        SupervisorCallRegistry.Call call = new SupervisorCallRegistry.Call(4, "C05", "Zoé", "Prix", null);
        call.status = SupervisorCallRegistry.CallStatus.REFUSED;
        call.refusalReason = "Pas maintenant";
        when(resource.supervisorCallRegistry.getPending()).thenReturn(List.of(call));
        Map<String, Object> entry = ((List<Map<String, Object>>) resource.data().get("calls")).get(0);
        assertEquals("REFUSED", entry.get("status"));
        assertEquals("geweigerd", entry.get("statusLabel"));
        assertEquals("Pas maintenant", entry.get("refusalReason"));
    }
}
