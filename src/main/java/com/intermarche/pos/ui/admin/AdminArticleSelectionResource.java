package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.ArticleSelection;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

/**
 * The ARTICLE SELECTIONS administration screen
 * ({@code /admin/article-selections}, BO-02-03-31): saves named, reusable
 * article perimeters — a grouping defined by the same multi-criteria engine the
 * fiche list uses ({@link ArticleSearchCriteria}) — so a report or an export can
 * restrict its scope to a selection. Create, redefine and delete selections.
 * <p>
 * A selection stores its criteria as the canonical query string of
 * {@link ArticleSearchCriteria}, normalised on save, so applying a selection is
 * exactly re-running that search. Access reserved to ADMIN, like every
 * article-area screen. POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 */
@Path("/admin/article-selections")
public class AdminArticleSelectionResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminArticleSelectionResource.class);

    /** The selections list template. */
    @Inject
    @Location("admin-article-selections")
    Template adminArticleSelections;

    /**
     * Shows the saved selections, ordered by name, with the well-known
     * attribute catalog the create form offers as criteria.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with notice: " + notice + ", noticeOk: " + noticeOk);
        LOGGER.info("Exiting method list");
        return adminArticleSelections.data("selections", ArticleSelection.listAllOrdered())
                .data("attributeDefs", ProductAttributeCatalog.CATALOG)
                .data("types", ProductType.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Saves a selection — create when no id is posted, redefine otherwise. The
     * criteria fields of the posted form are normalised through
     * {@link ArticleSearchCriteria} into a canonical query string.
     *
     * @param form the posted form (id optional, name required, plus criteria)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        String name = trimmed(form.getFirst("name"));
        if (name.isEmpty()) {
            LOGGER.info("Exiting method save");
            return redirect("Le nom de la sélection est obligatoire.", false);
        }
        Long id = parseId(form.getFirst("id"));
        ArticleSelection selection = id != null
                ? ArticleSelection.<ArticleSelection>findById(id) : null;
        if (id != null && selection == null) {
            LOGGER.info("Exiting method save");
            return redirect("Sélection introuvable.", false);
        }
        ArticleSelection clash = ArticleSelection.findByName(name);
        if (clash != null && !clash.equals(selection)) {
            LOGGER.info("Exiting method save");
            return redirect("Une sélection porte déjà le nom « " + name + " ».", false);
        }
        String criteria = ArticleSearchCriteria.fromParams(form).toQueryString();
        if (selection == null) {
            selection = new ArticleSelection();
            selection.name = name;
            selection.criteria = criteria;
            selection.persist();
        } else {
            selection.name = name;
            selection.criteria = criteria;
        }
        LOGGER.info("Exiting method save");
        return redirect("Sélection « " + name + " » enregistrée.", true);
    }

    /**
     * Deletes a saved selection.
     *
     * @param form the posted form carrying the id
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        Long id = parseId(form.getFirst("id"));
        ArticleSelection selection = id != null
                ? ArticleSelection.<ArticleSelection>findById(id) : null;
        if (selection == null) {
            LOGGER.info("Exiting method delete");
            return redirect("Sélection introuvable.", false);
        }
        String name = selection.name;
        selection.delete();
        LOGGER.info("Exiting method delete");
        return redirect("Sélection « " + name + " » supprimée.", true);
    }

    /**
     * Parses a selection id, tolerating a null, blank or malformed value.
     *
     * @param raw the raw id, or null
     * @return the parsed id, or null when absent or malformed
     */
    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Trims a posted field, mapping a null to an empty string.
     *
     * @param raw the posted value, or null
     * @return the trimmed value, never null
     */
    private String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * Builds the 303 redirect back to the list carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/article-selections?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
