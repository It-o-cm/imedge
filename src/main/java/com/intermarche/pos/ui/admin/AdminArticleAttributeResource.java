package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.ArticleAttributeDefinition;
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
 * The ARTICLE ATTRIBUTES administration screen ({@code /admin/article-attributes},
 * BO-02-03-32): declares the custom attributes an administrator can then attach
 * to article fiches, on top of the well-known register attributes. Create,
 * rename and delete definitions; the fiche renders every declared attribute as
 * a captured field.
 * <p>
 * Access reserved to ADMIN, like every article-area screen. POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 * Deleting a definition drops the declaration only: the values already captured
 * on fiches stay in the open attribute map, so no article datum is lost.
 */
@Path("/admin/article-attributes")
public class AdminArticleAttributeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminArticleAttributeResource.class);

    /** The attribute-definitions list template. */
    @Inject
    @Location("admin-article-attributes")
    Template adminArticleAttributes;

    /**
     * Shows the declared attribute definitions, ordered by code.
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
        return adminArticleAttributes.data("definitions", ArticleAttributeDefinition.listAllOrdered())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates a definition, or renames an existing one when an id is posted.
     *
     * @param form the posted form (id optional, code and label required)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        String code = trimmedUpper(form.getFirst("code"));
        String label = trimmed(form.getFirst("label"));
        if (code.isEmpty() || label.isEmpty()) {
            LOGGER.info("Exiting method save");
            return redirect("Le code et le libellé de l'attribut sont obligatoires.", false);
        }
        Long id = parseId(form.getFirst("id"));
        ArticleAttributeDefinition definition = id != null
                ? ArticleAttributeDefinition.<ArticleAttributeDefinition>findById(id) : null;
        if (id != null && definition == null) {
            LOGGER.info("Exiting method save");
            return redirect("Attribut introuvable.", false);
        }
        ArticleAttributeDefinition clash = ArticleAttributeDefinition.findByCode(code);
        if (clash != null && !clash.equals(definition)) {
            LOGGER.info("Exiting method save");
            return redirect("Un attribut porte déjà le code « " + code + " ».", false);
        }
        if (definition == null) {
            definition = new ArticleAttributeDefinition();
            definition.code = code;
            definition.label = label;
            definition.persist();
        } else {
            definition.code = code;
            definition.label = label;
        }
        LOGGER.info("Exiting method save");
        return redirect("Attribut « " + code + " » enregistré.", true);
    }

    /**
     * Deletes a declared attribute definition.
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
        ArticleAttributeDefinition definition = id != null
                ? ArticleAttributeDefinition.<ArticleAttributeDefinition>findById(id) : null;
        if (definition == null) {
            LOGGER.info("Exiting method delete");
            return redirect("Attribut introuvable.", false);
        }
        String code = definition.code;
        definition.delete();
        LOGGER.info("Exiting method delete");
        return redirect("Attribut « " + code + " » supprimé.", true);
    }

    /**
     * Parses a definition id, tolerating a null, blank or malformed value.
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
     * Trims and upper-cases a posted code, mapping a null to an empty string.
     *
     * @param raw the posted value, or null
     * @return the trimmed upper-cased value, never null
     */
    private String trimmedUpper(String raw) {
        return trimmed(raw).toUpperCase();
    }

    /**
     * Builds the 303 redirect back to the list carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/article-attributes?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
