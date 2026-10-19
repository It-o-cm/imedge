package com.intermarche.edge.ui.report;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportResource}, the back office's reports surface.
 *
 * <p>Plain JUnit 5 + Mockito: the three injected collaborators (both Qute
 * templates and the {@link ReportService}) are mocked, the report definition and
 * writer are mocked per test, and the frame's static helpers
 * ({@link ReportCriteria}, {@link ReportChart}, {@link ReportHeading},
 * {@link ReportFormat}) run for real over the data the mocks hand back.
 */
class ReportResourceTest {

    /** The resource under test. */
    private ReportResource resource;

    /** The mocked report catalogue template. */
    private Template adminReports;

    /** The mocked single-report template. */
    private Template adminReport;

    /** The mocked report runner. */
    private ReportService reportService;

    /** The template instance every {@code data(...)} call returns. */
    private TemplateInstance instance;

    /**
     * Wires the resource with its three injected mocks and a self-returning
     * template instance, so a chain of {@code data(...)} calls resolves.
     */
    @BeforeEach
    void setUp() {
        adminReports = mock(Template.class);
        adminReport = mock(Template.class);
        reportService = mock(ReportService.class);
        instance = mock(TemplateInstance.class);
        when(instance.data(anyString(), any())).thenReturn(instance);
        when(adminReports.data(anyString(), any())).thenReturn(instance);
        when(adminReport.data(anyString(), any())).thenReturn(instance);
        resource = new ReportResource();
        resource.adminReports = adminReports;
        resource.adminReport = adminReport;
        resource.reportService = reportService;
    }

    /**
     * Builds a mock report definition with the two test columns and a chart
     * turned off, so the frame's chart helper stays a no-op.
     *
     * @return the mocked definition
     */
    private ReportDefinition definition() {
        ReportDefinition definition = mock(ReportDefinition.class);
        when(definition.columns()).thenReturn(List.of(
                ReportColumn.text("k0", "L0"),
                ReportColumn.amount("k1", "L1")));
        when(definition.chartColumn()).thenReturn(-1);
        return definition;
    }

    /**
     * Builds executed report data over a mock definition and the given rows.
     *
     * @param definition the report the rows belong to
     * @param rows the produced rows
     * @return the executed report
     */
    private ReportData data(ReportDefinition definition, List<List<Object>> rows) {
        return new ReportData(definition, rows);
    }

