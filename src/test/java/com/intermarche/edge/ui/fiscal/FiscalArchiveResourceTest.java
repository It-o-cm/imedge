package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.fiscal.FiscalArchive;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.fiscal.FiscalArchiveService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link FiscalArchiveResource} (BO-05-01-01): the archives
 * listed, a period produced, an archive downloaded.
 */
class FiscalArchiveResourceTest {

    /**
     * Builds the resource with its collaborators mocked.
     *
     * @return the resource
     */
    private FiscalArchiveResource resource() {
        FiscalArchiveResource resource = new FiscalArchiveResource();
        resource.adminFiscalArchives = mock(Template.class);
        resource.fiscalArchiveService = mock(FiscalArchiveService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.currentUser = mock(CurrentUser.class);
        return resource;
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
     * The page lists the archives and proposes the month just ended.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thepageListsTheArchives() {
        FiscalArchiveResource resource = resource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.adminFiscalArchives.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        when(resource.fiscalArchiveService.pdvNumber()).thenReturn("07039");
        when(resource.posSettingsService.fiscalArchiveAuto()).thenReturn(true);
        FiscalArchive archive = new FiscalArchive();
        PanacheQuery<FiscalArchive> query = mock(PanacheQuery.class);
        when(query.page(0, FiscalArchiveResource.SHOWN)).thenReturn(query);
        when(query.list()).thenReturn(List.of(archive));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("kind in ?1 order by generatedAt desc, id desc",
                    List.of(FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, FiscalArchive.Kind.TAX_AUDIT_REPORTS)))
                    .thenReturn(query);
            assertSame(view, resource.list(null, true));
        }
        verify(resource.adminFiscalArchives).data("archives", List.of(archive));
        LocalDate today = LocalDate.now();
        verify(view).data("from", today.minusMonths(1).withDayOfMonth(1).toString());
        verify(view).data("to", today.withDayOfMonth(1).minusDays(1).toString());
        verify(view).data("pdv", "07039");
        verify(view).data("automatic", true);
    }

    /**
     * A period is produced under the signed-in name; a period inverted, to
     * come or unreadable is refused.
     */
    @Test
    void aperiodIsProduced() {
        FiscalArchiveResource resource = resource();
        when(resource.currentUser.getName()).thenReturn("controleur");
        FiscalArchive extracts = new FiscalArchive();
        extracts.fileName = "TaxAuditExtracts_07039_20260930.zip";
        FiscalArchive reports = new FiscalArchive();
        reports.fileName = "TaxAuditReports_07039_20260930.zip";
        when(resource.fiscalArchiveService.generate(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                "controleur", false)).thenReturn(List.of(extracts, reports));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("from", "2026-09-01");
        form.add("to", "2026-09-30");
        assertTrue(location(resource.generate(form)).contains(
                "Archives produites : TaxAuditExtracts_07039_20260930.zip, TaxAuditReports_07039_20260930.zip"));
        resource.currentUser = null;
        when(resource.fiscalArchiveService.generate(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null,
                false)).thenReturn(List.of(extracts, reports));
        assertTrue(location(resource.generate(form)).contains("noticeOk=true"));
        String refusal = "Période invalide";
        MultivaluedMap<String, String> inverted = new MultivaluedHashMap<>();
        inverted.add("from", "2026-09-30");
        inverted.add("to", "2026-09-01");
        assertTrue(location(resource.generate(inverted)).contains(refusal));
        MultivaluedMap<String, String> future = new MultivaluedHashMap<>();
        future.add("from", "2026-09-01");
        future.add("to", LocalDate.now().plusDays(1).toString());
        assertTrue(location(resource.generate(future)).contains(refusal));
        MultivaluedMap<String, String> noEnd = new MultivaluedHashMap<>();
        noEnd.add("from", "2026-09-01");
        assertTrue(location(resource.generate(noEnd)).contains(refusal));
        assertTrue(location(resource.generate(new MultivaluedHashMap<>())).contains(refusal));
    }

    /**
     * An archive downloads as produced, a zip or an XML by its kind; an
     * unknown one is not found.
     */
    @Test
    void anArchiveDownloadsAsProduced() {
        FiscalArchiveResource resource = resource();
        FiscalArchive zip = new FiscalArchive();
        zip.kind = FiscalArchive.Kind.TAX_AUDIT_REPORTS;
        zip.fileName = "a.zip";
        zip.content = new byte[] {1};
        FiscalArchive xml = new FiscalArchive();
        xml.kind = FiscalArchive.Kind.SAFT_MONTHLY;
        xml.fileName = "a.xml";
        xml.content = new byte[] {2};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(zip);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(xml);
            assertEquals("application/zip", resource.download(1L).getMediaType().toString());
            assertEquals("application/xml", resource.download(2L).getMediaType().toString());
            assertEquals("attachment; filename=\"a.zip\"", resource.download(1L).getHeaderString("Content-Disposition"));
            assertEquals(404, resource.download(3L).getStatus());
        }
        assertEquals(404, resource.download(null).getStatus());
    }
}
