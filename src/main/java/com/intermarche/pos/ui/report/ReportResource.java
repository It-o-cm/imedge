package com.intermarche.pos.ui.report;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
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

    /**
     * Lists every report, grouped by family.
     *
     * @return the catalogue page
     */
    @GET
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list() {
        LOGGER.info("Entering method list");
        LOGGER.info("Exiting method list");
        return adminReports
                .data("families", reportService.catalog().families())
                .data("catalog", reportService.catalog());
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
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance show(@PathParam("code") String code,
                                 @QueryParam("page") Integer page,
                                 @Context UriInfo uriInfo) {
        LOGGER.info("Entering method show with code: " + code + ", page: " + page);
        ReportDefinition definition = required(code);
        ReportCriteria criteria = ReportCriteria.fromParams(uriInfo.getQueryParameters());
        ReportData data = reportService.run(definition, criteria);
        int pageCount = Math.max(1, (data.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = page == null ? 1 : Math.min(Math.max(page, 1), pageCount);
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, data.size());
        LOGGER.info("Exiting method show");
        return adminReport
                .data("definition", definition)
                .data("criteria", criteria)
                .data("data", data)
                .data("columns", definition.columns())
                .data("pageRows", render(data, data.rows.subList(from, to)))
                .data("totalCells", render(data, List.of(data.totals)).get(0))
                .data("page", current)
                .data("pageCount", pageCount)
                .data("hasPrev", current > 1)
                .data("hasNext", current < pageCount)
                .data("prevPage", current - 1)
                .data("nextPage", current + 1)
                .data("heading", ReportHeading.period(criteria))
                .data("chart", ReportChart.svg(data))
                .data("hasChart", ReportChart.available(data))
                .data("periods", ReportPeriod.values())
                .data("baseQuery", baseQuery(uriInfo));
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
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Response export(@PathParam("code") String code,
                           @QueryParam("format") String format,
                           @Context UriInfo uriInfo) {
        LOGGER.info("Entering method export with code: " + code + ", format: " + format);
        ReportDefinition definition = required(code);
        ReportWriter writer = reportService.writer(format);
        if (writer == null) {
            throw new NotFoundException("Format de rapport inconnu : " + format);
        }
        ReportCriteria criteria = ReportCriteria.fromParams(uriInfo.getQueryParameters());
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
            throw new NotFoundException("Rapport inconnu : " + code);
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
            if ("page".equals(entry.getKey())) {
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