    /**
     * Produces a list of the given count of two-cell numeric rows.
     *
     * @param count the number of rows to produce
     * @return the rows
     */
    private List<List<Object>> manyRows(int count) {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(List.of("r" + i, BigDecimal.ONE));
        }
        return rows;
    }

    /**
     * A mock URI info whose query parameters are the given map.
     *
     * @param params the query parameters to expose
     * @return the mocked URI info
     */
    private UriInfo uriInfo(MultivaluedMap<String, String> params) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(params);
        return uriInfo;
    }

    /**
     * list(): renders the catalogue template with the families and the catalog.
     */
    @Test
    void listRendersCatalogue() {
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.families()).thenReturn(List.of(ReportFamily.values()[0]));
        TemplateInstance result = resource.list();
        Assertions.assertSame(instance, result);
        verify(catalog).families();
        verify(adminReports).data(eq("families"), any());
        verify(instance).data(eq("catalog"), eq(catalog));
    }

    /**
     * show(): first page of a single-page report — page null takes the ternary's
     * null arm, hasPrev and hasNext are both false, and a short row exercises the
     * render ternary's null arm while a full row exercises its value arm.
     */
    @Test
    void showFirstPageSinglePage() {
        ReportDefinition definition = definition();
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("a"));
        rows.add(List.of("b", new BigDecimal("10.00")));
        ReportData reportData = data(definition, rows);
        when(reportService.run(eq(definition), any(ReportCriteria.class))).thenReturn(reportData);
        TemplateInstance result = resource.show("rpt", null, uriInfo(new MultivaluedHashMap<>()));
        Assertions.assertSame(instance, result);
        verify(adminReport).data(eq("definition"), eq(definition));
        verify(instance).data(eq("hasPrev"), eq(false));
        verify(instance).data(eq("hasNext"), eq(false));
        verify(instance).data(eq("page"), eq(1));
        verify(instance).data(eq("pageCount"), eq(1));
    }

    /**
     * show(): first page of a two-page report — current stays 1 so hasPrev is
     * false and hasNext is true, covering the hasNext true arm.
     */
    @Test
    void showFirstPageOfTwo() {
        ReportDefinition definition = definition();
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        ReportData reportData = data(definition, manyRows(101));
        when(reportService.run(eq(definition), any(ReportCriteria.class))).thenReturn(reportData);
        TemplateInstance result = resource.show("rpt", 1, uriInfo(new MultivaluedHashMap<>()));
        Assertions.assertSame(instance, result);
        verify(instance).data(eq("hasPrev"), eq(false));
        verify(instance).data(eq("hasNext"), eq(true));
        verify(instance).data(eq("page"), eq(1));
        verify(instance).data(eq("pageCount"), eq(2));
    }

    /**
     * show(): last page of a two-page report — page provided takes the ternary's
     * non-null arm, current is 2 so hasPrev is true and hasNext is false,
     * covering the hasPrev true arm.
     */
    @Test
    void showLastPageOfTwo() {
        ReportDefinition definition = definition();
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        ReportData reportData = data(definition, manyRows(101));
        when(reportService.run(eq(definition), any(ReportCriteria.class))).thenReturn(reportData);
        TemplateInstance result = resource.show("rpt", 2, uriInfo(new MultivaluedHashMap<>()));
        Assertions.assertSame(instance, result);
        verify(instance).data(eq("hasPrev"), eq(true));
        verify(instance).data(eq("hasNext"), eq(false));
        verify(instance).data(eq("page"), eq(2));
        verify(instance).data(eq("nextPage"), eq(3));
    }

    /**
     * show(): an unknown code makes required() throw, covering its null arm.
     */
    @Test
    void showUnknownReportThrows() {
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("nope")).thenReturn(null);
        UriInfo uriInfo = uriInfo(new MultivaluedHashMap<>());
        Assertions.assertThrows(NotFoundException.class,
                () -> resource.show("nope", null, uriInfo));
    }

    /**
     * export(): a known report and a known format build a 200 attachment
     * response carrying the writer's bytes, content type and file name.
     */
    @Test
    void exportKnownFormatBuildsAttachment() {
        ReportDefinition definition = definition();
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        ReportWriter writer = mock(ReportWriter.class);
        when(reportService.writer("csv")).thenReturn(writer);
        ReportData reportData = data(definition, manyRows(1));
        when(reportService.run(eq(definition), any(ReportCriteria.class))).thenReturn(reportData);
        byte[] bytes = new byte[]{1, 2, 3};
        when(writer.write(eq(reportData), any(ReportCriteria.class))).thenReturn(bytes);
        when(writer.contentType()).thenReturn("text/csv");
        when(reportService.fileName(eq(definition), any(ReportCriteria.class), eq(writer)))
                .thenReturn("rpt-20250101.csv");
        Response response = resource.export("rpt", "csv", uriInfo(new MultivaluedHashMap<>()));
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertSame(bytes, response.getEntity());
        Assertions.assertEquals("attachment; filename=\"rpt-20250101.csv\"",
                response.getHeaderString("Content-Disposition"));
    }

    /**
     * export(): an unknown format yields a null writer, covering the writer-null
     * arm with a NotFoundException.
     */
    @Test
    void exportUnknownFormatThrows() {
        ReportDefinition definition = definition();
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        when(reportService.writer("zip")).thenReturn(null);
        UriInfo uriInfo = uriInfo(new MultivaluedHashMap<>());
        Assertions.assertThrows(NotFoundException.class,
                () -> resource.export("rpt", "zip", uriInfo));
    }

    /**
     * export(): an unknown code makes required() throw before any writer lookup.
     */
    @Test
    void exportUnknownReportThrows() {
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("nope")).thenReturn(null);
        UriInfo uriInfo = uriInfo(new MultivaluedHashMap<>());
        Assertions.assertThrows(NotFoundException.class,
                () -> resource.export("nope", "csv", uriInfo));
    }

    /**
     * baseQuery(): the page parameter is dropped and every other parameter is
     * URL-encoded, covering the page-skip continue and the value loop.
     */
    @Test
    void baseQueryDropsPageAndEncodesRest() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("page", "3");
        params.add("terminal", "A B");
        params.add("terminal", "C");
        String query = resource.baseQuery(uriInfo(params));
        Assertions.assertEquals("terminal=A+B&terminal=C&", query);
    }

    /**
     * baseQuery(): an empty query string yields an empty prefix, covering the
     * outer loop's not-entered arm.
     */
    @Test
    void baseQueryEmptyWhenNoParams() {
        String query = resource.baseQuery(uriInfo(new MultivaluedHashMap<>()));
        Assertions.assertEquals("", query);
    }
}
