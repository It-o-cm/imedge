package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.DisplayType;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.TpvType;
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

import java.util.List;
import org.jboss.logging.Logger;

/**
 * The CHECKOUT POINT administration screen ({@code /admin/checkout-points}) —
 * the register as an administered object (BO-03-09).
 *
 * <p>A checkout point is created and activated here (BO-03-09-01), attached to an
 * island (BO-03-09-05), given its type (BO-03-09-07), a technical hostess for
 * the self-service families (BO-03-09-08), the type and resolution of its
 * cashier and customer displays (BO-03-09-12/13) and a cash-menu model
 * assignment (BO-03-10-16). It never implements a driver or a protocol — only
 * the attributes the back office records.
 */
@Path("/admin/checkout-points")
public class AdminCheckoutPointResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCheckoutPointResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/checkout-points";

    /** The checkout-points page template. */
    @Inject
    @Location("admin-checkout-points")
    Template adminCheckoutPoints;

    /**
     * Shows the checkout-points page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance pointsPage(@QueryParam("notice") String notice,
                                       @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method pointsPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<CheckoutPoint> points = CheckoutPoint.listAllOrdered();
        LOGGER.info("Exiting method pointsPage");
        return adminCheckoutPoints.data("points", points)
                .data("islands", CheckoutIsland.list("order by code"))
                .data("pdvs", Pdv.listAllOrdered())
                .data("tpvTypes", TpvType.values())
                .data("displayTypes", DisplayType.values())
                .data("scaleProtocols", com.intermarche.edge.domain.store.ScaleProtocol.values())
                .data("coinProtocols",
                        com.intermarche.edge.domain.store.CoinMachineProtocol.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Upserts a checkout point by its terminal identifier (BO-03-09).
     *
     * @param form the posted form (terminalId, label, pdvNumber, islandCode,
     *        active, tpvType, technicalHostessBadge, cashierDisplayType,
     *        cashierDisplayResolution, customerDisplayType,
     *        customerDisplayResolution, menuTemplateCode)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response savePoint(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method savePoint with form: " + form);
        String terminalId = AdminForms.text(form, "terminalId");
        if (terminalId == null) {
            LOGGER.info("Exiting method savePoint");
            return AdminForms.redirect(PAGE, "L'identifiant du terminal est obligatoire.", false);
        }
        String islandCode = AdminForms.text(form, "islandCode");
        if (islandCode != null && CheckoutIsland.findByCode(islandCode) == null) {
            LOGGER.info("Exiting method savePoint");
            return AdminForms.redirect(PAGE, "Îlot inconnu : " + islandCode, false);
        }
        String pdvNumber = AdminForms.text(form, "pdvNumber");
        if (pdvNumber != null && Pdv.findByNumber(pdvNumber) == null) {
            LOGGER.info("Exiting method savePoint");
            return AdminForms.redirect(PAGE, "Point de vente inconnu : " + pdvNumber, false);
        }
        String hostessBadge = AdminForms.text(form, "technicalHostessBadge");
        if (hostessBadge != null && Employee.find("badgeId", hostessBadge).firstResult() == null) {
            LOGGER.info("Exiting method savePoint");
            return AdminForms.redirect(PAGE, "Hôtesse inconnue (badge " + hostessBadge + ").", false);
        }
        CheckoutPoint existing = CheckoutPoint.findByTerminal(terminalId);
        CheckoutPoint point = existing;
        if (point == null) {
            point = new CheckoutPoint();
            point.terminalId = terminalId;
            point.persist();
        }
        point.label = AdminForms.text(form, "label");
        point.pdvNumber = pdvNumber;
        point.islandCode = islandCode;
        point.active = AdminForms.checkbox(form, "active");
        TpvType tpvType = TpvType.fromName(AdminForms.text(form, "tpvType"));
        point.tpvType = tpvType == null ? TpvType.CASH_REGISTER : tpvType;
        point.technicalHostessBadge = hostessBadge;
        point.cashierDisplayType = display(AdminForms.text(form, "cashierDisplayType"));
        point.cashierDisplayResolution = AdminForms.text(form, "cashierDisplayResolution");
        point.customerDisplayType = display(AdminForms.text(form, "customerDisplayType"));
        point.customerDisplayResolution = AdminForms.text(form, "customerDisplayResolution");
        // BO-03-09-15/-24/-25/-26: the peripherals of this point of contact.
        // The protocol is a choice, the address and the port are what the shop
        // was given by whoever installed the machine; an unreadable port is
        // refused rather than stored as zero, which would be a port.
        point.scaleProtocol = scaleProtocol(AdminForms.text(form, "scaleProtocol"));
        point.scaleAddress = AdminForms.text(form, "scaleAddress");
        point.scalePort = port(AdminForms.text(form, "scalePort"));
        point.coinMachineProtocol = coinProtocol(AdminForms.text(form, "coinMachineProtocol"));
        point.coinMachineAddress = AdminForms.text(form, "coinMachineAddress");
        point.coinMachinePort = port(AdminForms.text(form, "coinMachinePort"));
        point.menuTemplateCode = AdminForms.text(form, "menuTemplateCode");
        LOGGER.info("Exiting method savePoint");
        return AdminForms.redirect(PAGE, existing == null ? "Point de caisse créé." : "Point de caisse enregistré.", true);
    }

    /**
     * Deletes a checkout point by its terminal identifier.
     *
     * @param form the posted form (terminalId)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deletePoint(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deletePoint with form: " + form);
        CheckoutPoint point = CheckoutPoint.findByTerminal(AdminForms.text(form, "terminalId"));
        if (point == null) {
            LOGGER.info("Exiting method deletePoint");
            return AdminForms.redirect(PAGE, "Point de caisse inconnu.", false);
        }
        point.delete();
        LOGGER.info("Exiting method deletePoint");
        return AdminForms.redirect(PAGE, "Point de caisse supprimé.", true);
    }

    /**
     * Resolves a posted display-type name to a type, an unknown or blank value
     * falling back to {@link DisplayType#NONE}.
     *
     * @param raw the posted name, or null
     * @return the resolved type, never null
     */
    private DisplayType display(String raw) {
        DisplayType type = DisplayType.fromName(raw);
        return type == null ? DisplayType.NONE : type;
    }

    /**
     * Reads the posted scale protocol, NONE when nothing recognisable was
     * posted (BO-03-09-15).
     *
     * @param raw the posted name, or null
     * @return the protocol, never null
     */
    private com.intermarche.edge.domain.store.ScaleProtocol scaleProtocol(String raw) {
        com.intermarche.edge.domain.store.ScaleProtocol protocol =
                com.intermarche.edge.domain.store.ScaleProtocol.fromName(raw);
        return protocol == null ? com.intermarche.edge.domain.store.ScaleProtocol.NONE : protocol;
    }

    /**
     * Reads the posted coin-machine protocol, NONE when nothing recognisable
     * was posted (BO-03-09-24/-25/-26).
     *
     * @param raw the posted name, or null
     * @return the protocol, never null
     */
    private com.intermarche.edge.domain.store.CoinMachineProtocol coinProtocol(String raw) {
        com.intermarche.edge.domain.store.CoinMachineProtocol protocol =
                com.intermarche.edge.domain.store.CoinMachineProtocol.fromName(raw);
        return protocol == null
                ? com.intermarche.edge.domain.store.CoinMachineProtocol.NONE : protocol;
    }

    /**
     * Reads a posted TCP port, or null when none was posted or it is not a port.
     *
     * <p>Null and not zero: a machine with no port and a machine whose port was
     * mistyped must not both read as port 0, which is a port nothing listens on
     * and a value the register would try.
     *
     * @param raw the posted value, or null
     * @return the port between 1 and 65535, or null
     */
    private Integer port(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 1 || value > 65535 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
