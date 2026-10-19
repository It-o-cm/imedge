package com.intermarche.edge.ui.admin;

import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.DayEndService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminDayEndResource} ({@code BO-09-02-01} to
 * {@code -03}).
 *
 * <p>A thin facade over {@link DayEndService}: every collaborator is a mock
 * and the template is a chaining stub. Branch enumeration (both arms): a
 * launch that found something to close, whose notice is a warning, and one
 * that found a quiet shop, whose notice is a success.
 */
class AdminDayEndResourceTest {

    /**
     * Builds a resource with every collaborator mocked and a template that
     * chains its {@code data} calls.
     *
     * @return the resource under test
     */
    private AdminDayEndResource resource() {
        AdminDayEndResource resource = new AdminDayEndResource();
        resource.dayEndService = mock(DayEndService.class);
        resource.currentUser = mock(CurrentUser.class);
        when(resource.currentUser.getFullName()).thenReturn("Marie Curie");
        resource.registerPresenceService = mock(RegisterPresenceService.class);
        when(resource.registerPresenceService.all()).thenReturn(List.of());
        when(resource.registerPresenceService.offlineMinutes()).thenReturn(3);
        // BO-09-01-10/-11: nothing programmed and nothing edited unless a test
        // says so, which is the state of a shop that has not programmed any.
        resource.scheduledReportService =
                mock(com.intermarche.edge.service.ScheduledReportService.class);
        when(resource.scheduledReportService.programmed()).thenReturn(List.of());
        when(resource.scheduledReportService.latest(20)).thenReturn(List.of());
        resource.reportService = mock(com.intermarche.edge.ui.report.ReportService.class);
        com.intermarche.edge.ui.report.ReportCatalog catalog =
                mock(com.intermarche.edge.ui.report.ReportCatalog.class);
        when(resource.reportService.catalog()).thenReturn(catalog);
        when(catalog.all()).thenReturn(List.of());
        resource.posSettingsService =
                mock(com.intermarche.edge.service.PosSettingsService.class);
        resource.periodScheduleService =
                mock(com.intermarche.edge.service.PeriodScheduleService.class);
        when(resource.periodScheduleService.endingOn(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of(
                        com.intermarche.edge.service.PeriodScheduleService.PeriodEnd.DAY));
        resource.adminDayEnd = mock(Template.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminDayEnd.data(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(instance);
        when(instance.data(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(instance);
        return resource;
    }

    /**
     * Builds a state carrying the given alerts.
     *
     * @param open how many lanes are open
     * @param offline how many are silent
     * @param parked how many carts wait
     * @return the state
     */
    private DayEndService.DayEndState state(int open, int offline, int parked) {
        return new DayEndService.DayEndState(true, "23:30", 10L, true,
                java.util.Collections.nCopies(open,
                        new DayEndService.OpenRegister("CAISSE1", "S-1", "Martin")),
                java.util.Collections.nCopies(offline,
                        new RegisterPresenceService.Presence("CAISSE9", LocalDateTime.now(),
                                12L, false)),
                java.util.Collections.nCopies(parked,
                        new DayEndService.ParkedCart("CAISSE1", "C04-1", "Martin", "12.50")));
    }

    /**
     * The screen hands the picture and the register list to its template.
     */
    @Test
    void theScreenHandsOverThePicture() {
        AdminDayEndResource resource = resource();
        when(resource.dayEndService.state()).thenReturn(state(0, 0, 0));
        assertSame(resource.adminDayEnd.data("state", null), resource.page(null, true));
        verify(resource.dayEndService).state();
        verify(resource.registerPresenceService).all();
    }

    /**
     * BO-09-02-01 to -03: the launch says what it found, and a launch that
     * found something to close is not reported as a clean one.
     */
    @Test
    void theLaunchSaysWhatItFound() {
        AdminDayEndResource resource = resource();
        when(resource.dayEndService.launch("Marie Curie")).thenReturn(state(1, 1, 2));
        Response response = resource.launch();
        // The notice is URL-encoded on the way out, read here as the browser
        // reads it on the way back.
        String notice = java.net.URLDecoder.decode(response.getLocation().getQuery(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(notice.contains("1 caisse(s) ouverte(s)"));
        assertTrue(notice.contains("2 ticket(s) en attente"));
        assertTrue(notice.contains("noticeOk=false"));
    }

    /**
     * A launch on a quiet shop reports a clean end of day.
     */
    @Test
    void aQuietShopReportsACleanEndOfDay() {
        AdminDayEndResource resource = resource();
        when(resource.dayEndService.launch("Marie Curie")).thenReturn(state(0, 0, 0));
        Response response = resource.launch();
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
        assertEquals(303, response.getStatus());
    }
}
