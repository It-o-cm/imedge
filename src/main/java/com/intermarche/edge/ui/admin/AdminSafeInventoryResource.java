package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.SafeDenomination;
import com.intermarche.edge.domain.safe.SafeInventory;
import com.intermarche.edge.domain.safe.SafeInventoryLine;
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
import java.util.ArrayList;
import java.util.List;

/**
 * The SAFE INVENTORY screen ({@code /admin/safe/inventories},
 * {@code BO-05-02-11}): counting what is physically in the safe, denomination by
 * denomination, against what the ledger says should be there.
 *
 * <p>Counted per denomination and not as one figure, for the same reason the
 * drawer is: a shop that finds itself two hundred euros short needs to know
 * whether it is four fifty-euro notes or twenty tens, and a single total
 * answers neither.
 *
 * <p>A count does not by itself move the safe. The discrepancy stands until
 * somebody decides to write it off, and writing it off is a MOVEMENT like any
 * other — so the balance is never corrected behind the ledger's back, and the
 * count that justified the correction stays readable beside it.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code safe_inventory} is what
 * puts it in an operator's menu.
 */
@Path("/admin/safe/inventories")
public class AdminSafeInventoryResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminSafeInventoryResource.class);

    /** The inventory template. */
    @Inject
    @Location("admin-safe-inventories")
    Template adminSafeInventories;

    /** The safe: the count is taken and written off through it. */
    @Inject
    SafeService safeService;

    /** Who is asking, so a count can name who took it. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the counts already taken and the sheet to take a new one.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the inventory page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_INVENTORY)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<SafeInventory> inventories = SafeInventory.listNewestFirst();
        LOGGER.info("Exiting method list with " + inventories.size() + " inventory(ies)");
        return adminSafeInventories.data("inventories", inventories)
                .data("notes", SafeDenomination.notes())
                .data("coins", SafeDenomination.coins())
                .data("theoretical", format(safeService.balance()))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Takes a count of the safe ({@code BO-05-02-11}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_INVENTORY, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response count(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method count");
        List<SafeInventoryLine> lines = countedLines(form);
        if (lines.isEmpty()) {
            LOGGER.info("Exiting method count: nothing counted");
            return back("Aucune coupure comptée.", false);
        }
        SafeInventory inventory = new SafeInventory();
        inventory.lines = lines;
        inventory.comment = AdminForms.text(form, "comment");
        safeService.takeInventory(inventory, signedIn());
        LOGGER.info("Exiting method count");
        return back(inventory.isBalanced()
                ? "Inventaire sans écart."
                : "Inventaire enregistré, écart de " + inventory.getVarianceFormatted().replace('.', ',') + " €.",
                inventory.isBalanced());
    }

    /**
     * Writes the discrepancy of a count off ({@code BO-05-02-11}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/adjust")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_INVENTORY, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response adjust(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method adjust");
        Long id = AdminForms.id(form.getFirst("id"));
        SafeInventory inventory = id == null ? null : SafeInventory.<SafeInventory>findById(id);
        String refusal = safeService.adjustFromInventory(inventory, signedIn());
        if (refusal != null) {
            LOGGER.info("Exiting method adjust: " + refusal);
            return back(refusal, false);
        }
        LOGGER.info("Exiting method adjust");
        return back("Écart régularisé.", true);
    }

    /**
     * Reads the counted quantities, dropping the denominations left blank.
     *
     * <p>A zero IS counted: declaring no fifty-euro note in the safe is a
     * statement, and dropping it would make an inventory of an empty safe
     * impossible to record.
     *
     * @param form the posted form
     * @return the counted lines, never null, possibly empty
     */
    List<SafeInventoryLine> countedLines(MultivaluedMap<String, String> form) {
        List<SafeInventoryLine> lines = new ArrayList<>();
        for (SafeDenomination denomination : SafeDenomination.values()) {
            String raw = form.getFirst("qty" + denomination.name());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            int quantity = AdminForms.integer(raw, 0);
            lines.add(new SafeInventoryLine(denomination, Math.max(quantity, 0)));
        }
        return lines;
    }

    /**
     * The employee signed in, as a count names them.
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
        return AdminForms.redirect("/admin/safe/inventories", notice, ok);
    }
}
