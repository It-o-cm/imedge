package com.intermarche.edge.ui.journal;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The ELECTRONIC JOURNAL REGROUPED BY ADHÉRENT (BO-02-05-05), on the central
 * node: for a chosen adhérent — optionally narrowed to a country, an enseigne
 * or one point of sale — the tickets of all its points of sale, searchable by
 * period, register, ticket number, amount and type, each row naming its point
 * of sale and opening its detail.
 *
 * <p>The tickets are those the store nodes push up to the central node
 * ({@code JournalUplinkService}); a store node keeps its own journal at
 * {@code /admin/journal}, unchanged. Read screen: every endpoint is a GET.
 */
@Path("/admin/adherent-journal")
public class AdherentJournalResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdherentJournalResource.class);

    /** The search page. */
    @Inject
    @Location("journal-adherent")
    Template journalAdherent;

    /** The ticket detail page. */
    @Inject
    @Location("journal-adherent-detail")
    Template journalAdherentDetail;

    /** The read side. */
    @Inject
    AdherentJournalService adherentJournalService;

    /** The role of this node: register, store or central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /**
     * Shows the search form and one page of its results.
     *
     * @param uriInfo the request URI carrying the criteria
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.ADHERENT_JOURNAL, option = CrudOption.VIEW)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance search(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method search");
        AdherentJournalCriteria criteria = AdherentJournalCriteria.from(uriInfo.getQueryParameters());
        JournalPage<AdherentJournalRow> tickets = adherentJournalService.search(criteria);
        LOGGER.info("Exiting method search");
        return journalAdherent.data("criteria", criteria)
                .data("tickets", tickets)
                .data("baseQuery", criteria.query())
                .data("adherents", adherentJournalService.adherents())
                .data("countries", adherentJournalService.countries())
                .data("enseignes", adherentJournalService.enseignes())
                .data("pdvs", adherentJournalService.pdvs())
                .data("kinds", AdherentJournalCriteria.KINDS)
                .data("central", "central".equalsIgnoreCase(role));
    }

    /**
     * Shows one ticket.
     *
     * @param id the row's database id
     * @return the detail page, saying so when the id is unknown
     */
    @GET
    @Path("/{id}")
    @Authenticated
    @FeatureAccess(value = Feature.ADHERENT_JOURNAL, option = CrudOption.VIEW)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id) {
        LOGGER.info("Entering method detail with id: " + id);
        return journalAdherentDetail.data("detail", adherentJournalService.detail(id));
    }
}
