package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.fiscal.EReport;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.fiscal.EReportingService;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link EReportingResource} (BO-05-01-03): the periods listed,
 * a period extracted, transmitted and downloaded.
 */
class EReportingResourceTest {

    /**
     * Builds the resource with its collaborators mocked.
     *
     * @return the resource
     */
    private EReportingResource resource() {
        EReportingResource resource = new EReportingResource();
        resource.adminEReporting = mock(Template.class);
        resource.eReportingService = mock(EReportingService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.posSettingsService.ereportingPeriod()).thenReturn("MONTH");
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
     * Builds a posted form.
     *
     * @param pairs the field names and values, alternately
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    /**
     * The page lists the periods and proposes the last one over.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thepageListsThePeriods() {
        EReportingResource resource = resource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.adminEReporting.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        PanacheQuery<EReport> query = mock(PanacheQuery.class);
        when(query.page(0, EReportingResource.SHOWN)).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by periodStart desc")).thenReturn(query);
            resource.list(null, true);
        }
        LocalDate first = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        verify(view).data("start", first.toString());
        verify(view).data("end", first.withDayOfMonth(first.lengthOfMonth()).toString());
        verify(view).data("periodicity", "MONTH");
    }

    /**
     * A period is extracted and its transactions counted; an accepted one is
     * refused, and so is an unusable period.
     */
    @Test
    void aperiodIsExtracted() {
        EReportingResource resource = resource();
        EReport report = new EReport();
        report.transactionCount = 42;
        when(resource.eReportingService.extract(eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 10)), any()))
                .thenReturn(report, (EReport) null);
        MultivaluedMap<String, String> form = form("start", "2026-10-01", "end", "2026-10-10");
        assertTrue(location(resource.extract(form)).contains("Période extraite : 42 transaction(s)."));
        assertTrue(location(resource.extract(form)).contains("déjà acceptée"));
        assertTrue(location(resource.extract(form("start", "2026-10-10", "end", "2026-10-01")))
                .contains("Période invalide."));
        assertTrue(location(resource.extract(form("start", "2026-10-10"))).contains("Période invalide."));
        assertTrue(location(resource.extract(form())).contains("Période invalide."));
    }

    /**
     * A period is transmitted, or says why not; an unknown or accepted one is
     * refused.
     */
    @Test
    void aperiodIsTransmitted() {
        EReportingResource resource = resource();
        EReport report = new EReport();
        report.message = "Plateforme de dématérialisation non configurée ou injoignable.";
        EReport accepted = new EReport();
        accepted.status = EReport.Status.ACCEPTED;
        when(resource.eReportingService.transmit(eq(report), any())).thenReturn(true, false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(report);
            panache.when(() -> PanacheEntityBase.findById(2L)).thenReturn(accepted);
            assertTrue(location(resource.transmit(form("id", "1"))).contains("Période transmise."));
            assertTrue(location(resource.transmit(form("id", "1"))).contains("Transmission en échec : Plateforme"));
            assertTrue(location(resource.transmit(form("id", "2"))).contains("déjà acceptée"));
            assertTrue(location(resource.transmit(form("id", "3"))).contains("Transmission inconnue"));
        }
        assertTrue(location(resource.transmit(form())).contains("Transmission inconnue"));
    }

    /**
     * The transmitted document downloads; an unknown one is not found.
     */
    @Test
    void thedocumentDownloads() {
        EReportingResource resource = resource();
        EReport report = new EReport();
        report.periodStart = LocalDate.of(2026, 10, 1);
        report.periodEnd = LocalDate.of(2026, 10, 10);
        report.payload = "<EReporting/>";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(report);
            Response response = resource.document(1L);
            assertArrayEquals("<EReporting/>".getBytes(StandardCharsets.UTF_8), (byte[]) response.getEntity());
            assertEquals("attachment; filename=\"e-reporting-2026-10-01-2026-10-10.xml\"",
                    response.getHeaderString("Content-Disposition"));
            assertEquals(404, resource.document(2L).getStatus());
        }
        assertEquals(404, resource.document(null).getStatus());
    }
}
