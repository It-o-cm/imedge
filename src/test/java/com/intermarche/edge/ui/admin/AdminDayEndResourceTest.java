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
import static org.mockito.ArgumentMatchers.eq;
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
        resource.storePeriodCloseService = mock(com.intermarche.edge.service.StorePeriodCloseService.class);
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
        resource.periodCloseService = mock(com.intermarche.edge.service.PeriodCloseService.class);
        resource.parkedTicketCloseService = mock(com.intermarche.edge.service.ParkedTicketCloseService.class);
        resource.periodHookService = mock(com.intermarche.edge.service.PeriodHookService.class);
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
                        new DayEndService.OpenRegister("CAISSE1", "S-1", "Martin", null)),
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
        assertTrue(notice.contains("Traitement de fin de période déjà exécuté aujourd'hui."));
    }

    /**
     * A launch on a quiet shop reports a clean end of day.
     */
    @Test
    void aQuietShopReportsACleanEndOfDay() {
        AdminDayEndResource resource = resource();
        when(resource.dayEndService.launch("Marie Curie")).thenReturn(state(0, 0, 0));
        when(resource.periodCloseService.runEndOfPeriod(org.mockito.ArgumentMatchers.any(), eq("Marie Curie")))
                .thenReturn(true);
        Response response = resource.launch();
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
        assertTrue(java.net.URLDecoder.decode(response.getLocation().getQuery(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("Traitement de fin de période exécuté."));
        assertEquals(303, response.getStatus());
    }
    /** BO-09-01: missing selections and each malformed selection element are independent of the chosen output format. */
    @Test
    void coverageProgrammedReportsAndFormats() {
        var resource = resource();
        var form = new jakarta.ws.rs.core.MultivaluedHashMap<String, String>();
        assertTrue(resource.programReports(form).getLocation().toString().contains("noticeOk=true"));
        verify(resource.posSettingsService).store("period.reports", "");
        form.put("report", java.util.Arrays.asList(null, " ", " A ", "B"));
        form.putSingle("format", " ");
        resource.programReports(form);
        verify(resource.posSettingsService).store("period.reports", "A;B");
        form.putSingle("format", " csv ");
        resource.programReports(form);
        verify(resource.posSettingsService).store("period.reports-format", "csv");
        verify(resource.posSettingsService, org.mockito.Mockito.times(2)).store("period.reports-format", "pdf");
    }

    /** BO-09-01-10: missing editions/content refuse a download; known and unknown formats retain the stored bytes. */
    @Test
    void coverageScheduledDownloadLegs() {
        var resource = resource();
        var run = new com.intermarche.edge.domain.session.ScheduledReportRun();
        run.format = "raw";
        run.reportCode = "REPORT";
        run.periodEnd = java.time.LocalDate.of(2026, 10, 20);
        try (var base = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            assertEquals(404, resource.downloadRun(1L).getStatus());
            base.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.findById(1L)).thenReturn(run);
            assertEquals(404, resource.downloadRun(1L).getStatus());
            run.content = new byte[]{1, 2, 3};
            var raw = resource.downloadRun(1L);
            assertEquals("application/octet-stream", raw.getMediaType().toString());
            org.junit.jupiter.api.Assertions.assertSame(run.content, raw.getEntity());
            var writer = mock(com.intermarche.edge.ui.report.ReportWriter.class);
            when(writer.extension()).thenReturn("csv");
            when(writer.contentType()).thenReturn("text/csv");
            when(resource.reportService.writer("raw")).thenReturn(writer);
            var csv = resource.downloadRun(1L);
            assertEquals("text/csv", csv.getMediaType().toString());
            assertTrue(csv.getHeaderString("Content-Disposition").endsWith(".csv\""));
        }
    }

    /** BO-09-01: the administered output format is shown when a writer is available. */
    @Test
    void coveragePageWithConfiguredWriter() {
        var resource = resource();
        var writer = mock(com.intermarche.edge.ui.report.ReportWriter.class);
        when(writer.format()).thenReturn("csv");
        when(resource.scheduledReportService.format()).thenReturn(writer);
        var view = resource.page(null, true);
        verify(view).data("reportFormat", "csv");
    }


    /**
     * BO-09-01-08: the reset of the tickets on hold says how many it covers,
     * of one register or of all, and says so when there is none.
     */
    @Test
    void theResetSaysWhatItCovers() {
        AdminDayEndResource resource = resource();
        var form = new jakarta.ws.rs.core.MultivaluedHashMap<String, String>();
        form.putSingle("terminal", "CAISSE1");
        when(resource.parkedTicketCloseService.raz(eq("CAISSE1"), eq("Marie Curie"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(2);
        String covered = java.net.URLDecoder.decode(resource.razParked(form).getLocation().getQuery(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(covered.contains("Remise à zéro demandée pour 2 ticket(s) en attente."));
        String none = java.net.URLDecoder.decode(
                resource.razParked(new jakarta.ws.rs.core.MultivaluedHashMap<>()).getLocation().getQuery(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(none.contains("Aucun ticket en attente à remettre à zéro."));
        verify(resource.parkedTicketCloseService).raz(org.mockito.ArgumentMatchers.isNull(), eq("Marie Curie"),
                org.mockito.ArgumentMatchers.any());
    }
}
