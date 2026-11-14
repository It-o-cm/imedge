package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The register alerts on a MOBILE DEVICE (BO-10-08-02): the supervisor
 * calls of the line on a page laid out for a telephone, where a manager away
 * from the office acknowledges, authorises or refuses them — the remote
 * validation of a supervisor request.
 *
 * <p>The page is its own, not the dashboard shrunk: one column, large touch
 * targets, no navigation bar. Its gestures are the dashboard's own
 * ({@code /dashboard/ack}, {@code /authorise}, {@code /to-till}, {@code /refuse},
 * LS-04-04-09), so a call
 * answered on the telephone is answered on the dashboard and on the lane.
 * It shows nothing unless the back office activated it
 * ({@code dashboard.mobile-alerts-enabled}) and the dashboard alerts
 * themselves ({@code dashboard.alerts-enabled}).
 */
@Path("/dashboard/mobile")
public class MobileAlertResource {

    /** The mobile page. */
    @Inject
    @Location("mobile-alerts")
    Template mobilePage;

    /** The pending calls. */
    @Inject
    SupervisorCallRegistry supervisorCallRegistry;

    /** The activation parameters. */
    @Inject
    PosSettingsService posSettingsService;

    /** The back-office messages, which word the calls (BO-04-02-12). */
    @Inject
    com.intermarche.edge.ui.admin.BackOfficeMessages i18n;

    /**
     * Shows the mobile alert page.
     *
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.MOBILE_ALERTS)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page() {
        return mobilePage.data("enabled", enabled());
    }

    /**
     * Returns the pending calls, polled by the page.
     *
     * @return {@code enabled} and the {@code calls}, empty when deactivated
     */
    @GET
    @Path("/data")
    @Authenticated
    @FeatureAccess(value = Feature.MOBILE_ALERTS)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> data() {
        boolean enabled = enabled();
        List<Map<String, Object>> calls = new ArrayList<>();
        if (enabled) {
            for (SupervisorCallRegistry.Call call : supervisorCallRegistry.getPending()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", call.id);
                entry.put("terminal", call.terminalId);
                entry.put("operator", call.operator);
                entry.put("reason", call.reason);
                entry.put("message", call.message);
                entry.put("reasonLabel", CallWording.reason(i18n, call.reason));
                entry.put("gesture", CallWording.gesture(i18n, call.message));
                entry.put("status", call.status.name());
                // LS-04-04-09: a settled call says what it became, as on the dashboard.
                entry.put("statusLabel", i18n.text(call.status.getLabel()));
                entry.put("refusalReason", call.refusalReason);
                entry.put("time", call.time);
                calls.add(entry);
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", enabled);
        data.put("calls", calls);
        return data;
    }

    /**
     * Whether the mobile alerts are shown.
     *
     * @return true when both the mobile page and the dashboard alerts are activated
     */
    boolean enabled() {
        return posSettingsService.mobileAlertsEnabled() && posSettingsService.dashboardAlertsEnabled();
    }
}
