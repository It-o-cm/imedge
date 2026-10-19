package com.intermarche.edge.ui.admin;

import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.DayEndService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The END OF DAY screen ({@code BO-09-01-04} to {@code -08},
 * {@code BO-09-02-01} to {@code -03}, {@code BO-04-03-06}).
 *
 * <p>Three alerts and one button. The alerts are the three things that make
 * an end of day go wrong — a lane still open, a lane gone quiet, a cart left
 * parked — and the button launches the close for whoever is looking at them.
 * Before this screen the back office had the automatic close and no way to
 * see what it was about to close.
 *
 * <p>The countdown is the same one the dashboard banner shows, and it comes
 * from the same clock as the automatic close: a manager reading two screens
 * reads one truth.
 */
@ApplicationScoped
@Path("/admin/day-end")
public class AdminDayEndResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminDayEndResource.class);

    /** The end-of-day picture and its manual launch. */
    @Inject
    DayEndService dayEndService;

    /** Who is pressing the button. */
    @Inject
    CurrentUser currentUser;

    /** Every register this node knows, for the connectivity card. */
    @Inject
    RegisterPresenceService registerPresenceService;

    /** The end-of-day template. */
    @Inject
    @Location("admin-day-end")
    Template adminDayEnd;

    /**
     * Shows the end-of-day picture.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the end-of-day page
     */
    @GET
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page");
        DayEndService.DayEndState state = dayEndService.state();
        List<RegisterPresenceService.Presence> registers = registerPresenceService.all();
        LOGGER.info("Exiting method page");
        return adminDayEnd.data("state", state)
                .data("registers", registers)
                .data("offlineMinutes", registerPresenceService.offlineMinutes())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Launches the end of day by hand ({@code BO-09-02-01} to {@code -03}).
     *
     * @return a 303 back to the screen with what the launch found
     */
    @POST
    @Path("/launch")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Response launch() {
        LOGGER.info("Entering method launch");
        DayEndService.DayEndState state = dayEndService.launch(currentUser.getFullName());
        String notice = "Fin de journée lancée : " + state.openRegisters().size()
                + " caisse(s) ouverte(s) à fermer, " + state.offlineRegisters().size()
                + " déconnectée(s), " + state.parkedCarts().size() + " ticket(s) en attente.";
        LOGGER.info("Exiting method launch");
        return AdminForms.redirect("/admin/day-end", notice, !state.hasAlert());
    }
}
