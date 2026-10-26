package com.intermarche.edge.ui.report;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The back office's REPORTS surface ({@code /admin/reports}): the catalogue of
 * reports, one screen that runs any of them, and the same report downloadable in
 * the three demanded formats (BO-06-01-01/02/03) and drawn as a chart
 * (BO-06-01-04).
 *
 * <p>ONE resource for every report, not one per report. What changes from a
 * report to the next is its columns and its query, and both live in its
 * {@link ReportDefinition}; the period, the pager, the chart and the three
 * exports are the frame's, identical everywhere. That is what makes a new report
 * a bean rather than a screen, an export route and a set of formatting
 * decisions.
 *
 * <p>Paging is the SCREEN's only: an export runs the report whole, because an
 * export of the visible page is not an export.
 */
@Path("/admin/reports")
public class ReportResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReportResource.class);

    /** The number of rows one screen page shows. */
    static final int PAGE_SIZE = 100;

    /** The report catalogue template. */
    @Inject @Location("admin-reports") Template adminReports;

    /** The single report template. */
    @Inject @Location("admin-report") Template adminReport;

    /** The report runner. */
    @Inject ReportService reportService;

    /** The planned extractions (BO-06-04-06). */
    @Inject ReportScheduleService scheduleService;

    /** The reprint of invoices as duplicates (BO-06-04-03). */
    @Inject InvoiceDuplicateService invoiceDuplicateService;

    /** Translates the chart's computed French texts into the page's language. */
    @Inject com.intermarche.edge.ui.admin.BackOfficeMessages i18n;

    /** The signed-in operator, whose hidden report types are left out (BO-01-04-08). */
    @Inject io.quarkus.security.identity.SecurityIdentity identity;

    /** One family of the catalogue and the report types the operator shows. */
    public static class ReportGroup {

        /** The family. */
        public final ReportFamily family;

        /** The family's report types not hidden by the operator. */
        public final List<ReportDefinition> reports;

        /**
         * Builds a family group.
         *
         * @param family the family
         * @param reports its shown report types
         */
        public ReportGroup(ReportFamily family, List<ReportDefinition> reports) {
            this.family = family;
            this.reports = reports;
        }
    }

    /**
     * Lists every report, grouped by family, without the report types the
     * signed-in operator chose to hide (BO-01-04-08).
     *
     * @return the catalogue page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.REPORT)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        LOGGER.info("Entering method list");
        java.util.Set<String> hidden = hiddenTokens();
        List<ReportGroup> groups = new java.util.ArrayList<>();
        for (ReportFamily family : reportService.catalog().families()) {
            List<ReportDefinition> shown = new java.util.ArrayList<>();
            for (ReportDefinition definition : reportService.catalog().of(family)) {
                if (!hidden.contains(com.intermarche.edge.domain.people.UserFavorite.REPORT + definition.code())) {
                    shown.add(definition);
                }
            }
            if (!shown.isEmpty()) {
                groups.add(new ReportGroup(family, shown));
            }
        }
        LOGGER.info("Exiting method list");
        return adminReports
                .data("groups", groups)
                .data("catalog", reportService.catalog());
    }

    /**
     * Reads the signed-in operator's display preferences.
     *
     * @return the tokens, empty when nobody or an unknown account is signed in
     */
    java.util.Set<String> hiddenTokens() {
        if (identity == null || identity.isAnonymous()) {
            return java.util.Set.of();
        }
        com.intermarche.edge.domain.people.Employee operator =
                com.intermarche.edge.domain.people.Employee.findLogin(identity.getPrincipal().getName());
        return operator == null || operator.id == null
                ? java.util.Set.of()
                : com.intermarche.edge.domain.people.UserFavorite.hiddenKeys(operator.id);
    }

    /**
     * Runs one report and shows the page of results asked for.
     *
     * @param code the report's code
     * @param page the 1-based page asked for, null for the first
     * @param uriInfo the request URI carrying the edition parameters
     * @return the report page
     */
    @GET
    @Path("/{code}")
    @Authenticated
    @FeatureAccess(value = Feature.REPORT)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance show(@PathParam("code") String code,
                                 @QueryParam("page") Integer page,
                                 @Context UriInfo uriInfo) {
        LOGGER.info("Entering method show with code: " + code + ", page: " + page);
        ReportDefinition definition = required(code);
        ReportCriteria criteria = ReportCriteria.fromParams(uriInfo.getQueryParameters());
        // BO-06-01-02: the figures a report words itself follow the page's language.
        criteria.language = i18n.getLanguage();
        reportService.prepare(criteria);
        ReportData data = reportService.run(definition, criteria);
        int pageCount = Math.max(1, (data.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = page == null ? 1 : Math.min(Math.max(page, 1), pageCount);
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, data.size());
        java.util.Set<ReportFilter> filters = definition.filters();
        ReportChart.Type chartType = ReportChart.Type.of(criteria.chart);
        boolean hasChart = ReportChart.available(data);
        LOGGER.info("Exiting method show");
        return adminReport
                .data("definition", definition)
                .data("criteria", criteria)
                .data("data", data)
                .data("columns", definition.columns())
                .data("pageRows", render(data, data.rows.subList(from, to)))
                .data("pageMarks", marks(data.rows.subList(from, to)))
                .data("pageLinks", links(definition, data.rows.subList(from, to)))
                .data("totalCells", render(data, List.of(data.totals)).get(0))
                .data("page", current)
                .data("pageCount", pageCount)
                .data("hasPrev", current > 1)
                .data("hasNext", current < pageCount)
                .data("prevPage", current - 1)
                .data("nextPage", current + 1)
                .data("heading", ReportHeading.period(criteria, i18n))
                .data("preamble", definition.preamble(criteria))
                .data("chart", hasChart ? ReportChart.svg(data, chartType, criteria.series, i18n::text) : "")
                .data("hasChart", hasChart)
                .data("chartType", chartType)
                .data("chartTypes", ReportChart.Type.values())
                .data("chartSeries", hasChart ? seriesKeys(data, criteria) : List.of())
                .data("plottable", hasChart ? ReportChart.plottable(data) : List.of())
                .data("periods", ReportPeriod.values())
                .data("showTerminals", filters.contains(ReportFilter.TERMINALS))
                .data("showIsland", filters.contains(ReportFilter.ISLAND))
                .data("showCashiers", filters.contains(ReportFilter.CASHIERS))
                .data("showHours", filters.contains(ReportFilter.HOURS))
                .data("showArticles", filters.contains(ReportFilter.ARTICLES))
                .data("terminalChoices", filters.contains(ReportFilter.TERMINALS)
                        ? com.intermarche.edge.domain.store.CheckoutPoint.listAllOrdered() : List.of())
                .data("islands", filters.contains(ReportFilter.ISLAND)
                        ? com.intermarche.edge.domain.store.CheckoutIsland.listActive() : List.of())
                .data("cashierChoices", filters.contains(ReportFilter.CASHIERS) ? cashierChoices() : List.of())
                .data("selections", filters.contains(ReportFilter.ARTICLES)
                        ? com.intermarche.edge.domain.catalog.ArticleSelection.listForPdv(reportService.pdv())
                        : List.of())
                .data("familyText", String.join(";", criteria.families))
                .data("eanText", String.join(";", criteria.eans))
                .data("schedules", com.intermarche.edge.domain.session.ReportSchedule.ofReport(definition.code()))
                .data("formats", List.of("pdf", "xlsx", "csv"))
                .data("notice", notice(uriInfo))
                .data("baseQuery", baseQuery(uriInfo));
    }

    /**
     * Lists the cashiers the report form offers: every employee holding a
     * badge, by name.
     *
     * @return the employees, possibly empty
     */
    List<com.intermarche.edge.domain.people.Employee> cashierChoices() {
        return com.intermarche.edge.domain.people.Employee
                .list("badgeId is not null order by lastName, firstName");
    }

    /**
     * Names the series the chart draws, so the form checks them.
     *
     * @param data the executed report
     * @param criteria the edition parameters
     * @return the keys of the plotted columns
     */
    private List<String> seriesKeys(ReportData data, ReportCriteria criteria) {
        List<String> keys = new java.util.ArrayList<>();
        for (int index : ReportChart.series(data, criteria.series)) {
            keys.add(data.definition.columns().get(index).key());
        }
        return keys;
    }

    /**
     * Reads the action of each row of a slice, empty where a row has none.
     *
     * @param definition the report
     * @param rows the rows of the page
     * @return one address per row, an empty text for none
     */
    private List<String> links(ReportDefinition definition, List<List<Object>> rows) {
        List<String> links = new java.util.ArrayList<>();
        for (List<Object> row : rows) {
            String link = ReportData.isSubtotal(row) ? null : definition.rowLink(row);
            links.add(link == null ? "" : link);
        }
        return links;
    }

    /**
     * Flags the subtotal rows of a slice, so the screen draws them as such.
     *
     * @param rows the rows of the page
     * @return one flag per row, true for a subtotal
     */
    private List<Boolean> marks(List<List<Object>> rows) {
        List<Boolean> marks = new java.util.ArrayList<>();
        for (List<Object> row : rows) {
            marks.add(ReportData.isSubtotal(row));
        }
        return marks;
    }

    /**
     * Reads the notice a redirect carried back to the report screen.
     *
     * @param uriInfo the request URI
     * @return the notice, or an empty text
     */
    private String notice(UriInfo uriInfo) {
        List<String> values = uriInfo.getQueryParameters().get("notice");
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    /**
     * Plans an extraction of the report (BO-06-04-06): the edition parameters
     * the screen showed, the format, the moment it is due. The node edits it
     * when the moment comes and keeps the document for download here.
     *
     * @param code the report's code
     * @param form the posted plan: {@code runAt}, {@code format}, {@code query}
     * @return a 303 redirect back to the report with a notice
     */
    @POST
    @Path("/{code}/schedule")
    @Authenticated
    @FeatureAccess(value = Feature.REPORT, option = com.intermarche.edge.domain.people.CrudOption.CREATE)
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response schedule(@PathParam("code") String code, jakarta.ws.rs.core.MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method schedule with code: " + code);
        ReportDefinition definition = required(code);
        String query = form.getFirst("query") == null ? "" : form.getFirst("query");
        java.time.LocalDateTime runAt;
        try {
            runAt = java.time.LocalDateTime.parse(String.valueOf(form.getFirst("runAt")).trim());
        } catch (java.time.format.DateTimeParseException malformed) {
            LOGGER.info("Exiting method schedule: bad moment");
            return back(definition, query, "Moment d'extraction invalide.");
        }
        String format = reportService.writer(form.getFirst("format")) == null ? "pdf" : form.getFirst("format").trim();
        // The extraction is written in the language of the operator who planned it.
        String planned = query + (query.isEmpty() || query.endsWith("&") ? "" : "&") + "lang=" + i18n.getLanguage();
        scheduleService.plan(definition.code(), planned, format, runAt, operatorName());
        LOGGER.info("Exiting method schedule");
        return back(definition, query, "Extraction planifiée.");
    }

    /**
     * Downloads the document a planned extraction produced.
     *
     * @param id the edition's identifier
     * @return the document, as an attachment
     */
    @GET
    @Path("/runs/{id}")
    @Authenticated
    @FeatureAccess(value = Feature.REPORT)
    public Response run(@PathParam("id") Long id) {
        LOGGER.info("Entering method run with id: " + id);
        com.intermarche.edge.domain.session.ScheduledReportRun run =
                com.intermarche.edge.domain.session.ScheduledReportRun.findById(id);
        if (run == null || run.content == null) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity("Édition introuvable : " + id).type(MediaType.TEXT_PLAIN).build());
        }
        ReportWriter writer = reportService.writer(run.format);
        String type = writer == null ? MediaType.APPLICATION_OCTET_STREAM : writer.contentType();
        LOGGER.info("Exiting method run");
        return Response.ok(run.content, type)
                .header("Content-Disposition", "attachment; filename=\"" + run.reportCode + "-" + run.id
                        + "." + run.format + "\"").build();
    }

    /**
     * Reprints an invoice or a delivery note as a DUPLICATE (BO-06-04-03).
     *
     * @param number the document's number
     * @return the PDF, as an attachment
     */
    @GET
    @Path("/invoices/duplicate")
    @Authenticated
    @FeatureAccess(value = Feature.REPORT)
    public Response duplicate(@QueryParam("number") String number) {
        LOGGER.info("Entering method duplicate with number: " + number);
        byte[] pdf = invoiceDuplicateService.duplicate(number, i18n.getLanguage());
        if (pdf == null) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity("Document inconnu : " + number).type(MediaType.TEXT_PLAIN).build());
        }
        LOGGER.info("Exiting method duplicate");
        return Response.ok(pdf, "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"duplicata-" + number + ".pdf\"").build();
    }

    /**
     * Redirects back to the report screen with its parameters and a notice.
     *
     * @param definition the report
     * @param query the report's query string
     * @param notice the French notice
     * @return the 303 redirect
     */
    private Response back(ReportDefinition definition, String query, String notice) {
        String separator = query.isEmpty() || query.endsWith("&") ? "" : "&";
        return Response.seeOther(java.net.URI.create("/admin/reports/" + definition.code() + "?" + query
                + separator + "notice=" + encode(notice))).build();
    }

    /**
     * Names the signed-in operator, for the planned extraction.
     *
     * @return the principal's name, or null when nobody is signed in
     */
    private String operatorName() {
        return identity == null || identity.isAnonymous() ? null : identity.getPrincipal().getName();
    }

    /**
     * Runs one report WHOLE and returns it as a download in the asked format.
     *
     * @param code the report's code
     * @param format the format token: {@code csv}, {@code xlsx} or {@code pdf}
     * @param uriInfo the request URI carrying the edition parameters
     * @return the file, as an attachment
     */
    @GET
    @Path("/{code}/export")
    @Authenticated
    @FeatureAccess(value = Feature.REPORT)
    public Response export(@PathParam("code") String code,
                           @QueryParam("format") String format,
                           @Context UriInfo uriInfo) {
        LOGGER.info("Entering method export with code: " + code + ", format: " + format);
        ReportDefinition definition = required(code);
        ReportWriter writer = reportService.writer(format);
        if (writer == null) {
            // Same reason as in required(code): the MESSAGE of a bare
            // NotFoundException never reaches the response, so the refusal is
            // built as the response it must be and names the format refused.
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity("Format de rapport inconnu : " + format)
                    .type(MediaType.TEXT_PLAIN)
                    .build());
        }
        ReportCriteria criteria = ReportCriteria.fromParams(uriInfo.getQueryParameters());
        // BO-06-01-02: the export speaks the page's language, its values stay machine-readable.
        criteria.language = i18n.getLanguage();
        reportService.prepare(criteria);
        ReportData data = reportService.run(definition, criteria);
        byte[] body = writer.write(data, criteria);
        LOGGER.info("Exiting method export");
        return Response.ok(body, writer.contentType())
                .header("Content-Disposition", "attachment; filename=\""
                        + reportService.fileName(definition, criteria, writer) + "\"")
                .build();
    }

    /**
     * Renders a slice of rows into the characters the screen prints.
     *
     * <p>Formatting happens HERE and not in the template: the rule that turns a
     * {@code BigDecimal} into {@code 18 422,55 €} is the same one the CSV and
     * the PDF apply, and a template that re-implemented it would drift from the
     * exports the first time a decimal changed.
     *
     * @param data the executed report, read for its columns
     * @param rows the rows to render
     * @return one list of rendered cells per row
     */
    private List<List<String>> render(ReportData data, List<List<Object>> rows) {
        List<List<String>> rendered = new java.util.ArrayList<>();
        for (List<Object> row : rows) {
            List<String> cells = new java.util.ArrayList<>();
            for (int i = 0; i < data.definition.columns().size(); i++) {
                Object value = i < row.size() ? row.get(i) : null;
                cells.add(ReportFormat.cell(data.definition.columns().get(i), value));
            }
            rendered.add(cells);
        }
        return rendered;
    }

    /**
     * Finds a report or refuses the request.
     *
     * @param code the code carried by the URL
     * @return the report
     */
    private ReportDefinition required(String code) {
        ReportDefinition definition = reportService.catalog().find(code);
        if (definition == null) {
            // The MESSAGE carried by a NotFoundException never reaches the
            // response — Quarkus answers 404 with an empty body — so the
            // refusal is built as the response it must be, and the operator
            // reads which code was not found.
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity("Rapport inconnu : " + code)
                    .type(MediaType.TEXT_PLAIN)
                    .build());
        }
        return definition;
    }

    /**
     * Rebuilds the query string without its page parameter, so the pager appends
     * its own page number while keeping every edition parameter.
     *
     * @param uriInfo the request URI carrying the query parameters
     * @return the encoded prefix, empty or ending with an ampersand
     */
    String baseQuery(UriInfo uriInfo) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : uriInfo.getQueryParameters().entrySet()) {
            if ("page".equals(entry.getKey()) || "notice".equals(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                query.append(encode(entry.getKey())).append('=').append(encode(value)).append('&');
            }
        }
        return query.toString();
    }

    /**
     * URL-encodes one query-string token.
     *
     * @param value the raw token
     * @return the encoded token
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
