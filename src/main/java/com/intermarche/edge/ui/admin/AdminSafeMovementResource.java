package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.AccountingPeriod;
import com.intermarche.edge.domain.safe.SafeMovement;
import com.intermarche.edge.domain.safe.SafeMovementType;
import com.intermarche.edge.domain.safe.SafeVatLine;
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
 * The SAFE MOVEMENTS screen ({@code /admin/safe/movements}): the expenses
 * ({@code BO-05-02-01}), the receipts entered by hand ({@code BO-05-02-02}) and
 * the pointing that validates them ({@code BO-05-02-06}).
 *
 * <p>Both gestures are the same screen because they are the same row seen from
 * either side of the safe: a wording, an amount, a day, a VAT breakdown of none,
 * one or several rates. What tells them apart is the KIND, which decides the
 * direction — so an operator can never enter a receipt that empties the safe by
 * typing a minus sign.
 *
 * <p>The pointing is on the same screen for the same reason the requirement puts
 * them in the same paragraph: what is validated is what was entered during the
 * open period, and a screen showing the entries without offering to check them
 * would leave the check to be done from paper.
 *
 * <p>Access reserved to ADMIN, like every back-office screen; the profile grant
 * {@code safe_movement} is what puts it in an operator's menu.
 */
@Path("/admin/safe/movements")
public class AdminSafeMovementResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminSafeMovementResource.class);

    /** How many VAT rates one movement may state on the screen. */
    static final int VAT_LINES = 3;

    /** The movements template. */
    @Inject
    @Location("admin-safe-movements")
    Template adminSafeMovements;

    /** The safe: its balance, its ledger and its period. */
    @Inject
    SafeService safeService;

    /** Who is asking, so a movement can name the operator who entered it. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the movements of the open period, with what the safe holds.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the movements page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_MOVEMENT)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        AccountingPeriod period = safeService.openPeriod();
        List<SafeMovement> movements = safeService.movementsOf(period);
        LOGGER.info("Exiting method list with " + movements.size() + " movement(s)");
        return adminSafeMovements.data("movements", movements)
                .data("period", period)
                .data("balance", format(safeService.balance()))
                .data("pending", safeService.countPending(period))
                .data("types", manualTypes())
                .data("employees", payrollEmployees())
                .data("vatLines", VAT_LINES)
                .data("today", LocalDate.now().toString())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Enters an expense or a receipt ({@code BO-05-02-01}, {@code BO-05-02-02}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_MOVEMENT, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response record(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method record");
        SafeMovementType type = SafeMovementType.fromName(form.getFirst("type"));
        if (type == null || !type.isManual()) {
            LOGGER.info("Exiting method record: unknown kind");
            return back("Nature de mouvement inconnue.", false);
        }
        BigDecimal amount = AdminForms.amount(form.getFirst("amount"));
        if (amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method record: unusable amount");
            return back("Montant obligatoire et strictement positif.", false);
        }
        String label = AdminForms.text(form, "label");
        if (label == null) {
            LOGGER.info("Exiting method record: no wording");
            return back("Libellé obligatoire.", false);
        }
        Employee concerned = employeeOf(AdminForms.id(form.getFirst("employeeId")));
        String ceiling = safeService.advanceRefusal(type, amount, concerned);
        if (ceiling != null) {
            LOGGER.info("Exiting method record: over the advance ceiling");
            return back(ceiling, false);
        }
        SafeMovement movement = safeService.record(type, amount, label,
                AdminForms.day(form.getFirst("movementDate")), signedIn(), concerned,
                AdminForms.text(form, "reference"), vatLinesOf(form));
        if (movement == null) {
            LOGGER.info("Exiting method record: no open period");
            return back(SafeService.NO_OPEN_PERIOD, false);
        }
        LOGGER.info("Exiting method record");
        return back("Mouvement enregistré.", true);
    }

    /**
     * Validates one movement of the open period ({@code BO-05-02-06}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/validate")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_MOVEMENT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response validate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method validate");
        SafeMovement movement = movementOf(AdminForms.id(form.getFirst("id")));
        if (movement == null) {
            LOGGER.info("Exiting method validate: unknown movement");
            return back("Mouvement inconnu.", false);
        }
        Employee validator = signedIn();
        String outranked = safeService.forcingRefusal(movement, validator);
        if (outranked != null) {
            LOGGER.info("Exiting method validate: forcing level");
            return back(outranked, false);
        }
        if (!safeService.validate(movement, validator)) {
            LOGGER.info("Exiting method validate: already validated");
            return back("Ce mouvement est déjà validé.", false);
        }
        LOGGER.info("Exiting method validate");
        return back("Mouvement validé.", true);
    }

    /**
     * Validates EVERY pending movement of the open period ({@code BO-05-02-06}).
     *
     * <p>The gesture the requirement really describes: an operator points a
     * period, corrects what is wrong, then validates the rest in one go. Checking
     * forty rows one button at a time is how a closure gets postponed.
     *
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/validate-all")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_MOVEMENT, option = CrudOption.UPDATE)
    @Transactional
    public Response validateAll() {
        LOGGER.info("Entering method validateAll");
        Employee by = signedIn();
        int validated = 0;
        int reserved = 0;
        for (SafeMovement movement : safeService.movementsOf(safeService.openPeriod())) {
            if (!movement.isValidated() && safeService.forcingRefusal(movement, by) != null) {
                reserved++;
            } else if (safeService.validate(movement, by)) {
                validated++;
            }
        }
        LOGGER.info("Exiting method validateAll with " + validated + " movement(s)");
        return reserved == 0 ? back(validated + " mouvement(s) validé(s).", true)
                : back(validated + " mouvement(s) validé(s), " + reserved
                        + " réservé(s) à un niveau de forçage supérieur.", true);
    }

    /**
     * Corrects a movement before it is validated ({@code BO-05-02-06}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/correct")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_MOVEMENT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response correct(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method correct");
        SafeMovement movement = movementOf(AdminForms.id(form.getFirst("id")));
        String refusal = safeService.correct(movement,
                AdminForms.amount(form.getFirst("amount")), AdminForms.text(form, "label"),
                AdminForms.day(form.getFirst("movementDate")));
        if (refusal != null) {
            LOGGER.info("Exiting method correct: " + refusal);
            return back(refusal, false);
        }
        LOGGER.info("Exiting method correct");
        return back("Mouvement corrigé.", true);
    }

    /**
     * Reads the VAT breakdown posted beside the movement ({@code BO-05-02-01},
     * {@code BO-05-02-02}).
     *
     * <p>Three rates offered and none mandatory: the requirement asks for none,
     * one or several, so a blank line is dropped rather than stored as a zero
     * rate the movement does not bear.
     *
     * @param form the posted form
     * @return the lines stated, never null, possibly empty
     */
    List<SafeVatLine> vatLinesOf(MultivaluedMap<String, String> form) {
        List<SafeVatLine> lines = new ArrayList<>();
        for (int index = 0; index < VAT_LINES; index++) {
            SafeVatLine line = new SafeVatLine(
                    AdminForms.amount(form.getFirst("vatRate" + index)),
                    AdminForms.amount(form.getFirst("vatBase" + index)),
                    AdminForms.amount(form.getFirst("vatAmount" + index)));
            if (line.isStated()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * The kinds of movement the screen offers for entry.
     *
     * @return the manual kinds, in declaration order
     */
    List<SafeMovementType> manualTypes() {
        List<SafeMovementType> offered = new ArrayList<>();
        for (SafeMovementType type : SafeMovementType.values()) {
            if (type.isManual()) {
                offered.add(type);
            }
        }
        return offered;
    }

    /**
     * The employees a movement may be made out to ({@code BO-05-02-15}).
     *
     * @return the active files, business names included, by name
     */
    List<Employee> payrollEmployees() {
        return Employee.<Employee>find("active = true order by lastName, firstName").list();
    }

    /**
     * Re-reads a movement by its identifier.
     *
     * @param id the identifier, possibly null
     * @return the movement, or null when the identifier names none
     */
    private SafeMovement movementOf(Long id) {
        return id == null ? null : SafeMovement.<SafeMovement>findById(id);
    }

    /**
     * Re-reads an employee by their identifier.
     *
     * @param id the identifier, possibly null
     * @return the employee, or null when the identifier names none
     */
    private Employee employeeOf(Long id) {
        return id == null ? null : Employee.<Employee>findById(id);
    }

    /**
     * The employee signed in, as the ledger names them.
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
        return AdminForms.redirect("/admin/safe/movements", notice, ok);
    }
}
