package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.fiscal.FiscalArchive;
import com.intermarche.edge.domain.fiscal.SaftTransmission;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.export.SaftExportService;
import com.intermarche.edge.service.fiscal.FiscalArchiveService;
import com.intermarche.edge.service.fiscal.SaftStreamService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminSaftResource} (BO-10-06-03, BO-05-01-04,
 * BO-05-01-05): the documents of a period, the download of their SAF-T file,
 * the monthly extraction kept with its transmission and deadline, and the
 * supervision of the SAF-T at the flow.
 */
class AdminSaftResourceTest {

    /** The query listing the documents of a period. */
    private static final String PERIOD = "issuedAt >= ?1 and issuedAt < ?2 order by documentType, series, rank";

    /** The query of the monthly files of a period. */
    private static final String MONTHLY = "kind = ?1 and periodFrom = ?2 and periodTo = ?3 order by id desc";

    /**
     * Builds the resource with its collaborators mocked.
     *
     * @return the resource
     */
    private AdminSaftResource newResource() {
        AdminSaftResource resource = new AdminSaftResource();
        resource.adminSaft = mock(Template.class);
        resource.saftExportService = mock(SaftExportService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.fiscalArchiveService = mock(FiscalArchiveService.class);
        resource.saftStreamService = mock(SaftStreamService.class);
        resource.atWsSecurity = mock(com.intermarche.edge.service.fiscal.AtWsSecurity.class);
        when(resource.atWsSecurity.status()).thenReturn(com.intermarche.edge.service.fiscal.AtWsSecurity.Status.ABSENT);
        resource.currentUser = mock(CurrentUser.class);
        when(resource.posSettingsService.fiscalCertificateNumber()).thenReturn("123/AT");
        when(resource.posSettingsService.saftDeadlineDay()).thenReturn(25);
        return resource;
    }

    /**
     * Stubs what the page reads beside the documents: the monthly files of the
     * previous month, the latest files and the sends.
     *
     * @param panache the active PanacheEntityBase mock
     * @param previous the files of the previous month
     */
    @SuppressWarnings("unchecked")
    private void stubPage(MockedStatic<PanacheEntityBase> panache, List<FiscalArchive> previous) {
        LocalDate first = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        PanacheQuery<FiscalArchive> files = mock(PanacheQuery.class);
        when(files.list()).thenReturn(previous);
        panache.when(() -> PanacheEntityBase.find(MONTHLY, FiscalArchive.Kind.SAFT_MONTHLY, first,
                first.withDayOfMonth(first.lengthOfMonth()))).thenReturn(files);
        PanacheQuery<FiscalArchive> latest = mock(PanacheQuery.class);
        when(latest.page(0, AdminSaftResource.SHOWN)).thenReturn(latest);
        when(latest.list()).thenReturn(previous);
        panache.when(() -> PanacheEntityBase.find("kind in ?1 order by generatedAt desc, id desc",
                List.of(FiscalArchive.Kind.SAFT_MONTHLY))).thenReturn(latest);
        PanacheQuery<SaftTransmission> sends = mock(PanacheQuery.class);
        when(sends.page(0, AdminSaftResource.SHOWN)).thenReturn(sends);
        when(sends.list()).thenReturn(List.of());
        panache.when(() -> PanacheEntityBase.find("order by id desc")).thenReturn(sends);
        panache.when(() -> PanacheEntityBase.count("status", SaftTransmission.Status.PENDING)).thenReturn(2L);
        panache.when(() -> PanacheEntityBase.count("status", SaftTransmission.Status.SENT)).thenReturn(5L);
        panache.when(() -> PanacheEntityBase.count("status", SaftTransmission.Status.FAILED)).thenReturn(1L);
    }

    /**
     * Stubs a template's fluent chain.
     *
     * @param template the template
     * @return the view every call returns
     */
    private TemplateInstance stub(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * The page lists the documents of the typed period with the certification
     * number, the monthly extraction and the
     * supervision of the sends.
     */
    @Test
    void thePageListsTheDocumentsOfThePeriod() {
        AdminSaftResource resource = newResource();
        TemplateInstance instance = stub(resource.adminSaft);
        FiscalDocument document = new FiscalDocument();
        document.number = "FS 1";
        document.grossTotal = new BigDecimal("1234.5");
        when(resource.posSettingsService.saftRealtime()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(PERIOD, LocalDate.of(2026, 10, 1).atStartOfDay(),
                    LocalDate.of(2026, 11, 1).atStartOfDay())).thenReturn(List.of(document));
            stubPage(panache, List.of());
            assertSame(instance, resource.page("2026-10-01", " 2026-10-31 ", "ok", true));
        }
        verify(resource.adminSaft).data("documents", List.of(document));
        verify(instance, never()).data(eq("amounts"), any());
        verify(instance).data("from", "2026-10-01");
        verify(instance).data("to", "2026-10-31");
        verify(instance).data("certificate", "123/AT");
        verify(instance).data("month", LocalDate.now().minusMonths(1).toString().substring(0, 7));
        verify(instance).data("monthLabel", LocalDate.now().minusMonths(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy")));
        verify(instance).data("deadline", 25);
        verify(instance).data("previousExtracted", false);
        verify(instance).data("previousTransmitted", false);
        verify(instance).data("late", LocalDate.now().getDayOfMonth() > 25);
        verify(instance).data("realtime", true);
        verify(instance).data("wsSecurity", "ABSENT");
        verify(instance).data("pending", 2L);
        verify(instance).data("sent", 5L);
        verify(instance).data("failed", 1L);
        verify(instance).data("notice", "ok");
    }

    /**
     * A previous month extracted and transmitted is never late; extracted but
     * not transmitted, it is late past the deadline.
     */
    @Test
    void thePreviousMonthIsLateOnlyWhenNotTransmitted() {
        AdminSaftResource resource = newResource();
        when(resource.posSettingsService.saftDeadlineDay()).thenReturn(1, 28);
        TemplateInstance instance = stub(resource.adminSaft);
        FiscalArchive sent = new FiscalArchive();
        sent.transmittedAt = LocalDateTime.now();
        FiscalArchive kept = new FiscalArchive();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(eq(PERIOD), any(Object[].class))).thenReturn(List.of());
            stubPage(panache, List.of(kept));
            resource.page(null, null, null, true);
            stubPage(panache, List.of(kept, sent));
            resource.page(null, null, null, true);
        }
        verify(instance).data("previousTransmitted", true);
        verify(instance, org.mockito.Mockito.times(2)).data("previousExtracted", true);
        assertTrue(AdminSaftResource.late(false, 26, 25));
        assertTrue(!AdminSaftResource.late(false, 25, 25));
        assertTrue(!AdminSaftResource.late(true, 26, 25));
    }

    /** The export downloads the file of the period under its name, the days defaulting to the month so far. */
    @Test
    void theExportDownloadsTheFile() {
        AdminSaftResource resource = newResource();
        LocalDate today = LocalDate.now();
        LocalDate first = today.withDayOfMonth(1);
        when(resource.saftExportService.export(eq(first), eq(today), any())).thenReturn("<AuditFile/>");
        when(resource.saftExportService.fileName(first, today)).thenReturn("SAFT.xml");
        Response response = resource.export(null, "not a day");
        assertEquals(200, response.getStatus());
        assertEquals("<AuditFile/>", response.getEntity());
        assertEquals("attachment; filename=\"SAFT.xml\"", response.getHeaderString("Content-Disposition"));
    }

    /** A blank, null or unreadable day is the default one; a typed day is read. */
    @Test
    void daysFallBackToTheirDefault() {
        LocalDate fallback = LocalDate.of(2026, 1, 1);
        assertEquals(fallback, AdminSaftResource.day(null, fallback));
        assertEquals(fallback, AdminSaftResource.day(" ", fallback));
        assertEquals(fallback, AdminSaftResource.day("31/10/2026", fallback));
        assertEquals(LocalDate.of(2026, 10, 31), AdminSaftResource.day("2026-10-31", fallback));
    }

    /** A month is read from {@code yyyy-MM}; blank, null and unreadable give none. */
    @Test
    void monthsAreRead() {
        assertEquals(LocalDate.of(2026, 9, 1), AdminSaftResource.month(" 2026-09 "));
        assertNull(AdminSaftResource.month(null));
        assertNull(AdminSaftResource.month(" "));
        assertNull(AdminSaftResource.month("09/2026"));
    }

    /**
     * Reads the decoded location of a redirect.
     *
     * @param response the redirect
     * @return the location
     */
    private String location(Response response) {
        return URLDecoder.decode(response.getLocation().toString(), StandardCharsets.UTF_8);
    }

    /**
     * The monthly extraction draws the file of the whole month and keeps it
     * under the point of sale's name; a month unreadable or to come is refused.
     */
    @Test
    void theMonthlyExtractionKeepsTheFileOfTheMonth() {
        AdminSaftResource resource = newResource();
        when(resource.currentUser.getName()).thenReturn("admin");
        when(resource.fiscalArchiveService.pdvNumber()).thenReturn("07039");
        when(resource.saftExportService.export(eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30)), any()))
                .thenReturn("<AuditFile/>");
        FiscalArchive file = new FiscalArchive();
        file.fileName = "SAFT-PT_07039_20260930.xml";
        when(resource.fiscalArchiveService.keep(eq(FiscalArchive.Kind.SAFT_MONTHLY), eq("07039"),
                eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30)),
                eq("<AuditFile/>".getBytes(StandardCharsets.UTF_8)), eq("admin"), eq(false), any())).thenReturn(file);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("month", "2026-09");
        assertTrue(location(resource.monthly(form)).contains("Fichier SAF-T extrait : SAFT-PT_07039_20260930.xml"));
        resource.currentUser = null;
        when(resource.fiscalArchiveService.keep(any(), any(), any(), any(), any(), eq(null), eq(false), any()))
                .thenReturn(file);
        assertTrue(location(resource.monthly(form)).contains("noticeOk=true"));
        MultivaluedMap<String, String> future = new MultivaluedHashMap<>();
        future.add("month", LocalDate.now().plusMonths(1).toString().substring(0, 7));
        assertTrue(location(resource.monthly(future)).contains("Mois invalide."));
        assertTrue(location(resource.monthly(new MultivaluedHashMap<>())).contains("Mois invalide."));
    }

    /**
     * A monthly file downloads as it was kept; an unknown file, or one of
     * another kind, is not found.
     */
    @Test
    void amonthlyFileDownloadsAsKept() {
        AdminSaftResource resource = newResource();
        FiscalArchive file = new FiscalArchive();
        file.kind = FiscalArchive.Kind.SAFT_MONTHLY;
        file.fileName = "SAFT.xml";
        file.content = new byte[] {1, 2};
        FiscalArchive other = new FiscalArchive();
        other.kind = FiscalArchive.Kind.TAX_AUDIT_EXTRACTS;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(file);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(other);
            Response response = resource.download(1L);
            assertEquals(200, response.getStatus());
            assertArrayEquals(new byte[] {1, 2}, (byte[]) response.getEntity());
            assertEquals("attachment; filename=\"SAFT.xml\"", response.getHeaderString("Content-Disposition"));
            assertEquals(404, resource.download(2L).getStatus());
            assertEquals(404, resource.download(3L).getStatus());
        }
        assertEquals(404, resource.download(null).getStatus());
    }

    /**
     * A monthly file is declared transmitted by who is signed in, a dash when
     * nobody is named; an unknown file or another kind is refused.
     */
    @Test
    void amonthlyFileIsDeclaredTransmitted() {
        AdminSaftResource resource = newResource();
        FiscalArchive file = mock(FiscalArchive.class);
        file.kind = FiscalArchive.Kind.SAFT_MONTHLY;
        FiscalArchive other = new FiscalArchive();
        other.kind = FiscalArchive.Kind.TAX_AUDIT_REPORTS;
        when(resource.currentUser.getName()).thenReturn("admin", " ");
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "1");
        MultivaluedMap<String, String> wrong = new MultivaluedHashMap<>();
        wrong.add("id", "2");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(file);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(other);
            assertTrue(location(resource.transmitted(form)).contains("déclaré transmis"));
            assertEquals("admin", file.transmittedBy);
            assertNotNull(file.transmittedAt);
            resource.transmitted(form);
            assertEquals("—", file.transmittedBy);
            resource.currentUser = null;
            resource.transmitted(form);
            assertEquals("—", file.transmittedBy);
            assertTrue(location(resource.transmitted(wrong)).contains("Fichier SAF-T inconnu."));
            verify(file, org.mockito.Mockito.times(3)).persist();
        }
        assertTrue(location(resource.transmitted(new MultivaluedHashMap<>())).contains("Fichier SAF-T inconnu."));
    }

    /** The failed sends are relaunched and counted. */
    @Test
    void thefailedSendsAreRelaunched() {
        AdminSaftResource resource = newResource();
        when(resource.saftStreamService.relaunch()).thenReturn(3);
        assertTrue(location(resource.relaunch()).contains("3 envoi(s) SAF-T relancé(s)."));
    }
}
