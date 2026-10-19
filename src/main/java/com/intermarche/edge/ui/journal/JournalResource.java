package com.intermarche.edge.ui.journal;

import com.intermarche.edge.domain.payment.PaymentTypes;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.TechnicalEvent;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The electronic journal back office (BO-04-01, lot 4): a multi-criteria
 * search over the consolidated documents, split into a transactional tab
 * (tickets) and a functional tab (technical events) as BO-04-01-52 requires,
 * a third tab for the cash movements (BO-04-01-12/33/35/36/37/40/44, a distinct
 * consolidated entity that is neither a ticket nor a technical event), a ticket
 * detail view reachable from the list, and a CSV export that reproduces the
 * transactional list to the character (BO-04-01-50).
 * <p>
 * Reserved to MANAGER and ADMIN — the journal is a supervision surface, like
 * the dashboard, not a public LAN page. It reads this node's own database, so
 * it is the STORE journal when opened on the consolidated node. It produces
 * nothing: every endpoint is a GET, there is no state to mutate.
 * <p>
 * Rendered inside the back-office gabarit ({@code admin-layout}), the same
 * chrome as the parameters and supervision screens — one surface, one
 * navigation.
 */
@Path("/admin/journal")
public class JournalResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(JournalResource.class);

    /** The journal search page template (both tabs). */
    @Inject
    @Location("journal")
    Template journal;

    /** The ticket detail page template. */
    @Inject
    @Location("journal-detail")
    Template journalDetail;

    @Inject
    JournalService journalService;

    /**
     * Shows the transactional journal: the ticket search form and its results.
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the transactional tab page
     */
    @GET
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance transactional(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method transactional with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        JournalPage<JournalRow> tickets = journalService.search(criteria);
        LOGGER.info("Exiting method transactional");
        return page("transactional", criteria, uriInfo, tickets, emptyPage(), emptyPage());
    }

    /**
     * Shows the functional journal: the technical-event search form and its
     * results.
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the functional tab page
     */
    @GET
    @Path("/functional")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance functional(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method functional with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        JournalPage<JournalEventRow> events = journalService.searchEvents(criteria);
        LOGGER.info("Exiting method functional");
        return page("functional", criteria, uriInfo, emptyPage(), events, emptyPage());
    }

    /**
     * Shows the movements journal: the cash-movement search form and its results
     * (BO-04-01-12/33/35/36/37/40/44). Cash movements are a distinct consolidated
     * entity, so they get their own tab rather than being grafted onto the
     * transactional search (whose every clause presumes a ticket) or the
     * functional one (which reads technical events).
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the movements tab page
     */
    @GET
    @Path("/movements")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance movements(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method movements with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        JournalPage<JournalMovementRow> movements = journalService.searchMovements(criteria);
        LOGGER.info("Exiting method movements");
        return page("movements", criteria, uriInfo, emptyPage(), emptyPage(), movements);
    }

    /**
     * Builds an empty result page of the service's page size, for the two tabs
     * a request does not search.
     *
     * @param <T> the row type of the empty page
     * @return the empty page
     */
    private <T> JournalPage<T> emptyPage() {
        return new JournalPage<>(List.of(), 1, JournalService.PAGE_SIZE, 0);
    }

    /**
     * Shows the detail of one consolidated ticket.
     *
     * @param id the ticket database id
     * @return the detail page; {@code ticket} is null when the id is unknown
     */
    @GET
    @Path("/ticket/{id}")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id) {
        LOGGER.info("Entering method detail with id: " + id);
        JournalTicketDetail ticket = journalService.buildDetail(id);
        LOGGER.info("Exiting method detail");
        return journalDetail.data("ticket", ticket);
    }

    /**
     * Exports the transactional list as a CSV attachment, running the same
     * search as the screen (BO-04-01-50, export à l'identique).
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the CSV attachment response
     */
    @GET
    @Path("/export")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces("text/csv")
    public Response export(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method export with uriInfo: " + uriInfo);
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        String csv = journalService.exportCsv(criteria);
        LOGGER.info("Exiting method export");
        return Response.ok(csv)
                .header("Content-Disposition", "attachment; filename=\"journal.csv\"")
                .build();
    }

    /**
     * Assembles the search page with the active tab, the criteria echoed back
     * to keep the form filled, the result page, the option catalogs and the
     * query string the pager rebuilds its links from.
     *
     * @param tab the active tab ("transactional", "functional" or "movements")
     * @param criteria the parsed criteria to echo
     * @param uriInfo the request URI, read for the pager base query
     * @param tickets the transactional result page (empty off the transactional tab)
     * @param events the functional result page (empty off the functional tab)
     * @param movements the movements result page (empty off the movements tab)
     * @return the wired page instance
     */
    private TemplateInstance page(String tab, JournalCriteria criteria, UriInfo uriInfo,
                                  JournalPage<JournalRow> tickets,
                                  JournalPage<JournalEventRow> events,
                                  JournalPage<JournalMovementRow> movements) {
        return journal.data("tab", tab)
                .data("criteria", criteria)
                .data("tickets", tickets)
                .data("events", events)
                .data("movements", movements)
                .data("baseQuery", baseQuery(uriInfo))
                .data("paymentKeys", PaymentTypes.keys())
                .data("ticketFlags", JournalCriteria.Flag.values())
                .data("sorts", JournalSort.values())
                .data("eventTypes", TechnicalEvent.EventType.values())
                .data("movementTypes", CashMovement.MovementType.values());
    }

    /**
     * Rebuilds the current query string without its page parameter, so the
     * pager can append its own page number while keeping every search criterion
     * the user submitted. The returned string is empty or ends with an
     * ampersand, so a caller only has to append {@code page=n}.
     *
     * @param uriInfo the request URI carrying the query parameters
     * @return the re-encoded query string prefix
     */
    String baseQuery(UriInfo uriInfo) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : uriInfo.getQueryParameters().entrySet()) {
            if ("page".equals(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                query.append(encode(entry.getKey())).append('=')
                        .append(encode(value)).append('&');
            }
        }
        return query.toString();
    }

    /**
     * URL-encodes one query-string token.
     *
     * @param value the raw token
     * @return the encoded token
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
