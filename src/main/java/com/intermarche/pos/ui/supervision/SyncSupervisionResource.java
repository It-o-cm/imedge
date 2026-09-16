package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.sync.EngineFeedDeliveryService;
import com.intermarche.pos.service.sync.RefPullService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

/**
 * The store back office's SYNC SUPERVISION screen ({@code /admin/sync}, lot C2):
 * the read-only state of this node's three outbound flows (engine feeds,
 * referential pull, store outbox) plus the two manual relaunches and the local
 * session force-close.
 * <p>
 * The mechanisms all existed and nothing reached them: {@code /feeds/import/status}
 * answered without a screen, {@code pullOnce} and {@code deliverPending} were
 * callable with no button, and {@code closeSession(null, …)} already accepted a
 * forced close. This screen is the wiring, not a second mechanism.
 * <p>
 * Reserved to ADMIN, one notch above the read-only supervision (dashboard,
 * journal are MANAGER/ADMIN): forcing a pull, a delivery or a session close is a
 * back-office administrator's act. Rendered inside the back-office gabarit
 * ({@code admin-layout}) — one surface, one chrome — with the POST → 303 →
 * notice cycle. The node knows only itself: every action is local
 * (single-node invariant).
 */
@Path("/admin/sync")
public class SyncSupervisionResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SyncSupervisionResource.class);

    /** The supervision page template. */
    @Inject
    @Location("admin-sync")
    Template adminSync;

    /** The read-only view assembly. */
    @Inject
    SyncSupervisionService syncSupervisionService;

    /** The referential pull loop (manual relaunch). */
    @Inject
    RefPullService refPullService;

    /** The engine feed delivery loop (manual relaunch). */
    @Inject
    EngineFeedDeliveryService engineFeedDeliveryService;

    /** The cash session service (local force-close). */
    @Inject
    CashSessionService cashSessionService;

    /** The technical journal (the two relaunches are journaled). */
    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Shows the sync supervision page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the supervision page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page with notice: " + notice + ", noticeOk: " + noticeOk);
        LOGGER.info("Exiting method page");
        return adminSync.data("view", syncSupervisionService.build())
                .data("sessionOpen", cashSessionService.getOpenSession() != null)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Forces an immediate referential pull (BO-08-04-09/10), journals the
     * relaunch with its outcome and redirects back with the notice.
     *
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/pull")
    @RolesAllowed("ADMIN")
    public Response relaunchPull() {
        LOGGER.info("Entering method relaunchPull");
        String error = refPullService.triggerPull();
        if (error == null) {
            technicalEventService.log(TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED,
                    "Tirage manuel des référentiels réussi");
            LOGGER.info("Exiting method relaunchPull");
            return redirect("Tirage des référentiels relancé.", true);
        }
        technicalEventService.log(TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED,
                "Tirage manuel des référentiels en échec: " + error);
        LOGGER.info("Exiting method relaunchPull");
        return redirect("Tirage des référentiels en échec : " + error, false);
    }

    /**
     * Forces an immediate engine-feed delivery (BO-08-04-13), journals the
     * relaunch with its outcome and redirects back with the notice.
     *
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/deliver")
    @RolesAllowed("ADMIN")
    public Response relaunchDelivery() {
        LOGGER.info("Entering method relaunchDelivery");
        String error = engineFeedDeliveryService.triggerDelivery();
        if (error == null) {
            technicalEventService.log(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED,
                    "Livraison manuelle des flux moteur réussie");
            LOGGER.info("Exiting method relaunchDelivery");
            return redirect("Livraison des flux moteur relancée.", true);
        }
        technicalEventService.log(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED,
                "Livraison manuelle des flux moteur en échec: " + error);
        LOGGER.info("Exiting method relaunchDelivery");
        return redirect("Livraison des flux moteur en échec : " + error, false);
    }

    /**
     * Forces the close of THIS node's open session (BO-09-01-09), with no
     * closing cashier and no drawer count — the stuck-register recovery an
     * administrator triggers when the cashier is gone. Delegates to the
     * existing {@code closeSession(null, …)}, which journals the closing
     * itself; a node with no open session gets a plain notice.
     *
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/force-close")
    @RolesAllowed("ADMIN")
    public Response forceCloseSession() {
        LOGGER.info("Entering method forceCloseSession");
        CashSessionService.SessionReport report = cashSessionService.closeSession(
                null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        if (report == null) {
            LOGGER.info("Exiting method forceCloseSession");
            return redirect("Aucune session ouverte sur ce nœud.", false);
        }
        LOGGER.info("Exiting method forceCloseSession");
        return redirect("Session " + report.session.sessionNumber + " clôturée de force.", true);
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/sync?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
