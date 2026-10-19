package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * JAX-RS resource of the reprint screen, under its own {@code /reprint}
 * prefix: history list, paged navigation, ticket detail and the duplicata
 * print action. Review is free (reading persisted tickets mutates
 * nothing); only the print action carries fiscal weight — it bumps the
 * duplicata counter and is blocked in training by the service.
 */
@Path("/reprint")
@DrawerMustBeClosed
public class ReprintResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReprintResource.class);

    @Inject
    PosState state;
    @Inject ReprintService reprintService;

    @Inject @Location("reprint-ticket") Template reprintTicketPage;
    @Inject @Location("reprint-ticket-detail") Template reprintDetailPage;

    /** The mask naming a ticket held by another register (LC-08-05-05). */
    @Inject @Location("reprint-foreign") Template reprintForeignPage;

    @GET
    @Produces(MediaType.TEXT_HTML)
    /**
     * Shows the reprint screen with a fresh closed-ticket history.
     *
     * @return the reprint page
     */
    public TemplateInstance showReprintPage() {
        LOGGER.info("Entering method showReprintPage");
        reprintService.loadHistory();
        LOGGER.info("Exiting method showReprintPage");
        return reprintTicketPage.data("state", state);
    }

    /**
     * Searches the duplicata history by number, date or amount (EXP-47) — the
     * shared ticket search engine. A blank value restores the full history.
     *
     * @param pattern the value keyed on the search field
     * @return the reprint page with the matching tickets
     */
    @POST
    @Path("/search")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance searchReprint(@FormParam("pattern") String pattern) {
        LOGGER.info("Entering method searchReprint with pattern: " + pattern);
        reprintService.search(pattern);
        LOGGER.info("Exiting method searchReprint");
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/prev")
    /**
     * Pages the history back.
     *
     * @return the reprint page
     */
    public TemplateInstance reprintPrevPage() {
        LOGGER.info("Entering method reprintPrevPage");
        if (state.reprint.isHasListPrev()) state.reprint.listPage--;
        state.touch();
        LOGGER.info("Exiting method reprintPrevPage");
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/next")
    /**
     * Pages the history forward.
     *
     * @return the reprint page
     */
    public TemplateInstance reprintNextPage() {
        LOGGER.info("Entering method reprintNextPage");
        if (state.reprint.isHasListNext()) state.reprint.listPage++;
        state.touch();
        LOGGER.info("Exiting method reprintNextPage");
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/view/{id}")
    /**
     * Opens a ticket in the detail view.
     *
     * @param id the database id of the ticket
     * @return the reprint page on its detail view
     */
    public TemplateInstance showReprintDetail(@PathParam("id") Long id) {
        LOGGER.info("Entering method showReprintDetail with id: " + id);
        Ticket t = Ticket.findById(id);
        if (t != null) {
            state.reprint.setViewedTicket(t);
        }
        state.touch();
        LOGGER.info("Exiting method showReprintDetail");
        return reprintDetailPage.data("state", state);
    }

    @GET
    @Path("/view/{id}/prev")
    /**
     * Pages the detail back.
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    public TemplateInstance detailPrevPage(@PathParam("id") Long id) {
        LOGGER.info("Entering method detailPrevPage with id: " + id);
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            LOGGER.info("Exiting method detailPrevPage");
            return showReprintDetail(id);
        }
        if (state.reprint.isHasDetailPrev()) state.reprint.detailPage--;
        state.touch();
        LOGGER.info("Exiting method detailPrevPage");
        return reprintDetailPage.data("state", state);
    }

    @GET
    @Path("/view/{id}/next")
    /**
     * Pages the detail forward.
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    public TemplateInstance detailNextPage(@PathParam("id") Long id) {
        LOGGER.info("Entering method detailNextPage with id: " + id);
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            LOGGER.info("Exiting method detailNextPage");
            return showReprintDetail(id);
        }
        if (state.reprint.isHasDetailNext()) state.reprint.detailPage++;
        state.touch();
        LOGGER.info("Exiting method detailNextPage");
        return reprintDetailPage.data("state", state);
    }

    @GET
    /**
     * Prints a numbered duplicata of a ticket (refused in training by the
     * service) and stays on the detail view.
     *
     * @param id the database id of the ticket to reprint
     * @return the reprint page
     */
    @Path("/print/{id}")
    public Response doReprint(@PathParam("id") Long id) {
        LOGGER.info("Entering method doReprint with id: " + id);
        reprintService.print(id);
        LOGGER.info("Exiting method doReprint");
        return Response.seeOther(URI.create("/reprint/view/" + id)).build();
    }

    /**
     * Opens the bon-pour-échange preparation on the viewed ticket (LC-08-05-10).
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    @GET
    @Path("/view/{id}/exchange")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance startExchange(@PathParam("id") Long id) {
        LOGGER.info("Entering method startExchange with id: " + id);
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            LOGGER.info("Exiting method startExchange");
            return showReprintDetail(id);
        }
        reprintService.startExchange();
        LOGGER.info("Exiting method startExchange");
        return reprintDetailPage.data("state", state);
    }

    /**
     * Leaves the bon-pour-échange preparation without printing.
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    @GET
    @Path("/view/{id}/exchange/cancel")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelExchange(@PathParam("id") Long id) {
        LOGGER.info("Entering method cancelExchange with id: " + id);
        reprintService.cancelExchange();
        LOGGER.info("Exiting method cancelExchange");
        return showReprintDetail(id);
    }

    /**
     * Names a line for the bon pour échange, or unnames it (LC-08-05-12).
     *
     * @param id the database id of the viewed ticket
     * @param lineId the database id of the line touched
     * @return the reprint page on its detail view
     */
    @GET
    @Path("/view/{id}/exchange/toggle/{lineId}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance toggleExchangeLine(@PathParam("id") Long id,
            @PathParam("lineId") Long lineId) {
        LOGGER.info("Entering method toggleExchangeLine with id: " + id + ", lineId: " + lineId);
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            LOGGER.info("Exiting method toggleExchangeLine");
            return showReprintDetail(id);
        }
        reprintService.toggleExchangeLine(lineId);
        LOGGER.info("Exiting method toggleExchangeLine");
        return reprintDetailPage.data("state", state);
    }

    /**
     * Prints the bon pour échange of the viewed ticket (LC-08-05-13).
     *
     * @param id the database id of the ticket
     * @return a redirect to the detail view (PRG pattern, so a reload never prints a
     *         second bon)
     */
    @GET
    @Path("/view/{id}/exchange/print")
    public Response printExchange(@PathParam("id") Long id) {
        LOGGER.info("Entering method printExchange with id: " + id);
        reprintService.printExchange(id);
        LOGGER.info("Exiting method printExchange");
        return Response.seeOther(URI.create("/reprint/view/" + id)).build();
    }

    /**
     * Opens the mask naming a ticket held by another register (LC-08-05-05).
     *
     * @return the foreign-duplicata page
     */
    @GET
    @Path("/foreign")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance showForeign() {
        LOGGER.info("Entering method showForeign");
        reprintService.startForeign();
        LOGGER.info("Exiting method showForeign");
        return reprintForeignPage.data("state", state);
    }

    /**
     * Prints the duplicata of a ticket made on another register (LC-08-05-05).
     *
     * @param ticketNumber the number typed
     * @return the foreign-duplicata page, carrying the failure when there was one
     */
    @jakarta.ws.rs.POST
    @Path("/foreign")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance printForeign(
            @jakarta.ws.rs.FormParam("ticketNumber") String ticketNumber) {
        LOGGER.info("Entering method printForeign with ticketNumber: " + ticketNumber);
        reprintService.printForeign(ticketNumber);
        LOGGER.info("Exiting method printForeign");
        return reprintForeignPage.data("state", state);
    }

    /**
     * Enters the REASSIGNMENT browsing mode (LC-03-06-02): the history keeps
     * only the closed tickets no client is attached to.
     *
     * @return the reprint page on the reassignment pool
     */
    @GET
    @Path("/reassign")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance startReassignMode() {
        LOGGER.info("Entering method startReassignMode");
        reprintService.startReassignMode();
        LOGGER.info("Exiting method startReassignMode");
        return reprintTicketPage.data("state", state);
    }

    /**
     * Leaves the REASSIGNMENT browsing mode and restores the ordinary
     * duplicata history (LC-03-06-02).
     *
     * @return the reprint page on the ordinary history
     */
    @GET
    @Path("/reassign/cancel")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelReassignMode() {
        LOGGER.info("Entering method cancelReassignMode");
        reprintService.cancelReassignMode();
        LOGGER.info("Exiting method cancelReassignMode");
        return reprintTicketPage.data("state", state);
    }

    /**
     * Opens the card mask of the reassignment on the viewed ticket
     * (LC-03-06-03): the card number is typed on the detail page of a
     * non-affected closed ticket.
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view, mask open
     */
    @GET
    @Path("/view/{id}/reassign")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance beginReassign(@PathParam("id") Long id) {
        LOGGER.info("Entering method beginReassign with id: " + id);
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            LOGGER.info("Exiting method beginReassign");
            return showReprintDetail(id);
        }
        reprintService.beginReassign(id);
        LOGGER.info("Exiting method beginReassign");
        return reprintDetailPage.data("state", state);
    }

    /**
     * Leaves the card mask without reassigning anything (LC-03-06-03).
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view, mask closed
     */
    @GET
    @Path("/view/{id}/reassign/cancel")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelReassign(@PathParam("id") Long id) {
        LOGGER.info("Entering method cancelReassign with id: " + id);
        reprintService.cancelReassignMask();
        LOGGER.info("Exiting method cancelReassign");
        return showReprintDetail(id);
    }

    /**
     * Performs the reassignment of the viewed ticket to the typed card
     * (LC-03-06-01 to -06), through the administered endorsement: the
     * supervisor's modal is served by the detail page when a store demands
     * the aval, and the chained gesture runs at once otherwise.
     *
     * @param id the database id of the closed ticket
     * @param card the fidelity card number, as typed in the mask
     * @return a 303 redirect to the detail view (PRG pattern, so a browser
     *         reload never replays the reassignment)
     */
    @POST
    @Path("/view/{id}/reassign")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response applyReassign(@PathParam("id") Long id,
            @FormParam("card") String card) {
        LOGGER.info("Entering method applyReassign with id: " + id + ", card: " + card);
        reprintService.applyReassign(id, card);
        LOGGER.info("Exiting method applyReassign");
        return Response.seeOther(URI.create("/reprint/view/" + id)).build();
    }
}