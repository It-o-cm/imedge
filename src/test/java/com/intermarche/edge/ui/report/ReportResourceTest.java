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
import org.mockito.MockedStatic;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import com.intermarche.edge.domain.catalog.ArticleSelection;
import com.intermarche.edge.domain.catalog.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
        resource.i18n = mock(com.intermarche.edge.ui.admin.BackOfficeMessages.class);
        when(resource.i18n.text(any())).thenAnswer(call -> String.valueOf((Object) call.getArgument(0)));
        when(resource.i18n.getLanguage()).thenReturn("nl");
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
     * list(): renders the catalogue grouped by family; with nobody signed in
     * every report type is shown.
     */
    @Test
    void listRendersCatalogue() {
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        ReportFamily family = ReportFamily.values()[0];
        ReportDefinition shown = definition();
        when(shown.code()).thenReturn("R1");
        when(catalog.families()).thenReturn(List.of(family));
        when(catalog.of(family)).thenReturn(List.of(shown));
        TemplateInstance result = resource.list();
        Assertions.assertSame(instance, result);
        org.mockito.ArgumentCaptor<Object> groups = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(adminReports).data(eq("groups"), groups.capture());
        List<?> list = (List<?>) groups.getValue();
        Assertions.assertEquals(1, list.size());
        ReportResource.ReportGroup group = (ReportResource.ReportGroup) list.get(0);
        Assertions.assertSame(family, group.family);
        Assertions.assertEquals(List.of(shown), group.reports);
        verify(instance).data(eq("catalog"), eq(catalog));
    }

    /**
     * list() without the report types the operator hid (BO-01-04-08): a family
     * whose every type is hidden disappears; an anonymous identity, an unknown
     * account or an account without id hides nothing.
     */
    @Test
    void listLeavesOutTheHiddenReportTypes() {
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        ReportFamily first = ReportFamily.values()[0];
        ReportFamily second = ReportFamily.values()[1];
        ReportDefinition hidden = definition();
        when(hidden.code()).thenReturn("R1");
        ReportDefinition kept = definition();
        when(kept.code()).thenReturn("R2");
        when(catalog.families()).thenReturn(List.of(first, second));
        when(catalog.of(first)).thenReturn(List.of(hidden));
        when(catalog.of(second)).thenReturn(List.of(hidden, kept));
        resource.identity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        java.security.Principal principal = () -> "mcurie";
        when(resource.identity.getPrincipal()).thenReturn(principal);
        com.intermarche.edge.domain.people.Employee marie = new com.intermarche.edge.domain.people.Employee();
        marie.id = 4L;
        io.quarkus.hibernate.orm.panache.PanacheQuery<Object> query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(marie, null, new com.intermarche.edge.domain.people.Employee());
        com.intermarche.edge.domain.people.UserFavorite token = new com.intermarche.edge.domain.people.UserFavorite();
        token.featureKey = "REPORT:R1";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find(anyString(), any(Object[].class)))
                    .thenReturn(query);
            panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.list("employeeId", 4L))
                    .thenReturn(List.of(token));
            resource.list();
            org.mockito.ArgumentCaptor<Object> groups = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(adminReports).data(eq("groups"), groups.capture());
            List<?> list = (List<?>) groups.getValue();
            Assertions.assertEquals(1, list.size());
            Assertions.assertEquals(List.of(kept), ((ReportResource.ReportGroup) list.get(0)).reports);
            Assertions.assertTrue(resource.hiddenTokens().isEmpty());
            Assertions.assertTrue(resource.hiddenTokens().isEmpty());
            when(resource.identity.isAnonymous()).thenReturn(true);
            Assertions.assertTrue(resource.hiddenTokens().isEmpty());
        }
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
        TemplateInstance result = shown("rpt", null, uriInfo(new MultivaluedHashMap<>()));
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
        TemplateInstance result = shown("rpt", 1, uriInfo(new MultivaluedHashMap<>()));
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
        TemplateInstance result = shown("rpt", 2, uriInfo(new MultivaluedHashMap<>()));
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
        org.mockito.ArgumentCaptor<ReportCriteria> criteria = org.mockito.ArgumentCaptor.forClass(ReportCriteria.class);
        verify(writer).write(eq(reportData), criteria.capture());
        Assertions.assertEquals("nl", criteria.getValue().language);
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

    /**
     * Runs {@code show} with the article lists of the page answered by an empty
     * catalog.
     *
     * @param code the report code
     * @param page the page asked for
     * @param uriInfo the request
     * @return the page
     */
    private TemplateInstance shown(String code, Integer page, UriInfo uriInfo) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            return resource.show(code, page, uriInfo);
        }
    }

    /**
     * show(): a report declaring every filter and a chart lists the registers,
     * the islands, the cashiers and the saved lists, draws its chart with the
     * chosen type, flags its subtotal rows, links its rows, carries its notice
     * and lists its planned extractions.
     */
    @Test
    void showDrawsEveryFilterTheChartAndTheRowActions() {
        ReportDefinition definition = definition();
        when(definition.chartColumn()).thenReturn(1);
        when(definition.code()).thenReturn("rpt");
        when(definition.breakKey()).thenReturn("k0");
        when(definition.filters()).thenReturn(java.util.EnumSet.allOf(ReportFilter.class));
        when(definition.rowLink(any())).thenReturn("/x", (String) null);
        when(definition.preamble(any())).thenReturn(List.of("Préambule"));
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("a", new BigDecimal("10.00")));
        rows.add(List.of("b", new BigDecimal("5.00")));
        ReportData reportData = data(definition, rows);
        when(reportService.run(eq(definition), any(ReportCriteria.class))).thenReturn(reportData);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("chart", "line");
        params.add("notice", "Extraction planifiée.");
        params.add("family", "10;20");
        params.add("ean", "300");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            resource.show("rpt", null, uriInfo(params));
        }
        verify(instance).data(eq("hasChart"), eq(true));
        verify(instance).data(eq("chartType"), eq(ReportChart.Type.LINE));
        verify(instance).data(eq("chartSeries"), eq(List.of("k1")));
        verify(instance).data(eq("pageMarks"), eq(List.of(false, true, false, true)));
        verify(instance).data(eq("pageLinks"), eq(List.of("/x", "", "", "")));
        verify(instance).data(eq("showTerminals"), eq(true));
        verify(instance).data(eq("showIsland"), eq(true));
        verify(instance).data(eq("showCashiers"), eq(true));
        verify(instance).data(eq("showHours"), eq(true));
        verify(instance).data(eq("showArticles"), eq(true));
        verify(instance).data(eq("familyText"), eq("10;20"));
        verify(instance).data(eq("eanText"), eq("300"));
        verify(instance).data(eq("notice"), eq("Extraction planifiée."));
        verify(instance).data(eq("preamble"), eq(List.of("Préambule")));
        verify(reportService).prepare(any(ReportCriteria.class));
    }

    /**
     * show(): a report without filters nor chart reads none of the lists and
     * draws nothing, its notice empty even when the parameter is given bare.
     */
    @Test
    void showWithoutFiltersReadsNoList() {
        ReportDefinition definition = definition();
        when(definition.filters()).thenReturn(java.util.EnumSet.noneOf(ReportFilter.class));
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        ReportData single = data(definition, manyRows(1));
        when(reportService.run(eq(definition), any(ReportCriteria.class))).thenReturn(single);
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.put("notice", new java.util.ArrayList<>());
        shown("rpt", null, uriInfo(params));
        verify(instance).data(eq("hasChart"), eq(false));
        verify(instance).data(eq("chart"), eq(""));
        verify(instance).data(eq("chartSeries"), eq(List.of()));
        verify(instance).data(eq("plottable"), eq(List.of()));
        verify(instance).data(eq("terminalChoices"), eq(List.of()));
        verify(instance).data(eq("islands"), eq(List.of()));
        verify(instance).data(eq("cashierChoices"), eq(List.of()));
        verify(instance).data(eq("selections"), eq(List.of()));
        verify(instance).data(eq("notice"), eq(""));
    }

    /**
     * cashierChoices(): the employees holding a badge, by name.
     */
    @Test
    void cashierChoicesListTheBadgedEmployees() {
        com.intermarche.edge.domain.people.Employee jean = new com.intermarche.edge.domain.people.Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.edge.domain.people.Employee
                    .list("badgeId is not null order by lastName, firstName")).thenReturn(List.of(jean));
            Assertions.assertEquals(List.of(jean), resource.cashierChoices());
        }
    }

    /**
     * schedule(): a valid moment plans the extraction in the format posted,
     * named after the operator, and goes back to the report with its query.
     */
    @Test
    void scheduleOfAValidMomentPlansTheExtraction() {
        ReportDefinition definition = definition();
        when(definition.code()).thenReturn("rpt");
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        when(reportService.writer(" xlsx ")).thenReturn(mock(ReportWriter.class));
        resource.scheduleService = mock(ReportScheduleService.class);
        resource.identity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        java.security.Principal principal = () -> "admin";
        when(resource.identity.getPrincipal()).thenReturn(principal);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("runAt", "2026-10-26T06:00");
        form.add("format", " xlsx ");
        form.add("query", "period=WEEK&");
        Response response = resource.schedule("rpt", form);
        Assertions.assertEquals(303, response.getStatus());
        Assertions.assertEquals("/admin/reports/rpt?period=WEEK&notice=Extraction+planifi%C3%A9e.",
                response.getLocation().toString());
        verify(resource.scheduleService).plan("rpt", "period=WEEK&lang=nl", "xlsx",
                java.time.LocalDateTime.of(2026, 10, 26, 6, 0), "admin");
    }

    /**
     * schedule(): an unknown format falls back to the PDF, a query without its
     * trailing separator gets one, and an anonymous operator plans nameless;
     * the planning operator's language always travels with the query.
     */
    @Test
    void scheduleOfAnUnknownFormatPlansAPdf() {
        ReportDefinition definition = definition();
        when(definition.code()).thenReturn("rpt");
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        resource.scheduleService = mock(ReportScheduleService.class);
        resource.identity = mock(io.quarkus.security.identity.SecurityIdentity.class);
        when(resource.identity.isAnonymous()).thenReturn(true);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("runAt", "2026-10-26T06:00");
        form.add("query", "period=DAY");
        Response response = resource.schedule("rpt", form);
        Assertions.assertTrue(response.getLocation().toString().startsWith("/admin/reports/rpt?period=DAY&notice="));
        verify(resource.scheduleService).plan("rpt", "period=DAY&lang=nl", "pdf",
                java.time.LocalDateTime.of(2026, 10, 26, 6, 0), null);
        resource.identity = null;
        resource.schedule("rpt", form);
        verify(resource.scheduleService, org.mockito.Mockito.times(2)).plan("rpt", "period=DAY&lang=nl", "pdf",
                java.time.LocalDateTime.of(2026, 10, 26, 6, 0), null);
        MultivaluedMap<String, String> bare = new MultivaluedHashMap<>();
        bare.add("runAt", "2026-10-26T06:00");
        resource.schedule("rpt", bare);
        verify(resource.scheduleService).plan("rpt", "lang=nl", "pdf",
                java.time.LocalDateTime.of(2026, 10, 26, 6, 0), null);
    }

    /**
     * schedule(): a malformed moment plans nothing and says so, an absent
     * query reading as an empty one.
     */
    @Test
    void scheduleOfAMalformedMomentIsRefused() {
        ReportDefinition definition = definition();
        when(definition.code()).thenReturn("rpt");
        ReportCatalog catalog = mock(ReportCatalog.class);
        when(reportService.catalog()).thenReturn(catalog);
        when(catalog.find("rpt")).thenReturn(definition);
        resource.scheduleService = mock(ReportScheduleService.class);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("runAt", "demain");
        Response response = resource.schedule("rpt", form);
        Assertions.assertEquals("/admin/reports/rpt?notice=Moment+d%27extraction+invalide.",
                response.getLocation().toString());
        org.mockito.Mockito.verifyNoInteractions(resource.scheduleService);
    }

    /**
     * run(): a kept edition downloads with its writer's media type, or as
     * bytes when its format names no writer.
     */
    @Test
    void runDownloadsTheKeptEdition() {
        com.intermarche.edge.domain.session.ScheduledReportRun run =
                new com.intermarche.edge.domain.session.ScheduledReportRun();
        run.id = 7L;
        run.reportCode = "rpt";
        run.format = "csv";
        run.content = new byte[]{1};
        ReportWriter writer = mock(ReportWriter.class);
        when(writer.contentType()).thenReturn("text/csv");
        when(reportService.writer("csv")).thenReturn(writer, (ReportWriter) null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(run);
            Response response = resource.run(7L);
            Assertions.assertEquals("text/csv", response.getMediaType().toString());
            Assertions.assertEquals("attachment; filename=\"rpt-7.csv\"", response.getHeaderString("Content-Disposition"));
            Assertions.assertEquals("application/octet-stream", resource.run(7L).getMediaType().toString());
        }
    }

    /**
     * run(): an unknown edition, or one kept without a document, is not found.
     */
    @Test
    void runOfAMissingEditionIsNotFound() {
        com.intermarche.edge.domain.session.ScheduledReportRun failed =
                new com.intermarche.edge.domain.session.ScheduledReportRun();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(8L)).thenReturn(failed);
            Assertions.assertThrows(NotFoundException.class, () -> resource.run(9L));
            Assertions.assertThrows(NotFoundException.class, () -> resource.run(8L));
        }
    }

    /**
     * duplicate(): a known document downloads its duplicate, an unknown one is
     * not found.
     */
    @Test
    void duplicateDownloadsTheReprint() {
        resource.invoiceDuplicateService = mock(InvoiceDuplicateService.class);
        when(resource.invoiceDuplicateService.duplicate("F1", "nl")).thenReturn(new byte[]{2});
        Response response = resource.duplicate("F1");
        Assertions.assertEquals("application/pdf", response.getMediaType().toString());
        Assertions.assertEquals("attachment; filename=\"duplicata-F1.pdf\"", response.getHeaderString("Content-Disposition"));
        Assertions.assertThrows(NotFoundException.class, () -> resource.duplicate("F2"));
    }

    /**
     * baseQuery(): the one-shot notice is dropped like the page.
     */
    @Test
    void baseQueryDropsTheNotice() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("notice", "x");
        params.add("period", "DAY");
        Assertions.assertEquals("period=DAY&", resource.baseQuery(uriInfo(params)));
    }
}
