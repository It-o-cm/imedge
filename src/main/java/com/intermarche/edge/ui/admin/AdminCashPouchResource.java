package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.CashPouch;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.SafeService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The POUCH screen ({@code /admin/safe/pouches}, {@code BO-05-02-10}): the
 * registers' withdrawals of a day, gathered into one or two pouches and brought
 * to the safe.
 *
 * <p>The step the register cannot take. A closing writes what the cashier took
 * out of the drawer, and there it stops: the money then walks to the safe, and
 * until somebody says so the safe has received nothing. This screen is that
 * saying-so, and it is what makes the safe's balance match what is physically in
 * it rather than what the registers believe they handed over.
 *
 * <p>The day's withdrawals are PROPOSED, never imposed: the requirement says one
 * or two pouches, so an operator splits the day as they actually sealed it.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code safe_pouch} is what puts
 * it in an operator's menu.
 */
@Path("/admin/safe/pouches")
public class AdminCashPouchResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCashPouchResource.class);

    /** The pouch list template. */
    @Inject
    @Location("admin-safe-pouches")
    Template adminSafePouches;

    /** The safe: the pouch is taken in through it. */
    @Inject
    SafeService safeService;

    /** Who is asking, so a pouch can name who brought it. */
    @Inject
    CurrentUser currentUser;

    /** The sequence and the count of the pouches, as administered (BO-05-02-10). */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /**
     * Shows the pouches and what the registers withdrew on a day.
     *
     * @param day the accounting day examined, blank meaning today
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the pouches page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_POUCH)
    public TemplateInstance list(@QueryParam("day") String day,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with day: " + day);
        LocalDate examined = AdminForms.day(day) == null ? LocalDate.now() : AdminForms.day(day);
        List<CashSession> sessions = sessionsOf(examined);
        LOGGER.info("Exiting method list with " + sessions.size() + " session(s)");
        return adminSafePouches.data("pouches", CashPouch.listNewestFirst())
                .data("sessions", sessions)
                .data("day", examined.toString())
                .data("withdrawn", format(withdrawnOf(sessions)))
                .data("terminals", terminalsOf(sessions))
                .data("validation", com.intermarche.edge.domain.safe.SafeDayValidation.forDay(examined))
                .data("validationRequired", posSettingsService.pouchRequiresDayValidation())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Declares a pouch and brings it into the safe ({@code BO-05-02-10}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_POUCH, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deposit(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deposit");
        String number = AdminForms.text(form, "pouchNumber");
        if (number == null) {
            LOGGER.info("Exiting method deposit: no number");
            return back("Numéro de pochette obligatoire.", false);
        }
        if (CashPouch.find("pouchNumber", number).firstResult() != null) {
            LOGGER.info("Exiting method deposit: duplicate number");
            return back("Cette pochette existe déjà : " + number, false);
        }
        BigDecimal amount = AdminForms.amount(form.getFirst("amount"));
        if (amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method deposit: unusable amount");
            return back("Montant obligatoire et strictement positif.", false);
        }
        LocalDate day = AdminForms.day(form.getFirst("accountingDay"));
        LocalDate accountingDay = day == null ? LocalDate.now() : day;
        String sequence = sequenceRefusal(accountingDay);
        if (sequence != null) {
            LOGGER.info("Exiting method deposit: " + sequence);
            return back(sequence, false);
        }
        CashPouch pouch = new CashPouch();
        pouch.pouchNumber = number;
        pouch.label = AdminForms.text(form, "label");
        pouch.accountingDay = accountingDay;
        pouch.amount = amount;
        pouch.terminals = AdminForms.text(form, "terminals");
        String refusal = safeService.depositPouch(pouch, signedIn());
        if (refusal != null) {
            LOGGER.info("Exiting method deposit: " + refusal);
            return back(refusal, false);
        }
        LOGGER.infof("Pochette %s de %s remise au coffre", number, amount);
        LOGGER.info("Exiting method deposit");
        return back("Pochette remise au coffre.", true);
    }

    /**
     * The refusal the sequence of the safe imposes on a pouch
     * ({@code BO-05-02-10}): the movements of the day are validated, then its
     * net revenue, and only then are its takings sealed — in one or two
     * pouches, as administered.
     *
     * @param day the accounting day of the pouch
     * @return the refusal, or null when the pouch may be declared
     */
    String sequenceRefusal(LocalDate day) {
        if (posSettingsService.pouchRequiresDayValidation()
                && com.intermarche.edge.domain.safe.SafeDayValidation.forDay(day) == null) {
            return "La journée comptable n'est pas validée : validez les mouvements des caissières puis "
                    + "le chiffre d'affaires de la journée avant la remise au coffre.";
        }
        int limit = posSettingsService.pouchesPerDay();
        if (limit > 0 && CashPouch.count("accountingDay", day) >= limit) {
            return "Cette journée comptable compte déjà " + limit + " pochette(s) remise(s) au coffre.";
        }
        return null;
    }

    /**
     * The sessions closed on one accounting day, whichever register they belong
     * to.
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
     * What the registers withdrew on the day, which is what the pouches should
     * hold.
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
     * The registers of the day, as the pouch records them.
     *
     * @param sessions the sessions of the day
     * @return the register identifiers, comma-separated, empty when there are none
     */
    String terminalsOf(List<CashSession> sessions) {
        List<String> named = new ArrayList<>();
        for (CashSession session : sessions) {
            if (session.terminalId != null && !named.contains(session.terminalId)) {
                named.add(session.terminalId);
            }
        }
        return String.join(",", named);
    }

    /**
     * The employee signed in, as a pouch names them.
     *
     * @return the employee, or null when the session names nobody known
     */
    Employee signedIn() {
        String login = currentUser == null ? null : currentUser.getName();
        if (login == null || login.isBlank()) {
            return null;
        }
        return Employee.<Employee>find("loginName", login).firstResult();
    }

    /**
     * Formats an amount the way the screens show it.
     *
     * @param amount the amount, possibly null
     * @return the amount, two decimals
     */
    private String format(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/safe/pouches", notice, ok);
    }
}
