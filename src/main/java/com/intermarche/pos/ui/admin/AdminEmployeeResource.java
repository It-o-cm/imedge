package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.safe.SafeMovement;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * The PAYROLL screen ({@code /admin/employees}, {@code BO-05-02-15}): the people
 * — and the companies — who may receive an advance, a reimbursement or a
 * down-payment from the safe.
 *
 * <p>Beside {@code /admin/users} and not instead of it. That screen assigns
 * PROFILES to accounts that sign in; this one holds the file of everyone the
 * safe may pay, which is a different population: a cleaning company paid in cash
 * belongs here and will never sign in, and a cashier who signs in every morning
 * may have no forcing level and no advance ceiling at all.
 *
 * <p>The three columns the requirement names are the ones this screen writes:
 * whether the file is a legal entity, the forcing level that orders the staff
 * among themselves, and the largest advance the shop will pay. What a file may
 * OPEN stays in the profile grants — a rank is not a right.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code employee} is what puts
 * it in an operator's menu.
 */
@Path("/admin/employees")
public class AdminEmployeeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminEmployeeResource.class);

    /** The payroll template. */
    @Inject
    @Location("admin-employees")
    Template adminEmployees;

    /**
     * Shows the payroll files, filtered by a fragment of name.
     *
     * @param text a fragment of the name or the login
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the payroll page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@QueryParam("text") String text,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with text: " + text + ", page: " + page);
        List<Employee> matches = search(text);
        LOGGER.info("Exiting method list with " + matches.size() + " file(s)");
        return adminEmployees.data("page", AdminForms.page(matches, page))
                .data("baseQuery", AdminForms.pageParam("text", text))
                .data("text", text == null ? "" : text)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Writes the payroll columns of one file ({@code BO-05-02-15}).
     *
     * <p>ONLY those columns. The login, the password, the badge and the role are
     * the business of {@code /admin/users}, and a payroll screen that could
     * quietly rewrite a credential would be a way around the rights model.
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        Long id = AdminForms.id(form.getFirst("id"));
        Employee employee = id == null ? null : Employee.<Employee>findById(id);
        if (employee == null) {
            LOGGER.info("Exiting method save: unknown file");
            return back("Salarié inconnu.", false);
        }
        BigDecimal limit = AdminForms.amount(form.getFirst("advanceLimit"));
        if (limit != null && limit.signum() < 0) {
            LOGGER.info("Exiting method save: negative ceiling");
            return back("Le plafond d'acompte ne peut pas être négatif.", false);
        }
        employee.legalEntity = AdminForms.checkbox(form, "legalEntity");
        employee.forcingLevel = Math.max(AdminForms.integer(form.getFirst("forcingLevel"), 0), 0);
        employee.advanceLimit = limit;
        employee.persist();
        LOGGER.infof("Fiche salarié %s enregistrée", employee.getFullName());
        LOGGER.info("Exiting method save");
        return back("Fiche salarié enregistrée.", true);
    }

    /**
     * The advances and reimbursements the safe already paid one file
     * ({@code BO-05-02-15}).
     *
     * @param employee the file, null answering zero
     * @return the total paid out to them, never null
     */
    BigDecimal paidTo(Employee employee) {
        if (employee == null || employee.id == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SafeMovement movement
                : SafeMovement.<SafeMovement>find("employee.id = ?1", employee.id).list()) {
            total = total.add(movement.signedAmount());
        }
        return total.negate();
    }

    /**
     * Runs the list search over the payroll files.
     *
     * @param text a fragment of the name or the login
     * @return the matching files, ordered by name
     */
    List<Employee> search(String text) {
        String needle = text == null || text.isBlank() ? null : text.trim().toLowerCase();
        List<Employee> matches = new java.util.ArrayList<>();
        for (Employee employee
                : Employee.<Employee>find("order by lastName, firstName").list()) {
            if (needle != null && !matches(employee, needle)) {
                continue;
            }
            matches.add(employee);
        }
        return matches;
    }

    /**
     * Says whether a file's name or login carries a fragment.
     *
     * @param employee the file examined
     * @param needle the lower-case fragment, never null
     * @return true when one of them carries it
     */
    private boolean matches(Employee employee, String needle) {
        return (employee.lastName != null && employee.lastName.toLowerCase().contains(needle))
                || (employee.firstName != null && employee.firstName.toLowerCase().contains(needle))
                || (employee.loginName != null && employee.loginName.toLowerCase().contains(needle));
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/employees", notice, ok);
    }
}
