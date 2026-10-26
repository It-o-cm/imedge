package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.AccountingPeriod;
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
import java.util.List;

/**
 * The ACCOUNTING PERIOD screen ({@code /admin/safe/periods},
 * {@code BO-05-02-12}): opening the safe's period and closing it.
 *
 * <p>The closure is what the whole lot leads to. It refuses to run while a
 * movement of the period is still unvalidated, because the requirement's order
 * — point, validate, THEN close — is not a suggestion: a closure that swallowed
 * an unchecked movement would freeze a mistake into an accounting period nobody
 * may touch afterwards.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code safe_period} is what
 * puts it in an operator's menu.
 */
@Path("/admin/safe/periods")
public class AdminSafePeriodResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminSafePeriodResource.class);

    /** The periods template. */
    @Inject
    @Location("admin-safe-periods")
    Template adminSafePeriods;

    /** The safe: its balance, its ledger and its periods. */
    @Inject
    SafeService safeService;

    /** Who is asking, so a closure can name who ran it. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the periods, the open one first with what still blocks its closure.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the periods page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_PERIOD)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        AccountingPeriod open = safeService.openPeriod();
        List<AccountingPeriod> periods = AccountingPeriod.listNewestFirst();
        LOGGER.info("Exiting method list with " + periods.size() + " period(s)");
        return adminSafePeriods.data("periods", periods)
                .data("open", open)
                .data("balance", format(safeService.balance()))
                .data("pending", safeService.countPending(open))
                .data("today", LocalDate.now().toString())
                .data("suggestedLabel", LocalDate.now().toString().substring(0, 7))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Opens a period ({@code BO-05-02-12}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_PERIOD, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response open(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method open");
        String label = AdminForms.text(form, "label");
        if (label == null) {
            LOGGER.info("Exiting method open: no label");
            return back("Libellé de période obligatoire.", false);
        }
        AccountingPeriod period = safeService.open(label,
                AdminForms.day(form.getFirst("startDate")));
        if (period == null) {
            LOGGER.info("Exiting method open: a period is already open");
            return back("Une période est déjà ouverte.", false);
        }
        LOGGER.info("Exiting method open");
        return back("Période " + period.label + " ouverte.", true);
    }

    /**
     * Closes the open period ({@code BO-05-02-12}).
     *
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/close")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_PERIOD, option = CrudOption.UPDATE)
    @Transactional
    public Response close() {
        LOGGER.info("Entering method close");
        String refusal = safeService.close(signedIn());
        if (refusal != null) {
            LOGGER.info("Exiting method close: " + refusal);
            return back(refusal, false);
        }
        LOGGER.info("Exiting method close");
        return back("Période close.", true);
    }

    /**
     * The employee signed in, as the closure names them.
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
        return AdminForms.redirect("/admin/safe/periods", notice, ok);
    }
}
