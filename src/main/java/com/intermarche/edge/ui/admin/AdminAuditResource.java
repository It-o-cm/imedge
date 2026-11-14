package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.audit.AdminAuditEntry;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The ADMINISTRATION LOG screen (BO-01-01-01, BO-01-01-03): every
 * modifying back-office gesture and every refusal the access filter wrote,
 * filtered by period, account and screen, and extractable as CSV.
 *
 * <p>Read-only on purpose: a log that could be corrected from the log would
 * not be one.
 */
@Path("/admin/audit")
public class AdminAuditResource {

    private static final Logger LOGGER = Logger.getLogger(AdminAuditResource.class);

    /** How a moment of the log reads. */
    static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** The log template. */
    @Inject
    @Location("admin-audit")
    Template adminAudit;

    /** One log entry as the screen lists it. */
    public static class AuditRow {

        /** When, as the operator reads it. */
        public String moment;

        /** The account's login. */
        public String loginName;

        /** The account's full name, or an empty string. */
        public String fullName;

        /** The screen's label. */
        public String feature;

        /** The option's label. */
        public String option;

        /** The HTTP method and path. */
        public String path;

        /** The record named, or an empty string. */
        public String target;

        /** How it ended. */
        public String outcome;

        /** Whether it ended as asked. */
        public boolean done;

        /** The notice or the reason, or an empty string. */
        public String detail;

        /** The validator's login, or an empty string. */
        public String validator;

        /** The node's point of sale, or an empty string. */
        public String pdv;
    }

    /**
     * Lists the log.
     *
     * @param from the first day kept, or blank for the last seven days
     * @param to the last day kept, or blank for today
     * @param login the account kept, or blank for every account
     * @param feature the screen kept, or blank for every screen
     * @param page the 1-based page number
     * @return the log page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.AUDIT)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("from") String from, @QueryParam("to") String to,
                                 @QueryParam("login") String login, @QueryParam("feature") String feature,
                                 @QueryParam("page") @DefaultValue("1") int page) {
        LOGGER.info("Entering method list");
        LocalDate first = firstDay(from);
        LocalDate last = lastDay(to);
        List<AuditRow> rows = rows(first, last, login, feature);
        String query = AdminForms.pageParam("from", first.toString()) + AdminForms.pageParam("to", last.toString())
                + AdminForms.pageParam("login", login) + AdminForms.pageParam("feature", feature);
        LOGGER.info("Exiting method list");
        return adminAudit.data("page", AdminForms.page(rows, page))
                .data("baseQuery", query)
                .data("from", first.toString())
                .data("to", last.toString())
                .data("login", blank(login) ? "" : login.trim())
                .data("feature", blank(feature) ? "" : feature.trim())
                .data("features", Feature.values());
    }

    /**
     * Extracts the filtered log as CSV.
     *
     * @param from the first day kept, or blank for the last seven days
     * @param to the last day kept, or blank for today
     * @param login the account kept, or blank for every account
     * @param feature the screen kept, or blank for every screen
     * @return the CSV file
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.AUDIT)
    @Produces("text/csv; charset=UTF-8")
    @Transactional
    public Response export(@QueryParam("from") String from, @QueryParam("to") String to,
                           @QueryParam("login") String login, @QueryParam("feature") String feature) {
        LOGGER.info("Entering method export");
        LocalDate first = firstDay(from);
        LocalDate last = lastDay(to);
        StringBuilder csv = new StringBuilder(
                "DATE;IDENTIFIANT;NOM;ECRAN;OPTION;REQUETE;CIBLE;ISSUE;DETAIL;VALIDEUR;PDV\n");
        for (AuditRow row : rows(first, last, login, feature)) {
            csv.append(cell(row.moment)).append(cell(row.loginName)).append(cell(row.fullName))
                    .append(cell(row.feature)).append(cell(row.option)).append(cell(row.path))
                    .append(cell(row.target)).append(cell(row.outcome)).append(cell(row.detail))
                    .append(cell(row.validator)).append(row.pdv.replace(";", ",")).append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition",
                        "attachment; filename=\"journal-administration-" + first + "-" + last + ".csv\"")
                .build();
    }

    /**
     * Reads and projects the filtered entries.
     *
     * @param first the first day kept
     * @param last the last day kept
     * @param login the account kept, or blank
     * @param feature the screen kept, or blank
     * @return the rows, newest first
     */
    List<AuditRow> rows(LocalDate first, LocalDate last, String login, String feature) {
        List<AuditRow> rows = new ArrayList<>();
        for (AdminAuditEntry entry : AdminAuditEntry.search(first.atStartOfDay(), last.plusDays(1).atStartOfDay(),
                blank(login) ? null : login.trim(), blank(feature) ? null : feature.trim())) {
            rows.add(row(entry));
        }
        return rows;
    }

    /**
     * Projects one entry onto its row.
     *
     * @param entry the entry
     * @return the row
     */
    AuditRow row(AdminAuditEntry entry) {
        AuditRow row = new AuditRow();
        row.moment = entry.occurredAt == null ? "" : MOMENT.format(entry.occurredAt);
        row.loginName = entry.loginName;
        row.fullName = orEmpty(entry.fullName);
        Feature feature = Feature.fromKey(entry.featureKey);
        row.feature = feature == null ? entry.featureKey : feature.getLabel();
        CrudOption option = optionOf(entry.option);
        row.option = option == null ? orEmpty(entry.option) : option.getLabel();
        row.path = entry.requestPath;
        row.target = orEmpty(entry.targetRef);
        row.outcome = outcomeLabel(entry.outcome);
        row.done = entry.outcome == AdminAuditEntry.Outcome.DONE;
        row.detail = orEmpty(entry.detail);
        row.validator = orEmpty(entry.validatorLogin);
        row.pdv = orEmpty(entry.pdv);
        return row;
    }

    /**
     * Names an outcome for the operator.
     *
     * @param outcome the outcome, possibly null
     * @return its French label
     */
    static String outcomeLabel(AdminAuditEntry.Outcome outcome) {
        if (outcome == null) {
            return "";
        }
        return switch (outcome) {
            case DONE -> "Effectué";
            case REFUSED -> "Refusé par l'écran";
            case DENIED -> "Droit refusé";
            case VALIDATION -> "Validation";
        };
    }

    /**
     * Resolves an option from its stored name.
     *
     * @param name the name, possibly null
     * @return the option, or null when unknown
     */
    private static CrudOption optionOf(String name) {
        for (CrudOption option : CrudOption.values()) {
            if (option.name().equals(name)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Reads the first day of the period.
     *
     * @param raw the posted day, possibly blank or malformed
     * @return the day, or seven days ago
     */
    LocalDate firstDay(String raw) {
        LocalDate day = AdminForms.day(raw);
        return day == null ? LocalDate.now().minusDays(7) : day;
    }

    /**
     * Reads the last day of the period.
     *
     * @param raw the posted day, possibly blank or malformed
     * @return the day, or today
     */
    LocalDate lastDay(String raw) {
        LocalDate day = AdminForms.day(raw);
        return day == null ? LocalDate.now() : day;
    }

    /**
     * Writes one CSV cell followed by its separator.
     *
     * @param value the value, possibly null
     * @return the cell, its separators replaced
     */
    private static String cell(String value) {
        return (value == null ? "" : value.replace(";", ",").replace("\n", " ")) + ";";
    }

    /**
     * Tells whether a parameter is absent.
     *
     * @param value the parameter, possibly null
     * @return true for null or blank
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Replaces null by an empty string.
     *
     * @param value the value, possibly null
     * @return the value, or an empty string
     */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
