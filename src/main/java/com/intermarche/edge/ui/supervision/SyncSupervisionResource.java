package com.intermarche.edge.ui.supervision;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.CashSessionService;
import com.intermarche.edge.service.TechnicalEventService;
import com.intermarche.edge.service.sync.EngineFeedDeliveryService;
import com.intermarche.edge.service.sync.RefPullService;
import com.intermarche.edge.service.sync.ScheduledDeliveryService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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

    /** The engine feed delivery loop (manual relaunch, targeted variant). */
    @Inject
    EngineFeedDeliveryService engineFeedDeliveryService;

    /** Produces the outgoing accounting flows on demand (BO-11-03-01). */
    @Inject
    com.intermarche.edge.service.export.OutboundFlowService outboundFlowService;

    /** The at-the-flow stream (manual recovery pass, BO-11-02-05). */
    @Inject
    com.intermarche.edge.service.sync.TicketStreamService ticketStreamService;

    /** The pull of the commercial service's feeds (manual relaunch, BO-11-01-01). */
    @Inject
    com.intermarche.edge.service.sync.IntegrationPullService integrationPullService;

    /** The deferred deliveries keeper (booking, listing, cancellation). */
    @Inject
    ScheduledDeliveryService scheduledDeliveryService;

    /** The cash session service (local force-close). */
    @Inject
    CashSessionService cashSessionService;

    /** The technical journal (the two relaunches are journaled). */
    @Inject
    TechnicalEventService technicalEventService;

    /** Who is asking, so a booking can name the administrator who created it. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the sync supervision page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the supervision page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SYNC)
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
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
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
     * REGENERATES the outgoing accounting flows of one day on demand
     * (BO-11-03-01), journals the gesture with its outcome and redirects back
     * with the notice.
     *
     * <p>Same call as the scheduled production, not a replay path beside it: a
     * flow is a pure function of a closed accounting day, so re-producing it
     * rewrites the same file unless the data itself changed. A second path would
     * be a second truth, and the support team would be regenerating something
     * other than what was emitted.
     *
     * <p>The day defaults to YESTERDAY, which is the one a support call is about.
     * An unparseable date is refused rather than silently replaced: regenerating
     * the wrong day and saying so is the one outcome nobody could detect.
     *
     * @param day the accounting day to regenerate, {@code yyyy-MM-dd}, or null
     *        for yesterday
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
    public Response regenerateOutboundFlows(@jakarta.ws.rs.FormParam("day") String day) {
        LOGGER.info("Entering method regenerateOutboundFlows with day: " + day);
        java.time.LocalDate target;
        try {
            target = (day == null || day.isBlank())
                    ? java.time.LocalDate.now().minusDays(1)
                    : java.time.LocalDate.parse(day.trim());
        } catch (java.time.format.DateTimeParseException e) {
            LOGGER.info("Exiting method regenerateOutboundFlows");
            return redirect("Date illisible : « " + day + " ». Attendu aaaa-mm-jj.", false);
        }
        String shown = target.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        try {
            java.util.List<java.nio.file.Path> written = outboundFlowService.produceAll(target);
            technicalEventService.log(TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED,
                    "Régénération des flux sortants du " + shown + ": "
                            + written.size() + " fichier(s)");
            LOGGER.info("Exiting method regenerateOutboundFlows");
            if (written.isEmpty()) {
                return redirect("Aucun flux produit pour le " + shown
                        + " : flux non activés, répertoire non administré,"
                        + " ou aucun ticket clôturé ce jour-là.", true);
            }
            return redirect(written.size() + " flux produit(s) pour le " + shown + ".", true);
        } catch (java.io.IOException e) {
            technicalEventService.log(TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED,
                    "Régénération des flux sortants du " + shown + " en échec: " + e.getMessage());
            LOGGER.info("Exiting method regenerateOutboundFlows");
            return redirect("Flux sortants en échec : " + e.getMessage(), false);
        }
    }

    /**
     * Pulls the integration feeds of the commercial service now (BO-11-01-01)
     * and redirects back with how many files went in.
     *
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/integration-pull")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
    public Response pullIntegrationFeeds() {
        LOGGER.info("Entering method pullIntegrationFeeds");
        com.intermarche.edge.service.sync.IntegrationPullService.Outcome outcome =
                integrationPullService.pullNow(java.time.LocalDateTime.now());
        LOGGER.info("Exiting method pullIntegrationFeeds");
        return redirect("Flux d'intégration : " + outcome.integrated() + " intégré(s), "
                + outcome.unchanged() + " inchangé(s), " + outcome.failed() + " en échec.",
                outcome.failed() == 0);
    }

    /**
     * Forces an immediate engine-feed delivery (BO-08-04-13), journals the
     * relaunch with its outcome and redirects back with the notice.
     *
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/deliver")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
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
     * Runs an at-the-flow delivery pass NOW, outside the cadence (BO-11-02-05).
     *
     * <p>The recovery half of the requirement: the alert is the healthcheck on the
     * page, this is the button beside it. Safe to press at any time — a pass only
     * delivers what is pending and deletes what the subscriber acknowledged, so
     * pressing it twice delivers nothing the second time.
     *
     * <p>Refused when the stream is not activated at this echelon, rather than
     * silently doing nothing: an operator pressing a button on a disabled
     * mechanism must be told it is disabled.
     *
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/stream")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
    public Response relaunchStream() {
        LOGGER.info("Entering method relaunchStream");
        if (!ticketStreamService.isEnabled()) {
            LOGGER.info("Exiting method relaunchStream");
            return redirect("La diffusion au fil de l'eau n'est pas activée sur cet échelon.",
                    false);
        }
        int delivered = ticketStreamService.deliverPending();
        technicalEventService.log(TechnicalEvent.EventType.STREAM_DELIVERY_FORCED,
                "Passe manuelle de diffusion au fil de l'eau: " + delivered
                        + " événement(s) livré(s)");
        LOGGER.info("Exiting method relaunchStream");
        return redirect(delivered + " événement(s) diffusé(s) au fil de l'eau.", true);
    }

    /**
     * Delivers ONE feed NOW, by code, from its per-row "Livrer maintenant"
     * button (GB-01-01-01), and — on the very same button when the row is in
     * error — replays it (GB-01-01-19/30). Delegates to the targeted delivery
     * variant, journals the act under the same {@code ENGINE_FEED_DELIVERY_FORCED}
     * type as the global relaunch (naming the feed), and redirects with the notice.
     * <p>
     * The replay is safe by construction — the downstream chain is idempotent
     * (the engine imports each parcel by checksum, the till consumes each result
     * by ticket) — so pressing the button on a feed already delivered, or on one
     * left half-applied by an outage, never double-applies anything; see
     * {@link EngineFeedDeliveryService#triggerDelivery(String)}.
     *
     * @param code the feed code to deliver
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/deliver/{code}")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
    public Response relaunchDeliveryFor(@PathParam("code") String code) {
        LOGGER.info("Entering method relaunchDeliveryFor with code: " + code);
        String error = engineFeedDeliveryService.triggerDelivery(code);
        if (error == null) {
            technicalEventService.log(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED,
                    "Livraison manuelle du flux moteur " + code + " réussie");
            LOGGER.info("Exiting method relaunchDeliveryFor");
            return redirect("Flux " + code + " livré.", true);
        }
        technicalEventService.log(TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED,
                "Livraison manuelle du flux moteur " + code + " en échec: " + error);
        LOGGER.info("Exiting method relaunchDeliveryFor");
        return redirect("Livraison du flux " + code + " en échec : " + error, false);
    }

    /**
     * Books a deferred delivery (GB-01-01-02) for a feed (or every feed) at a
     * chosen minute; the existing delivery loop runs it on its next wake. A
     * blank feed code books every feed; a missing or malformed date is refused
     * with a red notice.
     *
     * @param feedCode the feed code to deliver, blank for every feed
     * @param scheduledAt the local date-time (ISO {@code yyyy-MM-ddTHH:mm}) to run at
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/schedule")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
    public Response schedule(@FormParam("feedCode") String feedCode,
                             @FormParam("scheduledAt") String scheduledAt) {
        LOGGER.info("Entering method schedule with feedCode: " + feedCode + ", scheduledAt: " + scheduledAt);
        if (scheduledAt == null || scheduledAt.isBlank()) {
            LOGGER.info("Exiting method schedule");
            return redirect("Date de planification manquante.", false);
        }
        LocalDateTime when;
        try {
            when = LocalDateTime.parse(scheduledAt);
        } catch (DateTimeParseException e) {
            LOGGER.info("Exiting method schedule");
            return redirect("Date de planification invalide.", false);
        }
        scheduledDeliveryService.schedule(feedCode, when, currentUser.getName());
        LOGGER.info("Exiting method schedule");
        return redirect("Livraison planifiée.", true);
    }

    /**
     * Cancels a still-pending deferred delivery (GB-01-01-02) and redirects with
     * the notice; a booking already run (DONE/FAILED) or gone cannot be
     * cancelled and yields a red notice.
     *
     * @param id the booking identifier
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/schedule/{id}/cancel")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
    public Response cancelSchedule(@PathParam("id") Long id) {
        LOGGER.info("Entering method cancelSchedule with id: " + id);
        boolean cancelled = scheduledDeliveryService.cancel(id);
        LOGGER.info("Exiting method cancelSchedule");
        return cancelled
                ? redirect("Planification annulée.", true)
                : redirect("Planification introuvable ou déjà exécutée.", false);
    }

    /**
     * Renders the engine-feed table body alone, for the 2-second auto-refresh
     * (GB-01-01-20/30) that swaps table bodies in place without reloading.
     *
     * @return the {@code feeds} fragment of the supervision page
     */
    @GET
    @Path("/feeds")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance feedsFragment() {
        LOGGER.info("Entering method feedsFragment");
        LOGGER.info("Exiting method feedsFragment");
        return adminSync.getFragment("feeds").data("view", syncSupervisionService.build());
    }

    /**
     * Renders the referential-pull table body alone, for the auto-refresh.
     *
     * @return the {@code pulls} fragment of the supervision page
     */
    @GET
    @Path("/pulls")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pullsFragment() {
        LOGGER.info("Entering method pullsFragment");
        LOGGER.info("Exiting method pullsFragment");
        return adminSync.getFragment("pulls").data("view", syncSupervisionService.build());
    }

    /**
     * Renders the store-outbox table body alone, for the auto-refresh.
     *
     * @return the {@code backlog} fragment of the supervision page
     */
    @GET
    @Path("/outbox")
    @Authenticated
    @FeatureAccess(value = Feature.SYNC)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance outboxFragment() {
        LOGGER.info("Entering method outboxFragment");
        LOGGER.info("Exiting method outboxFragment");
        return adminSync.getFragment("backlog").data("view", syncSupervisionService.build());
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
    @Authenticated
    @FeatureAccess(value = Feature.SYNC, option = CrudOption.UPDATE)
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
