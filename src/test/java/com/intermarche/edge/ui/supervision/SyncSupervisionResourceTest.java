package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.CashSessionService;
import com.intermarche.edge.service.TechnicalEventService;
import com.intermarche.edge.service.sync.EngineFeedDeliveryService;
import com.intermarche.edge.service.sync.RefPullService;
import com.intermarche.edge.service.sync.ScheduledDeliveryService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SyncSupervisionResource}.
 * <p>
 * The resource is a Qute-backed back-office page over four mocked services. The
 * template is a Mockito mock whose chained {@code data(...)} returns a single
 * self-returning instance; the three POST actions return 303 redirects whose
 * location carries the notice. No database and no Quarkus context is booted.
 * <p>
 * Branch enumeration — every arm: {@code page} covers the
 * {@code getOpenSession() != null} ternary (open and closed); {@code relaunchPull}
 * and {@code relaunchDelivery} cover the {@code error == null} success and
 * failure arms (green and red notice, the matching journal detail);
 * {@code forceCloseSession} covers the report and null arms of
 * {@code closeSession}.
 */
class SyncSupervisionResourceTest {

    /**
     * Builds a resource over a mocked template and the four service mocks.
     *
     * @return the wired resource
     */
    private SyncSupervisionResource newResource() {
        SyncSupervisionResource resource = new SyncSupervisionResource();
        resource.adminSync = mock(Template.class);
        resource.syncSupervisionService = mock(SyncSupervisionService.class);
        resource.refPullService = mock(RefPullService.class);
        resource.engineFeedDeliveryService = mock(EngineFeedDeliveryService.class);
        resource.scheduledDeliveryService = mock(ScheduledDeliveryService.class);
        resource.cashSessionService = mock(CashSessionService.class);
        resource.technicalEventService = mock(TechnicalEventService.class);
        resource.currentUser = mock(CurrentUser.class);
        resource.integrationPullService = mock(com.intermarche.edge.service.sync.IntegrationPullService.class);
        resource.outboundFlowService = mock(
                com.intermarche.edge.service.export.OutboundFlowService.class);
        resource.ticketStreamService = mock(
                com.intermarche.edge.service.sync.TicketStreamService.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(SyncSupervisionResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminSync.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * {@code page} builds the view and flags the local session as OPEN (non-null
     * arm), echoing the notice back.
     */
    @Test
    void pageFlagsOpenSession() {
        SyncSupervisionResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        SyncSupervisionService.SyncView view = new SyncSupervisionService.SyncView();
        when(resource.syncSupervisionService.build()).thenReturn(view);
        when(resource.cashSessionService.getOpenSession()).thenReturn(new CashSession());
        assertEquals(instance, resource.page("hi", true));
        verify(resource.adminSync).data("view", view);
        verify(instance).data("sessionOpen", true);
        verify(instance).data("notice", "hi");
        verify(instance).data("noticeOk", true);
    }

    /**
     * {@code page} flags the local session as CLOSED (null arm) when no session
     * is open.
     */
    @Test
    void pageFlagsClosedSession() {
        SyncSupervisionResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        when(resource.syncSupervisionService.build()).thenReturn(new SyncSupervisionService.SyncView());
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        resource.page(null, false);
        verify(instance).data("sessionOpen", false);
        verify(instance).data("noticeOk", false);
    }

    /**
     * {@code relaunchStream} on an activated stream runs a pass, journals how many
     * events it delivered and redirects green (BO-11-02-05).
     */
    @Test
    void relaunchStreamRunsAPass() {
        SyncSupervisionResource resource = newResource();
        when(resource.ticketStreamService.isEnabled()).thenReturn(true);
        when(resource.ticketStreamService.deliverPending()).thenReturn(4);
        Response response = resource.relaunchStream();
        verify(resource.technicalEventService).log(
                eq(TechnicalEvent.EventType.STREAM_DELIVERY_FORCED), contains("4"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code relaunchStream} on a DISABLED stream refuses and delivers nothing: an
     * operator pressing a button on a disabled mechanism must be told it is
     * disabled, not left to read an empty success.
     */
    @Test
    void relaunchStreamRefusesWhenTheStreamIsDisabled() {
        SyncSupervisionResource resource = newResource();
        when(resource.ticketStreamService.isEnabled()).thenReturn(false);
        Response response = resource.relaunchStream();
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        verify(resource.ticketStreamService, never()).deliverPending();
        verify(resource.technicalEventService, never()).log(
                eq(TechnicalEvent.EventType.STREAM_DELIVERY_FORCED), anyString());
    }

    /**
     * {@code relaunchPull} on a clean cycle (null error arm) journals the
     * success and redirects green.
     */
    @Test
    void relaunchPullSucceeds() {
        SyncSupervisionResource resource = newResource();
        when(resource.refPullService.triggerPull()).thenReturn(null);
        Response response = resource.relaunchPull();
        verify(resource.technicalEventService).log(
                eq(TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED), contains("réussi"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code relaunchPull} on a failing cycle (error arm) journals the failure
     * with its message and redirects red.
     */
    @Test
    void relaunchPullFails() {
        SyncSupervisionResource resource = newResource();
        when(resource.refPullService.triggerPull()).thenReturn("HTTP 500");
        Response response = resource.relaunchPull();
        verify(resource.technicalEventService).log(
                eq(TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED), contains("HTTP 500"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code relaunchDelivery} on a clean cycle (null error arm) journals the
     * success and redirects green.
     */
    @Test
    void relaunchDeliverySucceeds() {
        SyncSupervisionResource resource = newResource();
        when(resource.engineFeedDeliveryService.triggerDelivery()).thenReturn(null);
        Response response = resource.relaunchDelivery();
        verify(resource.technicalEventService).log(
                eq(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED), contains("réussie"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code relaunchDelivery} on a failing cycle (error arm) journals the
     * failure with its message and redirects red.
     */
    @Test
    void relaunchDeliveryFails() {
        SyncSupervisionResource resource = newResource();
        when(resource.engineFeedDeliveryService.triggerDelivery()).thenReturn("db down");
        Response response = resource.relaunchDelivery();
        verify(resource.technicalEventService).log(
                eq(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED), contains("db down"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code forceCloseSession} on an open session (report arm) redirects green
     * naming the closed session; the delegated {@code closeSession(null, …)}
     * carries the forced-close semantics.
     */
    @Test
    void forceCloseSessionClosesOpenSession() {
        SyncSupervisionResource resource = newResource();
        CashSession session = new CashSession();
        session.sessionNumber = "C04-S00001";
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.session = session;
        when(resource.cashSessionService.closeSession(any(), any(), any(), any())).thenReturn(report);
        Response response = resource.forceCloseSession();
        verify(resource.cashSessionService).closeSession(null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        assertTrue(response.getLocation().toString().contains("C04-S00001"));
    }

    /**
     * {@code forceCloseSession} on a node with no open session (null arm)
     * redirects red with a plain notice.
     */
    @Test
    void forceCloseSessionReportsNoSession() {
        SyncSupervisionResource resource = newResource();
        when(resource.cashSessionService.closeSession(any(), any(), any(), any())).thenReturn(null);
        Response response = resource.forceCloseSession();
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * Normative case 2 — {@code relaunchDeliveryFor} on a clean targeted delivery
     * (null error arm) journals the success naming the feed and redirects green.
     */
    @Test
    void relaunchDeliveryForSucceeds() {
        SyncSupervisionResource resource = newResource();
        when(resource.engineFeedDeliveryService.triggerDelivery("OFFERS")).thenReturn(null);
        Response response = resource.relaunchDeliveryFor("OFFERS");
        verify(resource.technicalEventService).log(
                eq(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED), contains("OFFERS"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        assertTrue(response.getLocation().toString().contains("OFFERS"));
    }

    /**
     * Normative case 3 — {@code relaunchDeliveryFor} on a failing targeted
     * delivery (error arm) journals the failure with its message and redirects red.
     */
    @Test
    void relaunchDeliveryForFails() {
        SyncSupervisionResource resource = newResource();
        when(resource.engineFeedDeliveryService.triggerDelivery("OFFERS")).thenReturn("HTTP 503");
        Response response = resource.relaunchDeliveryFor("OFFERS");
        verify(resource.technicalEventService).log(
                eq(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED), contains("HTTP 503"));
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * Normative case 4 — {@code schedule} with a valid date books the delivery
     * (naming the current user) and redirects green.
     */
    @Test
    void scheduleBooksDelivery() {
        SyncSupervisionResource resource = newResource();
        when(resource.currentUser.getName()).thenReturn("admin");
        Response response = resource.schedule("OFFERS", "2026-09-26T14:30");
        verify(resource.scheduledDeliveryService).schedule("OFFERS",
                LocalDateTime.of(2026, 9, 26, 14, 30), "admin");
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code schedule} with a missing date (blank arm) is refused red without
     * booking anything.
     */
    @Test
    void scheduleRejectsMissingDate() {
        SyncSupervisionResource resource = newResource();
        Response response = resource.schedule("OFFERS", "  ");
        verify(resource.scheduledDeliveryService, never()).schedule(any(), any(), any());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code schedule} with a null date (null arm of the guard) is refused red
     * without booking anything.
     */
    @Test
    void scheduleRejectsNullDate() {
        SyncSupervisionResource resource = newResource();
        Response response = resource.schedule("OFFERS", null);
        verify(resource.scheduledDeliveryService, never()).schedule(any(), any(), any());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code schedule} with a malformed date (parse-exception arm) is refused red
     * without booking anything.
     */
    @Test
    void scheduleRejectsMalformedDate() {
        SyncSupervisionResource resource = newResource();
        Response response = resource.schedule("OFFERS", "not-a-date");
        verify(resource.scheduledDeliveryService, never()).schedule(any(), any(), any());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * Normative case 4 — {@code cancelSchedule} of a still-pending booking (true
     * arm) redirects green.
     */
    @Test
    void cancelScheduleSucceeds() {
        SyncSupervisionResource resource = newResource();
        when(resource.scheduledDeliveryService.cancel(7L)).thenReturn(true);
        Response response = resource.cancelSchedule(7L);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=true"));
    }

    /**
     * {@code cancelSchedule} of an already-run or missing booking (false arm)
     * redirects red.
     */
    @Test
    void cancelScheduleFails() {
        SyncSupervisionResource resource = newResource();
        when(resource.scheduledDeliveryService.cancel(7L)).thenReturn(false);
        Response response = resource.cancelSchedule(7L);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * Normative case 1 — the three fragment endpoints render their own table body
     * off a freshly built view, for the auto-refresh.
     */
    @Test
    void fragmentEndpointsRenderTableBodies() {
        SyncSupervisionResource resource = newResource();
        SyncSupervisionService.SyncView view = new SyncSupervisionService.SyncView();
        when(resource.syncSupervisionService.build()).thenReturn(view);
        Template.Fragment fragment = mock(Template.Fragment.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminSync.getFragment(anyString())).thenReturn(fragment);
        when(fragment.data(anyString(), any())).thenReturn(instance);
        assertEquals(instance, resource.feedsFragment());
        assertEquals(instance, resource.pullsFragment());
        assertEquals(instance, resource.outboxFragment());
        verify(resource.adminSync).getFragment("feeds");
        verify(resource.adminSync).getFragment("pulls");
        verify(resource.adminSync).getFragment("backlog");
    }

    /**
     * Normative case 5 — access: every JAX-RS handler of the screen requires a
     * signed-in account and declares the SYNC feature, so the access filter
     * opens it only to the profiles granting it (BO-01-04-01) — the ADMIN
     * superuser always. A handler missing either annotation is the regression
     * this test guards.
     */
    @Test
    void everyHandlerIsGuardedBySyncFeature() {
        for (Method method : SyncSupervisionResource.class.getDeclaredMethods()) {
            boolean handler = method.isAnnotationPresent(jakarta.ws.rs.GET.class)
                    || method.isAnnotationPresent(jakarta.ws.rs.POST.class);
            if (!handler) {
                continue;
            }
            assertNotNull(method.getAnnotation(io.quarkus.security.Authenticated.class),
                    "handler " + method.getName() + " must carry @Authenticated");
            com.intermarche.edge.security.FeatureAccess access =
                    method.getAnnotation(com.intermarche.edge.security.FeatureAccess.class);
            assertNotNull(access, "handler " + method.getName() + " must carry @FeatureAccess");
            assertEquals(com.intermarche.edge.domain.setting.Feature.SYNC, access.value());
        }
    }
    /** BO-11-03-01: regeneration checks each date leg and reports empty, written and failed outputs. */
    @Test
    void outboundRegenerationHandlesEveryDateAndOutcome() throws Exception {
        SyncSupervisionResource resource = newResource();
        var yesterday = java.time.LocalDate.now().minusDays(1);
        assertTrue(resource.regenerateOutboundFlows(null).getLocation().toString().contains("noticeOk=true"));
        assertTrue(resource.regenerateOutboundFlows(" ").getLocation().toString().contains("noticeOk=true"));
        verify(resource.outboundFlowService, org.mockito.Mockito.times(2)).produceAll(yesterday);
        assertTrue(resource.regenerateOutboundFlows("invalid").getLocation().toString().contains("noticeOk=false"));
        var day = java.time.LocalDate.of(2026, 10, 19);
        when(resource.outboundFlowService.produceAll(day)).thenReturn(java.util.List.of(java.nio.file.Path.of("sales.csv")));
        String written = java.net.URLDecoder.decode(resource.regenerateOutboundFlows(" 2026-10-19 ").getLocation()
                .toString(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(written.contains("noticeOk=true"));
        assertTrue(written.contains("1 flux produit(s) pour le 19/10/2026."));
        when(resource.outboundFlowService.produceAll(day.plusDays(1))).thenReturn(java.util.List.of());
        assertTrue(java.net.URLDecoder.decode(resource.regenerateOutboundFlows("2026-10-20").getLocation().toString(),
                java.nio.charset.StandardCharsets.UTF_8).contains("Aucun flux produit pour le 20/10/2026 :"));
        when(resource.outboundFlowService.produceAll(day)).thenThrow(new java.io.IOException("disk unavailable"));
        assertTrue(resource.regenerateOutboundFlows("2026-10-19").getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * BO-11-01-01: the manual pull of the integration feeds redirects with its
     * counts, as a success when nothing failed and as a failure otherwise.
     */
    @Test
    void theIntegrationPullIsLaunchedFromTheConsole() {
        SyncSupervisionResource resource = newResource();
        when(resource.integrationPullService.pullNow(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.intermarche.edge.service.sync.IntegrationPullService.Outcome(2, 1, 0),
                        new com.intermarche.edge.service.sync.IntegrationPullService.Outcome(0, 0, 3));
        String ok = java.net.URLDecoder.decode(resource.pullIntegrationFeeds().getLocation().toString(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(ok.contains("noticeOk=true"));
        assertTrue(ok.contains("Flux d'intégration : 2 intégré(s), 1 inchangé(s), 0 en échec."));
        assertTrue(resource.pullIntegrationFeeds().getLocation().toString().contains("noticeOk=false"));
    }
}
