package com.intermarche.edge.ui.financial;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialState.PeriodKind;
import com.intermarche.edge.domain.session.FinancialState.StateScope;
import com.intermarche.edge.domain.session.FinancialStateLine.StateSection;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.service.PermissionService;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportService;
import com.intermarche.edge.ui.report.ReportWriter;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The back office's FINANCIAL STATES surface ({@code /admin/financial-states},
 * §6): the list of emitted Z with their anomalies, the edition of an X, one Z
 * read back, the manual emission of a Z, and each section exported through the
 * existing report writers.
 *
 * <p>Rights go through {@link PermissionService} on {@link Feature#FINANCIAL_STATE}
 * (§6): {@link CrudOption#VIEW} to read an X or re-read a Z, {@link CrudOption#CREATE}
 * to emit a Z — emitting is not a reading and does not fall under the read right.
 */
@Path("/admin/financial-states")
public class FinancialStateResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FinancialStateResource.class);

    /** The list of emitted Z. */
    @Inject @Location("admin-financial-states") Template adminFinancialStates;

    /** One financial state, X or Z. */
    @Inject @Location("admin-financial-state") Template adminFinancialState;

    /** The screen-facing financial-state service. */
    @Inject FinancialStateService financialStateService;

    /** The administered authorization layer. */
    @Inject PermissionService permissionService;

    /** The report writers, reused for the exports. */
    @Inject ReportService reportService;

    /** The signed-in operator, whose login drives the permission check. */
    @Inject SecurityIdentity identity;

    /**
     * Lists the emitted Z with their control-variance and late-ticket flags.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page, or a redirect when the account may not view it
     */
    @GET
    @Authenticated
    public Object listPage(@QueryParam("notice") String notice,
                           @QueryParam("noticeOk") @jakarta.ws.rs.DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method listPage");
        Response denied = guard(CrudOption.VIEW);
        if (denied != null) {
            LOGGER.info("Exiting method listPage: denied");
            return denied;
        }
        java.util.List<FinancialState> states = financialStateService.listEmitted();
        java.util.List<Long> flagged = new java.util.ArrayList<>();
        for (FinancialState state : states) {
            if (financialStateService.hasAnomaly(state)) {
                flagged.add(state.id);
            }
        }
        LOGGER.info("Exiting method listPage");
        return adminFinancialStates
                .data("states", states)
                .data("flagged", flagged)
                .data("scopes", StateScope.values())
                .data("periods", PeriodKind.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Reads a throw-away X on the open interval and shows it (§1); a read, so it
     * needs only the view right.
     *
     * @param scope the counter holder name, defaulting to STORE
     * @param period the period name, defaulting to DAY
     * @param cashierLogin the cashier login for a cashier-scope X, or null
     * @return the X page, or a redirect when not permitted
     */
    @GET
    @Path("/x")
    @Authenticated
    public Object readX(@QueryParam("scope") String scope,
                        @QueryParam("period") String period,
                        @QueryParam("cashier") String cashierLogin) {
        LOGGER.info("Entering method readX");
        Response denied = guard(CrudOption.VIEW);
        if (denied != null) {
            LOGGER.info("Exiting method readX: denied");
            return denied;
        }
        StateScope resolvedScope = parseScope(scope);
        FinancialState state = financialStateService.readX(resolvedScope, parsePeriod(period),
                resolvedScope == StateScope.CASHIER ? Employee.findLogin(cashierLogin) : null);
        LOGGER.info("Exiting method readX");
        return adminFinancialState.data("state", state).data("sections", StateSection.values());
    }

    /**
     * Shows one emitted Z, read back from the frozen document.
     *
     * @param id the state id
     * @return the Z page, a redirect when not permitted, or 404 when unknown
     */
    @GET
    @Path("/{id}")
    @Authenticated
    public Object show(@PathParam("id") Long id) {
        LOGGER.info("Entering method show with id: " + id);
        Response denied = guard(CrudOption.VIEW);
        if (denied != null) {
            LOGGER.info("Exiting method show: denied");
            return denied;
        }
        FinancialState state = financialStateService.byId(id);
        if (state == null) {
            LOGGER.info("Exiting method show: not found");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method show");
        return adminFinancialState.data("state", state).data("sections", StateSection.values());
    }

    /**
     * Emits a Z manually (§4). Emitting is a creation, not a reading, so it needs
     * the create right.
     *
     * @param scope the counter holder name
     * @param period the period name
     * @param cashierLogin the cashier login for a cashier-scope Z, or null
     * @return a redirect to the emitted Z, or a refusal
     */
    @POST
    @Path("/emit")
    @Authenticated
    public Response emit(@QueryParam("scope") String scope,
                         @QueryParam("period") String period,
                         @QueryParam("cashier") String cashierLogin) {
        LOGGER.info("Entering method emit");
        Response denied = guard(CrudOption.CREATE);
        if (denied != null) {
            LOGGER.info("Exiting method emit: denied");
            return denied;
        }
        StateScope resolvedScope = parseScope(scope);
        Employee emittedBy = Employee.findLogin(identity.getPrincipal().getName());
        FinancialState state = financialStateService.emitManual(resolvedScope, parsePeriod(period),
                resolvedScope == StateScope.CASHIER ? Employee.findLogin(cashierLogin) : null, emittedBy);
        LOGGER.info("Exiting method emit");
        return Response.seeOther(URI.create("/admin/financial-states/" + state.id)).build();
    }

    /**
     * Exports one section of a state through an existing report writer (§6).
     *
     * @param id the state id
     * @param sectionName the section name
     * @param format the format token: csv, xlsx or pdf
     * @return the file as an attachment, a refusal, or 404 when unknown
     */
    @GET
    @Path("/{id}/export")
    @Authenticated
    public Response export(@PathParam("id") Long id,
                           @QueryParam("section") String sectionName,
                           @QueryParam("format") String format) {
        LOGGER.info("Entering method export with section: " + sectionName + ", format: " + format);
        Response denied = guard(CrudOption.VIEW);
        if (denied != null) {
            LOGGER.info("Exiting method export: denied");
            return denied;
        }
        FinancialState state = financialStateService.byId(id);
        StateSection section = parseSection(sectionName);
        ReportWriter writer = reportService.writer(format);
        if (state == null || section == null || writer == null) {
            LOGGER.info("Exiting method export: not found");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        ReportData data = financialStateService.export(state, section);
        byte[] body = writer.write(data, new com.intermarche.edge.ui.report.ReportCriteria());
        LOGGER.info("Exiting method export");
        return Response.ok(body, writer.contentType())
                .header("Content-Disposition", "attachment; filename=\""
                        + data.definition.code() + "." + writer.extension() + "\"")
                .build();
    }

    /**
     * Runs the permission check for the financial-state feature.
     *
     * @param option the option to check
     * @return a redirect when refused, or null when authorized
     */
    private Response guard(CrudOption option) {
        String loginName = identity.getPrincipal().getName();
        if (permissionService.isAllowed(loginName, Feature.FINANCIAL_STATE, option)) {
            return null;
        }
        return Response.seeOther(URI.create("/admin?notice="
                + URLEncoder.encode("Droit refusé : états financiers.", StandardCharsets.UTF_8)
                + "&noticeOk=false")).build();
    }

    /**
     * Parses a scope name, defaulting to the store scope.
     *
     * @param name the posted scope name
     * @return the scope, STORE when absent or unknown
     */
    StateScope parseScope(String name) {
        for (StateScope scope : StateScope.values()) {
            if (scope.name().equalsIgnoreCase(name)) {
                return scope;
            }
        }
        return StateScope.STORE;
    }

    /**
     * Parses a period name, defaulting to the day.
     *
     * @param name the posted period name
     * @return the period, DAY when absent or unknown
     */
    PeriodKind parsePeriod(String name) {
        for (PeriodKind period : PeriodKind.values()) {
            if (period.name().equalsIgnoreCase(name)) {
                return period;
            }
        }
        return PeriodKind.DAY;
    }

    /**
     * Parses a section name, tolerating an unknown value.
     *
     * @param name the posted section name
     * @return the section, or null when the name is unknown
     */
    StateSection parseSection(String name) {
        for (StateSection section : StateSection.values()) {
            if (section.name().equalsIgnoreCase(name)) {
                return section;
            }
        }
        return null;
    }
}
