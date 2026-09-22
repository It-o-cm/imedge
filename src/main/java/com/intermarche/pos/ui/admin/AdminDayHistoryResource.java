package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashSessionService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DAY HISTORY screen ({@code /admin/safe/days}): the takings of an
 * accounting day broken down by tender ({@code BO-05-02-08}) and the validation
 * of each cashier's movements ({@code BO-05-02-09}).
 *
 * <p>The X and Z reports already state a breakdown by tender — for ONE session.
 * What the requirement asks for is the day: every register consolidated, in one
 * place, with the net takings a shop is expected to confirm before anything is
 * banked. Reading the sessions of the day and summing their reports is what
 * makes that figure the same one the registers produced, rather than a second
 * computation that can disagree with them.
 *
 * <p>The validation is detailed by cashier and includes the floats and the
 * withdrawals, because that is what {@code -09} names: what a cashier did to
 * their drawer, checked one by one, with the transfers that repair a mis-keying
 * left where they happened — at the register ({@code LC-12-10}), which is the
 * only place that knows what was actually in the drawer.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code safe_day} is what puts
 * it in an operator's menu.
 */
@Path("/admin/safe/days")
public class AdminDayHistoryResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminDayHistoryResource.class);

    /** The day-history template. */
    @Inject
    @Location("admin-safe-days")
    Template adminSafeDays;

    /** The consolidation of a session, tender by tender. */
    @Inject
    CashSessionService cashSessionService;

    /**
     * One tender of the day, with what the registers took in it.
     */
    public static class TenderRow {

        /** The tender key, as the registers name it. */
        public final String method;

        /** What the day took in that tender. */
        public final String amount;

        /**
         * Builds a row.
         *
         * @param method the tender key
         * @param amount the amount, two decimals
         */
        public TenderRow(String method, String amount) {
            this.method = method;
            this.amount = amount;
        }

        /**
         * Returns the tender key, for a template.
         *
         * @return the key
         */
        public String getMethod() {
            return method;
        }

        /**
         * Returns the amount, for a template.
         *
         * @return the amount, two decimals
         */
        public String getAmount() {
            return amount;
        }
    }

    /**
     * Shows one accounting day: its sessions, its breakdown by tender and the
     * movements each cashier made.
     *
     * @param day the accounting day, blank meaning today
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the day page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance day(@QueryParam("day") String day,
                                @QueryParam("notice") String notice,
                                @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method day with day: " + day);
        LocalDate examined = AdminForms.day(day) == null ? LocalDate.now() : AdminForms.day(day);
        List<CashSession> sessions = sessionsOf(examined);
        Map<String, BigDecimal> byTender = totalsByTender(sessions);
        LOGGER.info("Exiting method day with " + sessions.size() + " session(s)");
        return adminSafeDays.data("sessions", sessions)
                .data("day", examined.toString())
                .data("tenders", rowsOf(byTender))
                .data("total", format(sum(byTender.values())))
                .data("movements", movementsOf(examined))
                .data("withdrawn", format(withdrawnOf(sessions)))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Validates one cashier's movement of the day ({@code BO-05-02-09}).
     *
     * <p>Validating a drawer movement is an ENDORSEMENT and not a correction:
     * the row is the register's witness of what happened, and the back office
     * says it has checked it by naming the manager who did — which is the same
     * field a large movement endorsed at the till already carries.
     *
     * @param form the posted form
     * @return a 303 back to the day with the notice
     */
    @POST
    @Path("/validate")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response validate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method validate");
        Long id = AdminForms.id(form.getFirst("id"));
        CashMovement movement = id == null ? null : CashMovement.<CashMovement>findById(id);
        if (movement == null) {
            LOGGER.info("Exiting method validate: unknown movement");
            return back(AdminForms.text(form, "day"), "Mouvement inconnu.", false);
        }
        String endorsement = AdminForms.text(form, "endorsedBy");
        if (endorsement == null) {
            LOGGER.info("Exiting method validate: no endorsement");
            return back(AdminForms.text(form, "day"),
                    "Nom ou badge du valideur obligatoire.", false);
        }
        movement.endorsedBy = endorsement;
        movement.persist();
        LOGGER.infof("Mouvement de caisse %s validé par %s", movement.movementUid, endorsement);
        LOGGER.info("Exiting method validate");
        return back(AdminForms.text(form, "day"), "Mouvement validé.", true);
    }

    /**
     * Extracts the day's breakdown by tender as CSV ({@code BO-05-02-08}).
     *
     * @param day the accounting day, blank meaning today
     * @return the CSV file, named after the day
     */
    @GET
    @Path("/export")
    @RolesAllowed("ADMIN")
    @Produces("text/csv; charset=UTF-8")
    public Response export(@QueryParam("day") String day) {
        LOGGER.info("Entering method export");
        LocalDate examined = AdminForms.day(day) == null ? LocalDate.now() : AdminForms.day(day);
        Map<String, BigDecimal> byTender = totalsByTender(sessionsOf(examined));
        StringBuilder csv = new StringBuilder("JOURNEE;MOYEN;MONTANT\n");
        for (Map.Entry<String, BigDecimal> entry : byTender.entrySet()) {
            csv.append(examined).append(';')
                    .append(entry.getKey() == null ? "" : entry.getKey().replace(';', ',')).append(';')
                    .append(format(entry.getValue())).append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition",
                        "attachment; filename=\"journee-" + examined + ".csv\"")
                .build();
    }

    /**
     * The sessions closed on one accounting day.
     *
     * @param day the accounting day
     * @return the closed sessions of that day, by register
     */
    List<CashSession> sessionsOf(LocalDate day) {
        return CashSession.<CashSession>find(
                "status = ?1 and closingDate >= ?2 and closingDate < ?3 order by terminalId",
                CashSession.SessionStatus.CLOSED, day.atStartOfDay(),
                day.plusDays(1).atStartOfDay()).list();
    }

    /**
     * The drawer movements of one accounting day, whichever register they were
     * made on ({@code BO-05-02-09}).
     *
     * @param day the accounting day
     * @return the movements of that day, oldest first
     */
    List<CashMovement> movementsOf(LocalDate day) {
        return CashMovement.<CashMovement>find(
                "movementDate >= ?1 and movementDate < ?2 order by movementDate",
                day.atStartOfDay(), day.plusDays(1).atStartOfDay()).list();
    }

    /**
     * The day's takings, tender by tender ({@code BO-05-02-08}).
     *
     * @param sessions the sessions of the day
     * @return the totals by tender key, in the order the registers report them
     */
    Map<String, BigDecimal> totalsByTender(List<CashSession> sessions) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (CashSession session : sessions) {
            CashSessionService.SessionReport report = cashSessionService.buildReport(session);
            for (Map.Entry<String, BigDecimal> entry : report.totalsByMethod.entrySet()) {
                BigDecimal already = totals.get(entry.getKey());
                BigDecimal added = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
                totals.put(entry.getKey(), already == null ? added : already.add(added));
            }
        }
        return totals;
    }

    /**
     * Turns the totals into printable rows.
     *
     * @param totals the totals by tender key
     * @return the rows, in the same order
     */
    List<TenderRow> rowsOf(Map<String, BigDecimal> totals) {
        List<TenderRow> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            rows.add(new TenderRow(entry.getKey(), format(entry.getValue())));
        }
        return rows;
    }

    /**
     * What the registers withdrew on the day.
     *
     * @param sessions the sessions of the day
     * @return the total withdrawn, never null
     */
    BigDecimal withdrawnOf(List<CashSession> sessions) {
        BigDecimal total = BigDecimal.ZERO;
        for (CashSession session : sessions) {
            total = total.add(session.withdrawnAmount == null
                    ? BigDecimal.ZERO : session.withdrawnAmount);
        }
        return total;
    }

    /**
     * Sums a collection of amounts.
     *
     * @param amounts the amounts, nulls counting as zero
     * @return the sum, never null
     */
    BigDecimal sum(Iterable<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(amount == null ? BigDecimal.ZERO : amount);
        }
        return total;
    }

    /**
     * Formats an amount the way the screens show it.
     *
     * @param amount the amount, possibly null
     * @return the amount, two decimals
     */
    private String format(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Builds the 303 back to the day examined.
     *
     * @param day the day examined, null falling back to the screen's default
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String day, String notice, boolean ok) {
        String path = day == null ? "/admin/safe/days" : "/admin/safe/days?day=" + day;
        return AdminForms.redirect(path, notice, ok);
    }

    /**
     * Re-reads an employee by their identifier, for a caller that names one.
     *
     * @param id the identifier, possibly null
     * @return the employee, or null when the identifier names none
     */
    Employee employeeOf(Long id) {
        return id == null ? null : Employee.<Employee>findById(id);
    }
}
